/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.applications;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.Applications;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches the list of applications installed on the phone to provide search suggestions.
 * If the functionality of this provider changes, the documentation at
 * {@link android.provider.Applications} should be updated.
 *
 * TODO: this provider should be moved to the Launcher, which contains similar logic to keep an up
 * to date list of installed applications.  Alternatively, Launcher could be updated to use this 
 * provider.
 */
public class ApplicationsProvider extends ContentProvider implements ThreadFactory {
    
    private static final boolean DBG = false;
    
    private static final String TAG = "ApplicationsProvider";

    private static final int SEARCH_SUGGEST = 0;
    private static final int SHORTCUT_REFRESH = 1;
    private static final UriMatcher sURIMatcher = buildUriMatcher();

    // TODO: Move these to android.provider.Applications?
    public static final String _ID = "_id";
    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String PACKAGE = "package";
    public static final String CLASS = "class";
    public static final String ICON = "icon";
    
    private static final String APPLICATIONS_TABLE = "applications";
    
    private static final HashMap<String, String> sSearchSuggestionsProjectionMap =
            buildSuggestionsProjectionMap();
    
    private SQLiteDatabase mDb;
    private final AtomicInteger mThreadCount = new AtomicInteger(1);
    private Executor mExecutor;

    // mQLock protects access to the list of pending updates
    private final Object mQLock = new Object();
    private final LinkedList<UpdateRunnable> mPending = new LinkedList<UpdateRunnable>();

    /**
     * We delay application updates by this many millis to avoid doing more than one update to the
     * applications list within this window.
     */
    private static final long UPDATE_DELAY_MILLIS = 1000L;

    private static UriMatcher buildUriMatcher() {
        UriMatcher matcher =  new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(Applications.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY,
                SEARCH_SUGGEST);
        matcher.addURI(Applications.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*",
                SEARCH_SUGGEST);
        matcher.addURI(Applications.AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT,
                SHORTCUT_REFRESH);
        matcher.addURI(Applications.AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT + "/*",
                SHORTCUT_REFRESH);
        return matcher;
    }

    // Broadcast receiver for updating applications list.
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                // do this in a worker thread to avoid ANRs
                if (DBG) Log.d(TAG, "package update: " + intent);
                postAppsUpdate();
            }
        }
    };

    @Override
    public boolean onCreate() {
        createDatabase();
        registerBroadcastReceiver();
        mExecutor = new ThreadPoolExecutor(1, 1,
                5, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                this);
        postAppsUpdate();
        return true;
    }

    // ----------
    // BEGIN ASYC UPDATE CODE
    // - only one update at a time
    // - cancel any outstanding updates when a new one comes in so they become no-ops
    // ----------

    /**
     * {@inheritDoc}
     */
    public Thread newThread(Runnable r) {
        return new WorkerThread(r, "ApplicationsProvider #" + mThreadCount.getAndIncrement());
    }

    // a thread that runs with background priority
    private static class WorkerThread extends Thread {

        private WorkerThread(Runnable runnable, String threadName) {
            super(runnable, threadName);
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            super.run();
        }
    }

    /**
     * Post an update, and add it to the pending queue.  Cancel any other pending operatinos.
     */
    private void postAppsUpdate() {
        final UpdateRunnable r = new UpdateRunnable();
        synchronized (mQLock) {
            for (UpdateRunnable updateRunnable : mPending) {
                updateRunnable.cancel();
            }
            mPending.add(r);
        }
        mExecutor.execute(r);
    }

    private void doneRunning(UpdateRunnable runnable) {
        synchronized (mQLock) {
            mPending.remove(runnable);
        }
    }

    /**
     * Updates the applications list, unless it was cancelled.  When done, calls back to
     * {@link ApplicationsProvider#doneRunning} do be removed from pending queue.
     */
    class UpdateRunnable implements Runnable {

        private volatile boolean mCancelled = false;

        void cancel() {
            mCancelled = true;
        }

        public void run() {

            try {
                Thread.sleep(UPDATE_DELAY_MILLIS);
            } catch (InterruptedException e) {
                // not expected, but meh
                mCancelled = true;
            }

            try {
                if (!mCancelled) {
                    updateApplicationsList();
                } else if (DBG) {
                    Log.d(TAG, "avoided applications update.");

                }
            } catch (Exception e) {
                Log.e(TAG, "error updating applications list.", e);
            } finally {
                doneRunning(this);
            }
        }
    }

    // ----------
    // END ASYC UPDATE CODE
    // ----------


    /**
     * Creates an in-memory database for storing application info.
     */
    private void createDatabase() {
        mDb = SQLiteDatabase.create(null);
        mDb.execSQL("CREATE TABLE IF NOT EXISTS " + APPLICATIONS_TABLE + " ("+
                _ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                NAME + " TEXT COLLATE LOCALIZED," +
                DESCRIPTION + " description TEXT," +
                PACKAGE + " TEXT," +
                CLASS + " TEXT," +
                ICON + " TEXT" +
                ");");
        // Needed for efficient update and remove
        mDb.execSQL("CREATE INDEX applicationsComponentIndex ON " + APPLICATIONS_TABLE + " (" 
                + PACKAGE + "," + CLASS + ");");
        // Maps token from the app name to records in the applications table
        mDb.execSQL("CREATE TABLE applicationsLookup (" +
                "token TEXT," +
                "source INTEGER REFERENCES " + APPLICATIONS_TABLE + "(" + _ID + ")," +
                "token_index INTEGER" +
                ");");
        mDb.execSQL("CREATE INDEX applicationsLookupIndex ON applicationsLookup (" +
                "token," +
                "source" +
                ");");
        // Triggers to keep the applicationsLookup table up to date
        mDb.execSQL("CREATE TRIGGER applicationsLookup_update UPDATE OF " + NAME + " ON " + 
                APPLICATIONS_TABLE + " " +
                "BEGIN " +
                "DELETE FROM applicationsLookup WHERE source = new." + _ID + ";" +
                "SELECT _TOKENIZE('applicationsLookup', new." + _ID + ", new." + NAME + ", ' ', 1);" +
                "END");
        mDb.execSQL("CREATE TRIGGER applicationsLookup_insert AFTER INSERT ON " + 
                APPLICATIONS_TABLE + " " +
                "BEGIN " +
                "SELECT _TOKENIZE('applicationsLookup', new." + _ID + ", new." + NAME + ", ' ', 1);" +
                "END");
        mDb.execSQL("CREATE TRIGGER applicationsLookup_delete DELETE ON " + 
                APPLICATIONS_TABLE + " " +
                "BEGIN " +
                "DELETE FROM applicationsLookup WHERE source = old." + _ID + ";" +
                "END");
    }
    
    /**
     * Registers a receiver which will be notified when packages are added, removed,
     * or changed.
     */
    private void registerBroadcastReceiver() {
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        getContext().registerReceiver(mBroadcastReceiver, packageFilter);
    }
    
    /**
     * This will always return {@link SearchManager#SUGGEST_MIME_TYPE} as this
     * provider is purely to provide suggestions.
     */
    @Override
    public String getType(Uri uri) {
        switch (sURIMatcher.match(uri)) {
            case SEARCH_SUGGEST:
                return SearchManager.SUGGEST_MIME_TYPE;
            case SHORTCUT_REFRESH:
                return SearchManager.SHORTCUT_MIME_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }
    }

    /**
     * Queries for a given search term and returns a cursor containing
     * suggestions ordered by best match.
     */
    @Override
    public Cursor query(Uri uri, String[] projectionIn, String selection,
            String[] selectionArgs, String sortOrder) {
        if (DBG) Log.d(TAG, "query(" + uri + ")");

        if (!TextUtils.isEmpty(selection)) {
            throw new IllegalArgumentException("selection not allowed for " + uri);
        }
        if (selectionArgs != null && selectionArgs.length != 0) {
            throw new IllegalArgumentException("selectionArgs not allowed for " + uri);
        }
        if (!TextUtils.isEmpty(sortOrder)) {
            throw new IllegalArgumentException("sortOrder not allowed for " + uri);
        }

        switch (sURIMatcher.match(uri)) {
            case SEARCH_SUGGEST:
                String query = null;
                if (uri.getPathSegments().size() > 1) {
                    query = uri.getLastPathSegment().toLowerCase();
                }
                return getSuggestions(query, projectionIn);
            case SHORTCUT_REFRESH:
                String shortcutId = null;
                if (uri.getPathSegments().size() > 1) {
                    shortcutId = uri.getLastPathSegment();
                }
                return refreshShortcut(shortcutId, projectionIn);
            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }
    }

    private Cursor getSuggestions(String query, String[] projectionIn) {
        // No zero-query suggestions
        if (TextUtils.isEmpty(query)) {
            return null;
        }

        // Build SQL query
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables("applicationsLookup JOIN " + APPLICATIONS_TABLE + " ON"
                + " applicationsLookup.source = " + APPLICATIONS_TABLE + "." + _ID);
        qb.setProjectionMap(sSearchSuggestionsProjectionMap);
        qb.appendWhere(buildTokenFilter(query));
        // don't return duplicates when there are two matching tokens for an app
        String groupBy = APPLICATIONS_TABLE + "." + _ID;
        // order first by whether it a full prefix match, then by name
        // MIN(token_index) != 0 is true for non-full prefix matches,
        // and since false (0) < true(1), this expression makes sure
        // that full prefix matches come first.
        String order = "MIN(token_index) != 0, " + NAME;
        Cursor cursor = qb.query(mDb, projectionIn, null, null, groupBy, null, order);
        if (DBG) Log.d(TAG, "Returning " + cursor.getCount() + " results for " + query);
        return cursor;
    }

    /**
     * Refreshes the shortcut of an application.
     *
     * @param shortcutId Flattened component name of an activity.
     */
    private Cursor refreshShortcut(String shortcutId, String[] projectionIn) {
        ComponentName component = ComponentName.unflattenFromString(shortcutId);
        if (component == null) {
            Log.w(TAG, "Bad shortcut id: " + shortcutId);
            return null;
        }
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(APPLICATIONS_TABLE);
        qb.setProjectionMap(sSearchSuggestionsProjectionMap);
        qb.appendWhere("package = ? AND class = ?");
        String[] selectionArgs = { component.getPackageName(), component.getClassName() };
        Cursor cursor = qb.query(mDb, projectionIn, null, selectionArgs, null, null, null);
        if (DBG) Log.d(TAG, "Returning " + cursor.getCount() + " results for shortcut refresh.");
        return cursor;
    }

    @SuppressWarnings("deprecation")
    private String buildTokenFilter(String filterParam) {
        StringBuilder filter = new StringBuilder("token GLOB ");
        // NOTE: Query parameters won't work here since the SQL compiler
        // needs to parse the actual string to know that it can use the
        // index to do a prefix scan.
        DatabaseUtils.appendEscapedSQLString(filter, 
                DatabaseUtils.getHexCollationKey(filterParam) + "*");
        return filter.toString();
    }
    
    private static HashMap<String, String> buildSuggestionsProjectionMap() {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put(_ID, _ID);
        map.put(SearchManager.SUGGEST_COLUMN_TEXT_1,
                NAME + " AS " + SearchManager.SUGGEST_COLUMN_TEXT_1);
        map.put(SearchManager.SUGGEST_COLUMN_TEXT_2,
                DESCRIPTION + " AS " + SearchManager.SUGGEST_COLUMN_TEXT_2);
        map.put(SearchManager.SUGGEST_COLUMN_INTENT_DATA,
                "'content://" + Applications.AUTHORITY + "/applications/'"
                + " || " + PACKAGE + " || '/' || " + CLASS
                + " AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA);
        map.put(SearchManager.SUGGEST_COLUMN_ICON_1,
                ICON + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1);
        map.put(SearchManager.SUGGEST_COLUMN_ICON_2,
                "NULL AS " + SearchManager.SUGGEST_COLUMN_ICON_2);
        map.put(SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
                PACKAGE + " || '/' || " + CLASS + " AS "
                + SearchManager.SUGGEST_COLUMN_SHORTCUT_ID);
        return map;
    }

    /**
     * Updates the cached list of installed applications.
     */
    private void updateApplicationsList() {
        // TODO: Instead of rebuilding the whole list on every change,
        // just add, remove or update the application that has changed.
        // Adding and updating seem tricky, since I can't see an easy way to list the
        // launchable activities in a given package.
        if (DBG) Log.d(TAG, "Updating database...");
        
        DatabaseUtils.InsertHelper inserter = 
                new DatabaseUtils.InsertHelper(mDb, APPLICATIONS_TABLE);
        int nameCol = inserter.getColumnIndex(NAME);
        int descriptionCol = inserter.getColumnIndex(DESCRIPTION);
        int packageCol = inserter.getColumnIndex(PACKAGE);
        int classCol = inserter.getColumnIndex(CLASS);
        int iconCol = inserter.getColumnIndex(ICON);
        
        mDb.beginTransaction();
        try {
            mDb.execSQL("DELETE FROM " + APPLICATIONS_TABLE);
            String description = getContext().getString(R.string.application_desc);
            // Iterate and find all the activities which have the LAUNCHER category set.
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            final PackageManager manager = getContext().getPackageManager();
            for (ResolveInfo info : manager.queryIntentActivities(mainIntent, 0)) {
                String title = info.loadLabel(manager).toString();
                if (TextUtils.isEmpty(title)) {
                    title = info.activityInfo.name;
                }
                
                String icon;
                if (info.activityInfo.getIconResource() != 0) {
                    // Use a resource Uri for the icon.
                    icon = new Uri.Builder()
                            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                            .authority(info.activityInfo.applicationInfo.packageName)
                            .encodedPath(String.valueOf(info.activityInfo.getIconResource()))
                            .toString();
                } else {
                    // No icon for app, use default app icon.
                    icon = String.valueOf(com.android.internal.R.drawable.sym_def_app_icon);
                }
                inserter.prepareForInsert();
                inserter.bind(nameCol, title);
                inserter.bind(descriptionCol, description);
                inserter.bind(packageCol, info.activityInfo.applicationInfo.packageName);
                inserter.bind(classCol, info.activityInfo.name);
                inserter.bind(iconCol, icon);
                inserter.execute();
            }
            mDb.setTransactionSuccessful();
        } finally {
            mDb.endTransaction();
        }
        if (DBG) Log.d(TAG, "Finished updating database.");
    }

    
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    
    /**
     * Gets the application component name from an application URI.
     * TODO: Move this to android.provider.Applications?
     * 
     * @param appUri A URI of the form 
     * "content://applications/applications/&lt;packageName&gt;/&lt;className&gt;".
     * @return The component name for the application, or
     * <code>null</null> if the given URI was <code>null</code>
     * or malformed.
     */
    public static ComponentName getComponentName(Uri appUri) {
        if (appUri == null) {
            return null;
        }
        List<String> pathSegments = appUri.getPathSegments();
        if (pathSegments.size() < 3) {
            return null;
        }
        String packageName = pathSegments.get(1);
        String name = pathSegments.get(2);
        return new ComponentName(packageName, name);
    }
    
    /**
     * Gets the URI for an application.
     * TODO: Move this to android.provider.Applications?
     * 
     * @param packageName The name of the application's package.
     * @param className The class name of the application.
     * @return A URI of the form 
     * "content://applications/applications/&lt;packageName&gt;/&lt;className&gt;".
     */
    public static Uri getUri(String packageName, String className) {
        return Applications.CONTENT_URI.buildUpon()
                .appendEncodedPath("applications")
                .appendPath(packageName)
                .appendPath(className)
                .build();
    }
}
