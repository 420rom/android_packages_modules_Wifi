/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.SystemClock;
import android.util.Base64;
import android.util.SparseArray;

import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.util.InformationElementUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Provides storage for wireless connectivity metrics, as they are generated.
 * Metrics logged by this class include:
 *   Aggregated connection stats (num of connections, num of failures, ...)
 *   Discrete connection event stats (time, duration, failure codes, ...)
 *   Router details (technology type, authentication type, ...)
 *   Scan stats
 */
public class WifiMetrics {
    private static final String TAG = "WifiMetrics";
    private final Object mLock = new Object();
    private static final int MAX_CONNECTION_EVENTS = 256;
    /**
     * Metrics are stored within an instance of the WifiLog proto during runtime,
     * The ConnectionEvent, SystemStateEntries & ScanReturnEntries metrics are stored during
     * runtime in member lists of this WifiMetrics class, with the final WifiLog proto being pieced
     * together at dump-time
     */
    private final WifiMetricsProto.WifiLog mWifiLogProto;
    /**
     * Session information that gets logged for every Wifi connection attempt.
     */
    private final List<ConnectionEvent> mConnectionEventList;
    /**
     * The latest started (but un-ended) connection attempt
     */
    private ConnectionEvent mCurrentConnectionEvent;
    /**
     * Count of number of times each scan return code, indexed by WifiLog.ScanReturnCode
     */
    private final SparseArray<WifiMetricsProto.WifiLog.ScanReturnEntry> mScanReturnEntries;
    /**
     * Mapping of system state to the counts of scans requested in that wifi state * screenOn
     * combination. Indexed by WifiLog.WifiState * (1 + screenOn)
     */
    private final SparseArray<WifiMetricsProto.WifiLog.WifiSystemStateEntry>
            mWifiSystemStateEntries;

    class RouterFingerPrint {
        private WifiMetricsProto.RouterFingerPrint mRouterFingerPrintProto;
        RouterFingerPrint() {
            mRouterFingerPrintProto = new WifiMetricsProto.RouterFingerPrint();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            synchronized (mLock) {
                sb.append("mConnectionEvent.roamType=" + mRouterFingerPrintProto.roamType);
                sb.append(", mChannelInfo=" + mRouterFingerPrintProto.channelInfo);
                sb.append(", mDtim=" + mRouterFingerPrintProto.dtim);
                sb.append(", mAuthentication=" + mRouterFingerPrintProto.authentication);
                sb.append(", mHidden=" + mRouterFingerPrintProto.hidden);
                sb.append(", mRouterTechnology=" + mRouterFingerPrintProto.routerTechnology);
                sb.append(", mSupportsIpv6=" + mRouterFingerPrintProto.supportsIpv6);
            }
            return sb.toString();
        }
        public void updateFromWifiConfiguration(WifiConfiguration config) {
            if (config != null) {
                /*<TODO>
                mRouterFingerPrintProto.roamType
                mRouterFingerPrintProto.supportsIpv6
                */
                mRouterFingerPrintProto.hidden = config.hiddenSSID;
                mRouterFingerPrintProto.channelInfo = config.apChannel;
                // Config may not have a valid dtimInterval set yet, in which case dtim will be zero
                // (These are only populated from beacon frame scan results, which are returned as
                // scan results from the chip far less frequently than Probe-responses)
                if (config.dtimInterval > 0) {
                    mRouterFingerPrintProto.dtim = config.dtimInterval;
                }
            }
        }
    }

    /**
     * Log event, tracking the start time, end time and result of a wireless connection attempt.
     */
    class ConnectionEvent {
        WifiMetricsProto.ConnectionEvent mConnectionEvent;
        //<TODO> Move these constants into a wifi.proto Enum
        // Level 2 Failure Codes
        // Failure is unknown
        public static final int LLF_UNKNOWN = 0;
        // NONE
        public static final int LLF_NONE = 1;
        // ASSOCIATION_REJECTION_EVENT
        public static final int LLF_ASSOCIATION_REJECTION = 2;
        // AUTHENTICATION_FAILURE_EVENT
        public static final int LLF_AUTHENTICATION_FAILURE = 3;
        // SSID_TEMP_DISABLED (Also Auth failure)
        public static final int LLF_SSID_TEMP_DISABLED = 4;
        // reconnect() or reassociate() call to WifiNative failed
        public static final int LLF_CONNECT_NETWORK_FAILED = 5;
        // NETWORK_DISCONNECTION_EVENT
        public static final int LLF_NETWORK_DISCONNECTION = 6;
        // NEW_CONNECTION_ATTEMPT before previous finished
        public static final int LLF_NEW_CONNECTION_ATTEMPT = 7;
        RouterFingerPrint mRouterFingerPrint;
        private long mRealStartTime;
        private long mRealEndTime;
        private String mConfigSsid;
        private String mConfigBssid;

        private ConnectionEvent() {
            mConnectionEvent = new WifiMetricsProto.ConnectionEvent();
            mRealEndTime = 0;
            mRealStartTime = 0;
            mRouterFingerPrint = new RouterFingerPrint();
            mConnectionEvent.routerFingerprint = mRouterFingerPrint.mRouterFingerPrintProto;
            mConfigSsid = "<NULL>";
            mConfigBssid = "<NULL>";
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("startTime=");
            Calendar c = Calendar.getInstance();
            synchronized (mLock) {
                c.setTimeInMillis(mConnectionEvent.startTimeMillis);
                sb.append(mConnectionEvent.startTimeMillis == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", SSID=");
                sb.append(mConfigSsid);
                sb.append(", BSSID=");
                sb.append(mConfigBssid);
                sb.append(", durationMillis=");
                sb.append(mConnectionEvent.durationTakenToConnectMillis);
                sb.append(", roamType=");
                switch(mConnectionEvent.roamType) {
                    case 1:
                        sb.append("ROAM_NONE");
                        break;
                    case 2:
                        sb.append("ROAM_DBDC");
                        break;
                    case 3:
                        sb.append("ROAM_ENTERPRISE");
                        break;
                    case 4:
                        sb.append("ROAM_USER_SELECTED");
                        break;
                    case 5:
                        sb.append("ROAM_UNRELATED");
                        break;
                    default:
                        sb.append("ROAM_UNKNOWN");
                }
                sb.append(", connectionResult=");
                sb.append(mConnectionEvent.connectionResult);
                sb.append(", level2FailureCode=");
                switch(mConnectionEvent.level2FailureCode) {
                    case LLF_NONE:
                        sb.append("NONE");
                        break;
                    case LLF_ASSOCIATION_REJECTION:
                        sb.append("ASSOCIATION_REJECTION");
                        break;
                    case LLF_AUTHENTICATION_FAILURE:
                        sb.append("AUTHENTICATION_FAILURE");
                        break;
                    case LLF_SSID_TEMP_DISABLED:
                        sb.append("SSID_TEMP_DISABLED");
                        break;
                    case LLF_CONNECT_NETWORK_FAILED:
                        sb.append("CONNECT_NETWORK_FAILED");
                        break;
                    case LLF_NETWORK_DISCONNECTION:
                        sb.append("NETWORK_DISCONNECTION");
                        break;
                    case LLF_NEW_CONNECTION_ATTEMPT:
                        sb.append("NEW_CONNECTION_ATTEMPT");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", connectivityLevelFailureCode=");
                switch(mConnectionEvent.connectivityLevelFailureCode) {
                    case WifiMetricsProto.ConnectionEvent.HLF_NONE:
                        sb.append("NONE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_DHCP:
                        sb.append("DHCP");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_NO_INTERNET:
                        sb.append("NO_INTERNET");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_UNWANTED:
                        sb.append("UNWANTED");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", signalStrength=");
                sb.append(mConnectionEvent.signalStrength);
                sb.append("\n  ");
                sb.append("mRouterFingerprint: ");
                sb.append(mRouterFingerPrint.toString());
            }
            return sb.toString();
        }
    }

    public WifiMetrics() {
        mWifiLogProto = new WifiMetricsProto.WifiLog();
        mConnectionEventList = new ArrayList<>();
        mCurrentConnectionEvent = null;
        mScanReturnEntries = new SparseArray<WifiMetricsProto.WifiLog.ScanReturnEntry>();
        mWifiSystemStateEntries = new SparseArray<WifiMetricsProto.WifiLog.WifiSystemStateEntry>();
    }

    /**
     * Create a new connection event. Call when wifi attempts to make a new network connection
     * If there is a current 'un-ended' connection event, it will be ended with UNKNOWN connectivity
     * failure code.
     * Gathers and sets the RouterFingerPrint data as well
     *
     * @param config WifiConfiguration of the config used for the current connection attempt
     * @param roamType Roam type that caused connection attempt, see WifiMetricsProto.WifiLog.ROAM_X
     */
    public void startConnectionEvent(WifiConfiguration config, int roamType) {
        if (mCurrentConnectionEvent != null) {
            endConnectionEvent(ConnectionEvent.LLF_NEW_CONNECTION_ATTEMPT,
                    WifiMetricsProto.ConnectionEvent.HLF_NONE);
        }
        synchronized (mLock) {
            //If at maximum connection events, start removing the oldest
            while(mConnectionEventList.size() >= MAX_CONNECTION_EVENTS) {
                mConnectionEventList.remove(0);
            }
            mCurrentConnectionEvent = new ConnectionEvent();
            mCurrentConnectionEvent.mConnectionEvent.startTimeMillis =
                    System.currentTimeMillis();
            mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
            mCurrentConnectionEvent.mRouterFingerPrint.updateFromWifiConfiguration(config);
            if (config != null) {
                ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
                if (candidate != null) {
                    updateMetricsFromScanResult(candidate);
                }
                mCurrentConnectionEvent.mConfigSsid = config.SSID;
                mCurrentConnectionEvent.mConfigBssid = config.BSSID;
            }
            mCurrentConnectionEvent.mRealStartTime = SystemClock.elapsedRealtime();
            mConnectionEventList.add(mCurrentConnectionEvent);
        }
    }

    /**
     * set the RoamType of the current ConnectionEvent (if any)
     */
    public void setConnectionEventRoamType(int roamType) {
        if (mCurrentConnectionEvent != null) {
            mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
        }
    }

    /**
     * Set AP related metrics from ScanDetail
     */
    public void setConnectionScanDetail(ScanDetail scanDetail) {
        if (mCurrentConnectionEvent != null && scanDetail != null) {
            NetworkDetail networkDetail = scanDetail.getNetworkDetail();
            ScanResult scanResult = scanDetail.getScanResult();
            //Ensure that we have a networkDetail, and that it corresponds to the currently
            //tracked connection attempt
            if (networkDetail != null && scanResult != null
                    && mCurrentConnectionEvent.mConfigSsid != null
                    && mCurrentConnectionEvent.mConfigSsid
                    .equals("\"" + networkDetail.getSSID() + "\"")) {
                updateMetricsFromNetworkDetail(networkDetail);
                updateMetricsFromScanResult(scanResult);
            }
        }
    }

    /**
     * End a Connection event record. Call when wifi connection attempt succeeds or fails.
     * If a Connection event has not been started and is active when .end is called, a new one is
     * created with zero duration.
     *
     * @param level2FailureCode Level 2 failure code returned by supplicant
     * @param connectivityFailureCode WifiMetricsProto.ConnectionEvent.HLF_X
     */
    public void endConnectionEvent(int level2FailureCode, int connectivityFailureCode) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                boolean result = (level2FailureCode == 1)
                        && (connectivityFailureCode == WifiMetricsProto.ConnectionEvent.HLF_NONE);
                mCurrentConnectionEvent.mConnectionEvent.connectionResult = result ? 1 : 0;
                mCurrentConnectionEvent.mRealEndTime = SystemClock.elapsedRealtime();
                mCurrentConnectionEvent.mConnectionEvent.durationTakenToConnectMillis = (int)
                        (mCurrentConnectionEvent.mRealEndTime
                        - mCurrentConnectionEvent.mRealStartTime);
                mCurrentConnectionEvent.mConnectionEvent.level2FailureCode = level2FailureCode;
                mCurrentConnectionEvent.mConnectionEvent.connectivityLevelFailureCode =
                        connectivityFailureCode;
                // ConnectionEvent already added to ConnectionEvents List. Safe to null current here
                mCurrentConnectionEvent = null;
            }
        }
    }

    /**
     * Set ConnectionEvent DTIM Interval (if set), and 802.11 Connection mode, from NetworkDetail
     */
    private void updateMetricsFromNetworkDetail(NetworkDetail networkDetail) {
        int dtimInterval = networkDetail.getDtimInterval();
        if (dtimInterval > 0) {
            mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.dtim =
                    dtimInterval;
        }
        int connectionWifiMode;
        switch (networkDetail.getWifiMode()) {
            case InformationElementUtil.WifiMode.MODE_UNDEFINED:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_UNKNOWN;
                break;
            case InformationElementUtil.WifiMode.MODE_11A:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_A;
                break;
            case InformationElementUtil.WifiMode.MODE_11B:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_B;
                break;
            case InformationElementUtil.WifiMode.MODE_11G:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_G;
                break;
            case InformationElementUtil.WifiMode.MODE_11N:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_N;
                break;
            case InformationElementUtil.WifiMode.MODE_11AC  :
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_AC;
                break;
            default:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_OTHER;
                break;
        }
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                .routerTechnology = connectionWifiMode;
    }

    /**
     * Set ConnectionEvent RSSI and authentication type from ScanResult
     */
    private void updateMetricsFromScanResult(ScanResult scanResult) {
        mCurrentConnectionEvent.mConnectionEvent.signalStrength = scanResult.level;
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
        if (scanResult.capabilities != null) {
            if (scanResult.capabilities.contains("WEP")) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (scanResult.capabilities.contains("PSK")) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (scanResult.capabilities.contains("EAP")) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
            }
        }
    }

    void setNumSavedNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numSavedNetworks = num;
        }
    }

    void setNumOpenNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numOpenNetworks = num;
        }
    }

    void setNumPersonalNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numPersonalNetworks = num;
        }
    }

    void setNumEnterpriseNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numEnterpriseNetworks = num;
        }
    }

    void setNumNetworksAddedByUser(int num) {
        synchronized (mLock) {
            mWifiLogProto.numNetworksAddedByUser = num;
        }
    }

    void setNumNetworksAddedByApps(int num) {
        synchronized (mLock) {
            mWifiLogProto.numNetworksAddedByApps = num;
        }
    }

    void setIsLocationEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isLocationEnabled = enabled;
        }
    }

    void setIsScanningAlwaysEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isScanningAlwaysEnabled = enabled;
        }
    }

    /**
     * Increment Airplane mode toggle count
     */
    public void incrementAirplaneToggleCount() {
        synchronized (mLock) {
            mWifiLogProto.numWifiToggledViaAirplane++;
        }
    }

    /**
     * Increment Wifi Toggle count
     */
    public void incrementWifiToggleCount() {
        synchronized (mLock) {
            mWifiLogProto.numWifiToggledViaSettings++;
        }
    }

    /**
     * Increment Non Empty Scan Results count
     */
    public void incrementNonEmptyScanResultCount() {
        synchronized (mLock) {
            mWifiLogProto.numNonEmptyScanResults++;
        }
    }

    /**
     * Increment Empty Scan Results count
     */
    public void incrementEmptyScanResultCount() {
        synchronized (mLock) {
            mWifiLogProto.numEmptyScanResults++;
        }
    }

    /**
     * Increment count of scan return code occurrence
     *
     * @param scanReturnCode Return code from scan attempt WifiMetricsProto.WifiLog.SCAN_X
     */
    public void incrementScanReturnEntry(int scanReturnCode) {
        synchronized (mLock) {
            WifiMetricsProto.WifiLog.ScanReturnEntry entry = mScanReturnEntries.get(scanReturnCode);
            if (entry == null) {
                entry = new WifiMetricsProto.WifiLog.ScanReturnEntry();
                entry.scanReturnCode = scanReturnCode;
                entry.scanResultsCount = 0;
            }
            entry.scanResultsCount++;
            mScanReturnEntries.put(scanReturnCode, entry);
        }
    }

    /**
     * Increments the count of scans initiated by each wifi state, accounts for screenOn/Off
     *
     * @param state State of the system when scan was initiated, see WifiMetricsProto.WifiLog.WIFI_X
     * @param screenOn Is the screen on
     */
    public void incrementWifiSystemScanStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            int index = state * (screenOn ? 2 : 1);
            WifiMetricsProto.WifiLog.WifiSystemStateEntry entry =
                    mWifiSystemStateEntries.get(index);
            if (entry == null) {
                entry = new WifiMetricsProto.WifiLog.WifiSystemStateEntry();
                entry.wifiState = state;
                entry.wifiStateCount = 0;
                entry.isScreenOn = screenOn;
            }
            entry.wifiStateCount++;
            mWifiSystemStateEntries.put(state, entry);
        }
    }

    public static final String PROTO_DUMP_ARG = "wifiMetricsProto";
    /**
     * Dump all WifiMetrics. Collects some metrics from ConfigStore, Settings and WifiManager
     * at this time
     *
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("WifiMetrics:");
            if (args.length > 0 && PROTO_DUMP_ARG.equals(args[0])) {
                //Dump serialized WifiLog proto
                consolidateProto(true);
                for (ConnectionEvent event : mConnectionEventList) {
                    if (mCurrentConnectionEvent != event) {
                        //indicate that automatic bug report has been taken for all valid
                        //connection events
                        event.mConnectionEvent.automaticBugReportTaken = true;
                    }
                }
                byte[] wifiMetricsProto = WifiMetricsProto.WifiLog.toByteArray(mWifiLogProto);
                String metricsProtoDump = Base64.encodeToString(wifiMetricsProto, Base64.DEFAULT);
                pw.println(metricsProtoDump);
                pw.println("EndWifiMetrics");
                clear();
            } else {
                pw.println("mConnectionEvents:");
                for (ConnectionEvent event : mConnectionEventList) {
                    String eventLine = event.toString();
                    if (event == mCurrentConnectionEvent) {
                        eventLine += "CURRENTLY OPEN EVENT";
                    }
                    pw.println(eventLine);
                }
                pw.println("mWifiLogProto.numSavedNetworks=" + mWifiLogProto.numSavedNetworks);
                pw.println("mWifiLogProto.numOpenNetworks=" + mWifiLogProto.numOpenNetworks);
                pw.println("mWifiLogProto.numPersonalNetworks="
                        + mWifiLogProto.numPersonalNetworks);
                pw.println("mWifiLogProto.numEnterpriseNetworks="
                        + mWifiLogProto.numEnterpriseNetworks);
                pw.println("mWifiLogProto.isLocationEnabled=" + mWifiLogProto.isLocationEnabled);
                pw.println("mWifiLogProto.isScanningAlwaysEnabled="
                        + mWifiLogProto.isScanningAlwaysEnabled);
                pw.println("mWifiLogProto.numWifiToggledViaSettings="
                        + mWifiLogProto.numWifiToggledViaSettings);
                pw.println("mWifiLogProto.numWifiToggledViaAirplane="
                        + mWifiLogProto.numWifiToggledViaAirplane);
                pw.println("mWifiLogProto.numNetworksAddedByUser="
                        + mWifiLogProto.numNetworksAddedByUser);
                //TODO - Pending scanning refactor
                pw.println("mWifiLogProto.numNetworksAddedByApps=" + "<TODO>");
                pw.println("mWifiLogProto.numNonEmptyScanResults=" + "<TODO>");
                pw.println("mWifiLogProto.numEmptyScanResults=" + "<TODO>");
                pw.println("mWifiLogProto.numOneshotScans=" + "<TODO>");
                pw.println("mWifiLogProto.numBackgroundScans=" + "<TODO>");
                pw.println("mScanReturnEntries:" + " <TODO>");
                pw.println("mSystemStateEntries:" + " <TODO>");
            }
        }
    }

    /**
     * Assign the separate ConnectionEvent, SystemStateEntry and ScanReturnCode lists to their
     * respective lists within mWifiLogProto, and clear the original lists managed here.
     *
     * @param incremental Only include ConnectionEvents created since last automatic bug report
     */
    private void consolidateProto(boolean incremental) {
        List<WifiMetricsProto.ConnectionEvent> events = new ArrayList<>();
        synchronized (mLock) {
            for (ConnectionEvent event : mConnectionEventList) {
                if (!incremental || ((mCurrentConnectionEvent != event)
                        && !event.mConnectionEvent.automaticBugReportTaken)) {
                    //Get all ConnectionEvents that haven not been dumped as a proto, also exclude
                    //the current active un-ended connection event
                    events.add(event.mConnectionEvent);
                    event.mConnectionEvent.automaticBugReportTaken = true;
                }
            }
            if (events.size() > 0) {
                mWifiLogProto.connectionEvent = events.toArray(mWifiLogProto.connectionEvent);
            }
            //<TODO> SystemStateEntry and ScanReturnCode list consolidation
        }
    }

    /**
     * Serializes all of WifiMetrics to WifiLog proto, and returns the byte array.
     * Does not count as taking an automatic bug report
     *
     * @return byte array of the deserialized & consolidated Proto
     */
    public byte[] toByteArray() {
        consolidateProto(false);
        return mWifiLogProto.toByteArray(mWifiLogProto);
    }

    /**
     * Clear all WifiMetrics, except for currentConnectionEvent.
     */
    private void clear() {
        synchronized (mLock) {
            mConnectionEventList.clear();
            if (mCurrentConnectionEvent != null) {
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
            mScanReturnEntries.clear();
            mWifiSystemStateEntries.clear();
            mWifiLogProto.clear();
        }
    }
}
