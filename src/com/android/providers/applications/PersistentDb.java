/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;


/**
 * Manages a database that stores those details of applications that are
 * managed by the ApplicationsProvider. As these properties are not retrievable
 * from an external service and therefore have to be stored in a persistent
 * database.
 *
 * Currently used to store the launch counts of applications to improve ranking
 * by popularity.
 */
class PersistentDb {

    private static final boolean DBG = false;

    private static final String TAG = "ApplicationsProvider";

    private static final int DB_VERSION = 1;

    // Table names and constants.
    private static final String APPLICATIONS_TABLE = "applications";
    public static final String PACKAGE = "package";
    public static final String CLASS = "class";
    public static final String LAUNCH_COUNT = "launch_count";

    private static final String INCREASE_LAUNCH_COUNT_SQL =
            "UPDATE " + APPLICATIONS_TABLE +
            " SET " + LAUNCH_COUNT + " = " + LAUNCH_COUNT + " + 1" +
            " WHERE " + PACKAGE + " = ?" +
            " AND " + CLASS + " = ?";

    private SQLiteDatabase mDb;

    public PersistentDb(Context context) {
        openDatabase(context);
    }

    private void openDatabase(Context context) {
        try {
            mDb = new DbOpenHelper(context, DB_VERSION).getWritableDatabase();
            if (DBG) Log.d(TAG, "Opened persistent ApplicationsProvider database");
        } catch (SQLiteException e) {
            Log.w(TAG, "Could not open persistent ApplicationsProvider database", e);
        }
    }

    /**
     * Returns the persisted launch count of each component.
     */
    Map<ComponentName, Long> getLaunchCounts() {
        Cursor cursor = null;
        try {
            cursor = mDb.query(
                  APPLICATIONS_TABLE,
                  new String[] { PACKAGE, CLASS, LAUNCH_COUNT },
                  null, null, null, null, null);
        } catch (SQLiteException e) {
            Log.w(TAG, "Could not query persistent database.", e);
            return new HashMap<ComponentName, Long>();
        }

        try {
            int packageCol = cursor.getColumnIndex(PACKAGE);
            int classCol = cursor.getColumnIndex(CLASS);
            int launchCountCol = cursor.getColumnIndex(LAUNCH_COUNT);

            HashMap<ComponentName, Long> packageLaunchCounts = new HashMap<ComponentName, Long>();
            while (cursor.moveToNext()) {
                ComponentName componentName = new ComponentName(
                    cursor.getString(packageCol),
                    cursor.getString(classCol));

                packageLaunchCounts.put(
                        componentName,
                        cursor.getLong(launchCountCol));
            }
            return packageLaunchCounts;
        } finally {
          if (cursor != null && !cursor.isClosed()) {
              cursor.close();
          }
        }
    }

    /**
     * Add a new component to the database with a zero launch count.
     */
    void insertNewComponent(ComponentName component) {
        if (DBG) Log.d(TAG, "Adding component " + component + " to persistent database.");
        try {
            mDb.beginTransaction();
            try {
                ContentValues columns = new ContentValues();
                columns.put(PACKAGE, component.getPackageName());
                columns.put(CLASS, component.getClassName());
                columns.put(LAUNCH_COUNT, 0);

                mDb.insert(APPLICATIONS_TABLE, null, columns);
                mDb.setTransactionSuccessful();
            } finally {
                mDb.endTransaction();
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "Could not add component " + component + " to persistent database.", e);
        }
    }

    /**
     * Increase the launch count of a single package.
     */
    void increaseLaunchCount(ComponentName componentName) {
        try {
            mDb.beginTransaction();
            try {
              mDb.execSQL(INCREASE_LAUNCH_COUNT_SQL, new Object[] {
                  componentName.getPackageName(),
                  componentName.getClassName()});

              mDb.setTransactionSuccessful();
            } finally {
              mDb.endTransaction();
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "Could not increase persisted launch count of " + componentName, e);
        }
    }

    private class DbOpenHelper extends SQLiteOpenHelper {

        private static final String DB_NAME = "applicationsprovider.db";

        public DbOpenHelper(Context context, int version) {
            super(context, DB_NAME, null, version);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new UnsupportedOperationException("ApplicationsProvider DB cannot"
                    + " require an upgrade - only one version exists so far.");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (DBG) Log.d(TAG, "Creating persistent database for ApplicationsProvider.");

            db.execSQL("CREATE TABLE " + APPLICATIONS_TABLE + " (" +
                    PACKAGE + " TEXT, " +
                    CLASS + " TEXT, " +
                    LAUNCH_COUNT + " INTEGER DEFAULT 0" +
                    ");");

            db.execSQL("CREATE INDEX componentNameIndex ON " + APPLICATIONS_TABLE + " ("
                    + PACKAGE + ","
                    + CLASS
                    + ");");
        }
    }
}
