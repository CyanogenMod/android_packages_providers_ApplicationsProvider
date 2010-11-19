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
import android.net.Uri;
import android.provider.Applications;
import android.test.ProviderTestCase2;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Instrumentation test for the ApplicationsProvider.
 *
 * The tests use an IsolatedContext, and are not affected by the real list of
 * applications on the device. The ApplicationsProvider's persistent database
 * is also created in an isolated context so it doesn't interfere with the
 * database of the actual ApplicationsProvider installed on the device.
 */
public class ApplicationsProviderTest extends ProviderTestCase2<ApplicationsProviderForTesting> {

    private ApplicationsProviderForTesting mProvider;

    public ApplicationsProviderTest() {
        super(ApplicationsProviderForTesting.class, Applications.AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProvider = getProvider();
        initProvider(mProvider);
    }

    /**
     * Ensures that the ApplicationsProvider is in a ready-to-test state.
     */
    private void initProvider(ApplicationsProviderForTesting provider) throws Exception {
        // Decouple the provider from Android's real list of applications.
        MockPackageManager mockPackageManager = new MockPackageManager();
        addDefaultTestPackages(mockPackageManager);
        provider.setMockPackageManager(mockPackageManager);

        // We need to wait for the applications database to be updated (it's
        // updated with a slight delay by a separate thread) before we can use
        // the ApplicationsProvider.
        Runnable markerRunnable = new Runnable() {
            @Override
            public void run() {
            }
        };
        FutureTask<Void> onApplicationsListUpdated = new FutureTask<Void>(markerRunnable, null);

        provider.setOnApplicationsListUpdated(onApplicationsListUpdated);
        onApplicationsListUpdated.get();
    }

    /**
     * Register a few default applications with the ApplicationsProvider that
     * tests can query.
     */
    private void addDefaultTestPackages(MockPackageManager mockPackageManager) {
        mockPackageManager.addPackage(
                "Email", new ComponentName("com.android.email", "com.android.email.MainView"));
        mockPackageManager.addPackage(
                "Ebay", new ComponentName("com.android.ebay", "com.android.ebay.Shopping"));
        mockPackageManager.addPackage(
                "Fakeapp", new ComponentName("com.android.fakeapp", "com.android.fakeapp.FakeView"));

        // Apps that can be used to test ordering.
        mockPackageManager.addPackage("AlphabeticA", new ComponentName("a", "a.AView"));
        mockPackageManager.addPackage("AlphabeticB", new ComponentName("b", "b.BView"));
        mockPackageManager.addPackage("AlphabeticC", new ComponentName("c", "c.CView"));
        mockPackageManager.addPackage("AlphabeticD", new ComponentName("d", "d.DView"));
    }

    public void testSearch_singleResult() {
        testSearch("ema", "Email");
    }

    public void testSearch_multipleResults() {
        testSearch("e", "Ebay", "Email");
    }

    public void testSearch_noResults() {
        testSearch("nosuchapp");
    }

    public void testSearch_orderingIsAlphabeticByDefault() {
        testSearch("alphabetic", "AlphabeticA", "AlphabeticB", "AlphabeticC", "AlphabeticD");
    }

    public void testSearch_emptySearchQueryReturnsEverything() {
        testSearch("",
                "AlphabeticA", "AlphabeticB", "AlphabeticC", "AlphabeticD",
                "Ebay", "Email", "Fakeapp");
    }

    public void testSearch_appsAreRankedByLaunchCount() throws Exception {
        // Original ranking: A, B, C, D (alphabetic; all launch counts are 0
        // by default).
        increaseLaunchCount(new ComponentName("b", "b.BView"));
        increaseLaunchCount(new ComponentName("d", "d.DView"));
        increaseLaunchCount(new ComponentName("d", "d.DView"));

        // New ranking: D, B, A, C (first by launch count, then
        // - if the launch counts of two apps are equal - alphabetically)
        testSearch("alphabetic", "AlphabeticD", "AlphabeticB", "AlphabeticA", "AlphabeticC");
    }

    /**
     * Tests that the launch count values are persisted even if the
     * ApplicationsProvider is restarted.
     */
    public void testSearch_launchCountInformationIsPersistent() throws Exception {
        // Original ranking: A, B, C, D (alphabetic; all launch counts are 0
        // by default).
        increaseLaunchCount(new ComponentName("b", "b.BView"));
        increaseLaunchCount(new ComponentName("d", "d.DView"));
        increaseLaunchCount(new ComponentName("d", "d.DView"));

        // New ranking: D, B, A, C (first by launch count, then
        // - if the launch counts of two apps are equal - alphabetically)
        testSearch("alphabetic", "AlphabeticD", "AlphabeticB", "AlphabeticA", "AlphabeticC");

        // Now we'll create a new ApplicationsProvider instance (the provider
        // may be killed by Android at any time) and verify that it has access
        // to the same launch count information as the original provider instance.
        // The new instance will use the same IsolatedContext as the previous one.
        ApplicationsProviderForTesting newProviderInstance = createNewProvider(mProvider.getContext());
        assertNotSame(newProviderInstance, mProvider);

        // Override the previous provider with the new instance in the
        // ContentResolver.
        getMockContentResolver().addProvider(Applications.AUTHORITY, newProviderInstance);

        initProvider(newProviderInstance);

        // Verify that the launch-count-dependent ordering is still correct.
        testSearch("alphabetic", "AlphabeticD", "AlphabeticB", "AlphabeticA", "AlphabeticC");
    }

    private void testSearch(String searchQuery, String... expectedResultsInOrder) {
        Cursor cursor = Applications.search(getMockContentResolver(), searchQuery);

        assertNotNull(cursor);
        assertFalse(cursor.isClosed());

        verifySearchResults(cursor, expectedResultsInOrder);

        cursor.close();
    }

    private void verifySearchResults(Cursor cursor, String... expectedResultsInOrder) {
        int expectedResultCount = expectedResultsInOrder.length;
        assertEquals("Wrong number of app search results.",
                expectedResultCount, cursor.getCount());

        if (expectedResultCount > 0) {
            cursor.moveToFirst();
            int nameIndex = cursor.getColumnIndex(ApplicationsProvider.NAME);
            // Verify that the actual results match the expected ones.
            for (int i = 0; i < cursor.getCount(); i++) {
                assertEquals("Wrong search result at position " + i,
                        expectedResultsInOrder[i], cursor.getString(nameIndex));
                cursor.moveToNext();
            }
        }
    }

    /**
     * Makes the ApplicationsProvider increase the launch count of this
     * application stored in its database.
     */
    private void increaseLaunchCount(ComponentName componentName) {
        Applications.increaseLaunchCount(getMockContentResolver(), componentName);
    }

    private ApplicationsProviderForTesting createNewProvider(Context context) throws Exception {
        ApplicationsProviderForTesting newProviderInstance =
                ApplicationsProviderForTesting.class.newInstance();
        newProviderInstance.attachInfo(context, null);
        return newProviderInstance;
    }
}
