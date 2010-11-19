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

// Copyright 2011 Google Inc. All Rights Reserved.

package com.android.providers.applications;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

public class MockPackageManager extends android.test.mock.MockPackageManager {

    private List<ResolveInfo> mPackages = new ArrayList<ResolveInfo>();

    /**
     * Returns all packages registered with the mock package manager.
     * ApplicationsProvider uses this method to query the list of applications.
     */
    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        return mPackages;
    }

    /**
     * Adds a new package to the mock package manager.
     *
     * Example:
     * addPackage("Email", new ComponentName("com.android.email", "com.android.email.MainView"));
     *
     * @param title the user-friendly title of the application (this is what
     *         users will search for)
     */
    public void addPackage(final String title, ComponentName componentName) {
        // Set the application's title.
        ResolveInfo packageInfo = new ResolveInfo() {
            @Override
            public CharSequence loadLabel(PackageManager pm) {
                return title;
            }
        };

        // Set the application's ComponentName.
        packageInfo.activityInfo = new ActivityInfo();
        packageInfo.activityInfo.name = componentName.getClassName();
        packageInfo.activityInfo.applicationInfo = new ApplicationInfo();
        packageInfo.activityInfo.applicationInfo.packageName = componentName.getPackageName();

        mPackages.add(packageInfo);
    }
}
