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
 * limitations under the License.
 */

package com.android.server.wifi;

import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementation of the WifiScanner HAL API that uses wpa_supplicant to perform all scans
 * @see com.android.server.wifi.WifiScannerImpl for more details on each method
 */
public class SupplicantWifiScannerImpl extends WifiScannerImpl implements Handler.Callback {
    private static final String TAG = "SupplicantWifiScannerImpl";
    private static final boolean DBG = false;

    private static final int SCAN_BUFFER_CAPACITY = 10;
    private static final int MAX_APS_PER_SCAN = 32;
    private static final int MAX_SCAN_BUCKETS = 16;

    private static final String ACTION_SCAN_PERIOD =
            "com.android.server.util.SupplicantWifiScannerImpl.action.SCAN_PERIOD";

    private final Context mContext;
    private final WifiNative mWifiNative;
    private final AlarmManager mAlarmManager;
    private final Handler mEventHandler;

    private Object mSettingsLock = new Object();

    // Next scan settings to apply when the previous scan completes
    private WifiNative.ScanSettings mPendingBackgroundScanSettings = null;
    private WifiNative.ScanEventHandler mPendingBackgroundScanEventHandler = null;
    private WifiNative.ScanSettings mPendingSingleScanSettings = null;
    private WifiNative.ScanEventHandler mPendingSingleScanEventHandler = null;

    // Active background scan settings/state
    private WifiNative.ScanSettings mBackgroundScanSettings = null;
    private WifiNative.ScanEventHandler mBackgroundScanEventHandler = null;
    private int mNextBackgroundScanPeriod = 0;
    private int mNextBackgroundScanId = 0;
    private boolean mBackgroundScanPeriodPending = false;
    private boolean mBackgroundScanPaused = false;
    private ScanBuffer mBackgroundScanBuffer = new ScanBuffer(SCAN_BUFFER_CAPACITY);

    private WifiScanner.ScanData mLatestSingleScanResult =
            new WifiScanner.ScanData(0, 0, new ScanResult[0]);

    // Settings for the currently running scan, null if no scan active
    private LastScanSettings mLastScanSettings = null;

    // Active hotlist settings
    private WifiNative.HotlistEventHandler mHotlistHandler = null;
    private ChangeBuffer mHotlistChangeBuffer = new ChangeBuffer();

    AlarmManager.OnAlarmListener mScanPeriodListener = new AlarmManager.OnAlarmListener() {
            public void onAlarm() {
                synchronized (mSettingsLock) {
                    handleScanPeriod();
                }
            }
        };

    public SupplicantWifiScannerImpl(Context context, WifiNative wifiNative, Looper looper) {
        mContext = context;
        mWifiNative = wifiNative;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        mEventHandler = new Handler(looper, this);
        WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_FAILED_EVENT, mEventHandler);
        WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_RESULTS_EVENT, mEventHandler);
    }

    @Override
    public boolean getScanCapabilities(WifiNative.ScanCapabilities capabilities) {
        capabilities.max_scan_cache_size = Integer.MAX_VALUE;
        capabilities.max_scan_buckets = MAX_SCAN_BUCKETS;
        capabilities.max_ap_cache_per_scan = MAX_APS_PER_SCAN;
        capabilities.max_rssi_sample_size = 8;
        capabilities.max_scan_reporting_threshold = SCAN_BUFFER_CAPACITY;
        capabilities.max_hotlist_bssids = 0;
        capabilities.max_significant_wifi_change_aps = 0;
        return true;
    }

    @Override
    public boolean startSingleScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        if (eventHandler == null || settings == null) {
            Log.w(TAG, "Invalid arguments for startSingleScan: settings=" + settings
                    + ",eventHandler=" + eventHandler);
            return false;
        }
        if (mPendingSingleScanSettings != null
                || (mLastScanSettings != null && mLastScanSettings.singleScanActive)) {
            Log.w(TAG, "A single scan is already running");
            return false;
        }
        synchronized (mSettingsLock) {
            mPendingSingleScanSettings = settings;
            mPendingSingleScanEventHandler = eventHandler;
            processPendingScans();
            return true;
        }
    }

    @Override
    public WifiScanner.ScanData getLatestSingleScanResults() {
        return mLatestSingleScanResult;
    }

    @Override
    public boolean startBatchedScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        if (settings == null || eventHandler == null) {
            Log.w(TAG, "Invalid arguments for startBatched: settings=" + settings
                    + ",eventHandler=" + eventHandler);
            return false;
        }

        if (settings.max_ap_per_scan < 0 || settings.max_ap_per_scan > MAX_APS_PER_SCAN) {
            return false;
        }
        if (settings.num_buckets < 0 || settings.num_buckets > MAX_SCAN_BUCKETS) {
            return false;
        }
        if (settings.report_threshold_num_scans < 0
                || settings.report_threshold_num_scans > SCAN_BUFFER_CAPACITY) {
            return false;
        }
        if (settings.report_threshold_percent < 0 || settings.report_threshold_percent > 100) {
            return false;
        }
        if (settings.base_period_ms <= 0) {
            return false;
        }
        for (int i = 0; i < settings.num_buckets; ++i) {
            WifiNative.BucketSettings bucket = settings.buckets[i];
            if (bucket.period_ms % settings.base_period_ms != 0) {
                return false;
            }
        }

        synchronized (mSettingsLock) {
            stopBatchedScan();
            Log.d(TAG, "Starting scan num_buckets=" + settings.num_buckets + ", base_period="
                    + settings.base_period_ms + " ms");
            mPendingBackgroundScanSettings = settings;
            mPendingBackgroundScanEventHandler = eventHandler;
            handleScanPeriod(); // Try to start scan immediately
            return true;
        }
    }

    @Override
    public void stopBatchedScan() {
        synchronized (mSettingsLock) {
            if (DBG) Log.d(TAG, "Stopping scan");
            mBackgroundScanSettings = null;
            mBackgroundScanEventHandler = null;
            mPendingBackgroundScanSettings = null;
            mPendingBackgroundScanEventHandler = null;
            mBackgroundScanPaused = false;
            mBackgroundScanPeriodPending = false;
            unscheduleScansLocked();
        }
    }

    @Override
    public void pauseBatchedScan() {
        synchronized (mSettingsLock) {
            if (DBG) Log.d(TAG, "Pausing scan");
            // if there isn't a pending scan then make the current scan pending
            if (mPendingBackgroundScanSettings == null) {
                mPendingBackgroundScanSettings = mBackgroundScanSettings;
                mPendingBackgroundScanEventHandler = mBackgroundScanEventHandler;
            }
            mBackgroundScanSettings = null;
            mBackgroundScanEventHandler = null;
            mBackgroundScanPeriodPending = false;
            mBackgroundScanPaused = true;

            unscheduleScansLocked();

            WifiScanner.ScanData[] results = getLatestBatchedScanResults(/* flush = */ true);
            if (mPendingBackgroundScanEventHandler != null) {
                mPendingBackgroundScanEventHandler.onScanPaused(results);
            }
        }
    }

    @Override
    public void restartBatchedScan() {
        synchronized (mSettingsLock) {
            if (DBG) Log.d(TAG, "Restarting scan");
            if (mPendingBackgroundScanEventHandler != null) {
                mPendingBackgroundScanEventHandler.onScanRestarted();
            }
            mBackgroundScanPaused = false;
            handleScanPeriod();
        }
    }

    private void unscheduleScansLocked() {
        mAlarmManager.cancel(mScanPeriodListener);
        mLastScanSettings = null; // make sure that a running scan is marked as ended
    }

    private void handleScanPeriod() {
        synchronized (mSettingsLock) {
            mBackgroundScanPeriodPending = true;
            processPendingScans();
        }
    }

    private void processPendingScans() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings != null) {
                return;
            }

            Set<Integer> freqs = new HashSet<>();
            LastScanSettings newScanSettings = new LastScanSettings(SystemClock.elapsedRealtime());

            // Update scan settings if there is a pending scan
            if (!mBackgroundScanPaused) {
                if (mPendingBackgroundScanSettings != null) {
                    mBackgroundScanSettings = mPendingBackgroundScanSettings;
                    mBackgroundScanEventHandler = mPendingBackgroundScanEventHandler;
                    mNextBackgroundScanPeriod = 0;
                    mPendingBackgroundScanSettings = null;
                    mPendingBackgroundScanEventHandler = null;
                    mBackgroundScanPeriodPending = true;
                }
                if (mBackgroundScanPeriodPending) {
                    if (mBackgroundScanSettings != null) {
                        int reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH; // default to no batch
                        boolean haveBackgroundScanChannels = false;
                        for (int bucket_id = 0; bucket_id < mBackgroundScanSettings.num_buckets;
                                ++bucket_id) {
                            WifiNative.BucketSettings bucket =
                                    mBackgroundScanSettings.buckets[bucket_id];
                            if (mNextBackgroundScanPeriod % (bucket.period_ms
                                            / mBackgroundScanSettings.base_period_ms) == 0) {
                                if ((bucket.report_events
                                                & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0) {
                                    reportEvents |= WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                                }
                                if ((bucket.report_events
                                                & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                                    reportEvents |= WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
                                }
                                // only no batch if all buckets specify it
                                if ((bucket.report_events
                                                & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                                    reportEvents &= ~WifiScanner.REPORT_EVENT_NO_BATCH;
                                }

                                if (bucket.band != WifiScanner.WIFI_BAND_UNSPECIFIED) {
                                    WifiScanner.ChannelSpec[] channels =
                                            WifiChannelHelper.getChannelsForBand(bucket.band);
                                    for (WifiScanner.ChannelSpec channel : channels) {
                                        freqs.add(channel.frequency);
                                        haveBackgroundScanChannels = true;
                                    }
                                } else {
                                    for (int channel_id = 0; channel_id < bucket.num_channels;
                                            ++channel_id) {
                                        WifiNative.ChannelSettings channel =
                                                bucket.channels[channel_id];
                                        freqs.add(channel.frequency);
                                        haveBackgroundScanChannels = true;
                                    }
                                }
                            }
                        }
                        if (haveBackgroundScanChannels) {
                            newScanSettings.setBackgroundScan(mNextBackgroundScanId++,
                                    mBackgroundScanSettings.max_ap_per_scan, reportEvents,
                                    mBackgroundScanSettings.report_threshold_num_scans,
                                    mBackgroundScanSettings.report_threshold_percent);
                        }
                    }

                    mNextBackgroundScanPeriod++;
                    mBackgroundScanPeriodPending = false;
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + mBackgroundScanSettings.base_period_ms,
                            "SupplicantWifiScannerImpl Period",
                            mScanPeriodListener, mEventHandler);
                }
            }

            if (mPendingSingleScanSettings != null) {
                boolean reportFullResults = false;
                Set<Integer> singleScanFreqs = new HashSet<>();
                for (int i = 0; i < mPendingSingleScanSettings.num_buckets; ++i) {
                    WifiNative.BucketSettings bucketSettings =
                            mPendingSingleScanSettings.buckets[i];
                    if ((bucketSettings.report_events
                                    & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                        reportFullResults = true;
                    }
                    if (bucketSettings.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
                        for (int j = 0; j < bucketSettings.num_channels; ++j) {
                            WifiNative.ChannelSettings channel = bucketSettings.channels[j];
                            singleScanFreqs.add(channel.frequency);
                        }
                    } else {
                        WifiScanner.ChannelSpec[] channels =
                                WifiChannelHelper.getChannelsForBand(bucketSettings.band);
                        for (WifiScanner.ChannelSpec channel : channels) {
                            singleScanFreqs.add(channel.frequency);
                        }
                    }
                }
                freqs.addAll(singleScanFreqs);
                newScanSettings.setSingleScan(reportFullResults, singleScanFreqs,
                        mPendingSingleScanEventHandler);

                mPendingSingleScanSettings = null;
                mPendingSingleScanEventHandler = null;
            }

            if (freqs.size() > 0) {
                boolean success = mWifiNative.scan(
                        WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, freqs);
                if (success) {
                    // TODO handle scan timeout
                    Log.d(TAG, "Starting wifi scan for " + freqs.size() + " freqs"
                            + ", background=" + newScanSettings.backgroundScanActive
                            + ", single=" + newScanSettings.singleScanActive);
                    mLastScanSettings = newScanSettings;
                } else {
                    Log.w(TAG, "Failed starting wifi scan for " + freqs.size() + " freqs");
                    // TODO indicate failure for single and background scans
                }
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case WifiMonitor.SCAN_FAILED_EVENT:
                // TODO indicate failure to caller
                Log.w(TAG, "Scan failed");
                synchronized (mSettingsLock) {
                    mLastScanSettings = null;
                }
                processPendingScans();
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                pollLatestScanData();
                processPendingScans();
                break;
            default:
                // ignore unknown event
        }
        return true;
    }

    private static final Comparator<ScanResult> SCAN_RESULT_RSSI_COMPARATOR =
            new Comparator<ScanResult>() {
        public int compare(ScanResult r1, ScanResult r2) {
            return r2.level - r1.level;
        }
    };
    private void pollLatestScanData() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings == null) {
                 // got a scan before we started scanning or after scan was canceled
                return;
            }

            if (DBG) Log.d(TAG, "Polling scan data for scan: " + mLastScanSettings.scanId);
            ArrayList<ScanDetail> nativeResults = mWifiNative.getScanResults();
            List<ScanResult> singleScanResults = new ArrayList<>();
            List<ScanResult> backgroundScanResults = new ArrayList<>();
            for (int i = 0; i < nativeResults.size(); ++i) {
                ScanResult result = nativeResults.get(i).getScanResult();
                long timestamp_ms = result.timestamp / 1000; // convert us -> ms
                if (timestamp_ms > mLastScanSettings.startTime) {
                    if (mLastScanSettings.backgroundScanActive) {
                        backgroundScanResults.add(result);
                    }
                    if (mLastScanSettings.singleScanActive
                            && mLastScanSettings.singleScanFreqs.contains(result.frequency)) {
                        singleScanResults.add(result);
                    }
                } else {
                    // was a cached result in wpa_supplicant
                }
            }

            if (mLastScanSettings.backgroundScanActive) {
                if (mBackgroundScanEventHandler != null) {
                    if ((mLastScanSettings.reportEvents
                                    & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                        for (ScanResult scanResult : backgroundScanResults) {
                            mBackgroundScanEventHandler.onFullScanResult(scanResult);
                        }
                    }
                }

                Collections.sort(backgroundScanResults, SCAN_RESULT_RSSI_COMPARATOR);
                ScanResult[] scanResultsArray = new ScanResult[Math.min(mLastScanSettings.maxAps,
                            backgroundScanResults.size())];
                for (int i = 0; i < scanResultsArray.length; ++i) {
                    scanResultsArray[i] = backgroundScanResults.get(i);
                }

                if ((mLastScanSettings.reportEvents & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                    mBackgroundScanBuffer.add(new WifiScanner.ScanData(mLastScanSettings.scanId, 0,
                                    scanResultsArray));
                }

                if (mBackgroundScanEventHandler != null) {
                    if ((mLastScanSettings.reportEvents
                                    & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0
                            || (mLastScanSettings.reportEvents
                                    & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0
                            || (mLastScanSettings.reportEvents
                                    == WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL
                                    && (mBackgroundScanBuffer.size()
                                            >= (mBackgroundScanBuffer.capacity()
                                                    * mLastScanSettings.reportPercentThreshold
                                                    / 100)
                                            || mBackgroundScanBuffer.size()
                                            >= mLastScanSettings.reportNumScansThreshold))) {
                        mBackgroundScanEventHandler.onScanStatus();
                    }
                }

                if (mHotlistHandler != null) {
                    int event = mHotlistChangeBuffer.processScan(backgroundScanResults);
                    if ((event & ChangeBuffer.EVENT_FOUND) != 0) {
                        mHotlistHandler.onHotlistApFound(
                                mHotlistChangeBuffer.getLastResults(ChangeBuffer.EVENT_FOUND));
                    }
                    if ((event & ChangeBuffer.EVENT_LOST) != 0) {
                        mHotlistHandler.onHotlistApLost(
                                mHotlistChangeBuffer.getLastResults(ChangeBuffer.EVENT_LOST));
                    }
                }
            }

            if (mLastScanSettings.singleScanActive
                    && mLastScanSettings.singleScanEventHandler != null) {
                if (mLastScanSettings.reportSingleScanFullResults) {
                    for (ScanResult scanResult : singleScanResults) {
                        mLastScanSettings.singleScanEventHandler.onFullScanResult(scanResult);
                    }
                }
                Collections.sort(singleScanResults, SCAN_RESULT_RSSI_COMPARATOR);
                mLatestSingleScanResult = new WifiScanner.ScanData(mLastScanSettings.scanId, 0,
                        singleScanResults.toArray(new ScanResult[singleScanResults.size()]));
                mLastScanSettings.singleScanEventHandler.onScanResultsAvailable();
            }

            mLastScanSettings = null;
        }
    }


    @Override
    public WifiScanner.ScanData[] getLatestBatchedScanResults(boolean flush) {
        synchronized (mSettingsLock) {
            WifiScanner.ScanData[] results = mBackgroundScanBuffer.get();
            if (flush) {
                mBackgroundScanBuffer.clear();
            }
            return results;
        }
    }

    @Override
    public boolean setHotlist(WifiScanner.HotlistSettings settings,
            WifiNative.HotlistEventHandler eventHandler) {
        if (settings == null || eventHandler == null) {
            return false;
        }
        synchronized (mSettingsLock) {
            mHotlistHandler = eventHandler;
            mHotlistChangeBuffer.setSettings(settings.bssidInfos, settings.apLostThreshold, 1);
            return true;
        }
    }

    @Override
    public void resetHotlist() {
        synchronized (mSettingsLock) {
            mHotlistChangeBuffer.clearSettings();
            mHotlistHandler = null;
        }
    }

    private static class LastScanSettings {
        public long startTime;

        public LastScanSettings(long startTime) {
            this.startTime = startTime;
        }

        // Background settings
        public boolean backgroundScanActive = false;
        public int scanId;
        public int maxAps;
        public int reportEvents;
        public int reportNumScansThreshold;
        public int reportPercentThreshold;

        public void setBackgroundScan(int scanId, int maxAps, int reportEvents,
                int reportNumScansThreshold, int reportPercentThreshold) {
            this.backgroundScanActive = true;
            this.scanId = scanId;
            this.startTime = startTime;
            this.maxAps = maxAps;
            this.reportEvents = reportEvents;
            this.reportNumScansThreshold = reportNumScansThreshold;
            this.reportPercentThreshold = reportPercentThreshold;
        }

        // Single scan settings
        public boolean singleScanActive = false;
        public boolean reportSingleScanFullResults;
        public Set<Integer> singleScanFreqs;
        public WifiNative.ScanEventHandler singleScanEventHandler;

        public void setSingleScan(boolean reportSingleScanFullResults,
                Set<Integer> singleScanFreqs, WifiNative.ScanEventHandler singleScanEventHandler) {
            singleScanActive = true;
            this.reportSingleScanFullResults = reportSingleScanFullResults;
            this.singleScanFreqs = singleScanFreqs;
            this.singleScanEventHandler = singleScanEventHandler;
        }
    }


    private static class ScanBuffer {
        private final ArrayDeque<WifiScanner.ScanData> mBuffer;
        private int mCapacity;

        public ScanBuffer(int capacity) {
            mCapacity = capacity;
            mBuffer = new ArrayDeque<>(mCapacity);
        }

        public int size() {
            return mBuffer.size();
        }

        public int capacity() {
            return mCapacity;
        }

        public boolean isFull() {
            return size() == mCapacity;
        }

        public void add(WifiScanner.ScanData scanData) {
            if (isFull()) {
                mBuffer.pollFirst();
            }
            mBuffer.offerLast(scanData);
        }

        public void clear() {
            mBuffer.clear();
        }

        public WifiScanner.ScanData[] get() {
            return mBuffer.toArray(new WifiScanner.ScanData[mBuffer.size()]);
        }
    }

    private static class ChangeBuffer {
        public static int EVENT_NONE = 0;
        public static int EVENT_LOST = 1;
        public static int EVENT_FOUND = 2;

        public static int STATE_FOUND = 0;

        private WifiScanner.BssidInfo[] mBssidInfos = null;
        private int mApLostThreshold;
        private int mMinEvents;
        private int[] mLostCount = null;
        private ScanResult[] mMostRecentResult = null;
        private int[] mPendingEvent = null;
        private boolean mFiredEvents = false;

        private static ScanResult findResult(List<ScanResult> results, String bssid) {
            for (int i = 0; i < results.size(); ++i) {
                if (bssid.equalsIgnoreCase(results.get(i).BSSID)) {
                    return results.get(i);
                }
            }
            return null;
        }

        public void setSettings(WifiScanner.BssidInfo[] bssidInfos, int apLostThreshold,
                                int minEvents) {
            mBssidInfos = bssidInfos;
            if (apLostThreshold <= 0) {
                mApLostThreshold = 1;
            } else {
                mApLostThreshold = apLostThreshold;
            }
            mMinEvents = minEvents;
            if (bssidInfos != null) {
                mLostCount = new int[bssidInfos.length];
                Arrays.fill(mLostCount, mApLostThreshold); // default to lost
                mMostRecentResult = new ScanResult[bssidInfos.length];
                mPendingEvent = new int[bssidInfos.length];
                mFiredEvents = false;
            } else {
                mLostCount = null;
                mMostRecentResult = null;
                mPendingEvent = null;
            }
        }

        public void clearSettings() {
            setSettings(null, 0, 0);
        }

        /**
         * Get the most recent scan results for APs that triggered the given event on the last call
         * to {@link #processScan}.
         */
        public ScanResult[] getLastResults(int event) {
            ArrayList<ScanResult> results = new ArrayList<>();
            for (int i = 0; i < mLostCount.length; ++i) {
                if (mPendingEvent[i] == event) {
                    results.add(mMostRecentResult[i]);
                }
            }
            return results.toArray(new ScanResult[results.size()]);
        }

        /**
         * Process the supplied scan results and determine if any events should be generated based
         * on the configured settings
         * @return The events that occurred
         */
        public int processScan(List<ScanResult> scanResults) {
            if (mBssidInfos == null) {
                return EVENT_NONE;
            }

            // clear events from last time
            if (mFiredEvents) {
                mFiredEvents = false;
                for (int i = 0; i < mLostCount.length; ++i) {
                    mPendingEvent[i] = EVENT_NONE;
                }
            }

            int eventCount = 0;
            int eventType = EVENT_NONE;
            for (int i = 0; i < mLostCount.length; ++i) {
                ScanResult result = findResult(scanResults, mBssidInfos[i].bssid);
                int rssi = Integer.MIN_VALUE;
                if (result != null) {
                    mMostRecentResult[i] = result;
                    rssi = result.level;
                }

                if (rssi < mBssidInfos[i].low) {
                    if (mLostCount[i] < mApLostThreshold) {
                        mLostCount[i]++;

                        if (mLostCount[i] >= mApLostThreshold) {
                            if (mPendingEvent[i] == EVENT_FOUND) {
                                mPendingEvent[i] = EVENT_NONE;
                            } else {
                                mPendingEvent[i] = EVENT_LOST;
                            }
                        }
                    }
                } else {
                    if (mLostCount[i] >= mApLostThreshold) {
                        if (mPendingEvent[i] == EVENT_LOST) {
                            mPendingEvent[i] = EVENT_NONE;
                        } else {
                            mPendingEvent[i] = EVENT_FOUND;
                        }
                    }
                    mLostCount[i] = STATE_FOUND;
                }
                if (DBG) {
                    Log.d(TAG, "ChangeBuffer BSSID: " + mBssidInfos[i].bssid + "=" + mLostCount[i]
                            + ", " + mPendingEvent[i] + ", rssi=" + rssi);
                }
                if (mPendingEvent[i] != EVENT_NONE) {
                    ++eventCount;
                    eventType |= mPendingEvent[i];
                }
            }
            if (DBG) Log.d(TAG, "ChangeBuffer events count=" + eventCount + ": " + eventType);
            if (eventCount >= mMinEvents) {
                mFiredEvents = true;
                return eventType;
            }
            return EVENT_NONE;
        }
    }
}
