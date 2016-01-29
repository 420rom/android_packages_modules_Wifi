/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wifi;

import static com.android.server.wifi.ScanTestUtil.NativeScanSettingsBuilder;
import static com.android.server.wifi.ScanTestUtil.assertNativeScanSettingsEquals;
import static com.android.server.wifi.ScanTestUtil.channelsToSpec;
import static com.android.server.wifi.ScanTestUtil.createRequest;
import static com.android.server.wifi.ScanTestUtil.getAllChannels;
import static com.android.server.wifi.ScanTestUtil.installWlanWifiNative;
import static com.android.server.wifi.ScanTestUtil.setupMockChannels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.validateMockitoUsage;

import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanSettings;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.WifiNative.BucketSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

/**
 * Unit tests for {@link com.android.server.wifi.MultiClientScheduler}.
 */
@SmallTest
public class MultiClientSchedulerTest {

    private static final int DEFAULT_MAX_BUCKETS = 9;
    private static final int DEFAULT_MAX_CHANNELS = 23;
    private static final int DEFAULT_MAX_BATCH = 11;
    private static final int DEFAULT_MAX_AP_PER_SCAN = 33;

    private WifiNative mWifiNative;
    private MultiClientScheduler mScheduler;

    @Before
    public void setUp() throws Exception {
        mWifiNative = mock(WifiNative.class);
        setupMockChannels(mWifiNative,
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650, 5660});
        installWlanWifiNative(mWifiNative);

        mScheduler = new MultiClientScheduler();
        mScheduler.setMaxBuckets(DEFAULT_MAX_BUCKETS);
        mScheduler.setMaxChannels(DEFAULT_MAX_CHANNELS);
        mScheduler.setMaxBatch(DEFAULT_MAX_BATCH);
        mScheduler.setMaxApPerScan(DEFAULT_MAX_AP_PER_SCAN);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    @Test
    public void noRequest() {
        Collection<ScanSettings> requests = Collections.emptyList();

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals(40000, schedule.base_period_ms);
        assertBuckets(schedule, 0);
    }

    @Test
    public void singleRequest() {
        Collection<ScanSettings> requests = Collections.singleton(createRequest(
                WifiScanner.WIFI_BAND_BOTH, 20000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        ));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals(20000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
    }

    @Test
    public void singleRequestWithoutPredefinedBucket() {
        Collection<ScanSettings> requests = Collections.singleton(createRequest(
                WifiScanner.WIFI_BAND_BOTH, 7500, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        ));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 10000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
    }

    @Test
    public void fewRequests() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(WifiScanner.WIFI_BAND_BOTH, 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));
        requests.add(createRequest(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY, 14000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 10000, schedule.base_period_ms);
        assertBuckets(schedule, 2);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
    }

    @Test
    public void manyRequests() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(WifiScanner.WIFI_BAND_BOTH, 20000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));
        requests.add(createRequest(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY, 15000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));
        requests.add(createRequest(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY, 10000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 10000, schedule.base_period_ms);
        assertBuckets(schedule, 2);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, false);
        }
    }

    @Test
    public void requestsWithNoPeriodCommonDenominator() {
        ArrayList<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(WifiScanner.WIFI_BAND_BOTH, 299999, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));
        requests.add(createRequest(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY, 10500, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 10000, schedule.base_period_ms);
        assertBuckets(schedule, 2);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
    }

    @Test
    public void manyRequestsDifferentReportScans() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(5175), 40000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL));
        requests.add(createRequest(channelsToSpec(2400), 40000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(2450), 40000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));
        requests.add(createRequest(channelsToSpec(5150), 40000, 0, 20,
                WifiScanner.REPORT_EVENT_NO_BATCH));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 40000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
    }

    @Test
    public void exceedMaxBatch() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(5175), 20000, 10, 20,
                WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL));

        mScheduler.setMaxBatch(5);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 20000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
        assertEquals("maxScansToCache", 5, schedule.report_threshold_num_scans);
    }

    @Test
    public void defaultMaxBatch() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(5175), 40000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL));

        mScheduler.setMaxBatch(6);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 40000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
        assertEquals("maxScansToCache", 6, schedule.report_threshold_num_scans);
    }

    @Test
    public void exceedMaxAps() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(5175), 30000, 10, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxApPerScan(5);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("maxScansToCache", 5, schedule.max_ap_per_scan);
    }

    @Test
    public void defaultMaxAps() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(5175), 30000, 10, 0,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxApPerScan(8);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("maxApsPerScan", 8, schedule.max_ap_per_scan);
    }

    @Test
    public void optimalScheduleExceedsNumberOfAvailableBuckets() {
        ArrayList<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(2400), 20000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(2450), 10000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(5150), 40000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxBuckets(2);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 20000, schedule.base_period_ms);
        assertBuckets(schedule, 2);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, true, true);
        }
    }

    @Test
    public void optimalScheduleExceedsNumberOfAvailableBuckets2() {
        ArrayList<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(2400), 20000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(2450), 40000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(5150), 2560000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxBuckets(2);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 20000, schedule.base_period_ms);
        assertBuckets(schedule, 2);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, true, true);
        }
    }

    /**
     * Ensure that a channel request is placed in the bucket closest to the original
     * period and not the bucket it is initially placed in. Here the 12 min period is
     * initially placed in the 640s bucket, but that bucket is eliminated because it
     * would be a 7th bucket. This test ensures that the request is placed in the 1280s
     * bucket and not the 320s bucket.
     */
    @Test
    public void optimalScheduleExceedsNumberOfAvailableBucketsClosestToOriginal() {
        ArrayList<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(2400), 40 * 1000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(2450), 20 * 1000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(5150), 160 * 1000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(5175), 320 * 1000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(5600), 10 * 1000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(5650), 1280 * 1000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        requests.add(createRequest(channelsToSpec(5660), 720 * 1000, 0, 20, // 12 min
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxBuckets(6);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 10000, schedule.base_period_ms);
        assertBuckets(schedule, 6);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, true, true);
        }
    }

    @Test
    public void optimalScheduleExceedsMaxChannelsOnSingleBand() {
        ArrayList<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(2400, 2450), 20000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxBuckets(2);
        mScheduler.setMaxChannels(1);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 20000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, true, true);
        }
    }

    @Test
    public void optimalScheduleExceedsMaxChannelsOnMultipleBands() {
        ArrayList<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(2400, 2450, 5150), 20000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxBuckets(2);
        mScheduler.setMaxChannels(2);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 20000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, true, true);
        }
    }

    @Test
    public void optimalScheduleExceedsMaxChannelsOnMultipleBandsFromMultipleRequests() {
        ArrayList<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(channelsToSpec(2400, 2450), 20000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(WifiScanner.WIFI_BAND_5_GHZ, 20000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));

        mScheduler.setMaxBuckets(2);
        mScheduler.setMaxChannels(2);
        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 20000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, true, true);
        }
    }

    @Test
    public void exactRequests() {
        scheduleAndTestExactRequest(createRequest(WifiScanner.WIFI_BAND_BOTH, 20000, 0,
                20, WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL));
        scheduleAndTestExactRequest(createRequest(WifiScanner.WIFI_BAND_5_GHZ, 40000, 3,
                13, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        scheduleAndTestExactRequest(createRequest(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY, 10000, 2,
                10, WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT));
        scheduleAndTestExactRequest(createRequest(WifiScanner.WIFI_BAND_BOTH, 25000, 0,
                10, WifiScanner.REPORT_EVENT_NO_BATCH));
        scheduleAndTestExactRequest(createRequest(WifiScanner.WIFI_BAND_BOTH, 25000, 3,
                0, WifiScanner.REPORT_EVENT_NO_BATCH));
        scheduleAndTestExactRequest(createRequest(channelsToSpec(2400, 5175, 5650) , 25000, 3,
                0, WifiScanner.REPORT_EVENT_NO_BATCH));
    }

    @Test
    public void singleExponentialBackOffRequest() {
        Collection<ScanSettings> requests = Collections.singleton(createRequest(
                WifiScanner.WIFI_BAND_BOTH, 20000, 160000, 2, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN
        ));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals(20000, schedule.base_period_ms);
        assertBuckets(schedule, 1);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
    }

    @Test
    public void exponentialBackOffAndRegularRequests() {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(createRequest(WifiScanner.WIFI_BAND_BOTH, 20000, 200000, 1,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN));
        requests.add(createRequest(channelsToSpec(5175), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL));

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        assertEquals("base_period_ms", 20000, schedule.base_period_ms);
        assertBuckets(schedule, 2);
        for (ScanSettings request : requests) {
            assertSettingsSatisfied(schedule, request, false, true);
        }
    }

    public void scheduleAndTestExactRequest(ScanSettings settings) {
        Collection<ScanSettings> requests = new ArrayList<>();
        requests.add(settings);

        mScheduler.updateSchedule(requests);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        int expectedPeriod = computeExpectedPeriod(settings.periodInMs);
        NativeScanSettingsBuilder expectedBuilder = new NativeScanSettingsBuilder()
                .withBasePeriod(expectedPeriod)
                .withMaxApPerScan(settings.numBssidsPerScan == 0
                        ? DEFAULT_MAX_AP_PER_SCAN
                        : settings.numBssidsPerScan)
                .withMaxScansToCache(settings.maxScansToCache == 0
                        ? DEFAULT_MAX_BATCH
                        : settings.maxScansToCache);

        if (settings.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
            expectedBuilder.addBucketWithChannels(expectedPeriod, settings.reportEvents,
                    getAllChannels(settings));
        } else {
            expectedBuilder.addBucketWithBand(expectedPeriod, settings.reportEvents, settings.band);
        }
        assertNativeScanSettingsEquals(expectedBuilder.build(), schedule);
    }

    private void assertBuckets(WifiNative.ScanSettings schedule, int numBuckets) {
        assertEquals("num_buckets", numBuckets, schedule.num_buckets);
        assertNotNull("buckets was null", schedule.buckets);
        assertEquals("num_buckets and actual buckets", schedule.num_buckets,
                schedule.buckets.length);
        for (int i = 0; i < numBuckets; i++) {
            assertNotNull("bucket[" + i + "] was null", schedule.buckets[i]);
            if (schedule.buckets[i].band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
                assertTrue("num channels <= 0", schedule.buckets[i].num_channels > 0);
                assertTrue("bucket channels > max channels",
                        schedule.buckets[i].num_channels <= mScheduler.getMaxChannels());
                assertNotNull("Channels was null", schedule.buckets[i].channels);
                for (int c = 0; c < schedule.buckets[i].num_channels; c++) {
                    assertNotNull("Channel was null", schedule.buckets[i].channels[c]);
                }
            } else {
                assertTrue("Invalid band: " + schedule.buckets[i].band,
                        schedule.buckets[i].band > WifiScanner.WIFI_BAND_UNSPECIFIED
                        && schedule.buckets[i].band <= WifiScanner.WIFI_BAND_BOTH_WITH_DFS);
            }
        }
    }

    private static void assertSettingsSatisfied(WifiNative.ScanSettings schedule,
            ScanSettings settings, boolean bucketsLimited, boolean exactPeriod) {
        assertTrue("bssids per scan: " + schedule.max_ap_per_scan + " /<= "
                + settings.numBssidsPerScan,
                schedule.max_ap_per_scan <= settings.numBssidsPerScan);

        if (settings.maxScansToCache > 0) {
            assertTrue("scans to cache: " + schedule.report_threshold_num_scans + " /<= "
                    + settings.maxScansToCache,
                    schedule.report_threshold_num_scans <= settings.maxScansToCache);
        }

        HashSet<Integer> channelSet = new HashSet<>();
        for (ChannelSpec channel : getAllChannels(settings)) {
            channelSet.add(channel.frequency);
        }

        StringBuilder ignoreString = new StringBuilder();

        HashSet<Integer> scheduleChannelSet = new HashSet<>();
        for (int b = 0; b < schedule.num_buckets; b++) {
            BucketSettings bucket = schedule.buckets[b];
            if ((settings.reportEvents & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0) {
                if ((bucket.report_events & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) == 0) {
                    ignoreString
                        .append(" ")
                        .append(WifiChannelHelper.toString(getAllChannels(bucket)))
                        .append("=after_each_scan:")
                        .append(bucket.report_events & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN)
                        .append("!=")
                        .append(settings.reportEvents & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
                    continue;
                }
            }
            if ((settings.reportEvents & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                if ((bucket.report_events & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) == 0) {
                    ignoreString
                        .append(" ")
                        .append(WifiChannelHelper.toString(getAllChannels(bucket)))
                        .append("=full_result:")
                        .append(bucket.report_events & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT)
                        .append("!=")
                        .append(settings.reportEvents & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT);
                    continue;
                }
            }
            if ((settings.reportEvents & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                if ((bucket.report_events & WifiScanner.REPORT_EVENT_NO_BATCH) != 0) {
                    ignoreString
                        .append(" ")
                        .append(WifiChannelHelper.toString(getAllChannels(bucket)))
                        .append("=no_batch:")
                        .append(bucket.report_events & WifiScanner.REPORT_EVENT_NO_BATCH)
                        .append("!=")
                        .append(settings.reportEvents & WifiScanner.REPORT_EVENT_NO_BATCH);
                    continue;
                }
            }
            int expectedPeriod;

            if (settings.maxPeriodInMs != 0 && settings.periodInMs != settings.maxPeriodInMs) {
                // exponential back off scan
                expectedPeriod = settings.periodInMs;
            } else {
                if (bucketsLimited) {
                    expectedPeriod = computeExpectedPeriod(settings.periodInMs, schedule);
                } else {
                    expectedPeriod = computeExpectedPeriod(settings.periodInMs);
                }
            }

            if (exactPeriod) {
                if (bucket.period_ms != expectedPeriod) {
                    ignoreString
                            .append(" ")
                            .append(WifiChannelHelper.toString(getAllChannels(bucket)))
                            .append("=period:")
                            .append(bucket.period_ms)
                            .append("!=")
                            .append(settings.periodInMs);
                    continue;
                }
            } else {
                if (bucket.period_ms > expectedPeriod) {
                    ignoreString
                            .append(" ")
                            .append(WifiChannelHelper.toString(getAllChannels(bucket)))
                            .append("=period:")
                            .append(bucket.period_ms)
                            .append(">")
                            .append(settings.periodInMs);
                    continue;
                }
            }
            for (ChannelSpec channel : getAllChannels(bucket)) {
                scheduleChannelSet.add(channel.frequency);
            }
        }

        assertTrue("expected that " + scheduleChannelSet + " contained " + channelSet
                + ", Channel ignore reasons:" + ignoreString.toString(),
                scheduleChannelSet.containsAll(channelSet));
    }

    private static int[] getPredefinedBuckets() {
        try {
            Field f = MultiClientScheduler.class.getDeclaredField("PREDEFINED_BUCKET_PERIODS");
            f.setAccessible(true);
            return (int[]) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Could not get predefined buckets", e);
        }
    }
    private static final int[] PREDEFINED_BUCKET_PERIODS = getPredefinedBuckets();

    // find closest bucket period to the requested period
    private static int computeExpectedPeriod(int requestedPeriod) {
        int period = 0;
        int minDiff = Integer.MAX_VALUE;
        for (int bucketPeriod : PREDEFINED_BUCKET_PERIODS) {
            int diff = Math.abs(bucketPeriod - requestedPeriod);
            if (diff < minDiff) {
                minDiff = diff;
                period = bucketPeriod;
            }
        }
        return period;
    }

    // find closest bucket period to the requested period that exists in the schedule
    private static int computeExpectedPeriod(int requestedPeriod,
            WifiNative.ScanSettings schedule) {
        int period = 0;
        int minDiff = Integer.MAX_VALUE;
        for (int i = 0; i < schedule.num_buckets; ++i) {
            int bucketPeriod = schedule.buckets[i].period_ms;
            int diff = Math.abs(bucketPeriod - requestedPeriod);
            if (diff < minDiff) {
                minDiff = diff;
                period = bucketPeriod;
            }
        }
        return period;
    }
}
