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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * This class is purely here to get launch intents.
 */
public class ApplicationLauncher extends Activity {
    private static final String TAG = ApplicationLauncher.class.getSimpleName();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        boolean handled = false;
        if (intent != null) {
            String action = intent.getAction();
            if (Intent.ACTION_MAIN.equals(action)) {
                // Launch the component given in the query string
                Uri contentUri = intent.getData();
                ComponentName componentName = ApplicationsProvider.getComponentName(contentUri);
                if (componentName != null) {
                    Intent launchIntent = new Intent(Intent.ACTION_MAIN);
                    launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    launchIntent.setComponent(componentName);
                    try {
                        startActivity(launchIntent);
                    } catch (ActivityNotFoundException ex) {
                        Log.w(TAG, "Activity not found: " + componentName);
                    }
                    handled = true;
                }
            } 
        }
        if (!handled) {
            Log.w(TAG, "Unhandled intent: " + intent);
        }
        finish();
    }

}
