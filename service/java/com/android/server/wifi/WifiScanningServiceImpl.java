/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.IWifiScanner;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.BssidInfo;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.am.BatteryStatsService;
import com.android.server.wifi.WifiScannerImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

public class WifiScanningServiceImpl extends IWifiScanner.Stub {

    private static final String TAG = WifiScanningService.TAG;
    private static final boolean DBG = false;

    private static final int MIN_PERIOD_PER_CHANNEL_MS = 200;               // DFS needs 120 ms
    private static final int UNKNOWN_PID = -1;

    private static final LocalLog mLocalLog = new LocalLog(1024);

    private static void localLog(String message) {
        mLocalLog.log(message);
    }

    private static void logw(String message) {
        Log.w(TAG, message);
        mLocalLog.log(message);
    }

    private static void loge(String message) {
        Log.e(TAG, message);
        mLocalLog.log(message);
    }

    private WifiScannerImpl mScannerImpl;

    @Override
    public Messenger getMessenger() {
        if (mClientHandler != null) {
            return new Messenger(mClientHandler);
        } else {
            loge("WifiScanningServiceImpl trying to get messenger w/o initialization");
            return null;
        }
    }

    @Override
    public Bundle getAvailableChannels(int band) {
        ChannelSpec channelSpecs[] = WifiChannelHelper.getChannelsForBand(band);
        ArrayList<Integer> list = new ArrayList<Integer>(channelSpecs.length);
        for (ChannelSpec channelSpec : channelSpecs) {
            list.add(channelSpec.frequency);
        }
        Bundle b = new Bundle();
        b.putIntegerArrayList(WifiScanner.GET_AVAILABLE_CHANNELS_EXTRA, list);
        return b;
    }

    private void enforceLocationHardwarePermission(int uid) {
        mContext.enforcePermission(
                Manifest.permission.LOCATION_HARDWARE,
                UNKNOWN_PID, uid,
                "LocationHardware");
    }

    private class ClientHandler extends Handler {

        ClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    ClientInfo client = mClients.get(msg.replyTo);
                    if (client != null) {
                        logw("duplicate client connection: " + msg.sendingUid);
                        client.mChannel.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                        return;
                    }

                    AsyncChannel ac = new AsyncChannel();
                    ac.connected(mContext, this, msg.replyTo);

                    client = new ClientInfo(msg.sendingUid, ac);
                    mClients.put(msg.replyTo, client);

                    ac.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                            AsyncChannel.STATUS_SUCCESSFUL);

                    if (DBG) Log.d(TAG, "client connected: " + client);
                    return;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    ClientInfo client = mClients.get(msg.replyTo);
                    if (client != null) {
                        client.mChannel.disconnect();
                    }
                    return;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    ClientInfo ci = mClients.remove(msg.replyTo);
                    if (ci != null) {
                        if (DBG) Log.d(TAG, "client disconnected: " + ci
                                + ", reason: " + msg.arg1);
                        ci.cleanup();
                    }
                    return;
                }
            }

            try {
                enforceLocationHardwarePermission(msg.sendingUid);
            } catch (SecurityException e) {
                localLog("failed to authorize app: " + e);
                replyFailed(msg, WifiScanner.REASON_NOT_AUTHORIZED, "Not authorized");
                return;
            }

            if (msg.what == WifiScanner.CMD_GET_SCAN_RESULTS) {
                mStateMachine.sendMessage(Message.obtain(msg));
                return;
            }
            ClientInfo ci = mClients.get(msg.replyTo);
            if (ci == null) {
                loge("Could not find client info for message " + msg.replyTo);
                replyFailed(msg, WifiScanner.REASON_INVALID_LISTENER, "Could not find listener");
                return;
            }

            int validCommands[] = {
                    WifiScanner.CMD_SCAN,
                    WifiScanner.CMD_START_BACKGROUND_SCAN,
                    WifiScanner.CMD_STOP_BACKGROUND_SCAN,
                    WifiScanner.CMD_START_SINGLE_SCAN,
                    WifiScanner.CMD_STOP_SINGLE_SCAN,
                    WifiScanner.CMD_SET_HOTLIST,
                    WifiScanner.CMD_RESET_HOTLIST,
                    WifiScanner.CMD_CONFIGURE_WIFI_CHANGE,
                    WifiScanner.CMD_START_TRACKING_CHANGE,
                    WifiScanner.CMD_STOP_TRACKING_CHANGE };

            for (int cmd : validCommands) {
                if (cmd == msg.what) {
                    mStateMachine.sendMessage(Message.obtain(msg));
                    return;
                }
            }

            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "Invalid request");
        }
    }

    private static final int BASE = Protocol.BASE_WIFI_SCANNER_SERVICE;

    private static final int CMD_SCAN_RESULTS_AVAILABLE              = BASE + 0;
    private static final int CMD_FULL_SCAN_RESULTS                   = BASE + 1;
    private static final int CMD_HOTLIST_AP_FOUND                    = BASE + 2;
    private static final int CMD_HOTLIST_AP_LOST                     = BASE + 3;
    private static final int CMD_WIFI_CHANGE_DETECTED                = BASE + 4;
    private static final int CMD_WIFI_CHANGES_STABILIZED             = BASE + 5;
    private static final int CMD_DRIVER_LOADED                       = BASE + 6;
    private static final int CMD_DRIVER_UNLOADED                     = BASE + 7;
    private static final int CMD_SCAN_PAUSED                         = BASE + 8;
    private static final int CMD_SCAN_RESTARTED                      = BASE + 9;
    private static final int CMD_STOP_SCAN_INTERNAL                  = BASE + 10;

    private final WifiNative mWifiNative;
    private final Context mContext;
    private HandlerThread mHandlerThread;
    private final ArrayMap<Messenger, ClientInfo> mClients;

    private final WifiScanningScheduler mScheduler;
    private WifiNative.ScanSettings mPreviousSchedule;

    private WifiScanningStateMachine mStateMachine;
    private ClientHandler mClientHandler;
    private final IBatteryStats mBatteryStats;

    WifiScanningServiceImpl(Context context) {
        mWifiNative = WifiNative.getWlanNativeInterface();
        mContext = context;
        mBatteryStats = BatteryStatsService.getService();
        mClients = new ArrayMap<>();

        mScheduler = new MultiClientScheduler();
        mPreviousSchedule = null;
    }

    public void startService() {
        mHandlerThread = new HandlerThread("WifiScanningService");
        mHandlerThread.start();

        mClientHandler = new ClientHandler(mHandlerThread.getLooper());
        mStateMachine = new WifiScanningStateMachine(mHandlerThread.getLooper());
        mWifiChangeStateMachine = new WifiChangeStateMachine(mHandlerThread.getLooper());

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int state = intent.getIntExtra(
                                WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
                        if (DBG) localLog("SCAN_AVAILABLE : " + state);
                        if (state == WifiManager.WIFI_STATE_ENABLED) {
                            mStateMachine.sendMessage(CMD_DRIVER_LOADED);
                        } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                            mStateMachine.sendMessage(CMD_DRIVER_UNLOADED);
                        }
                    }
                }, new IntentFilter(WifiManager.WIFI_SCAN_AVAILABLE));

        mStateMachine.start();
        mWifiChangeStateMachine.start();
    }

    class WifiScanningStateMachine extends StateMachine implements WifiNative.ScanEventHandler,
            WifiNative.HotlistEventHandler, WifiNative.SignificantWifiChangeEventHandler {

        private final DefaultState mDefaultState = new DefaultState();
        private final StartedState mStartedState = new StartedState();
        private final PausedState  mPausedState  = new PausedState();

        public WifiScanningStateMachine(Looper looper) {
            super(TAG, looper);

            setLogRecSize(512);
            setLogOnlyTransitions(false);

            addState(mDefaultState);
                addState(mStartedState, mDefaultState);
                addState(mPausedState, mDefaultState);

            setInitialState(mDefaultState);
        }

        @Override
        public void onScanResultsAvailable() {
            if (DBG) localLog("onScanResultAvailable event received");
            sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
        }

        @Override
        public void onScanStatus() {
            if (DBG) localLog("onScanStatus event received");
            sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
        }

        @Override
        public void onFullScanResult(ScanResult fullScanResult) {
            if (DBG) localLog("onFullScanResult received");
            sendMessage(CMD_FULL_SCAN_RESULTS, 0, 0, fullScanResult);
        }

        @Override
        public void onScanPaused(ScanData scanData[]) {
            if (DBG) localLog("onScanPaused received");
            sendMessage(CMD_SCAN_PAUSED, scanData);
        }

        @Override
        public void onScanRestarted() {
            if (DBG) localLog("onScanRestarted received");
            sendMessage(CMD_SCAN_RESTARTED);
        }

        @Override
        public void onHotlistApFound(ScanResult[] results) {
            if (DBG) localLog("onHotlistApFound event received");
            sendMessage(CMD_HOTLIST_AP_FOUND, 0, 0, results);
        }

        @Override
        public void onHotlistApLost(ScanResult[] results) {
            if (DBG) localLog("onHotlistApLost event received");
            sendMessage(CMD_HOTLIST_AP_LOST, 0, 0, results);
        }

        @Override
        public void onChangesFound(ScanResult[] results) {
            if (DBG) localLog("onWifiChangesFound event received");
            sendMessage(CMD_WIFI_CHANGE_DETECTED, 0, 0, results);
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("DefaultState");
            }
            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        if (mScannerImpl == null) {
                            mScannerImpl =
                                    WifiScannerImpl.create(mContext, mHandlerThread.getLooper());
                        }
                        WifiNative.ScanCapabilities capabilities = new WifiNative.ScanCapabilities();
                        if (!mScannerImpl.getScanCapabilities(capabilities)) {
                            loge("could not get scan capabilities");
                            return HANDLED;
                        }

                        mScheduler.setMaxBuckets(capabilities.max_scan_buckets);
                        mScheduler.setMaxApPerScan(capabilities.max_ap_cache_per_scan);

                        Log.i(TAG, "wifi driver loaded");
                        Log.i(TAG, "received scan capabilities: "
                                + "max buckets=" + capabilities.max_scan_buckets);

                        transitionTo(mStartedState);
                        return HANDLED;
                    case CMD_DRIVER_UNLOADED:
                        Log.i(TAG, "wifi driver unloaded");
                        transitionTo(mDefaultState);
                        break;
                    case WifiScanner.CMD_SCAN:
                    case WifiScanner.CMD_START_BACKGROUND_SCAN:
                    case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                    case WifiScanner.CMD_START_SINGLE_SCAN:
                    case WifiScanner.CMD_STOP_SINGLE_SCAN:
                    case WifiScanner.CMD_SET_HOTLIST:
                    case WifiScanner.CMD_RESET_HOTLIST:
                    case WifiScanner.CMD_CONFIGURE_WIFI_CHANGE:
                    case WifiScanner.CMD_START_TRACKING_CHANGE:
                    case WifiScanner.CMD_STOP_TRACKING_CHANGE:
                    case WifiScanner.CMD_GET_SCAN_RESULTS:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "not available");
                        break;

                    case CMD_SCAN_RESULTS_AVAILABLE:
                        if (DBG) localLog("ignored scan results available event");
                        break;

                    case CMD_FULL_SCAN_RESULTS:
                        if (DBG) localLog("ignored full scan result event");
                        break;

                    default:
                        break;
                }

                return HANDLED;
            }
        }

        class StartedState extends State {

            @Override
            public void enter() {
                if (DBG) localLog("StartedState");
            }

            @Override
            public boolean processMessage(Message msg) {
                ClientInfo ci = mClients.get(msg.replyTo);

                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        return NOT_HANDLED;
                    case CMD_DRIVER_UNLOADED:
                        return NOT_HANDLED;
                    case WifiScanner.CMD_SCAN:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "not implemented");
                        break;
                    case WifiScanner.CMD_START_BACKGROUND_SCAN:
                        if (addScanRequest(ci, msg.arg2, (ScanSettings) msg.obj)) {
                            replySucceeded(msg);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                        }
                        break;
                    case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                        removeScanRequest(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_GET_SCAN_RESULTS:
                        reportScanResults(mScannerImpl.getLatestBatchedScanResults(true));
                        replySucceeded(msg);
                        break;
                    case WifiScanner.CMD_START_SINGLE_SCAN:
                        if (addSingleScanRequest(ci, msg.arg2, (ScanSettings) msg.obj)) {
                            replySucceeded(msg);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                        }
                        break;
                    case WifiScanner.CMD_STOP_SINGLE_SCAN:
                        removeScanRequest(ci, msg.arg2);
                        break;
                    case CMD_STOP_SCAN_INTERNAL:
                        localLog("Removing single shot scan");
                        removeScanRequest((ClientInfo) msg.obj, msg.arg2);
                        break;
                    case WifiScanner.CMD_SET_HOTLIST:
                        setHotlist(ci, msg.arg2, (WifiScanner.HotlistSettings) msg.obj);
                        replySucceeded(msg);
                        break;
                    case WifiScanner.CMD_RESET_HOTLIST:
                        resetHotlist(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_START_TRACKING_CHANGE:
                        trackWifiChanges(ci, msg.arg2);
                        replySucceeded(msg);
                        break;
                    case WifiScanner.CMD_STOP_TRACKING_CHANGE:
                        untrackWifiChanges(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_CONFIGURE_WIFI_CHANGE:
                        configureWifiChange((WifiScanner.WifiChangeSettings) msg.obj);
                        break;
                    case CMD_SCAN_RESULTS_AVAILABLE:
                        reportScanResults(mScannerImpl.getLatestBatchedScanResults(true));
                        break;
                    case CMD_FULL_SCAN_RESULTS:
                        reportFullScanResult((ScanResult) msg.obj);
                        break;

                    case CMD_HOTLIST_AP_FOUND: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            if (DBG) localLog("Found " + results.length + " results");
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportHotlistResults(WifiScanner.CMD_AP_FOUND, results);
                            }
                        }
                        break;
                    case CMD_HOTLIST_AP_LOST: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            if (DBG) localLog("Lost " + results.length + " results");
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportHotlistResults(WifiScanner.CMD_AP_LOST, results);
                            }
                        }
                        break;
                    case CMD_WIFI_CHANGE_DETECTED: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            reportWifiChanged(results);
                        }
                        break;
                    case CMD_WIFI_CHANGES_STABILIZED: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            reportWifiStabilized(results);
                        }
                        break;
                    case CMD_SCAN_PAUSED:
                        reportScanResults((ScanData[]) msg.obj);
                        transitionTo(mPausedState);
                        break;
                    default:
                        return NOT_HANDLED;
                }

                return HANDLED;
            }
        }

        class PausedState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("PausedState");
            }

            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case CMD_SCAN_RESTARTED:
                        transitionTo(mStartedState);
                        break;
                    default:
                        deferMessage(msg);
                        break;
                }
                return HANDLED;
            }

        }
    }

    private class ClientInfo {
        private final AsyncChannel mChannel;
        private final int mUid;
        private final WorkSource mWorkSource;
        private boolean mScanWorkReported = false;

        ClientInfo(int uid, AsyncChannel c) {
            mChannel = c;
            mUid = uid;
            mWorkSource = new WorkSource(uid);
            if (DBG) localLog("New client, channel: " + c);
        }

        void reportBatchedScanStart() {
            if (mUid == 0)
                return;

            int csph = getCsph();

            try {
                mBatteryStats.noteWifiBatchedScanStartedFromSource(mWorkSource, csph);
            } catch (RemoteException e) {
                logw("failed to report scan work: " + e.toString());
            }
        }

        void reportBatchedScanStop() {
            if (mUid == 0)
                return;

            try {
                mBatteryStats.noteWifiBatchedScanStoppedFromSource(mWorkSource);
            } catch (RemoteException e) {
                logw("failed to cleanup scan work: " + e.toString());
            }
        }

        int getCsph() {
            int csph = 0;
            for (ScanSettings settings : getScanSettings()) {
                int num_channels = WifiChannelHelper.getChannelsForScanSettings(settings).length;
                int scans_per_Hour = settings.periodInMs == 0 ? 1 : (3600 * 1000) /
                        settings.periodInMs;
                csph += num_channels * scans_per_Hour;
            }

            return csph;
        }

        void reportScanWorkUpdate() {
            if (mScanWorkReported) {
                reportBatchedScanStop();
                mScanWorkReported = false;
            }
            if (mScanSettings.isEmpty() == false) {
                reportBatchedScanStart();
                mScanWorkReported = true;
            }
        }

        @Override
        public String toString() {
            return "ClientInfo[uid=" + mUid + ", channel=" + mChannel + "]";
        }

        void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            StringBuilder sb = new StringBuilder();
            sb.append(toString());

            Iterator<Map.Entry<Integer, ScanSettings>> it = mScanSettings.entrySet().iterator();
            for (; it.hasNext(); ) {
                Map.Entry<Integer, ScanSettings> entry = it.next();
                sb.append("ScanId ").append(entry.getKey()).append("\n");

                ScanSettings scanSettings = entry.getValue();
                sb.append(describe(scanSettings));
                sb.append("\n");
            }

            pw.println(sb.toString());
        }

        HashMap<Integer, ScanSettings> mScanSettings = new HashMap<Integer, ScanSettings>(4);
        HashMap<Integer, Integer> mScanPeriods = new HashMap<Integer, Integer>(4);

        void addScanRequest(ScanSettings settings, int id) {
            mScanSettings.put(id, settings);
            reportScanWorkUpdate();
        }

        void removeScanRequest(int id) {
            ScanSettings settings = mScanSettings.remove(id);
            if (settings != null && settings.periodInMs == 0) {
                /* this was a single shot scan */
                mChannel.sendMessage(WifiScanner.CMD_SINGLE_SCAN_COMPLETED, 0, id);
            }
            reportScanWorkUpdate();
        }

        Iterator<Map.Entry<Integer, ScanSettings>> getScans() {
            return mScanSettings.entrySet().iterator();
        }

        Collection<ScanSettings> getScanSettings() {
            return mScanSettings.values();
        }

        void reportScanResults(ScanData[] results) {
            Iterator<Integer> it = mScanSettings.keySet().iterator();
            while (it.hasNext()) {
                int handler = it.next();
                reportScanResults(results, handler);
            }
        }

        void reportScanResults(ScanData[] results, int handler) {
            ScanSettings settings = mScanSettings.get(handler);

            ScanData[] resultsToDeliver = mScheduler.filterResultsForSettings(results, settings);

            if (resultsToDeliver != null) {
                localLog("delivering results, num = " + resultsToDeliver.length);

                deliverScanResults(handler, resultsToDeliver);
            }

            if (settings.periodInMs == 0) {
                /* this is a single shot scan; stop the scan now */
                mStateMachine.sendMessage(CMD_STOP_SCAN_INTERNAL, 0, handler, this);
            }
        }

        void deliverScanResults(int handler, ScanData results[]) {
            WifiScanner.ParcelableScanData parcelableScanData =
                    new WifiScanner.ParcelableScanData(results);
            mChannel.sendMessage(WifiScanner.CMD_SCAN_RESULT, 0, handler, parcelableScanData);
        }

        void reportFullScanResult(ScanResult result) {
            Iterator<Integer> it = mScanSettings.keySet().iterator();
            while (it.hasNext()) {
                int handler = it.next();
                ScanSettings settings = mScanSettings.get(handler);
                if (mScheduler.shouldReportFullScanResultForSettings(result, settings)) {
                    ScanResult newResult = new ScanResult(result);
                    if (result.informationElements != null) {
                        newResult.informationElements = result.informationElements.clone();
                    }
                    else {
                        newResult.informationElements = null;
                    }
                    mChannel.sendMessage(WifiScanner.CMD_FULL_SCAN_RESULT, 0, handler, newResult);
                }
            }
        }

        void reportPeriodChanged(int handler, ScanSettings settings, int newPeriodInMs) {
            Integer prevPeriodObject = mScanPeriods.get(handler);
            int prevPeriodInMs = settings.periodInMs;
            if (prevPeriodObject != null) {
                prevPeriodInMs = prevPeriodObject;
            }

            if (prevPeriodInMs != newPeriodInMs) {
                mChannel.sendMessage(WifiScanner.CMD_PERIOD_CHANGED, newPeriodInMs, handler);
            }
        }

        HashMap<Integer, WifiScanner.HotlistSettings> mHotlistSettings =
                new HashMap<Integer, WifiScanner.HotlistSettings>();

        void addHostlistSettings(WifiScanner.HotlistSettings settings, int handler) {
            mHotlistSettings.put(handler, settings);
        }

        void removeHostlistSettings(int handler) {
            mHotlistSettings.remove(handler);
        }

        Collection<WifiScanner.HotlistSettings> getHotlistSettings() {
            return mHotlistSettings.values();
        }

        void reportHotlistResults(int what, ScanResult[] results) {
            Iterator<Map.Entry<Integer, WifiScanner.HotlistSettings>> it =
                    mHotlistSettings.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, WifiScanner.HotlistSettings> entry = it.next();
                int handler = entry.getKey();
                WifiScanner.HotlistSettings settings = entry.getValue();
                int num_results = 0;
                for (ScanResult result : results) {
                    for (BssidInfo BssidInfo : settings.bssidInfos) {
                        if (result.BSSID.equalsIgnoreCase(BssidInfo.bssid)) {
                            num_results++;
                            break;
                        }
                    }
                }

                if (num_results == 0) {
                    // nothing to report
                    return;
                }

                ScanResult results2[] = new ScanResult[num_results];
                int index = 0;
                for (ScanResult result : results) {
                    for (BssidInfo BssidInfo : settings.bssidInfos) {
                        if (result.BSSID.equalsIgnoreCase(BssidInfo.bssid)) {
                            results2[index] = result;
                            index++;
                        }
                    }
                }

                WifiScanner.ParcelableScanResults parcelableScanResults =
                        new WifiScanner.ParcelableScanResults(results2);

                mChannel.sendMessage(what, 0, handler, parcelableScanResults);
            }
        }

        HashSet<Integer> mSignificantWifiHandlers = new HashSet<Integer>();
        void addSignificantWifiChange(int handler) {
            mSignificantWifiHandlers.add(handler);
        }

        void removeSignificantWifiChange(int handler) {
            mSignificantWifiHandlers.remove(handler);
        }

        Collection<Integer> getWifiChangeHandlers() {
            return mSignificantWifiHandlers;
        }

        void reportWifiChanged(ScanResult[] results) {
            WifiScanner.ParcelableScanResults parcelableScanResults =
                    new WifiScanner.ParcelableScanResults(results);
            Iterator<Integer> it = mSignificantWifiHandlers.iterator();
            while (it.hasNext()) {
                int handler = it.next();
                mChannel.sendMessage(WifiScanner.CMD_WIFI_CHANGE_DETECTED,
                        0, handler, parcelableScanResults);
            }
        }

        void reportWifiStabilized(ScanResult[] results) {
            WifiScanner.ParcelableScanResults parcelableScanResults =
                    new WifiScanner.ParcelableScanResults(results);
            Iterator<Integer> it = mSignificantWifiHandlers.iterator();
            while (it.hasNext()) {
                int handler = it.next();
                mChannel.sendMessage(WifiScanner.CMD_WIFI_CHANGES_STABILIZED,
                        0, handler, parcelableScanResults);
            }
        }

        void cleanup() {
            mScanSettings.clear();
            updateSchedule();

            mHotlistSettings.clear();
            resetHotlist();

            for (Integer handler :  mSignificantWifiHandlers) {
                untrackWifiChanges(this, handler);
            }

            mSignificantWifiHandlers.clear();
            localLog("Successfully stopped all requests for client " + this);
        }
    }

    void replySucceeded(Message msg) {
        if (msg.replyTo != null) {
            Message reply = Message.obtain();
            reply.what = WifiScanner.CMD_OP_SUCCEEDED;
            reply.arg2 = msg.arg2;
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        } else {
            // locally generated message; doesn't need a reply!
        }
    }

    void replyFailed(Message msg, int reason, String description) {
        if (msg.replyTo != null) {
            Message reply = Message.obtain();
            reply.what = WifiScanner.CMD_OP_FAILED;
            reply.arg2 = msg.arg2;
            reply.obj = new WifiScanner.OperationResult(reason, description);
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        } else {
            // locally generated message; doesn't need a reply!
        }
    }

    private boolean updateSchedule() {
        ArrayList<ScanSettings> settings = new ArrayList<>();
        for (ClientInfo client : mClients.values()) {
            settings.addAll(client.getScanSettings());
        }

        mScheduler.updateSchedule(settings);
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();

        if (WifiScanningScheduler.scheduleEquals(mPreviousSchedule, schedule)) {
            if (DBG) Log.d(TAG, "schedule updated with no change");
            return true;
        }

        mPreviousSchedule = schedule;

        if (schedule.num_buckets == 0) {
            mScannerImpl.stopBatchedScan();
            if (DBG) Log.d(TAG, "scan stopped");
            return true;
        } else {
            Log.d(TAG, "starting scan: "
                 + "base period=" + schedule.base_period_ms
                 + ", max ap per scan=" + schedule.max_ap_per_scan
                 + ", batched scans=" + schedule.report_threshold_num_scans);
            for (int b = 0; b < schedule.num_buckets; b++) {
                WifiNative.BucketSettings bucket = schedule.buckets[b];
                Log.d(TAG, "bucket " + bucket.bucket + " (" + bucket.period_ms + "ms)"
                     + "[" + bucket.report_events + "]: "
                     + WifiChannelHelper.toString(bucket.band, bucket.channels,
                                                  bucket.num_channels, mScheduler.getMaxChannels()));
            }

            if (mScannerImpl.startBatchedScan(schedule, mStateMachine)) {
                if (DBG) Log.d(TAG, "scan restarted with " + schedule.num_buckets
                               + " bucket(s) and base period: " + schedule.base_period_ms);
                return true;
            } else {
                mPreviousSchedule = null;
                loge("error starting scan: "
                        + "base period=" + schedule.base_period_ms
                        + ", max ap per scan=" + schedule.max_ap_per_scan
                     + ", batched scans=" + schedule.report_threshold_num_scans);
                for (int b = 0; b < schedule.num_buckets; b++) {
                    WifiNative.BucketSettings bucket = schedule.buckets[b];
                    loge("bucket " + bucket.bucket + " (" + bucket.period_ms + "ms)"
                            + "[" + bucket.report_events + "]: "
                            + WifiChannelHelper.toString(bucket.band, bucket.channels,
                                    bucket.num_channels, mScheduler.getMaxChannels()));
                }
                return false;
            }
        }
    }

    void logScanRequest(String request, ClientInfo ci, int id, ScanSettings settings) {
        StringBuffer sb = new StringBuffer();
        sb.append(request);
        sb.append("\nClient ");
        sb.append(ci.toString());
        sb.append("\nId ");
        sb.append(id);
        sb.append("\n");
        if (settings != null) {
            sb.append(describe(settings));
            sb.append("\n");
        }
        sb.append("\n");
        localLog(sb.toString());
    }

    boolean addScanRequest(ClientInfo ci, int handler, ScanSettings settings) {
        // sanity check the input
        if (ci == null) {
            Log.d(TAG, "Failing scan request ClientInfo not found " + handler);
            return false;
        }
        if (settings.periodInMs < WifiScanner.MIN_SCAN_PERIOD_MS) {
            localLog("Failing scan request because periodInMs is " + settings.periodInMs);
            return false;
        }

        int channelCount = WifiChannelHelper.getChannelsForScanSettings(settings).length;
        if (channelCount == 0) {
            loge("No channels specified");
            return false;
        }
        int minSupportedPeriodMs = channelCount * MIN_PERIOD_PER_CHANNEL_MS;

        if (settings.periodInMs < minSupportedPeriodMs) {
            localLog("Failing scan request because minSupportedPeriodMs is "
                    + minSupportedPeriodMs + " but the request wants " + settings.periodInMs);
            return false;
        }

        // check truncated binary exponential back off scan settings
        if (settings.maxPeriodInMs != 0 && settings.maxPeriodInMs != settings.periodInMs) {
            if (settings.maxPeriodInMs < settings.periodInMs) {
                localLog("Failing scan request because maxPeriodInMs is " + settings.maxPeriodInMs
                        + " but less than periodInMs " + settings.periodInMs);
                return false;
            }
            if (settings.maxPeriodInMs > WifiScanner.MAX_SCAN_PERIOD_MS) {
                localLog("Failing scan request because maxSupportedPeriodMs is "
                    + WifiScanner.MAX_SCAN_PERIOD_MS + " but the request wants "
                    + settings.maxPeriodInMs);
                return false;
            }
            if (settings.stepCount < 1) {
                localLog("Failing scan request because stepCount is " + settings.stepCount
                        + " which is less than 1");
                return false;
            }
        }

        logScanRequest("addScanRequest", ci, handler, settings);
        ci.addScanRequest(settings, handler);

        if (updateSchedule()) {
            return true;
        } else {
            ci.removeScanRequest(handler);
            localLog("Failing scan request because failed to reset scan");
            return false;
        }
    }

    boolean addSingleScanRequest(ClientInfo ci, int handler, ScanSettings settings) {
        if (ci == null) {
            Log.d(TAG, "Failing single scan request ClientInfo not found " + handler);
            return false;
        }
        if (settings.reportEvents == 0) {
            settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
        }
        if (settings.periodInMs == 0) {
            settings.periodInMs = 10000;        // 10s - although second scan should never happen
        }

        logScanRequest("addSingleScanRequest", ci, handler, settings);
        ci.addScanRequest(settings, handler);

        if (updateSchedule()) {
            /* reset periodInMs to 0 to indicate single shot scan */
            settings.periodInMs = 0;
            return true;
        } else {
            ci.removeScanRequest(handler);
            localLog("Failing scan request because failed to reset scan");
            return false;
        }
    }

    void removeScanRequest(ClientInfo ci, int handler) {
        if (ci != null) {
            logScanRequest("removeScanRequest", ci, handler, null);
            ci.removeScanRequest(handler);
            updateSchedule();
        }
    }

    boolean reportScanResults(ScanData[] results) {
        if (DBG) Log.d(TAG, "reporting " + results.length + " scan(s)");
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci2 : clients) {
            ci2.reportScanResults(results);
        }

        return true;
    }

    boolean reportFullScanResult(ScanResult result) {
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci2 : clients) {
            ci2.reportFullScanResult(result);
        }

        return true;
    }

    void resetHotlist() {
        Collection<ClientInfo> clients = mClients.values();
        int num_hotlist_ap = 0;

        for (ClientInfo ci : clients) {
            Collection<WifiScanner.HotlistSettings> c = ci.getHotlistSettings();
            for (WifiScanner.HotlistSettings s : c) {
                num_hotlist_ap +=  s.bssidInfos.length;
            }
        }

        if (num_hotlist_ap == 0) {
            mScannerImpl.resetHotlist();
        } else {
            BssidInfo bssidInfos[] = new BssidInfo[num_hotlist_ap];
            int apLostThreshold = Integer.MAX_VALUE;
            int index = 0;
            for (ClientInfo ci : clients) {
                Collection<WifiScanner.HotlistSettings> settings = ci.getHotlistSettings();
                for (WifiScanner.HotlistSettings s : settings) {
                    for (int i = 0; i < s.bssidInfos.length; i++, index++) {
                        bssidInfos[index] = s.bssidInfos[i];
                    }
                    if (s.apLostThreshold < apLostThreshold) {
                        apLostThreshold = s.apLostThreshold;
                    }
                }
            }

            WifiScanner.HotlistSettings settings = new WifiScanner.HotlistSettings();
            settings.bssidInfos = bssidInfos;
            settings.apLostThreshold = apLostThreshold;
            mScannerImpl.setHotlist(settings, mStateMachine);
        }
    }

    void setHotlist(ClientInfo ci, int handler, WifiScanner.HotlistSettings settings) {
        ci.addHostlistSettings(settings, handler);
        resetHotlist();
    }

    void resetHotlist(ClientInfo ci, int handler) {
        ci.removeHostlistSettings(handler);
        resetHotlist();
    }

    WifiChangeStateMachine mWifiChangeStateMachine;

    void trackWifiChanges(ClientInfo ci, int handler) {
        mWifiChangeStateMachine.enable();
        ci.addSignificantWifiChange(handler);
    }

    void untrackWifiChanges(ClientInfo ci, int handler) {
        ci.removeSignificantWifiChange(handler);
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci2 : clients) {
            if (ci2.getWifiChangeHandlers().size() != 0) {
                // there is at least one client watching for
                // significant changes; so nothing more to do
                return;
            }
        }

        // no more clients looking for significant wifi changes
        // no need to keep the state machine running; disable it
        mWifiChangeStateMachine.disable();
    }

    void configureWifiChange(WifiScanner.WifiChangeSettings settings) {
        mWifiChangeStateMachine.configure(settings);
    }

    void reportWifiChanged(ScanResult results[]) {
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci : clients) {
            ci.reportWifiChanged(results);
        }
    }

    void reportWifiStabilized(ScanResult results[]) {
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci : clients) {
            ci.reportWifiStabilized(results);
        }
    }

    class WifiChangeStateMachine extends StateMachine
            implements WifiNative.SignificantWifiChangeEventHandler {

        private static final String TAG = "WifiChangeStateMachine";

        private static final int WIFI_CHANGE_CMD_NEW_SCAN_RESULTS           = 0;
        private static final int WIFI_CHANGE_CMD_CHANGE_DETECTED            = 1;
        private static final int WIFI_CHANGE_CMD_CHANGE_TIMEOUT             = 2;
        private static final int WIFI_CHANGE_CMD_ENABLE                     = 3;
        private static final int WIFI_CHANGE_CMD_DISABLE                    = 4;
        private static final int WIFI_CHANGE_CMD_CONFIGURE                  = 5;

        private static final int MAX_APS_TO_TRACK = 3;
        private static final int MOVING_SCAN_PERIOD_MS      = 10000;
        private static final int STATIONARY_SCAN_PERIOD_MS  =  5000;
        private static final int MOVING_STATE_TIMEOUT_MS    = 30000;

        State mDefaultState = new DefaultState();
        State mStationaryState = new StationaryState();
        State mMovingState = new MovingState();

        private static final String ACTION_TIMEOUT =
                "com.android.server.WifiScanningServiceImpl.action.TIMEOUT";
        AlarmManager  mAlarmManager;
        PendingIntent mTimeoutIntent;
        ScanResult    mCurrentBssids[];

        WifiChangeStateMachine(Looper looper) {
            super("SignificantChangeStateMachine", looper);

            mClients.put(null, mClientInfo);

            addState(mDefaultState);
            addState(mStationaryState, mDefaultState);
            addState(mMovingState, mDefaultState);

            setInitialState(mDefaultState);
        }

        public void enable() {
            if (mAlarmManager == null) {
                mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            }

            if (mTimeoutIntent == null) {
                Intent intent = new Intent(ACTION_TIMEOUT, null);
                mTimeoutIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

                mContext.registerReceiver(
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                sendMessage(WIFI_CHANGE_CMD_CHANGE_TIMEOUT);
                            }
                        }, new IntentFilter(ACTION_TIMEOUT));
            }

            sendMessage(WIFI_CHANGE_CMD_ENABLE);
        }

        public void disable() {
            sendMessage(WIFI_CHANGE_CMD_DISABLE);
        }

        public void configure(WifiScanner.WifiChangeSettings settings) {
            sendMessage(WIFI_CHANGE_CMD_CONFIGURE, settings);
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("Entering IdleState");
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) localLog("DefaultState state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        transitionTo(mMovingState);
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        // nothing to do
                        break;
                    case WIFI_CHANGE_CMD_NEW_SCAN_RESULTS:
                        // nothing to do
                        break;
                    case WIFI_CHANGE_CMD_CONFIGURE:
                        /* save configuration till we transition to moving state */
                        deferMessage(msg);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class StationaryState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("Entering StationaryState");
                reportWifiStabilized(mCurrentBssids);
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) localLog("Stationary state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        // do nothing
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_DETECTED:
                        if (DBG) localLog("Got wifi change detected");
                        reportWifiChanged((ScanResult[])msg.obj);
                        transitionTo(mMovingState);
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        if (DBG) localLog("Got Disable Wifi Change");
                        mCurrentBssids = null;
                        removeScanRequest();
                        untrackSignificantWifiChange();
                        transitionTo(mDefaultState);
                        break;
                    case WIFI_CHANGE_CMD_CONFIGURE:
                        /* save configuration till we transition to moving state */
                        deferMessage(msg);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MovingState extends State {
            boolean mWifiChangeDetected = false;
            boolean mScanResultsPending = false;

            @Override
            public void enter() {
                if (DBG) localLog("Entering MovingState");
                issueFullScan();
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) localLog("MovingState state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        // do nothing
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        if (DBG) localLog("Got Disable Wifi Change");
                        mCurrentBssids = null;
                        removeScanRequest();
                        untrackSignificantWifiChange();
                        transitionTo(mDefaultState);
                        break;
                    case WIFI_CHANGE_CMD_NEW_SCAN_RESULTS:
                        if (DBG) localLog("Got scan results");
                        if (mScanResultsPending) {
                            if (DBG) localLog("reconfiguring scan");
                            reconfigureScan((ScanData[])msg.obj,
                                    STATIONARY_SCAN_PERIOD_MS);
                            mWifiChangeDetected = false;
                            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                                    System.currentTimeMillis() + MOVING_STATE_TIMEOUT_MS,
                                    mTimeoutIntent);
                            mScanResultsPending = false;
                        }
                        break;
                    case WIFI_CHANGE_CMD_CONFIGURE:
                        if (DBG) localLog("Got configuration from app");
                        WifiScanner.WifiChangeSettings settings =
                                (WifiScanner.WifiChangeSettings) msg.obj;
                        reconfigureScan(settings);
                        mWifiChangeDetected = false;
                        long unchangedDelay = settings.unchangedSampleSize * settings.periodInMs;
                        mAlarmManager.cancel(mTimeoutIntent);
                        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + unchangedDelay,
                                mTimeoutIntent);
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_DETECTED:
                        if (DBG) localLog("Change detected");
                        mAlarmManager.cancel(mTimeoutIntent);
                        reportWifiChanged((ScanResult[])msg.obj);
                        mWifiChangeDetected = true;
                        issueFullScan();
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_TIMEOUT:
                        if (DBG) localLog("Got timeout event");
                        if (mWifiChangeDetected == false) {
                            transitionTo(mStationaryState);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                mAlarmManager.cancel(mTimeoutIntent);
            }

            void issueFullScan() {
                if (DBG) localLog("Issuing full scan");
                ScanSettings settings = new ScanSettings();
                settings.band = WifiScanner.WIFI_BAND_BOTH;
                settings.periodInMs = MOVING_SCAN_PERIOD_MS;
                settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                addScanRequest(settings);
                mScanResultsPending = true;
            }

        }

        void reconfigureScan(ScanData[] results, int period) {
            // find brightest APs and set them as sentinels
            if (results.length < MAX_APS_TO_TRACK) {
                localLog("too few APs (" + results.length + ") available to track wifi change");
                return;
            }

            removeScanRequest();

            // remove duplicate BSSIDs
            HashMap<String, ScanResult> bssidToScanResult = new HashMap<String, ScanResult>();
            for (ScanResult result : results[0].getResults()) {
                ScanResult saved = bssidToScanResult.get(result.BSSID);
                if (saved == null) {
                    bssidToScanResult.put(result.BSSID, result);
                } else if (saved.level > result.level) {
                    bssidToScanResult.put(result.BSSID, result);
                }
            }

            // find brightest BSSIDs
            ScanResult brightest[] = new ScanResult[MAX_APS_TO_TRACK];
            Collection<ScanResult> results2 = bssidToScanResult.values();
            for (ScanResult result : results2) {
                for (int j = 0; j < brightest.length; j++) {
                    if (brightest[j] == null
                            || (brightest[j].level < result.level)) {
                        for (int k = brightest.length; k > (j + 1); k--) {
                            brightest[k - 1] = brightest[k - 2];
                        }
                        brightest[j] = result;
                        break;
                    }
                }
            }

            // Get channels to scan for
            ArrayList<Integer> channels = new ArrayList<Integer>();
            for (int i = 0; i < brightest.length; i++) {
                boolean found = false;
                for (int j = i + 1; j < brightest.length; j++) {
                    if (brightest[j].frequency == brightest[i].frequency) {
                        found = true;
                    }
                }
                if (!found) {
                    channels.add(brightest[i].frequency);
                }
            }

            if (DBG) localLog("Found " + channels.size() + " channels");

            // set scanning schedule
            ScanSettings settings = new ScanSettings();
            settings.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            settings.channels = new ChannelSpec[channels.size()];
            for (int i = 0; i < channels.size(); i++) {
                settings.channels[i] = new ChannelSpec(channels.get(i));
            }

            settings.periodInMs = period;
            addScanRequest(settings);

            WifiScanner.WifiChangeSettings settings2 = new WifiScanner.WifiChangeSettings();
            settings2.rssiSampleSize = 3;
            settings2.lostApSampleSize = 3;
            settings2.unchangedSampleSize = 3;
            settings2.minApsBreachingThreshold = 2;
            settings2.bssidInfos = new BssidInfo[brightest.length];

            for (int i = 0; i < brightest.length; i++) {
                BssidInfo BssidInfo = new BssidInfo();
                BssidInfo.bssid = brightest[i].BSSID;
                int threshold = (100 + brightest[i].level) / 32 + 2;
                BssidInfo.low = brightest[i].level - threshold;
                BssidInfo.high = brightest[i].level + threshold;
                settings2.bssidInfos[i] = BssidInfo;

                if (DBG) localLog("Setting bssid=" + BssidInfo.bssid + ", " +
                        "low=" + BssidInfo.low + ", high=" + BssidInfo.high);
            }

            trackSignificantWifiChange(settings2);
            mCurrentBssids = brightest;
        }

        void reconfigureScan(WifiScanner.WifiChangeSettings settings) {

            if (settings.bssidInfos.length < MAX_APS_TO_TRACK) {
                localLog("too few APs (" + settings.bssidInfos.length
                        + ") available to track wifi change");
                return;
            }

            if (DBG) localLog("Setting configuration specified by app");

            mCurrentBssids = new ScanResult[settings.bssidInfos.length];
            HashSet<Integer> channels = new HashSet<Integer>();

            for (int i = 0; i < settings.bssidInfos.length; i++) {
                ScanResult result = new ScanResult();
                result.BSSID = settings.bssidInfos[i].bssid;
                mCurrentBssids[i] = result;
                channels.add(settings.bssidInfos[i].frequencyHint);
            }

            // cancel previous scan
            removeScanRequest();

            // set new scanning schedule
            ScanSettings settings2 = new ScanSettings();
            settings2.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            settings2.channels = new ChannelSpec[channels.size()];
            int i = 0;
            for (Integer channel : channels) {
                settings2.channels[i++] = new ChannelSpec(channel);
            }

            settings2.periodInMs = settings.periodInMs;
            addScanRequest(settings2);

            // start tracking new APs
            trackSignificantWifiChange(settings);
        }

        class WifiChangeClientInfo extends ClientInfo {
            WifiChangeClientInfo() {
                super(0, null); // TODO figure out how to charge apps for power correctly
            }
            @Override
            void deliverScanResults(int handler, ScanData results[]) {
                if (DBG) localLog("Delivering messages directly");
                sendMessage(WIFI_CHANGE_CMD_NEW_SCAN_RESULTS, 0, 0, results);
            }
            @Override
            void reportPeriodChanged(int handler, ScanSettings settings, int newPeriodInMs) {
                // nothing to do; no one is listening for this
            }
        }

        @Override
        public void onChangesFound(ScanResult results[]) {
            sendMessage(WIFI_CHANGE_CMD_CHANGE_DETECTED, 0, 0, results);
        }

        ClientInfo mClientInfo = new WifiChangeClientInfo();
        private static final int SCAN_COMMAND_ID = 1;

        void addScanRequest(ScanSettings settings) {
            if (DBG) localLog("Starting scans");
            Message msg = Message.obtain();
            msg.what = WifiScanner.CMD_START_BACKGROUND_SCAN;
            msg.arg2 = SCAN_COMMAND_ID;
            msg.obj = settings;
            mClientHandler.sendMessage(msg);
        }

        void removeScanRequest() {
            if (DBG) localLog("Stopping scans");
            Message msg = Message.obtain();
            msg.what = WifiScanner.CMD_STOP_BACKGROUND_SCAN;
            msg.arg2 = SCAN_COMMAND_ID;
            mClientHandler.sendMessage(msg);
        }

        void trackSignificantWifiChange(WifiScanner.WifiChangeSettings settings) {
            mWifiNative.untrackSignificantWifiChange();
            mWifiNative.trackSignificantWifiChange(settings, this);
        }

        void untrackSignificantWifiChange() {
            mWifiNative.untrackSignificantWifiChange();
        }

    }

    private static String toString(int uid, ScanSettings settings) {
        StringBuilder sb = new StringBuilder();
        sb.append("ScanSettings[uid=").append(uid);
        sb.append(", period=").append(settings.periodInMs);
        sb.append(", report=").append(settings.reportEvents);
        if (settings.reportEvents == WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL
                && settings.numBssidsPerScan > 0
                && settings.maxScansToCache > 1) {
            sb.append(", batch=").append(settings.maxScansToCache);
            sb.append(", numAP=").append(settings.numBssidsPerScan);
        }
        if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED) {
            sb.append(", band=(").append(WifiChannelHelper.toString(settings.band)).append(")");
        } else {
            sb.append(", channels=(").append(WifiChannelHelper.toString(settings.channels)).append(")");
        }
        sb.append("]");

        return sb.toString();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiScanner from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }
        mLocalLog.dump(fd, pw, args);
        pw.println();
        pw.println("clients:");
        for (ClientInfo client : mClients.values()) {
            pw.println("  " + client);
        }
        pw.println("listeners:");
        for (ClientInfo client : mClients.values()) {
            for (ScanSettings settings : client.getScanSettings()) {
                pw.println("  " + toString(client.mUid, settings));
            }
        }
        WifiNative.ScanSettings schedule = mScheduler.getSchedule();
        if (schedule != null) {
            pw.println("schedule:");
            pw.println("  base period: " + schedule.base_period_ms);
            pw.println("  max ap per scan: " + schedule.max_ap_per_scan);
            pw.println("  batched scans: " + schedule.report_threshold_num_scans);
            pw.println("  buckets:");
            for (int b = 0; b < schedule.num_buckets; b++) {
                WifiNative.BucketSettings bucket = schedule.buckets[b];
                pw.println("    bucket " + bucket.bucket + " (" + bucket.period_ms + "ms)["
                        + bucket.report_events + "]: "
                        + WifiChannelHelper.toString(bucket.band, bucket.channels, bucket.num_channels, 8));
            }
        }
    }

    static String describe(ScanSettings scanSettings) {
        StringBuilder sb = new StringBuilder();
        sb.append("  band:").append(scanSettings.band);
        sb.append("  period:").append(scanSettings.periodInMs);
        sb.append("  reportEvents:").append(scanSettings.reportEvents);
        sb.append("  numBssidsPerScan:").append(scanSettings.numBssidsPerScan);
        sb.append("  maxScansToCache:").append(scanSettings.maxScansToCache).append("\n");

        sb.append("  channels: ");

        if (scanSettings.channels != null) {
            for (int i = 0; i < scanSettings.channels.length; i++) {
                sb.append(scanSettings.channels[i].frequency);
                sb.append(" ");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

}
