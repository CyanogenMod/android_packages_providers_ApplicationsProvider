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
import java.util.List;

/**
 * Fetches the list of applications installed on the phone to provide search suggestions.
 * If the functionality of this provider changes, the documentation at
 * {@link android.provider.Applications} should be updated.
 */
public class ApplicationsProvider extends ContentProvider {
    
    private static final boolean DBG = false;
    
    private static final String TAG = "ApplicationsProvider";

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private static final int SEARCH_SUGGEST = 0;
    
    static {
        sURIMatcher.addURI(Applications.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY,
                SEARCH_SUGGEST);
        sURIMatcher.addURI(Applications.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*",
                SEARCH_SUGGEST);
    };
    
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

    // Broadcast receiver for updating applications list.
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                // TODO: Instead of rebuilding the whole list on every change,
                // just add, remove or update the application that has changed.
                // Adding and updating seem tricky, since I can't see an easy way to list the
                // launchable activities in a given package.
                updateApplicationsList();
            }
        }
    };
    
    @Override
    public boolean onCreate() {
        createDatabase();
        registerBroadcastReceiver();
        updateApplicationsList();
        return true;
    }
    
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
                "source INTEGER REFERENCES " + APPLICATIONS_TABLE + "(" + _ID + ")" +
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
                "SELECT _TOKENIZE('applicationsLookup', new." + _ID + ", new." + NAME + ", ' ');" +
                "END");
        mDb.execSQL("CREATE TRIGGER applicationsLookup_insert AFTER INSERT ON " + 
                APPLICATIONS_TABLE + " " +
                "BEGIN " +
                "SELECT _TOKENIZE('applicationsLookup', new." + _ID + ", new." + NAME + ", ' ');" +
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
        return SearchManager.SUGGEST_MIME_TYPE;
    }

    /**
     * Queries for a given search term and returns a cursor containing
     * suggestions ordered by best match.
     */
    @Override
    public Cursor query(Uri uri, String[] projectionIn, String selection,
            String[] selectionArgs, String sortOrder) {

        if (sURIMatcher.match(uri) != SEARCH_SUGGEST) {
            throw new IllegalArgumentException("Unknown URL " + uri);
        }

        // Get the search text
        String query = null;
        if (uri.getPathSegments().size() > 1) {
            query = uri.getLastPathSegment().toLowerCase();
        }
        if (TextUtils.isEmpty(query)) {
            return null;
        }

        // Build SQL query
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(APPLICATIONS_TABLE);
        qb.setProjectionMap(sSearchSuggestionsProjectionMap);
        qb.appendWhere(buildApplicationsLookupWhereClause(query));
        
        Cursor cursor = qb.query(mDb, projectionIn, selection, selectionArgs,
                null, null, sortOrder);
        return cursor;
    }
    
    // Stolen from ContactsProvider.buildPeopleLookupWhereClause(String)
    @SuppressWarnings("deprecation")
    private String buildApplicationsLookupWhereClause(String filterParam) {
        StringBuilder filter = new StringBuilder(
                APPLICATIONS_TABLE + "." + _ID + 
                " IN (SELECT source FROM applicationsLookup WHERE token GLOB ");
        // NOTE: Query parameters won't work here since the SQL compiler
        // needs to parse the actual string to know that it can use the
        // index to do a prefix scan.
        DatabaseUtils.appendEscapedSQLString(filter, 
                DatabaseUtils.getHexCollationKey(filterParam) + "*");
        filter.append(')');
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
        return map;
    }
    
    /**
     * Updates the cached list of installed applications.
     */
    private void updateApplicationsList() {
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
                // Use a resource Uri for the icon.
                String icon = new Uri.Builder()
                        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                        .authority(info.activityInfo.applicationInfo.packageName)
                        .encodedPath(String.valueOf(info.activityInfo.getIconResource()))
                        .toString();
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
