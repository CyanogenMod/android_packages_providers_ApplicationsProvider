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

import android.content.pm.PackageManager;

import java.util.Map;

/**
 * An extension of {@link ApplicationsProvider} that makes its testing easier.
 */
public class ApplicationsProviderForTesting extends ApplicationsProvider {

    private PackageManager mMockPackageManager;

    private MockActivityManager mMockActivityManager;

    private boolean mCanRankByLaunchCount;

    @Override
    protected PackageManager getPackageManager() {
        return mMockPackageManager;
    }

    protected void setMockPackageManager(PackageManager mockPackageManager) {
        mMockPackageManager = mockPackageManager;
    }

    @Override
    protected Map<String, Integer> fetchLaunchCounts() {
        return mMockActivityManager.getAllPackageLaunchCounts();
    }

    protected void setMockActivityManager(MockActivityManager mockActivityManager) {
        mMockActivityManager = mockActivityManager;
    }

    protected void setCanRankByLaunchCount(boolean canRankByLaunchCount) {
        mCanRankByLaunchCount = canRankByLaunchCount;
    }

    @Override
    protected boolean canRankByLaunchCount() {
        return mCanRankByLaunchCount;
    }

    @Override
    protected void scheduleRegularLaunchCountUpdates() {
    }
}
