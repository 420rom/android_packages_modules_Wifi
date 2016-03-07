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

package com.android.server.wifi;

import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;

import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.NetworkInfo.DetailedState;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.PasspointManagementObjectDefinition;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.Status;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.net.DelayedDiskWrite;
import com.android.server.net.IpConfigStore;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.ANQPFactory;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.hotspot2.ANQPData;
import com.android.server.wifi.hotspot2.AnqpCache;
import com.android.server.wifi.hotspot2.IconEvent;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.SupplicantBridge;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.omadm.PasspointManagementObjectManager;
import com.android.server.wifi.hotspot2.pps.Credential;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Checksum;


/**
 * This class provides the API to manage configured
 * wifi networks. The API is not thread safe is being
 * used only from WifiStateMachine.
 *
 * It deals with the following
 * - Add/update/remove a WifiConfiguration
 *   The configuration contains two types of information.
 *     = IP and proxy configuration that is handled by WifiConfigManager and
 *       is saved to disk on any change.
 *
 *       The format of configuration file is as follows:
 *       <version>
 *       <netA_key1><netA_value1><netA_key2><netA_value2>...<EOS>
 *       <netB_key1><netB_value1><netB_key2><netB_value2>...<EOS>
 *       ..
 *
 *       (key, value) pairs for a given network are grouped together and can
 *       be in any order. A EOS at the end of a set of (key, value) pairs
 *       indicates that the next set of (key, value) pairs are for a new
 *       network. A network is identified by a unique ID_KEY. If there is no
 *       ID_KEY in the (key, value) pairs, the data is discarded.
 *
 *       An invalid version on read would result in discarding the contents of
 *       the file. On the next write, the latest version is written to file.
 *
 *       Any failures during read or write to the configuration file are ignored
 *       without reporting to the user since the likelihood of these errors are
 *       low and the impact on connectivity is low.
 *
 *     = SSID & security details that is pushed to the supplicant.
 *       supplicant saves these details to the disk on calling
 *       saveConfigCommand().
 *
 *       We have two kinds of APIs exposed:
 *        > public API calls that provide fine grained control
 *          - enableNetwork, disableNetwork, addOrUpdateNetwork(),
 *          removeNetwork(). For these calls, the config is not persisted
 *          to the disk. (TODO: deprecate these calls in WifiManager)
 *        > The new API calls - selectNetwork(), saveNetwork() & forgetNetwork().
 *          These calls persist the supplicant config to disk.
 *
 * - Maintain a list of configured networks for quick access
 *
 */
public class WifiConfigManager {

    private Context mContext;
    public static final String TAG = "WifiConfigManager";
    private static final boolean DBG = true;
    private static boolean VDBG = false;
    private static boolean VVDBG = false;

    private static final String PPS_FILE = "/data/misc/wifi/PerProviderSubscription.conf";

    /* configured networks with network id as the key */
    private final ConfigurationMap mConfiguredNetworks;

    /* A network id is a unique identifier for a network configured in the
     * supplicant. Network ids are generated when the supplicant reads
     * the configuration file at start and can thus change for networks.
     * We store the IP configuration for networks along with a unique id
     * that is generated from SSID and security type of the network. A mapping
     * from the generated unique id to network id of the network is needed to
     * map supplicant config to IP configuration. */

    /* Stores a map of NetworkId to ScanCache */
    private ConcurrentHashMap<Integer, ScanDetailCache> mScanDetailCaches;

    /**
     * Framework keeps a list of (the CRC32 hashes of) all SSIDs that where deleted by user,
     * so as, framework knows not to re-add those SSIDs automatically to the Saved networks
     */
    private Set<Long> mDeletedSSIDs = new HashSet<Long>();

    /**
     * Framework keeps a list of ephemeral SSIDs that where deleted by user,
     * so as, framework knows not to autojoin again those SSIDs based on scorer input.
     * The list is never cleared up.
     *
     * The SSIDs are encoded in a String as per definition of WifiConfiguration.SSID field.
     */
    public Set<String> mDeletedEphemeralSSIDs = new HashSet<String>();

    /* Tracks the highest priority of configured networks */
    private int mLastPriority = -1;

    private static final String ipConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/ipconfig.txt";

    // The Wifi verbose log is provided as a way to persist the verbose logging settings
    // for testing purpose.
    // It is not intended for normal use.
    private static final String WIFI_VERBOSE_LOGS_KEY
            = "WIFI_VERBOSE_LOGS";

    // As we keep deleted PSK WifiConfiguration for a while, the PSK of
    // those deleted WifiConfiguration is set to this random unused PSK
    private static final String DELETED_CONFIG_PSK = "Mjkd86jEMGn79KhKll298Uu7-deleted";

    /**
     * The threshold for each kind of error. If a network continuously encounter the same error more
     * than the threshold times, this network will be disabled. -1 means unavailable.
     */
    private static final int[] NETWORK_SELECTION_DISABLE_THRESHOLD = {
            -1, //  threshold for NETWORK_SELECTION_ENABLE
            1,  //  threshold for DISABLED_BAD_LINK
            5,  //  threshold for DISABLED_ASSOCIATION_REJECTION
            5,  //  threshold for DISABLED_AUTHENTICATION_FAILURE
            5,  //  threshold for DISABLED_DHCP_FAILURE
            5,  //  threshold for DISABLED_DNS_FAILURE
            6,  //  threshold for DISABLED_TLS_VERSION_MISMATCH
            1,  //  threshold for DISABLED_AUTHENTICATION_NO_CREDENTIALS
            1,  //  threshold for DISABLED_NO_INTERNET
            1   //  threshold for DISABLED_BY_WIFI_MANAGER
    };

    /**
     * Timeout for each kind of error. After the timeout minutes, unblock the network again.
     */
    private static final int[] NETWORK_SELECTION_DISABLE_TIMEOUT = {
            Integer.MAX_VALUE,  // threshold for NETWORK_SELECTION_ENABLE
            15,                 // threshold for DISABLED_BAD_LINK
            5,                  // threshold for DISABLED_ASSOCIATION_REJECTION
            5,                  // threshold for DISABLED_AUTHENTICATION_FAILURE
            5,                  // threshold for DISABLED_DHCP_FAILURE
            5,                  // threshold for DISABLED_DNS_FAILURE
            Integer.MAX_VALUE,  // threshold for DISABLED_TLS_VERSION
            Integer.MAX_VALUE,  // threshold for DISABLED_AUTHENTICATION_NO_CREDENTIALS
            Integer.MAX_VALUE,  // threshold for DISABLED_NO_INTERNET
            Integer.MAX_VALUE   // threshold for DISABLED_BY_WIFI_MANAGER
    };

    public int maxTxPacketForFullScans = 8;
    public int maxRxPacketForFullScans = 16;

    public int maxTxPacketForPartialScans = 40;
    public int maxRxPacketForPartialScans = 80;

    public int associatedFullScanMaxIntervalMilli = 300000;

    // Sane value for roam blacklisting (not switching to a network if already associated)
    // 2 days
    public int networkSwitchingBlackListPeriodMilli = 2 * 24 * 60 * 60 * 1000;

    public int badLinkSpeed24 = 6;
    public int badLinkSpeed5 = 12;
    public int goodLinkSpeed24 = 24;
    public int goodLinkSpeed5 = 36;

    public int maxAuthErrorsToBlacklist = 4;
    public int maxConnectionErrorsToBlacklist = 4;
    public int wifiConfigBlacklistMinTimeMilli = 1000 * 60 * 5;

    // How long a disconnected config remain considered as the last user selection
    public int wifiConfigLastSelectionHysteresis = 1000 * 60 * 3;

    // Boost RSSI values of associated networks
    public int associatedHysteresisHigh = +14;
    public int associatedHysteresisLow = +8;

    boolean showNetworks = true; // TODO set this back to false, used for debugging 17516271

    public boolean roamOnAny = false;
    public boolean onlyLinkSameCredentialConfigurations = true;

    public boolean enableLinkDebouncing = true;
    public boolean enable5GHzPreference = true;
    public boolean enableWifiCellularHandoverUserTriggeredAdjustment = true;

    public static final int maxNumScanCacheEntries = 128;

    public final AtomicBoolean enableHalBasedPno = new AtomicBoolean(false);
    public final AtomicBoolean enableSsidWhitelist = new AtomicBoolean(false);
    public final AtomicBoolean enableAutoJoinWhenAssociated = new AtomicBoolean(true);
    public final AtomicBoolean enableFullBandScanWhenAssociated = new AtomicBoolean(true);
    public final AtomicBoolean enableChipWakeUpWhenAssociated = new AtomicBoolean(true);
    public final AtomicBoolean enableRssiPollWhenAssociated = new AtomicBoolean(true);
    public AtomicInteger thresholdSaturatedRssi5 = new AtomicInteger(
            WifiQualifiedNetworkSelector.RSSI_SATURATION_5G_BAND);
    public AtomicInteger thresholdQualifiedRssi5 = new AtomicInteger(
            WifiQualifiedNetworkSelector.QUALIFIED_RSSI_5G_BAND);
    public AtomicInteger thresholdMinimumRssi5 = new AtomicInteger(
            WifiQualifiedNetworkSelector.MINIMUM_5G_ACCEPT_RSSI);
    public AtomicInteger thresholdSaturatedRssi24 = new AtomicInteger(
            WifiQualifiedNetworkSelector.RSSI_SATURATION_2G_BAND);
    public AtomicInteger thresholdQualifiedRssi24 = new AtomicInteger(
            WifiQualifiedNetworkSelector.QUALIFIED_RSSI_24G_BAND);
    public AtomicInteger thresholdMinimumRssi24 = new AtomicInteger(
            WifiQualifiedNetworkSelector.MINIMUM_2G_ACCEPT_RSSI);
    public final AtomicInteger maxTxPacketForNetworkSwitching = new AtomicInteger(40);
    public final AtomicInteger maxRxPacketForNetworkSwitching = new AtomicInteger(80);
    public final AtomicInteger enableVerboseLogging = new AtomicInteger(0);
    public final AtomicInteger associatedFullScanBackoff =
            new AtomicInteger(12); // Will be divided by 8 by WifiStateMachine
    public final AtomicInteger alwaysEnableScansWhileAssociated = new AtomicInteger(0);
    public final AtomicInteger maxNumPassiveChannelsForPartialScans = new AtomicInteger(2);
    public final AtomicInteger maxNumActiveChannelsForPartialScans = new AtomicInteger(6);
    public final AtomicInteger wifiDisconnectedShortScanIntervalMilli = new AtomicInteger(15000);
    public final AtomicInteger wifiDisconnectedLongScanIntervalMilli = new AtomicInteger(120000);
    public final AtomicInteger wifiAssociatedShortScanIntervalMilli = new AtomicInteger(20000);
    public final AtomicInteger wifiAssociatedLongScanIntervalMilli = new AtomicInteger(180000);
    public AtomicInteger currentNetworkBoost = new AtomicInteger(
            WifiQualifiedNetworkSelector.SAME_NETWORK_AWARD);
    public AtomicInteger bandAward5Ghz = new AtomicInteger(
            WifiQualifiedNetworkSelector.BAND_AWARD_5GHz);

    /**
     * Regex pattern for extracting a connect choice.
     * Matches a strings like the following:
     * <configKey>=([0:9]+)
     */
    private static Pattern mConnectChoice =
            Pattern.compile("(.*)=([0-9]+)");


    /* Enterprise configuration keys */
    /**
     * In old configurations, the "private_key" field was used. However, newer
     * configurations use the key_id field with the engine_id set to "keystore".
     * If this field is found in the configuration, the migration code is
     * triggered.
     */
    public static final String OLD_PRIVATE_KEY_NAME = "private_key";

    /**
     * This represents an empty value of an enterprise field.
     * NULL is used at wpa_supplicant to indicate an empty value
     */
    static final String EMPTY_VALUE = "NULL";

    /**
     * If Connectivity Service has triggered an unwanted network disconnect
     */
    public long lastUnwantedNetworkDisconnectTimestamp = 0;

    /**
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    private static final int DEFAULT_MAX_DHCP_RETRIES = 9;


    private final LocalLog mLocalLog;

    private final KeyStore mKeyStore;

    private IpConfigStore mIpconfigStore;
    private DelayedDiskWrite mWriter;

    private final WifiNetworkHistory mWifiNetworkHistory;
    private final WifiConfigStore mWifiConfigStore;

    /**
     * The lastSelectedConfiguration is used to remember which network
     * was selected last by the user.
     * The connection to this network may not be successful, as well
     * the selection (i.e. network priority) might not be persisted.
     * WiFi state machine is the only object that sets this variable.
     */
    private String lastSelectedConfiguration = null;
    private long mLastSelectedTimeStamp =
            WifiConfiguration.NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP;

    /*
     * BSSID blacklist, i.e. list of BSSID we want to avoid
     */
    HashSet<String> mBssidBlacklist = new HashSet<String>();

    /*
     * Lost config list, whenever we read a config from networkHistory.txt that was not in
     * wpa_supplicant.conf
     */
    HashSet<String> mLostConfigsDbg = new HashSet<String>();

    private final AnqpCache mAnqpCache;
    private final SupplicantBridge mSupplicantBridge;
    private final SupplicantBridgeCallbacks mSupplicantBridgeCallbacks;

    private final PasspointManagementObjectManager mMOManager;
    private final boolean mEnableOsuQueries;
    private final SIMAccessor mSIMAccessor;

    private WifiStateMachine mWifiStateMachine;
    private FrameworkFacade mFacade;
    private Clock mClock;

    private class SupplicantBridgeCallbacks implements SupplicantBridge.SupplicantBridgeCallbacks {
        @Override
        public void notifyANQPResponse(ScanDetail scanDetail,
                                       Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
            updateAnqpCache(scanDetail, anqpElements);
            if (anqpElements == null || anqpElements.isEmpty()) {
                return;
            }
            scanDetail.propagateANQPInfo(anqpElements);

            Map<HomeSP, PasspointMatch> matches = matchNetwork(scanDetail, false);
            Log.d(Utils.hs2LogTag(getClass()), scanDetail.getSSID() + " pass 2 matches: "
                    + toMatchString(matches));

            cacheScanResultForPasspointConfigs(scanDetail, matches, null);
        }
        @Override
        public void notifyIconFailed(long bssid) {
            Intent intent = new Intent(WifiManager.PASSPOINT_ICON_RECEIVED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_PASSPOINT_ICON_BSSID, bssid);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

    }

    WifiConfigManager(Context context,  WifiStateMachine wifiStateMachine, WifiNative wifiNative,
            FrameworkFacade facade, Clock clock, UserManager userManager, KeyStore keyStore) {
        mContext = context;
        mFacade = facade;
        mClock = clock;
        mWifiStateMachine = wifiStateMachine;
        mKeyStore = keyStore;

        if (showNetworks) {
            mLocalLog = wifiNative.getLocalLog();
        } else {
            mLocalLog = null;
        }

        wifiAssociatedShortScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_associated_short_scan_interval));
        wifiAssociatedLongScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_associated_short_scan_interval));
        wifiDisconnectedShortScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_disconnected_short_scan_interval));
        wifiDisconnectedLongScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_disconnected_long_scan_interval));

        onlyLinkSameCredentialConfigurations = mContext.getResources().getBoolean(
                R.bool.config_wifi_only_link_same_credential_configurations);
        maxNumActiveChannelsForPartialScans.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_active_channels));
        maxNumPassiveChannelsForPartialScans.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_passive_channels));
        associatedFullScanMaxIntervalMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_full_scan_max_interval);
        associatedFullScanBackoff.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_full_scan_backoff));
        enableLinkDebouncing = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_disconnection_debounce);

        enable5GHzPreference = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_5GHz_preference);

        bandAward5Ghz.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor));

        associatedHysteresisHigh = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_association_hysteresis_high);
        associatedHysteresisLow = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_association_hysteresis_low);

        thresholdMinimumRssi5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz));
        thresholdQualifiedRssi5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz));
        thresholdSaturatedRssi5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz));
        thresholdMinimumRssi24.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz));
        thresholdQualifiedRssi24.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz));
        thresholdSaturatedRssi24.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz));

        enableWifiCellularHandoverUserTriggeredAdjustment = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_cellular_handover_enable_user_triggered_adjustment);

        badLinkSpeed24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_24);
        badLinkSpeed5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_5);
        goodLinkSpeed24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_24);
        goodLinkSpeed5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_5);

        maxAuthErrorsToBlacklist = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_max_auth_errors_to_blacklist);
        maxConnectionErrorsToBlacklist = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_max_connection_errors_to_blacklist);
        wifiConfigBlacklistMinTimeMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_network_black_list_min_time_milli);

        enableAutoJoinWhenAssociated.set(mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection));

        currentNetworkBoost.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_network_boost));
        networkSwitchingBlackListPeriodMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_network_switching_blacklist_time);

        enableHalBasedPno.set(mContext.getResources().getBoolean(
                        R.bool.config_wifi_hal_pno_enable));

        enableSsidWhitelist.set(mContext.getResources().getBoolean(
                R.bool.config_wifi_ssid_white_list_enable));
        if (!enableHalBasedPno.get() && enableSsidWhitelist.get()) {
            enableSsidWhitelist.set(false);
        }

        boolean hs2on = mContext.getResources().getBoolean(R.bool.config_wifi_hotspot2_enabled);
        Log.d(Utils.hs2LogTag(getClass()), "Passpoint is " + (hs2on ? "enabled" : "disabled"));

        mConfiguredNetworks = new ConfigurationMap(userManager);
        mMOManager = new PasspointManagementObjectManager(new File(PPS_FILE), hs2on);
        mEnableOsuQueries = true;
        mAnqpCache = new AnqpCache(mClock);
        mSupplicantBridgeCallbacks = new SupplicantBridgeCallbacks();
        mSupplicantBridge = new SupplicantBridge(wifiNative, mSupplicantBridgeCallbacks);
        mScanDetailCaches = new ConcurrentHashMap<>(16, 0.75f, 2);
        mSIMAccessor = new SIMAccessor(mContext);
        mWriter = new DelayedDiskWrite();
        mIpconfigStore = new IpConfigStore(mWriter);
        mWifiNetworkHistory = new WifiNetworkHistory(context, mLocalLog, mWriter);
        mWifiConfigStore =
                new WifiConfigStore(wifiNative, mKeyStore, mLocalLog, showNetworks, true);
    }

    public void trimANQPCache(boolean all) {
        mAnqpCache.clear(all, DBG);
    }

    void enableVerboseLogging(int verbose) {
        enableVerboseLogging.set(verbose);
        if (verbose > 0) {
            VDBG = true;
            showNetworks = true;
        } else {
            VDBG = false;
        }
        if (verbose > 1) {
            VVDBG = true;
        } else {
            VVDBG = false;
        }
    }

    /**
     * Fetch the list of configured networks
     * and enable all stored networks in supplicant.
     */
    void loadAndEnableAllNetworks() {
        if (DBG) log("Loading config and enabling all networks ");
        loadConfiguredNetworks();
        enableAllNetworks();
    }

    int getConfiguredNetworksSize() {
        return mConfiguredNetworks.sizeForCurrentUser();
    }

    private List<WifiConfiguration>
    getConfiguredNetworks(Map<String, String> pskMap) {
        List<WifiConfiguration> networks = new ArrayList<>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            WifiConfiguration newConfig = new WifiConfiguration(config);
            // When updating this condition, update WifiStateMachine's CONNECT_NETWORK handler to
            // correctly handle updating existing configs that are filtered out here.
            if (config.ephemeral) {
                // Do not enumerate and return this configuration to any one,
                // for instance WiFi Picker.
                // instead treat it as unknown. the configuration can still be retrieved
                // directly by the key or networkId
                continue;
            }

            if (pskMap != null && config.allowedKeyManagement != null
                    && config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                    && pskMap.containsKey(config.SSID)) {
                newConfig.preSharedKey = pskMap.get(config.SSID);
            }
            networks.add(newConfig);
        }
        return networks;
    }

    /**
     * This function returns all configuration, and is used for cebug and creating bug reports.
     */
    private List<WifiConfiguration>
    getAllConfiguredNetworks() {
        List<WifiConfiguration> networks = new ArrayList<>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            WifiConfiguration newConfig = new WifiConfiguration(config);
            networks.add(newConfig);
        }
        return networks;
    }

    /**
     * Fetch the list of currently configured networks
     * @return List of networks
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        return getConfiguredNetworks(null);
    }

    /**
     * Fetch the list of currently configured networks, filled with real preSharedKeys
     * @return List of networks
     */
    List<WifiConfiguration> getPrivilegedConfiguredNetworks() {
        Map<String, String> pskMap = getCredentialsBySsidMap();
        List<WifiConfiguration> configurations = getConfiguredNetworks(pskMap);
        for (WifiConfiguration configuration : configurations) {
            try {
                configuration
                        .setPasspointManagementObjectTree(mMOManager.getMOTree(configuration.FQDN));
            } catch (IOException ioe) {
                Log.w(TAG, "Failed to parse MO from " + configuration.FQDN + ": " + ioe);
            }
        }
        return configurations;
    }

    /**
     * Fetch the list of networkId's which are hidden in current user's configuration.
     * @return List of networkIds
     */
    public Set<Integer> getHiddenConfiguredNetworkIds() {
        return mConfiguredNetworks.getHiddenNetworkIdsForCurrentUser();
    }

    /**
     * Find matching network for this scanResult
     */
    WifiConfiguration getMatchingConfig(ScanResult scanResult) {

        for (Map.Entry entry : mScanDetailCaches.entrySet()) {
            Integer netId = (Integer) entry.getKey();
            ScanDetailCache cache = (ScanDetailCache) entry.getValue();
            WifiConfiguration config = getWifiConfiguration(netId);
            if (config == null)
                continue;
            if (cache.get(scanResult.BSSID) != null) {
                return config;
            }
        }

        return null;
    }

    /**
     * Fetch the preSharedKeys for all networks.
     * @return a map from Ssid to preSharedKey.
     */
    private Map<String, String> getCredentialsBySsidMap() {
        return readNetworkVariablesFromSupplicantFile("psk");
    }

    /**
     * Fetch the list of currently configured networks that were recently seen
     *
     * @return List of networks
     */
    List<WifiConfiguration> getRecentConfiguredNetworks(int milli, boolean copy) {
        List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();

        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            if (config.ephemeral) {
                // Do not enumerate and return this configuration to any one,
                // instead treat it as unknown. the configuration can still be retrieved
                // directly by the key or networkId
                continue;
            }

            // Calculate the RSSI for scan results that are more recent than milli
            ScanDetailCache cache = getScanDetailCache(config);
            if (cache == null) {
                continue;
            }
            config.setVisibility(cache.getVisibility(milli));
            if (config.visibility == null) {
                continue;
            }
            if (config.visibility.rssi5 == WifiConfiguration.INVALID_RSSI &&
                    config.visibility.rssi24 == WifiConfiguration.INVALID_RSSI) {
                continue;
            }
            if (copy) {
                networks.add(new WifiConfiguration(config));
            } else {
                networks.add(config);
            }
        }
        return networks;
    }

    /**
     *  Update the configuration and BSSID with latest RSSI value.
     */
    void updateConfiguration(WifiInfo info) {
        WifiConfiguration config = getWifiConfiguration(info.getNetworkId());
        if (config != null && getScanDetailCache(config) != null) {
            ScanDetail scanDetail = getScanDetailCache(config).getScanDetail(info.getBSSID());
            if (scanDetail != null) {
                ScanResult result = scanDetail.getScanResult();
                long previousSeen = result.seen;
                int previousRssi = result.level;

                // Update the scan result
                scanDetail.setSeen();
                result.level = info.getRssi();

                // Average the RSSI value
                result.averageRssi(previousRssi, previousSeen,
                        WifiQualifiedNetworkSelector.SCAN_RESULT_MAXIMUNM_AGE);
                if (VDBG) {
                    loge("updateConfiguration freq=" + result.frequency
                        + " BSSID=" + result.BSSID
                        + " RSSI=" + result.level
                        + " " + config.configKey());
                }
            }
        }
    }

    /**
     * get the Wificonfiguration for this netId
     *
     * @return Wificonfiguration
     */
    public WifiConfiguration getWifiConfiguration(int netId) {
        return mConfiguredNetworks.getForCurrentUser(netId);
    }

    /**
     * Get the Wificonfiguration for this key
     * @return Wificonfiguration
     */
    public WifiConfiguration getWifiConfiguration(String key) {
        return mConfiguredNetworks.getByConfigKeyForCurrentUser(key);
    }

    /**
     * Enable all networks (if disabled time expire) and save config. This will be a no-op if the
     * list of configured networks indicates all networks as being enabled
     */
    void enableAllNetworks() {
        boolean networkEnabledStateChanged = false;

        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            if (config != null && !config.ephemeral
                    && !config.getNetworkSelectionStatus().isNetworkEnabled()) {
                if (tryEnableQualifiedNetwork(config)) {
                    networkEnabledStateChanged = true;
                }
            }
        }

        if (networkEnabledStateChanged) {
            saveConfig();
            sendConfiguredNetworksChangedBroadcast();
        }
    }

    /**
     * Enable a network in wpa_supplicant.
     */
    boolean enableNetworkNative(WifiConfiguration config) {
        return mWifiConfigStore.enableNetwork(config);
    }

    /**
     * Enable all networks in wpa_supplicant.
     */
    void enableAllNetworksNative() {
        if (mWifiConfigStore.enableAllNetworks(mConfiguredNetworks.valuesForCurrentUser())) {
            sendConfiguredNetworksChangedBroadcast();
        }
    }

    private boolean setNetworkPriorityNative(WifiConfiguration config, int priority) {
        return mWifiConfigStore.setNetworkPriority(config, priority);
    }

    private boolean setSSIDNative(WifiConfiguration config, String ssid) {
        return mWifiConfigStore.setNetworkSSID(config, ssid);
    }

    public boolean updateLastConnectUid(WifiConfiguration config, int uid) {
        if (config != null) {
            if (config.lastConnectUid != uid) {
                config.lastConnectUid = uid;
                return true;
            }
        }
        return false;
    }

    /**
     * Selects the specified network for connection. This involves
     * updating the priority of all the networks and enabling the given
     * network while disabling others.
     *
     * Selecting a network will leave the other networks disabled and
     * a call to enableAllNetworks() needs to be issued upon a connection
     * or a failure event from supplicant
     *
     * @param config network to select for connection
     * @param updatePriorities makes config highest priority network
     * @return false if the network id is invalid
     */
    boolean selectNetwork(WifiConfiguration config, boolean updatePriorities, int uid) {
        if (VDBG) localLogNetwork("selectNetwork", config.networkId);
        if (config.networkId == INVALID_NETWORK_ID) return false;
        if (!WifiConfigurationUtil.isVisibleToAnyProfile(config,
                mWifiStateMachine.getCurrentUserProfiles())) {
            loge("selectNetwork " + Integer.toString(config.networkId) + ": Network config is not "
                    + "visible to current user.");
            return false;
        }

        // Reset the priority of each network at start or if it goes too high.
        if (mLastPriority == -1 || mLastPriority > 1000000) {
            if (updatePriorities) {
                for (WifiConfiguration config2 : mConfiguredNetworks.valuesForCurrentUser()) {
                    if (config2.networkId != INVALID_NETWORK_ID) {
                        setNetworkPriorityNative(config2, 0);
                    }
                }
            }
            mLastPriority = 0;
        }

        // Set to the highest priority and save the configuration.
        if (updatePriorities) {
            setNetworkPriorityNative(config, ++mLastPriority);
        }

        if (config.isPasspoint()) {
            /* need to slap on the SSID of selected bssid to work */
            if (getScanDetailCache(config).size() != 0) {
                ScanDetail result = getScanDetailCache(config).getFirst();
                if (result == null) {
                    loge("Could not find scan result for " + config.BSSID);
                } else {
                    log("Setting SSID for " + config.networkId + " to" + result.getSSID());
                    setSSIDNative(config, result.getSSID());
                }

            } else {
                loge("Could not find bssid for " + config);
            }
        }

        mWifiConfigStore.enableHS20(config.isPasspoint());

        if (updatePriorities)
            saveConfig();

        updateLastConnectUid(config, uid);

        writeKnownNetworkHistory();

        /* Enable the given network while disabling all other networks */
        selectNetworkWithoutBroadcast(config.networkId);

       /* Avoid saving the config & sending a broadcast to prevent settings
        * from displaying a disabled list of networks */
        return true;
    }

    /**
     * Add/update the specified configuration and save config
     *
     * @param config WifiConfiguration to be saved
     * @return network update result
     */
    NetworkUpdateResult saveNetwork(WifiConfiguration config, int uid) {
        WifiConfiguration conf;

        // A new network cannot have null SSID
        if (config == null || (config.networkId == INVALID_NETWORK_ID &&
                config.SSID == null)) {
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        if (!WifiConfigurationUtil.isVisibleToAnyProfile(config,
                mWifiStateMachine.getCurrentUserProfiles())) {
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        if (VDBG) localLogNetwork("WifiConfigManager: saveNetwork netId", config.networkId);
        if (VDBG) {
            logd("WifiConfigManager saveNetwork,"
                    + " size=" + Integer.toString(mConfiguredNetworks.sizeForAllUsers())
                    + " (for all users)"
                    + " SSID=" + config.SSID
                    + " Uid=" + Integer.toString(config.creatorUid)
                    + "/" + Integer.toString(config.lastUpdateUid));
        }

        if (mDeletedEphemeralSSIDs.remove(config.SSID)) {
            if (VDBG) {
                loge("WifiConfigManager: removed from ephemeral blacklist: " + config.SSID);
            }
            // NOTE: This will be flushed to disk as part of the addOrUpdateNetworkNative call
            // below, since we're creating/modifying a config.
        }

        boolean newNetwork = (config.networkId == INVALID_NETWORK_ID);
        NetworkUpdateResult result = addOrUpdateNetworkNative(config, uid);
        int netId = result.getNetworkId();

        if (VDBG) localLogNetwork("WifiConfigManager: saveNetwork got it back netId=", netId);

        conf = mConfiguredNetworks.getForCurrentUser(netId);
        if (conf != null) {
            if (!conf.getNetworkSelectionStatus().isNetworkEnabled()) {
                if (VDBG) localLog("WifiConfigManager: re-enabling: " + conf.SSID);

                // reenable autojoin, since new information has been provided
                updateNetworkSelectionStatus(netId,
                        WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
            }
            if (VDBG) {
                loge("WifiConfigManager: saveNetwork got config back netId="
                        + Integer.toString(netId)
                        + " uid=" + Integer.toString(config.creatorUid));
            }
        }

        saveConfig();
        sendConfiguredNetworksChangedBroadcast(conf, result.isNewNetwork() ?
                WifiManager.CHANGE_REASON_ADDED : WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        return result;
    }

    void noteRoamingFailure(WifiConfiguration config, int reason) {
        if (config == null) return;
        config.lastRoamingFailure = System.currentTimeMillis();
        config.roamingFailureBlackListTimeMilli
                = 2 * (config.roamingFailureBlackListTimeMilli + 1000);
        if (config.roamingFailureBlackListTimeMilli
                > networkSwitchingBlackListPeriodMilli) {
            config.roamingFailureBlackListTimeMilli =
                    networkSwitchingBlackListPeriodMilli;
        }
        config.lastRoamingFailureReason = reason;
    }

    void saveWifiConfigBSSID(WifiConfiguration config, String bssid) {
        mWifiConfigStore.setNetworkBSSID(config, bssid);
    }


    void updateStatus(int netId, DetailedState state) {
        if (netId != INVALID_NETWORK_ID) {
            WifiConfiguration config = mConfiguredNetworks.getForAllUsers(netId);
            if (config == null) return;
            switch (state) {
                case CONNECTED:
                    config.status = Status.CURRENT;
                    //we successfully connected, hence remove the blacklist
                    updateNetworkSelectionStatus(netId,
                            WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
                    break;
                case DISCONNECTED:
                    //If network is already disabled, keep the status
                    if (config.status == Status.CURRENT) {
                        config.status = Status.ENABLED;
                    }
                    break;
                default:
                    //do nothing, retain the existing state
                    break;
            }
        }
    }


    /**
     * Disable an ephemeral SSID for the purpose of auto-joining thru scored.
     * This SSID will never be scored anymore.
     * The only way to "un-disable it" is if the user create a network for that SSID and then
     * forget it.
     *
     * @param SSID caller must ensure that the SSID passed thru this API match
     *            the WifiConfiguration.SSID rules, and thus be surrounded by quotes.
     * @return the {@link WifiConfiguration} corresponding to this SSID, if any, so that we can
     *         disconnect if this is the current network.
     */
    WifiConfiguration disableEphemeralNetwork(String SSID) {
        if (SSID == null) {
            return null;
        }

        WifiConfiguration foundConfig = mConfiguredNetworks.getEphemeralForCurrentUser(SSID);

        mDeletedEphemeralSSIDs.add(SSID);
        loge("Forget ephemeral SSID " + SSID + " num=" + mDeletedEphemeralSSIDs.size());

        if (foundConfig != null) {
            loge("Found ephemeral config in disableEphemeralNetwork: " + foundConfig.networkId);
        }

        writeKnownNetworkHistory();
        return foundConfig;
    }

    /**
     * Forget the specified network and save config
     *
     * @param netId network to forget
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean forgetNetwork(int netId) {
        if (showNetworks) localLogNetwork("forgetNetwork", netId);
        if (!removeNetwork(netId)) {
            loge("Failed to forget network " + netId);
            return false;
        }
        saveConfig();
        writeKnownNetworkHistory();
        return true;
    }

    /**
     * Add/update a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful saveNetwork() is used by the
     * state machine
     *
     * @param config wifi configuration to add/update
     * @return network Id
     */
    int addOrUpdateNetwork(WifiConfiguration config, int uid) {
        if (config == null || !WifiConfigurationUtil.isVisibleToAnyProfile(config,
                mWifiStateMachine.getCurrentUserProfiles())) {
            return WifiConfiguration.INVALID_NETWORK_ID;
        }

        if (showNetworks) localLogNetwork("addOrUpdateNetwork id=", config.networkId);
        if (config.isPasspoint()) {
            /* create a temporary SSID with providerFriendlyName */
            Long csum = getChecksum(config.FQDN);
            config.SSID = csum.toString();
            config.enterpriseConfig.setDomainSuffixMatch(config.FQDN);
        }

        NetworkUpdateResult result = addOrUpdateNetworkNative(config, uid);
        if (result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
            WifiConfiguration conf = mConfiguredNetworks.getForCurrentUser(result.getNetworkId());
            if (conf != null) {
                sendConfiguredNetworksChangedBroadcast(conf,
                    result.isNewNetwork ? WifiManager.CHANGE_REASON_ADDED :
                            WifiManager.CHANGE_REASON_CONFIG_CHANGE);
            }
        }

        return result.getNetworkId();
    }

    public int addPasspointManagementObject(String managementObject) {
        try {
            mMOManager.addSP(managementObject);
            return 0;
        } catch (IOException | SAXException e) {
            return -1;
        }
    }

    public int modifyPasspointMo(String fqdn, List<PasspointManagementObjectDefinition> mos) {
        try {
            return mMOManager.modifySP(fqdn, mos);
        } catch (IOException | SAXException e) {
            return -1;
        }
    }

    public boolean queryPasspointIcon(long bssid, String fileName) {
        return mSupplicantBridge.doIconQuery(bssid, fileName);
    }

    public int matchProviderWithCurrentNetwork(String fqdn) {
        ScanDetail scanDetail = mWifiStateMachine.getActiveScanDetail();
        if (scanDetail == null) {
            return PasspointMatch.None.ordinal();
        }
        HomeSP homeSP = mMOManager.getHomeSP(fqdn);
        if (homeSP == null) {
            return PasspointMatch.None.ordinal();
        }

        ANQPData anqpData = mAnqpCache.getEntry(scanDetail.getNetworkDetail());

        Map<Constants.ANQPElementType, ANQPElement> anqpElements =
                anqpData != null ? anqpData.getANQPElements() : null;

        return homeSP.match(scanDetail.getNetworkDetail(), anqpElements, mSIMAccessor).ordinal();
    }

    /**
     * General PnoNetwork list sorting algorithm:
     * 1, Place the fully enabled networks first. Among the fully enabled networks,
     * sort them in the oder determined by the return of |compareConfigurations| method
     * implementation.
     * 2. Next place all the temporarily disabled networks. Among the temporarily disabled
     * networks, sort them in the order determined by the return of |compareConfigurations| method
     * implementation.
     * 3. Place the permanently disabled networks last. The order among permanently disabled
     * networks doesn't matter.
     */
    private static class PnoListComparator implements Comparator<WifiConfiguration> {

        public final int ENABLED_NETWORK_SCORE = 3;
        public final int TEMPORARY_DISABLED_NETWORK_SCORE = 2;
        public final int PERMANENTLY_DISABLED_NETWORK_SCORE = 1;

        @Override
        public int compare(WifiConfiguration a, WifiConfiguration b) {
            int configAScore = getPnoNetworkSortScore(a);
            int configBScore = getPnoNetworkSortScore(b);
            if (configAScore == configBScore) {
                return compareConfigurations(a, b);
            } else {
                return Integer.compare(configBScore, configAScore);
            }
        }

        // This needs to be implemented by the connected/disconnected PNO list comparator.
        public int compareConfigurations(WifiConfiguration a, WifiConfiguration b) {
            return 0;
        }

        /**
         * Returns an integer representing a score for each configuration. The scores are assigned
         * based on the status of the configuration. The scores are assigned according to the order:
         * Fully enabled network > Temporarily disabled network > Permanently disabled network.
         */
        private int getPnoNetworkSortScore(WifiConfiguration config) {
            if (config.getNetworkSelectionStatus().isNetworkEnabled()) {
                return ENABLED_NETWORK_SCORE;
            } else if (config.getNetworkSelectionStatus().isNetworkTemporaryDisabled()) {
                return TEMPORARY_DISABLED_NETWORK_SCORE;
            } else {
                return PERMANENTLY_DISABLED_NETWORK_SCORE;
            }
        }
    }

    /**
     * Disconnected PnoNetwork list sorting algorithm:
     * Place the configurations in descending order of their |numAssociation| values. If networks
     * have the same |numAssociation|, then sort them in descending order of their |priority|
     * values.
     */
    private static final PnoListComparator sDisconnectedPnoListComparator =
            new PnoListComparator() {
                @Override
                public int compareConfigurations(WifiConfiguration a, WifiConfiguration b) {
                    if (a.numAssociation != b.numAssociation) {
                        return Long.compare(b.numAssociation, a.numAssociation);
                    } else {
                        return Integer.compare(b.priority, a.priority);
                    }
                }
            };

    /**
     * Retrieves an updated list of priorities for all the saved networks before
     * enabling disconnected PNO (wpa_supplicant based PNO).
     *
     * @return list of networks with updated priorities.
     */
    public ArrayList<WifiNative.PnoNetwork> retrieveDisconnectedPnoNetworkList() {
        return retrievePnoNetworkList(true, sDisconnectedPnoListComparator);
    }

    /**
     * Retrieves an updated list of priorities for all the saved networks before
     * enabling/disabling disconnected PNO (wpa_supplicant based PNO).
     *
     * wpa_supplicant uses the priority of networks to build the list of SSID's to monitor
     * during PNO. If there are a lot of saved networks, this list will be truncated and we
     * might end up not connecting to the networks we use most frequently. So, We want the networks
     * to be re-sorted based on the relative |numAssociation| values.
     *
     * @param enablePno boolean indicating whether PNO is being enabled or disabled.
     * @return list of networks with updated priorities.
     */
    public ArrayList<WifiNative.PnoNetwork> retrieveDisconnectedPnoNetworkList(
            boolean enablePno) {
        return retrievePnoNetworkList(enablePno, sDisconnectedPnoListComparator);
    }

    /**
     * Connected PnoNetwork list sorting algorithm:
     * Place the configurations with |lastSeenInQualifiedNetworkSelection| set first. If networks
     * have the same value, then sort them in descending order of their |numAssociation|
     * values.
     */
    private static final PnoListComparator sConnectedPnoListComparator =
            new PnoListComparator() {
                @Override
                public int compareConfigurations(WifiConfiguration a, WifiConfiguration b) {
                    boolean isConfigALastSeen =
                            a.getNetworkSelectionStatus().getSeenInLastQualifiedNetworkSelection();
                    boolean isConfigBLastSeen =
                            b.getNetworkSelectionStatus().getSeenInLastQualifiedNetworkSelection();
                    if (isConfigALastSeen != isConfigBLastSeen) {
                        return Boolean.compare(isConfigBLastSeen, isConfigALastSeen);
                    } else {
                        return Long.compare(b.numAssociation, a.numAssociation);
                    }
                }
            };

    /**
     * Retrieves an updated list of priorities for all the saved networks before
     * enabling connected PNO (HAL based ePno).
     *
     * @return list of networks with updated priorities.
     */
    public ArrayList<WifiNative.PnoNetwork> retrieveConnectedPnoNetworkList() {
        return retrievePnoNetworkList(true, sConnectedPnoListComparator);
    }

    /**
     * Retrieves an updated list of priorities for all the saved networks before
     * enabling/disabling PNO.
     *
     * @param enablePno boolean indicating whether PNO is being enabled or disabled.
     * @return list of networks with updated priorities.
     */
    private ArrayList<WifiNative.PnoNetwork> retrievePnoNetworkList(
            boolean enablePno, PnoListComparator pnoListComparator) {
        ArrayList<WifiNative.PnoNetwork> pnoList =
                new ArrayList<WifiNative.PnoNetwork>();
        ArrayList<WifiConfiguration> wifiConfigurations =
                new ArrayList<WifiConfiguration>(mConfiguredNetworks.valuesForCurrentUser());
        if (enablePno) {
            Collections.sort(wifiConfigurations, pnoListComparator);
            // Let's use the network list size as the highest priority and then go down from there.
            // So, the most frequently connected network has the highest priority now.
            int priority = wifiConfigurations.size();
            if (DBG) {
                Log.d(TAG, "Retrieve network priorities before PNO. Max priority: " + priority);
            }
            for (WifiConfiguration config : wifiConfigurations) {
                pnoList.add(new WifiNative.PnoNetwork(config, priority));
                priority--;
            }
        } else {
            // Revert the priorities back to the saved config values after PNO.
            if (DBG) Log.d(TAG, "Retrieve network priorities after PNO.");
            for (WifiConfiguration config : wifiConfigurations) {
                pnoList.add(new WifiNative.PnoNetwork(config, config.priority));
            }
        }
        return pnoList;
    }

    /**
     * Remove a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful forgetNetwork() is used by the
     * state machine for network removal
     *
     * @param netId network to be removed
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean removeNetwork(int netId) {
        if (showNetworks) localLogNetwork("removeNetwork", netId);
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (!removeConfigAndSendBroadcastIfNeeded(config)) {
            return false;
        }
        if (config.isPasspoint()) {
            writePasspointConfigs(config.FQDN, null);
        }
        return true;
    }

    static private Long getChecksum(String source) {
        Checksum csum = new CRC32();
        csum.update(source.getBytes(), 0, source.getBytes().length);
        return csum.getValue();
    }

    private boolean removeConfigWithoutBroadcast(WifiConfiguration config) {
        if (config == null) {
            return false;
        }
        if (!mWifiConfigStore.removeNetwork(config)) {
            loge("Failed to remove network " + config.networkId);
            return false;
        }
        if (config.configKey().equals(lastSelectedConfiguration)) {
            lastSelectedConfiguration = null;
        }
        mConfiguredNetworks.remove(config.networkId);
        mScanDetailCaches.remove(config.networkId);
        return true;
    }

    private boolean removeConfigAndSendBroadcastIfNeeded(WifiConfiguration config) {
        if (!removeConfigWithoutBroadcast(config)) {
            return false;
        }
        String key = config.configKey();
        if (VDBG) {
            logd("removeNetwork " + " key=" + key + " config.id=" + config.networkId);
        }
        if (config.selfAdded || config.linkedConfigurations != null
                || config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            if (!TextUtils.isEmpty(config.SSID)) {
                    /* Remember that we deleted this PSK SSID */
                if (config.SSID != null) {
                    Long csum = getChecksum(config.SSID);
                    mDeletedSSIDs.add(csum);
                    logd("removeNetwork "
                            + " key=" + key
                            + " config.id=" + config.networkId
                            + "  crc=" + csum);
                } else {
                    logd("removeNetwork "
                            + " key=" + key
                            + " config.id=" + config.networkId);
                }
            }
        }
        writeIpAndProxyConfigurations();
        sendConfiguredNetworksChangedBroadcast(config, WifiManager.CHANGE_REASON_REMOVED);
        if (!config.ephemeral) {
            removeUserSelectionPreference(key);
        }
        writeKnownNetworkHistory();
        return true;
    }

    private void removeUserSelectionPreference(String configKey) {
        if (DBG) {
            Log.d(TAG, "removeUserSelectionPreference: key is " + configKey);
        }
        if (configKey == null) {
            return;
        }
        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            WifiConfiguration.NetworkSelectionStatus status = config.getNetworkSelectionStatus();
            String connectChoice = status.getConnectChoice();
            if (connectChoice != null && connectChoice.equals(configKey)) {
                Log.d(TAG, "remove connect choice:" + connectChoice + " from " + config.SSID
                        + " : " + config.networkId);
                status.setConnectChoice(null);
                status.setConnectChoiceTimestamp(WifiConfiguration.NetworkSelectionStatus
                            .INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
            }
        }
    }

    /*
     * Remove all networks associated with an application
     *
     * @param packageName name of the package of networks to remove
     * @return {@code true} if all networks removed successfully, {@code false} otherwise
     */
    boolean removeNetworksForApp(ApplicationInfo app) {
        if (app == null || app.packageName == null) {
            return false;
        }

        boolean success = true;

        WifiConfiguration [] copiedConfigs =
                mConfiguredNetworks.valuesForCurrentUser().toArray(new WifiConfiguration[0]);
        for (WifiConfiguration config : copiedConfigs) {
            if (app.uid != config.creatorUid || !app.packageName.equals(config.creatorName)) {
                continue;
            }
            if (showNetworks) {
                localLog("Removing network " + config.SSID
                         + ", application \"" + app.packageName + "\" uninstalled"
                         + " from user " + UserHandle.getUserId(app.uid));
            }
            success &= removeNetwork(config.networkId);
        }

        saveConfig();

        return success;
    }

    boolean removeNetworksForUser(int userId) {
        boolean success = true;

        WifiConfiguration[] copiedConfigs =
                mConfiguredNetworks.valuesForAllUsers().toArray(new WifiConfiguration[0]);
        for (WifiConfiguration config : copiedConfigs) {
            if (userId != UserHandle.getUserId(config.creatorUid)) {
                continue;
            }
            success &= removeNetwork(config.networkId);
            if (showNetworks) {
                localLog("Removing network " + config.SSID
                        + ", user " + userId + " removed");
            }
        }

        return success;
    }

    /**
     * Enable a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful selectNetwork()/saveNetwork() is used by the
     * state machine for connecting to a network
     *
     * @param netId network to be enabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean enableNetwork(int netId, boolean disableOthers, int uid) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config == null) {
            return false;
        }
        boolean ret = true;
        if (disableOthers) {
            ret = selectNetworkWithoutBroadcast(netId);
            if (VDBG) localLogNetwork("enableNetwork(disableOthers=true, uid=" + uid + ") ", netId);
            updateLastConnectUid(getWifiConfiguration(netId), uid);

            writeKnownNetworkHistory();
            sendConfiguredNetworksChangedBroadcast();
        } else {
            if (VDBG) localLogNetwork("enableNetwork(disableOthers=false) ", netId);
            WifiConfiguration enabledNetwork;
            synchronized(mConfiguredNetworks) {                     // !!! Useless synchronization!
                enabledNetwork = mConfiguredNetworks.getForCurrentUser(netId);
            }
            // check just in case the network was removed by someone else.
            if (enabledNetwork != null) {
                sendConfiguredNetworksChangedBroadcast(enabledNetwork,
                        WifiManager.CHANGE_REASON_CONFIG_CHANGE);
            }
        }
        return ret;
    }

    boolean selectNetworkWithoutBroadcast(int netId) {
        return mWifiConfigStore.selectNetwork(
                mConfiguredNetworks.getForCurrentUser(netId),
                mConfiguredNetworks.valuesForCurrentUser());
    }

    /**
     * Disable a network in wpa_supplicant.
     */
    boolean disableNetworkNative(WifiConfiguration config) {
        return mWifiConfigStore.disableNetwork(config);
    }

    /**
     * Disable all networks in wpa_supplicant.
     */
    void disableAllNetworksNative() {
        mWifiConfigStore.disableAllNetworks(mConfiguredNetworks.valuesForCurrentUser());
    }

    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean disableNetwork(int netId) {
        return mWifiConfigStore.disableNetwork(mConfiguredNetworks.getForCurrentUser(netId));
    }

    /**
     * Update a network according to the update reason and its current state
     * @param netId The network ID of the network need update
     * @param reason The reason to update the network
     * @return false if no change made to the input configure file, can due to error or need not
     *         true the input config file has been changed
     */
    boolean updateNetworkSelectionStatus(int netId, int reason) {
        WifiConfiguration config = getWifiConfiguration(netId);
        return updateNetworkSelectionStatus(config, reason);
    }

    /**
     * Update a network according to the update reason and its current state
     * @param config the network need update
     * @param reason The reason to update the network
     * @return false if no change made to the input configure file, can due to error or need not
     *         true the input config file has been changed
     */
    boolean updateNetworkSelectionStatus(WifiConfiguration config, int reason) {
        if (config == null) {
            return false;
        }

        WifiConfiguration.NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (reason == WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE) {
            updateNetworkStatus(config, WifiConfiguration.NetworkSelectionStatus
                    .NETWORK_SELECTION_ENABLE);
            localLog("Enable network:" + config.configKey());
            return true;
        }

        networkStatus.incrementDisableReasonCounter(reason);
        if (DBG) {
            localLog("Network:" + config.SSID + "disable counter of "
                    + WifiConfiguration.NetworkSelectionStatus.getNetworkDisableReasonString(reason)
                    + " is: " + networkStatus.getDisableReasonCounter(reason) + "and threshold is: "
                    + NETWORK_SELECTION_DISABLE_THRESHOLD[reason]);
        }

        if (networkStatus.getDisableReasonCounter(reason)
                >= NETWORK_SELECTION_DISABLE_THRESHOLD[reason]) {
            return updateNetworkStatus(config, reason);
        }
        return true;
    }

    /**
     * Check the config. If it is temporarily disabled, check the disable time is expired or not, If
     * expired, enabled it again for qualified network selection.
     * @param networkId the id of the network to be checked for possible unblock (due to timeout)
     * @return true if network status has been changed
     *         false network status is not changed
     */
    boolean tryEnableQualifiedNetwork(int networkId) {
        WifiConfiguration config = getWifiConfiguration(networkId);
        if (config == null) {
            localLog("updateQualifiedNetworkstatus invalid network.");
            return false;
        }
        return tryEnableQualifiedNetwork(config);
    }

    /**
     * Check the config. If it is temporarily disabled, check the disable is expired or not, If
     * expired, enabled it again for qualified network selection.
     * @param config network to be checked for possible unblock (due to timeout)
     * @return true if network status has been changed
     *         false network status is not changed
     */
    boolean tryEnableQualifiedNetwork(WifiConfiguration config) {
        WifiConfiguration.NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (networkStatus.isNetworkTemporaryDisabled()) {
            //time difference in minutes
            long timeDifference = (System.currentTimeMillis()
                    - networkStatus.getDisableTime()) / 1000 / 60;
            if (timeDifference < 0 || timeDifference
                    >= NETWORK_SELECTION_DISABLE_TIMEOUT[
                    networkStatus.getNetworkSelectionDisableReason()]) {
                updateNetworkSelectionStatus(config.networkId,
                        networkStatus.NETWORK_SELECTION_ENABLE);
                return true;
            }
        }
        return false;
    }

    /**
     * Update a network's status. Note that there is no saveConfig operation.
     * @param config network to be updated
     * @param reason reason code for updated
     * @return false if no change made to the input configure file, can due to error or need not
     *         true the input config file has been changed
     */
    boolean updateNetworkStatus(WifiConfiguration config, int reason) {
        localLog("updateNetworkStatus:" + (config == null ? null : config.SSID));
        if (config == null) {
            return false;
        }

        WifiConfiguration.NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (reason < 0 || reason >= WifiConfiguration.NetworkSelectionStatus
                .NETWORK_SELECTION_DISABLED_MAX) {
            localLog("Invalid Network disable reason:" + reason);
            return false;
        }

        if (reason == WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE) {
            if (networkStatus.isNetworkEnabled()) {
                if (DBG) {
                    localLog("Need not change Qualified network Selection status since"
                            + " already enabled");
                }
                return false;
            }
            networkStatus.setNetworkSelectionStatus(WifiConfiguration.NetworkSelectionStatus
                    .NETWORK_SELECTION_ENABLED);
            networkStatus.setNetworkSelectionDisableReason(reason);
            networkStatus.setDisableTime(
                    WifiConfiguration.NetworkSelectionStatus
                    .INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
            networkStatus.clearDisableReasonCounter();
            String disableTime = DateFormat.getDateTimeInstance().format(new Date());
            if (DBG) {
                localLog("Re-enable network: " + config.SSID + " at " + disableTime);
            }
            sendConfiguredNetworksChangedBroadcast(config, WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        } else {
            //disable the network
            if (networkStatus.isNetworkPermanentlyDisabled()) {
                //alreay permanent disable
                if (DBG) {
                    localLog("Do nothing. Alreay permanent disabled! "
                            + WifiConfiguration.NetworkSelectionStatus
                            .getNetworkDisableReasonString(reason));
                }
                return false;
            } else if (networkStatus.isNetworkTemporaryDisabled()
                    && reason < WifiConfiguration.NetworkSelectionStatus
                    .DISABLED_TLS_VERSION_MISMATCH) {
                //alreay temporarily disable
                if (DBG) {
                    localLog("Do nothing. Already temporarily disabled! "
                            + WifiConfiguration.NetworkSelectionStatus
                            .getNetworkDisableReasonString(reason));
                }
                return false;
            }

            if (networkStatus.isNetworkEnabled()) {
                disableNetworkNative(config);
                sendConfiguredNetworksChangedBroadcast(config,
                        WifiManager.CHANGE_REASON_CONFIG_CHANGE);
                localLog("Disable network " + config.SSID + " reason:"
                        + WifiConfiguration.NetworkSelectionStatus
                        .getNetworkDisableReasonString(reason));
            }
            if (reason < WifiConfiguration.NetworkSelectionStatus.DISABLED_TLS_VERSION_MISMATCH) {
                networkStatus.setNetworkSelectionStatus(WifiConfiguration.NetworkSelectionStatus
                        .NETWORK_SELECTION_TEMPORARY_DISABLED);
                networkStatus.setDisableTime(System.currentTimeMillis());
            } else {
                networkStatus.setNetworkSelectionStatus(WifiConfiguration.NetworkSelectionStatus
                        .NETWORK_SELECTION_PERMANENTLY_DISABLED);
            }
            networkStatus.setNetworkSelectionDisableReason(reason);
            if (DBG) {
                String disableTime = DateFormat.getDateTimeInstance().format(new Date());
                localLog("Network:" + config.SSID + "Configure new status:"
                        + networkStatus.getNetworkStatusString() + " with reason:"
                        + networkStatus.getNetworkDisableReasonString() + " at: " + disableTime);
            }
        }
        return true;
    }

    /**
     * Save the configured networks in supplicant to disk
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean saveConfig() {
        return mWifiConfigStore.saveConfig();
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the access point
     * @param config WPS configuration
     * @return Wps result containing status and pin
     */
    WpsResult startWpsWithPinFromAccessPoint(WpsInfo config) {
        return mWifiConfigStore.startWpsWithPinFromAccessPoint(
                config, mConfiguredNetworks.valuesForCurrentUser());
    }

    /**
     * Start WPS pin method configuration with obtained
     * from the device
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsWithPinFromDevice(WpsInfo config) {
        return mWifiConfigStore.startWpsWithPinFromDevice(
            config, mConfiguredNetworks.valuesForCurrentUser());
    }

    /**
     * Start WPS push button configuration
     * @param config WPS configuration
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsPbc(WpsInfo config) {
        return mWifiConfigStore.startWpsPbc(
            config, mConfiguredNetworks.valuesForCurrentUser());
    }

    /**
     * Fetch the static IP configuration for a given network id
     */
    StaticIpConfiguration getStaticIpConfiguration(int netId) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config != null) {
            return config.getStaticIpConfiguration();
        }
        return null;
    }

    /**
     * Set the static IP configuration for a given network id
     */
    void setStaticIpConfiguration(int netId, StaticIpConfiguration staticIpConfiguration) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config != null) {
            config.setStaticIpConfiguration(staticIpConfiguration);
        }
    }

    /**
     * set default GW MAC address
     */
    void setDefaultGwMacAddress(int netId, String macAddress) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config != null) {
            //update defaultGwMacAddress
            config.defaultGwMacAddress = macAddress;
        }
    }


    /**
     * Fetch the proxy properties for a given network id
     * @param netId id
     * @return ProxyInfo for the network id
     */
    ProxyInfo getProxyProperties(int netId) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config != null) {
            return config.getHttpProxy();
        }
        return null;
    }

    /**
     * Return if the specified network is using static IP
     * @param netId id
     * @return {@code true} if using static ip for netId
     */
    boolean isUsingStaticIp(int netId) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config != null && config.getIpAssignment() == IpAssignment.STATIC) {
            return true;
        }
        return false;
    }

    boolean isEphemeral(int netId) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        return config != null && config.ephemeral;
    }

    /**
     * Should be called when a single network configuration is made.
     * @param network The network configuration that changed.
     * @param reason The reason for the change, should be one of WifiManager.CHANGE_REASON_ADDED,
     * WifiManager.CHANGE_REASON_REMOVED, or WifiManager.CHANGE_REASON_CHANGE.
     */
    private void sendConfiguredNetworksChangedBroadcast(WifiConfiguration network,
            int reason) {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, false);
        intent.putExtra(WifiManager.EXTRA_WIFI_CONFIGURATION, network);
        intent.putExtra(WifiManager.EXTRA_CHANGE_REASON, reason);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Should be called when multiple network configuration changes are made.
     */
    private void sendConfiguredNetworksChangedBroadcast() {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, true);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    void loadConfiguredNetworks() {

        final Map<String, WifiConfiguration> configs = new HashMap<>();
        final SparseArray<Map<String, String>> networkExtras = new SparseArray<>();
        mLastPriority = mWifiConfigStore.loadNetworks(configs, networkExtras);

        readNetworkHistory(configs);
        readPasspointConfig(configs, networkExtras);

        // We are only now updating mConfiguredNetworks for two reasons:
        // 1) The information required to compute configKeys is spread across wpa_supplicant.conf
        //    and networkHistory.txt. Thus, we had to load both files first.
        // 2) mConfiguredNetworks caches a Passpoint network's FQDN the moment the network is added.
        //    Thus, we had to load the FQDNs first.
        mConfiguredNetworks.clear();
        for (Map.Entry<String, WifiConfiguration> entry : configs.entrySet()) {
            final String configKey = entry.getKey();
            final WifiConfiguration config = entry.getValue();
            if (!configKey.equals(config.configKey())) {
                if (showNetworks) {
                    log("Ignoring network " + config.networkId + " because the configKey loaded "
                            + "from wpa_supplicant.conf is not valid.");
                }
                mWifiConfigStore.removeNetwork(config);
                continue;
            }
            mConfiguredNetworks.put(config);
        }

        readIpAndProxyConfigurations();

        sendConfiguredNetworksChangedBroadcast();

        if (showNetworks) {
            localLog("loadConfiguredNetworks loaded " + mConfiguredNetworks.sizeForAllUsers()
                    + " networks (for all users)");
        }

        if (mConfiguredNetworks.sizeForAllUsers() == 0) {
            // no networks? Lets log if the file contents
            logKernelTime();
            logContents(WifiConfigStore.SUPPLICANT_CONFIG_FILE);
            logContents(WifiConfigStore.SUPPLICANT_CONFIG_FILE_BACKUP);
            logContents(WifiNetworkHistory.NETWORK_HISTORY_CONFIG_FILE);
        }
    }

    private void logContents(String file) {
        localLogAndLogcat("--- Begin " + file + " ---");
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                localLogAndLogcat(line);
            }
        } catch (FileNotFoundException e) {
            localLog("Could not open " + file + ", " + e);
            Log.w(TAG, "Could not open " + file + ", " + e);
        } catch (IOException e) {
            localLog("Could not read " + file + ", " + e);
            Log.w(TAG, "Could not read " + file + ", " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Just ignore the fact that we couldn't close
            }
        }
        localLogAndLogcat("--- End " + file + " Contents ---");
    }

    private Map<String, String> readNetworkVariablesFromSupplicantFile(String key) {
        return mWifiConfigStore.readNetworkVariablesFromSupplicantFile(key);
    }

    private String readNetworkVariableFromSupplicantFile(String ssid, String key) {
        return mWifiConfigStore.readNetworkVariableFromSupplicantFile(ssid, key);
    }

    boolean needsUnlockedKeyStore() {

        // Any network using certificates to authenticate access requires
        // unlocked key store; unless the certificates can be stored with
        // hardware encryption

        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {

            if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP)
                    && config.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {

                if (needsSoftwareBackedKeyStore(config.enterpriseConfig)) {
                    return true;
                }
            }
        }

        return false;
    }

    void readPasspointConfig(Map<String, WifiConfiguration> configs,
            SparseArray<Map<String, String>> networkExtras) {
        List<HomeSP> homeSPs;
        try {
            homeSPs = mMOManager.loadAllSPs();
        } catch (IOException e) {
            loge("Could not read " + PPS_FILE + " : " + e);
            return;
        }

        int matchedConfigs = 0;
        for (HomeSP homeSp : homeSPs) {
            String fqdn = homeSp.getFQDN();
            Log.d(TAG, "Looking for " + fqdn);
            for (WifiConfiguration config : configs.values()) {
                Log.d(TAG, "Testing " + config.SSID);

                if (config.enterpriseConfig == null) {
                    continue;
                }
                final String configFqdn =
                        networkExtras.get(config.networkId).get(WifiConfigStore.ID_STRING_KEY_FQDN);
                if (configFqdn != null && configFqdn.equals(fqdn)) {
                    Log.d(TAG, "Matched " + configFqdn + " with " + config.networkId);
                    ++matchedConfigs;
                    config.FQDN = fqdn;
                    config.providerFriendlyName = homeSp.getFriendlyName();

                    HashSet<Long> roamingConsortiumIds = homeSp.getRoamingConsortiums();
                    config.roamingConsortiumIds = new long[roamingConsortiumIds.size()];
                    int i = 0;
                    for (long id : roamingConsortiumIds) {
                        config.roamingConsortiumIds[i] = id;
                        i++;
                    }
                    IMSIParameter imsiParameter = homeSp.getCredential().getImsi();
                    config.enterpriseConfig.setPlmn(
                            imsiParameter != null ? imsiParameter.toString() : null);
                    config.enterpriseConfig.setRealm(homeSp.getCredential().getRealm());
                }
            }
        }

        Log.d(TAG, "loaded " + matchedConfigs + " passpoint configs");
    }

    public void writePasspointConfigs(final String fqdn, final HomeSP homeSP) {
        mWriter.write(PPS_FILE, new DelayedDiskWrite.Writer() {
            @Override
            public void onWriteCalled(DataOutputStream out) throws IOException {
                try {
                    if (homeSP != null) {
                        mMOManager.addSP(homeSP);
                    } else {
                        mMOManager.removeSP(fqdn);
                    }
                } catch (IOException e) {
                    loge("Could not write " + PPS_FILE + " : " + e);
                }
            }
        }, false);
    }

    /**
     *  Write network history, WifiConfigurations and mScanDetailCaches to file.
     */
    private void readNetworkHistory(Map<String, WifiConfiguration> configs) {
        mWifiNetworkHistory.readNetworkHistory(configs,
                mScanDetailCaches,
                mDeletedSSIDs,
                mDeletedEphemeralSSIDs);
    }

    /**
     *  Read Network history from file, merge it into mConfiguredNetowrks and mScanDetailCaches
     */
    public void writeKnownNetworkHistory() {
        final List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
            networks.add(new WifiConfiguration(config));
        }
        mWifiNetworkHistory.writeKnownNetworkHistory(networks,
                mScanDetailCaches,
                mDeletedSSIDs,
                mDeletedEphemeralSSIDs);
    }

    public void setAndEnableLastSelectedConfiguration(int netId) {
        if (VDBG) {
            loge("setLastSelectedConfiguration " + Integer.toString(netId));
        }
        if (netId == WifiConfiguration.INVALID_NETWORK_ID) {
            lastSelectedConfiguration = null;
            mLastSelectedTimeStamp = -1;
        } else {
            WifiConfiguration selected = getWifiConfiguration(netId);
            if (selected == null) {
                lastSelectedConfiguration = null;
                mLastSelectedTimeStamp = -1;
            } else {
                lastSelectedConfiguration = selected.configKey();
                mLastSelectedTimeStamp = System.currentTimeMillis();
                updateNetworkSelectionStatus(netId,
                        WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
                if (VDBG) {
                    loge("setLastSelectedConfiguration now: " + lastSelectedConfiguration);
                }
            }
        }
    }

    public void setLatestUserSelectedConfiguration(WifiConfiguration network) {
        if (network != null) {
            lastSelectedConfiguration = network.configKey();
            mLastSelectedTimeStamp = System.currentTimeMillis();
        }
    }

    public String getLastSelectedConfiguration() {
        return lastSelectedConfiguration;
    }

    public long getLastSelectedTimeStamp() {
        return mLastSelectedTimeStamp;
    }

    public boolean isLastSelectedConfiguration(WifiConfiguration config) {
        return (lastSelectedConfiguration != null
                && config != null
                && lastSelectedConfiguration.equals(config.configKey()));
    }

    private void writeIpAndProxyConfigurations() {
        final SparseArray<IpConfiguration> networks = new SparseArray<IpConfiguration>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
            if (!config.ephemeral) {
                networks.put(configKey(config), config.getIpConfiguration());
            }
        }

        mIpconfigStore.writeIpAndProxyConfigurations(ipConfigFile, networks);
    }

    private void readIpAndProxyConfigurations() {
        SparseArray<IpConfiguration> networks =
                mIpconfigStore.readIpAndProxyConfigurations(ipConfigFile);

        if (networks == null || networks.size() == 0) {
            // IpConfigStore.readIpAndProxyConfigurations has already logged an error.
            return;
        }

        for (int i = 0; i < networks.size(); i++) {
            int id = networks.keyAt(i);
            WifiConfiguration config = mConfiguredNetworks.getByConfigKeyIDForAllUsers(id);
            // This is the only place the map is looked up through a (dangerous) hash-value!

            if (config == null || config.ephemeral) {
                loge("configuration found for missing network, nid=" + id
                        +", ignored, networks.size=" + Integer.toString(networks.size()));
            } else {
                config.setIpConfiguration(networks.valueAt(i));
            }
        }
    }

    private NetworkUpdateResult addOrUpdateNetworkNative(WifiConfiguration config, int uid) {
        /*
         * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */

        if (VDBG) localLog("addOrUpdateNetworkNative " + config.getPrintableSsid());
        if (config.isPasspoint() && !mMOManager.isEnabled()) {
            Log.e(TAG, "Passpoint is not enabled");
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        boolean newNetwork = false;
        boolean existingMO = false;
        WifiConfiguration currentConfig;
        // networkId of INVALID_NETWORK_ID means we want to create a new network
        if (config.networkId == INVALID_NETWORK_ID) {
            // Try to fetch the existing config using configKey
            currentConfig = mConfiguredNetworks.getByConfigKeyForCurrentUser(config.configKey());
            if (currentConfig != null) {
                config.networkId = currentConfig.networkId;
            } else {
                if (mMOManager.getHomeSP(config.FQDN) != null) {
                    loge("addOrUpdateNetworkNative passpoint " + config.FQDN
                            + " was found, but no network Id");
                    existingMO = true;
                }
                newNetwork = true;
            }
        } else {
            // Fetch the existing config using networkID
            currentConfig = mConfiguredNetworks.getForCurrentUser(config.networkId);
        }
        if (!mWifiConfigStore.addOrUpdateNetwork(config, currentConfig)) {
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }
        int netId = config.networkId;

        /* An update of the network variables requires reading them
         * back from the supplicant to update mConfiguredNetworks.
         * This is because some of the variables (SSID, wep keys &
         * passphrases) reflect different values when read back than
         * when written. For example, wep key is stored as * irrespective
         * of the value sent to the supplicant.
         */
        if (currentConfig == null) {
            currentConfig = new WifiConfiguration();
            currentConfig.setIpAssignment(IpAssignment.DHCP);
            currentConfig.setProxySettings(ProxySettings.NONE);
            currentConfig.networkId = netId;
            if (config != null) {
                // Carry over the creation parameters
                currentConfig.selfAdded = config.selfAdded;
                currentConfig.didSelfAdd = config.didSelfAdd;
                currentConfig.ephemeral = config.ephemeral;
                currentConfig.lastConnectUid = config.lastConnectUid;
                currentConfig.lastUpdateUid = config.lastUpdateUid;
                currentConfig.creatorUid = config.creatorUid;
                currentConfig.creatorName = config.creatorName;
                currentConfig.lastUpdateName = config.lastUpdateName;
                currentConfig.peerWifiConfiguration = config.peerWifiConfiguration;
                currentConfig.FQDN = config.FQDN;
                currentConfig.providerFriendlyName = config.providerFriendlyName;
                currentConfig.roamingConsortiumIds = config.roamingConsortiumIds;
                currentConfig.validatedInternetAccess = config.validatedInternetAccess;
                currentConfig.numNoInternetAccessReports = config.numNoInternetAccessReports;
                currentConfig.updateTime = config.updateTime;
                currentConfig.creationTime = config.creationTime;
                currentConfig.shared = config.shared;
            }
            if (DBG) {
                log("created new config netId=" + Integer.toString(netId)
                        + " uid=" + Integer.toString(currentConfig.creatorUid)
                        + " name=" + currentConfig.creatorName);
            }
        }

        /* save HomeSP object for passpoint networks */
        HomeSP homeSP = null;

        if (!existingMO && config.isPasspoint()) {
            try {
                if (config.updateIdentifier == null) {   // Only create an MO for r1 networks
                    Credential credential =
                            new Credential(config.enterpriseConfig, mKeyStore, !newNetwork);
                    HashSet<Long> roamingConsortiumIds = new HashSet<Long>();
                    for (Long roamingConsortiumId : config.roamingConsortiumIds) {
                        roamingConsortiumIds.add(roamingConsortiumId);
                    }

                    homeSP = new HomeSP(Collections.<String, Long>emptyMap(), config.FQDN,
                            roamingConsortiumIds, Collections.<String>emptySet(),
                            Collections.<Long>emptySet(), Collections.<Long>emptyList(),
                            config.providerFriendlyName, null, credential);

                    log("created a homeSP object for " + config.networkId + ":" + config.SSID);
                }

                /* fix enterprise config properties for passpoint */
                currentConfig.enterpriseConfig.setRealm(config.enterpriseConfig.getRealm());
                currentConfig.enterpriseConfig.setPlmn(config.enterpriseConfig.getPlmn());
            }
            catch (IOException ioe) {
                Log.e(TAG, "Failed to create Passpoint config: " + ioe);
                return new NetworkUpdateResult(INVALID_NETWORK_ID);
            }
        }

        if (uid != WifiConfiguration.UNKNOWN_UID) {
            if (newNetwork) {
                currentConfig.creatorUid = uid;
            } else {
                currentConfig.lastUpdateUid = uid;
            }
        }

        // For debug, record the time the configuration was modified
        StringBuilder sb = new StringBuilder();
        sb.append("time=");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis());
        sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));

        if (newNetwork) {
            currentConfig.creationTime = sb.toString();
        } else {
            currentConfig.updateTime = sb.toString();
        }

        if (currentConfig.status == WifiConfiguration.Status.ENABLED) {
            // Make sure autojoin remain in sync with user modifying the configuration
            updateNetworkSelectionStatus(currentConfig.networkId,
                    WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        }

        if (currentConfig.configKey().equals(getLastSelectedConfiguration()) &&
                currentConfig.ephemeral) {
            // Make the config non-ephemeral since the user just explicitly clicked it.
            currentConfig.ephemeral = false;
            if (DBG) log("remove ephemeral status netId=" + Integer.toString(netId)
                    + " " + currentConfig.configKey());
        }

        if (VDBG) log("will read network variables netId=" + Integer.toString(netId));

        readNetworkVariables(currentConfig);

        // Persist configuration paramaters that are not saved by supplicant.
        if (config.lastUpdateName != null) {
            currentConfig.lastUpdateName = config.lastUpdateName;
        }
        if (config.lastUpdateUid != WifiConfiguration.UNKNOWN_UID) {
            currentConfig.lastUpdateUid = config.lastUpdateUid;
        }

        mConfiguredNetworks.put(currentConfig);

        NetworkUpdateResult result = writeIpAndProxyConfigurationsOnChange(currentConfig, config);
        result.setIsNewNetwork(newNetwork);
        result.setNetworkId(netId);

        if (homeSP != null) {
            writePasspointConfigs(null, homeSP);
        }

        saveConfig();
        writeKnownNetworkHistory();

        return result;
    }

    public WifiConfiguration getWifiConfigForHomeSP(HomeSP homeSP) {
        WifiConfiguration config = mConfiguredNetworks.getByFQDNForCurrentUser(homeSP.getFQDN());
        if (config == null) {
            Log.e(TAG, "Could not find network for homeSP " + homeSP.getFQDN());
        }
        return config;
    }

    public HomeSP getHomeSPForConfig(WifiConfiguration config) {
        WifiConfiguration storedConfig = mConfiguredNetworks.getForCurrentUser(config.networkId);
        return storedConfig != null && storedConfig.isPasspoint() ?
                mMOManager.getHomeSP(storedConfig.FQDN) : null;
    }

    public ScanDetailCache getScanDetailCache(WifiConfiguration config) {
        if (config == null) return null;
        ScanDetailCache cache = mScanDetailCaches.get(config.networkId);
        if (cache == null && config.networkId != WifiConfiguration.INVALID_NETWORK_ID) {
            cache = new ScanDetailCache(config);
            mScanDetailCaches.put(config.networkId, cache);
        }
        return cache;
    }

    /**
     * This function run thru the Saved WifiConfigurations and check if some should be linked.
     * @param config
     */
    public void linkConfiguration(WifiConfiguration config) {
        if (!WifiConfigurationUtil.isVisibleToAnyProfile(config,
                mWifiStateMachine.getCurrentUserProfiles())) {
            loge("linkConfiguration: Attempting to link config " + config.configKey()
                    + " that is not visible to the current user.");
            return;
        }

        if (getScanDetailCache(config) != null && getScanDetailCache(config).size() > 6) {
            // Ignore configurations with large number of BSSIDs
            return;
        }
        if (!config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            // Only link WPA_PSK config
            return;
        }
        for (WifiConfiguration link : mConfiguredNetworks.valuesForCurrentUser()) {
            boolean doLink = false;

            if (link.configKey().equals(config.configKey())) {
                continue;
            }

            if (link.ephemeral) {
                continue;
            }

            // Autojoin will be allowed to dynamically jump from a linked configuration
            // to another, hence only link configurations that have equivalent level of security
            if (!link.allowedKeyManagement.equals(config.allowedKeyManagement)) {
                continue;
            }

            ScanDetailCache linkedScanDetailCache = getScanDetailCache(link);
            if (linkedScanDetailCache != null && linkedScanDetailCache.size() > 6) {
                // Ignore configurations with large number of BSSIDs
                continue;
            }

            if (config.defaultGwMacAddress != null && link.defaultGwMacAddress != null) {
                // If both default GW are known, link only if they are equal
                if (config.defaultGwMacAddress.equals(link.defaultGwMacAddress)) {
                    if (VDBG) {
                        loge("linkConfiguration link due to same gw " + link.SSID +
                                " and " + config.SSID + " GW " + config.defaultGwMacAddress);
                    }
                    doLink = true;
                }
            } else {
                // We do not know BOTH default gateways hence we will try to link
                // hoping that WifiConfigurations are indeed behind the same gateway.
                // once both WifiConfiguration have been tried and thus once both efault gateways
                // are known we will revisit the choice of linking them
                if ((getScanDetailCache(config) != null)
                        && (getScanDetailCache(config).size() <= 6)) {

                    for (String abssid : getScanDetailCache(config).keySet()) {
                        for (String bbssid : linkedScanDetailCache.keySet()) {
                            if (VVDBG) {
                                loge("linkConfiguration try to link due to DBDC BSSID match "
                                        + link.SSID +
                                        " and " + config.SSID + " bssida " + abssid
                                        + " bssidb " + bbssid);
                            }
                            if (abssid.regionMatches(true, 0, bbssid, 0, 16)) {
                                // If first 16 ascii characters of BSSID matches,
                                // we assume this is a DBDC
                                doLink = true;
                            }
                        }
                    }
                }
            }

            if (doLink == true && onlyLinkSameCredentialConfigurations) {
                String apsk = readNetworkVariableFromSupplicantFile(link.SSID, "psk");
                String bpsk = readNetworkVariableFromSupplicantFile(config.SSID, "psk");
                if (apsk == null || bpsk == null
                        || TextUtils.isEmpty(apsk) || TextUtils.isEmpty(apsk)
                        || apsk.equals("*") || apsk.equals(DELETED_CONFIG_PSK)
                        || !apsk.equals(bpsk)) {
                    doLink = false;
                }
            }

            if (doLink) {
                if (VDBG) {
                    loge("linkConfiguration: will link " + link.configKey()
                            + " and " + config.configKey());
                }
                if (link.linkedConfigurations == null) {
                    link.linkedConfigurations = new HashMap<String, Integer>();
                }
                if (config.linkedConfigurations == null) {
                    config.linkedConfigurations = new HashMap<String, Integer>();
                }
                if (link.linkedConfigurations.get(config.configKey()) == null) {
                    link.linkedConfigurations.put(config.configKey(), Integer.valueOf(1));
                }
                if (config.linkedConfigurations.get(link.configKey()) == null) {
                    config.linkedConfigurations.put(link.configKey(), Integer.valueOf(1));
                }
            } else {
                if (link.linkedConfigurations != null
                        && (link.linkedConfigurations.get(config.configKey()) != null)) {
                    if (VDBG) {
                        loge("linkConfiguration: un-link " + config.configKey()
                                + " from " + link.configKey());
                    }
                    link.linkedConfigurations.remove(config.configKey());
                }
                if (config.linkedConfigurations != null
                        && (config.linkedConfigurations.get(link.configKey()) != null)) {
                    if (VDBG) {
                        loge("linkConfiguration: un-link " + link.configKey()
                                + " from " + config.configKey());
                    }
                    config.linkedConfigurations.remove(link.configKey());
                }
            }
        }
    }

    public HashSet<Integer> makeChannelList(WifiConfiguration config, int age, boolean restrict) {
        if (config == null)
            return null;
        long now_ms = System.currentTimeMillis();

        HashSet<Integer> channels = new HashSet<Integer>();

        //get channels for this configuration, if there are at least 2 BSSIDs
        if (getScanDetailCache(config) == null && config.linkedConfigurations == null) {
            return null;
        }

        if (VDBG) {
            StringBuilder dbg = new StringBuilder();
            dbg.append("makeChannelList age=" + Integer.toString(age)
                    + " for " + config.configKey()
                    + " max=" + maxNumActiveChannelsForPartialScans);
            if (getScanDetailCache(config) != null) {
                dbg.append(" bssids=" + getScanDetailCache(config).size());
            }
            if (config.linkedConfigurations != null) {
                dbg.append(" linked=" + config.linkedConfigurations.size());
            }
            loge(dbg.toString());
        }

        int numChannels = 0;
        if (getScanDetailCache(config) != null && getScanDetailCache(config).size() > 0) {
            for (ScanDetail scanDetail : getScanDetailCache(config).values()) {
                ScanResult result = scanDetail.getScanResult();
                //TODO : cout active and passive channels separately
                if (numChannels > maxNumActiveChannelsForPartialScans.get()) {
                    break;
                }
                if (VDBG) {
                    boolean test = (now_ms - result.seen) < age;
                    loge("has " + result.BSSID + " freq=" + Integer.toString(result.frequency)
                            + " age=" + Long.toString(now_ms - result.seen) + " ?=" + test);
                }
                if (((now_ms - result.seen) < age)/*||(!restrict || result.is24GHz())*/) {
                    channels.add(result.frequency);
                    numChannels++;
                }
            }
        }

        //get channels for linked configurations
        if (config.linkedConfigurations != null) {
            for (String key : config.linkedConfigurations.keySet()) {
                WifiConfiguration linked = getWifiConfiguration(key);
                if (linked == null)
                    continue;
                if (getScanDetailCache(linked) == null) {
                    continue;
                }
                for (ScanDetail scanDetail : getScanDetailCache(linked).values()) {
                    ScanResult result = scanDetail.getScanResult();
                    if (VDBG) {
                        loge("has link: " + result.BSSID
                                + " freq=" + Integer.toString(result.frequency)
                                + " age=" + Long.toString(now_ms - result.seen));
                    }
                    if (numChannels > maxNumActiveChannelsForPartialScans.get()) {
                        break;
                    }
                    if (((now_ms - result.seen) < age)/*||(!restrict || result.is24GHz())*/) {
                        channels.add(result.frequency);
                        numChannels++;
                    }
                }
            }
        }
        return channels;
    }

    private Map<HomeSP, PasspointMatch> matchPasspointNetworks(ScanDetail scanDetail) {
        if (!mMOManager.isConfigured()) {
            if (mEnableOsuQueries) {
                NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                List<Constants.ANQPElementType> querySet =
                        ANQPFactory.buildQueryList(networkDetail, false, true);

                if (networkDetail.queriable(querySet)) {
                    querySet = mAnqpCache.initiate(networkDetail, querySet);
                    if (querySet != null) {
                        mSupplicantBridge.startANQP(scanDetail, querySet);
                    }
                    updateAnqpCache(scanDetail, networkDetail.getANQPElements());
                }
            }
            return null;
        }
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        if (!networkDetail.hasInterworking()) {
            return null;
        }
        updateAnqpCache(scanDetail, networkDetail.getANQPElements());

        Map<HomeSP, PasspointMatch> matches = matchNetwork(scanDetail, true);
        Log.d(Utils.hs2LogTag(getClass()), scanDetail.getSSID() +
                " pass 1 matches: " + toMatchString(matches));
        return matches;
    }

    private Map<HomeSP, PasspointMatch> matchNetwork(ScanDetail scanDetail, boolean query) {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();

        ANQPData anqpData = mAnqpCache.getEntry(networkDetail);

        Map<Constants.ANQPElementType, ANQPElement> anqpElements =
                anqpData != null ? anqpData.getANQPElements() : null;

        boolean queried = !query;
        Collection<HomeSP> homeSPs = mMOManager.getLoadedSPs().values();
        Map<HomeSP, PasspointMatch> matches = new HashMap<>(homeSPs.size());
        Log.d(Utils.hs2LogTag(getClass()), "match nwk " + scanDetail.toKeyString() +
                ", anqp " + ( anqpData != null ? "present" : "missing" ) +
                ", query " + query + ", home sps: " + homeSPs.size());

        for (HomeSP homeSP : homeSPs) {
            PasspointMatch match = homeSP.match(networkDetail, anqpElements, mSIMAccessor);

            Log.d(Utils.hs2LogTag(getClass()), " -- " +
                    homeSP.getFQDN() + ": match " + match + ", queried " + queried);

            if ((match == PasspointMatch.Incomplete || mEnableOsuQueries) && !queried) {
                boolean matchSet = match == PasspointMatch.Incomplete;
                boolean osu = mEnableOsuQueries;
                List<Constants.ANQPElementType> querySet =
                        ANQPFactory.buildQueryList(networkDetail, matchSet, osu);
                if (networkDetail.queriable(querySet)) {
                    querySet = mAnqpCache.initiate(networkDetail, querySet);
                    if (querySet != null) {
                        mSupplicantBridge.startANQP(scanDetail, querySet);
                    }
                }
                queried = true;
            }
            matches.put(homeSP, match);
        }
        return matches;
    }

    public Map<Constants.ANQPElementType, ANQPElement> getANQPData(NetworkDetail network) {
        ANQPData data = mAnqpCache.getEntry(network);
        return data != null ? data.getANQPElements() : null;
    }

    public SIMAccessor getSIMAccessor() {
        return mSIMAccessor;
    }

    public void notifyANQPDone(Long bssid, boolean success) {
        mSupplicantBridge.notifyANQPDone(bssid, success);
    }

    public void notifyIconReceived(IconEvent iconEvent) {
        Intent intent = new Intent(WifiManager.PASSPOINT_ICON_RECEIVED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_PASSPOINT_ICON_BSSID, iconEvent.getBSSID());
        intent.putExtra(WifiManager.EXTRA_PASSPOINT_ICON_FILE, iconEvent.getFileName());
        try {
            intent.putExtra(WifiManager.EXTRA_PASSPOINT_ICON_DATA,
                    mSupplicantBridge.retrieveIcon(iconEvent));
        } catch (IOException ioe) {
            /* Simply omit the icon data as a failure indication */
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);

    }

    public void wnmFrameReceived(WnmData event) {
        // %012x HS20-SUBSCRIPTION-REMEDIATION "%u %s", osu_method, url
        // %012x HS20-DEAUTH-IMMINENT-NOTICE "%u %u %s", code, reauth_delay, url

        Intent intent = new Intent(WifiManager.PASSPOINT_WNM_FRAME_RECEIVED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        intent.putExtra(WifiManager.EXTRA_PASSPOINT_WNM_BSSID, event.getBssid());
        intent.putExtra(WifiManager.EXTRA_PASSPOINT_WNM_URL, event.getUrl());

        if (event.isDeauthEvent()) {
            intent.putExtra(WifiManager.EXTRA_PASSPOINT_WNM_ESS, event.isEss());
            intent.putExtra(WifiManager.EXTRA_PASSPOINT_WNM_DELAY, event.getDelay());
        } else {
            intent.putExtra(WifiManager.EXTRA_PASSPOINT_WNM_METHOD, event.getMethod());
            WifiConfiguration config = mWifiStateMachine.getCurrentWifiConfiguration();
            if (config != null && config.FQDN != null) {
                intent.putExtra(WifiManager.EXTRA_PASSPOINT_WNM_PPOINT_MATCH,
                        matchProviderWithCurrentNetwork(config.FQDN));
            }
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void updateAnqpCache(ScanDetail scanDetail,
                                 Map<Constants.ANQPElementType,ANQPElement> anqpElements)
    {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();

        if (anqpElements == null) {
            // Try to pull cached data if query failed.
            ANQPData data = mAnqpCache.getEntry(networkDetail);
            if (data != null) {
                scanDetail.propagateANQPInfo(data.getANQPElements());
            }
            return;
        }

        mAnqpCache.update(networkDetail, anqpElements);
    }

    private static String toMatchString(Map<HomeSP, PasspointMatch> matches) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<HomeSP, PasspointMatch> entry : matches.entrySet()) {
            sb.append(' ').append(entry.getKey().getFQDN()).append("->").append(entry.getValue());
        }
        return sb.toString();
    }

    private void cacheScanResultForPasspointConfigs(ScanDetail scanDetail,
            Map<HomeSP, PasspointMatch> matches,
            List<WifiConfiguration> associatedWifiConfigurations) {

        for (Map.Entry<HomeSP, PasspointMatch> entry : matches.entrySet()) {
            PasspointMatch match = entry.getValue();
            if (match == PasspointMatch.HomeProvider || match == PasspointMatch.RoamingProvider) {
                WifiConfiguration config = getWifiConfigForHomeSP(entry.getKey());
                if (config != null) {
                    cacheScanResultForConfig(config, scanDetail, entry.getValue());
                    if (associatedWifiConfigurations != null) {
                        associatedWifiConfigurations.add(config);
                    }
                } else {
		            Log.w(Utils.hs2LogTag(getClass()), "Failed to find config for '" +
                            entry.getKey().getFQDN() + "'");
                    /* perhaps the configuration was deleted?? */
                }
            }
        }
    }

    private void cacheScanResultForConfig(
            WifiConfiguration config, ScanDetail scanDetail, PasspointMatch passpointMatch) {

        ScanResult scanResult = scanDetail.getScanResult();

        ScanDetailCache scanDetailCache = getScanDetailCache(config);
        if (scanDetailCache == null) {
            Log.w(TAG, "Could not allocate scan cache for " + config.SSID);
            return;
        }

        // Adding a new BSSID
        ScanResult result = scanDetailCache.get(scanResult.BSSID);
        if (result != null) {
            // transfer the black list status
            scanResult.blackListTimestamp = result.blackListTimestamp;
            scanResult.numIpConfigFailures = result.numIpConfigFailures;
            scanResult.numConnection = result.numConnection;
            scanResult.isAutoJoinCandidate = result.isAutoJoinCandidate;
        }

        if (config.ephemeral) {
            // For an ephemeral Wi-Fi config, the ScanResult should be considered
            // untrusted.
            scanResult.untrusted = true;
        }

        if (scanDetailCache.size() > (maxNumScanCacheEntries + 64)) {
            long now_dbg = 0;
            if (VVDBG) {
                loge(" Will trim config " + config.configKey()
                        + " size " + scanDetailCache.size());

                for (ScanDetail sd : scanDetailCache.values()) {
                    loge("     " + sd.getBSSIDString() + " " + sd.getSeen());
                }
                now_dbg = SystemClock.elapsedRealtimeNanos();
            }
            // Trim the scan result cache to maxNumScanCacheEntries entries max
            // Since this operation is expensive, make sure it is not performed
            // until the cache has grown significantly above the trim treshold
            scanDetailCache.trim(maxNumScanCacheEntries);
            if (VVDBG) {
                long diff = SystemClock.elapsedRealtimeNanos() - now_dbg;
                loge(" Finished trimming config, time(ns) " + diff);
                for (ScanDetail sd : scanDetailCache.values()) {
                    loge("     " + sd.getBSSIDString() + " " + sd.getSeen());
                }
            }
        }

        // Add the scan result to this WifiConfiguration
        if (passpointMatch != null)
            scanDetailCache.put(scanDetail, passpointMatch, getHomeSPForConfig(config));
        else
            scanDetailCache.put(scanDetail);

        // Since we added a scan result to this configuration, re-attempt linking
        linkConfiguration(config);
    }

    private boolean isEncryptionWep(String encryption) {
        return encryption.contains("WEP");
    }

    private boolean isEncryptionPsk(String encryption) {
        return encryption.contains("PSK");
    }

    private boolean isEncryptionEap(String encryption) {
        return encryption.contains("EAP");
    }

    public boolean isOpenNetwork(String encryption) {
        if (!isEncryptionWep(encryption) && !isEncryptionPsk(encryption)
                && !isEncryptionEap(encryption)) {
            return true;
        }
        return false;
    }

    public boolean isOpenNetwork(ScanResult scan) {
        String scanResultEncrypt = scan.capabilities;
        return isOpenNetwork(scanResultEncrypt);
    }

    public boolean isOpenNetwork(WifiConfiguration config) {
        String configEncrypt = config.configKey();
        return isOpenNetwork(configEncrypt);
    }

    /**
     * create a mapping between the scandetail and the Wificonfiguration it associated with
     * because Passpoint, one BSSID can associated with multiple SSIDs
     * @param scanDetail input a scanDetail from the scan result
     * @return List<WifiConfiguration> a list of WifiConfigurations associated to this scanDetail
     */
    public List<WifiConfiguration> updateSavedNetworkWithNewScanDetail(ScanDetail scanDetail) {

        ScanResult scanResult = scanDetail.getScanResult();
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        List<WifiConfiguration> associatedWifiConfigurations = new ArrayList<WifiConfiguration>();

        if (scanResult == null)
            return null;

        String SSID = "\"" + scanResult.SSID + "\"";

        if (networkDetail.hasInterworking()) {
            Map<HomeSP, PasspointMatch> matches = matchPasspointNetworks(scanDetail);
            if (matches != null) {
                cacheScanResultForPasspointConfigs(scanDetail, matches,
                        associatedWifiConfigurations);
                //Do not return here. A BSSID can belong to both passpoint network and non-passpoint
                //Network
            }
        }

        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            boolean found = false;
            if (config.SSID == null || !config.SSID.equals(SSID)) {
                continue;
            }
            if (DBG) {
                localLog("updateSavedNetworkWithNewScanDetail(): try " + config.configKey()
                        + " SSID=" + config.SSID + " " + scanResult.SSID + " "
                        + scanResult.capabilities);
            }

            String scanResultEncrypt = scanResult.capabilities;
            String configEncrypt = config.configKey();
            if (isEncryptionWep(scanResultEncrypt) && isEncryptionWep(configEncrypt)
                    || (isEncryptionPsk(scanResultEncrypt) && isEncryptionPsk(configEncrypt))
                    || (isEncryptionEap(scanResultEncrypt) && isEncryptionEap(configEncrypt))
                    || (isOpenNetwork(scanResultEncrypt) && isOpenNetwork(configEncrypt))) {
                found = true;
            }

            if (found) {
                cacheScanResultForConfig(config, scanDetail, null);
                associatedWifiConfigurations.add(config);
            }
        }

        if (associatedWifiConfigurations.size() == 0) {
            return null;
        } else {
            return associatedWifiConfigurations;
        }
    }

    /**
     * Handles the switch to a different foreground user:
     * - Removes all ephemeral networks
     * - Disables private network configurations belonging to the previous foreground user
     * - Enables private network configurations belonging to the new foreground user
     *
     * TODO(b/26785736): Terminate background users if the new foreground user has one or more
     * private network configurations.
     */
    public void handleUserSwitch() {
        Set<WifiConfiguration> ephemeralConfigs = new HashSet<>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            if (config.ephemeral) {
                ephemeralConfigs.add(config);
            }
        }
        if (!ephemeralConfigs.isEmpty()) {
            for (WifiConfiguration config : ephemeralConfigs) {
                removeConfigWithoutBroadcast(config);
            }
            saveConfig();
            writeKnownNetworkHistory();
        }

        final List<WifiConfiguration> hiddenConfigurations =
                mConfiguredNetworks.handleUserSwitch(mWifiStateMachine.getCurrentUserId());
        for (WifiConfiguration network : hiddenConfigurations) {
            disableNetworkNative(network);
        }
        enableAllNetworks();

        // TODO(b/26785746): This broadcast is unnecessary if either of the following is true:
        // * The user switch did not change the list of visible networks
        // * The user switch revealed additional networks that were temporarily disabled and got
        //   re-enabled now (because enableAllNetworks() sent the same broadcast already).
        sendConfiguredNetworksChangedBroadcast();
    }

    /* Compare current and new configuration and write to file on change */
    private NetworkUpdateResult writeIpAndProxyConfigurationsOnChange(
            WifiConfiguration currentConfig,
            WifiConfiguration newConfig) {
        boolean ipChanged = false;
        boolean proxyChanged = false;

        if (VDBG) {
            loge("writeIpAndProxyConfigurationsOnChange: " + currentConfig.SSID + " -> " +
                    newConfig.SSID + " path: " + ipConfigFile);
        }


        switch (newConfig.getIpAssignment()) {
            case STATIC:
                if (currentConfig.getIpAssignment() != newConfig.getIpAssignment()) {
                    ipChanged = true;
                } else {
                    ipChanged = !Objects.equals(
                            currentConfig.getStaticIpConfiguration(),
                            newConfig.getStaticIpConfiguration());
                }
                break;
            case DHCP:
                if (currentConfig.getIpAssignment() != newConfig.getIpAssignment()) {
                    ipChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                loge("Ignore invalid ip assignment during write");
                break;
        }

        switch (newConfig.getProxySettings()) {
            case STATIC:
            case PAC:
                ProxyInfo newHttpProxy = newConfig.getHttpProxy();
                ProxyInfo currentHttpProxy = currentConfig.getHttpProxy();

                if (newHttpProxy != null) {
                    proxyChanged = !newHttpProxy.equals(currentHttpProxy);
                } else {
                    proxyChanged = (currentHttpProxy != null);
                }
                break;
            case NONE:
                if (currentConfig.getProxySettings() != newConfig.getProxySettings()) {
                    proxyChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                loge("Ignore invalid proxy configuration during write");
                break;
        }

        if (ipChanged) {
            currentConfig.setIpAssignment(newConfig.getIpAssignment());
            currentConfig.setStaticIpConfiguration(newConfig.getStaticIpConfiguration());
            log("IP config changed SSID = " + currentConfig.SSID);
            if (currentConfig.getStaticIpConfiguration() != null) {
                log(" static configuration: " +
                    currentConfig.getStaticIpConfiguration().toString());
            }
        }

        if (proxyChanged) {
            currentConfig.setProxySettings(newConfig.getProxySettings());
            currentConfig.setHttpProxy(newConfig.getHttpProxy());
            log("proxy changed SSID = " + currentConfig.SSID);
            if (currentConfig.getHttpProxy() != null) {
                log(" proxyProperties: " + currentConfig.getHttpProxy().toString());
            }
        }

        if (ipChanged || proxyChanged) {
            writeIpAndProxyConfigurations();
            sendConfiguredNetworksChangedBroadcast(currentConfig,
                    WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        }
        return new NetworkUpdateResult(ipChanged, proxyChanged);
    }

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    private void readNetworkVariables(WifiConfiguration config) {
        mWifiConfigStore.readNetworkVariables(config);
    }

    /* return the allowed key management based on a scan result */

    public WifiConfiguration wifiConfigurationFromScanResult(ScanResult result) {

        WifiConfiguration config = new WifiConfiguration();

        config.SSID = "\"" + result.SSID + "\"";

        if (VDBG) {
            loge("WifiConfiguration from scan results " +
                    config.SSID + " cap " + result.capabilities);
        }
        if (result.capabilities.contains("WEP")) {
            config.allowedKeyManagement.set(KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN); //?
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        }

        if (result.capabilities.contains("PSK")) {
            config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        }

        if (result.capabilities.contains("EAP")) {
            //this is probably wrong, as we don't have a way to enter the enterprise config
            config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
            config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        }

        /* getScanDetailCache(config).put(scanDetail); */

        return config;
    }

    public WifiConfiguration wifiConfigurationFromScanResult(ScanDetail scanDetail) {
        ScanResult result = scanDetail.getScanResult();
        return wifiConfigurationFromScanResult(result);
    }

    /* Returns a unique for a given configuration */
    private static int configKey(WifiConfiguration config) {
        String key = config.configKey();
        return key.hashCode();
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConfigManager");
        pw.println("mLastPriority " + mLastPriority);
        pw.println("Configured networks");
        for (WifiConfiguration conf : getAllConfiguredNetworks()) {
            pw.println(conf);
        }
        pw.println();
        if (mLostConfigsDbg != null && mLostConfigsDbg.size() > 0) {
            pw.println("LostConfigs: ");
            for (String s : mLostConfigsDbg) {
                pw.println(s);
            }
        }
        if (mLocalLog != null) {
            pw.println("WifiConfigManager - Log Begin ----");
            mLocalLog.dump(fd, pw, args);
            pw.println("WifiConfigManager - Log End ----");
        }
        if (mMOManager.isConfigured()) {
            pw.println("Begin dump of ANQP Cache");
            mAnqpCache.dump(pw);
            pw.println("End dump of ANQP Cache");
        }
    }

    public String getConfigFile() {
        return ipConfigFile;
    }

    protected void logd(String s) {
        Log.d(TAG, s);
    }

    protected void loge(String s) {
        loge(s, false);
    }

    protected void loge(String s, boolean stack) {
        if (stack) {
            Log.e(TAG, s + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
        } else {
            Log.e(TAG, s);
        }
    }

    private void logKernelTime() {
        long kernelTimeMs = System.nanoTime()/(1000*1000);
        StringBuilder builder = new StringBuilder();
        builder.append("kernel time = ").append(kernelTimeMs/1000).append(".").append
                (kernelTimeMs%1000).append("\n");
        localLog(builder.toString());
    }

    protected void log(String s) {
        Log.d(TAG, s);
    }

    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(s);
        }
    }

    private void localLogAndLogcat(String s) {
        localLog(s);
        Log.d(TAG, s);
    }

    private void localLogNetwork(String s, int netId) {
        if (mLocalLog == null) {
            return;
        }

        WifiConfiguration config;
        synchronized(mConfiguredNetworks) {             // !!! Useless synchronization
            config = mConfiguredNetworks.getForAllUsers(netId);
        }

        if (config != null) {
            mLocalLog.log(s + " " + config.getPrintableSsid() + " " + netId
                    + " status=" + config.status
                    + " key=" + config.configKey());
        } else {
            mLocalLog.log(s + " " + netId);
        }
    }

    static boolean needsSoftwareBackedKeyStore(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        if (!TextUtils.isEmpty(client)) {
            // a valid client certificate is configured

            // BUGBUG: keyStore.get() never returns certBytes; because it is not
            // taking WIFI_UID as a parameter. It always looks for certificate
            // with SYSTEM_UID, and never finds any Wifi certificates. Assuming that
            // all certificates need software keystore until we get the get() API
            // fixed.

            return true;
        }

        /*
        try {

            if (DBG) Slog.d(TAG, "Loading client certificate " + Credentials
                    .USER_CERTIFICATE + client);

            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            if (factory == null) {
                Slog.e(TAG, "Error getting certificate factory");
                return;
            }

            byte[] certBytes = keyStore.get(Credentials.USER_CERTIFICATE + client);
            if (certBytes != null) {
                Certificate cert = (X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(certBytes));

                if (cert != null) {
                    mNeedsSoftwareKeystore = hasHardwareBackedKey(cert);

                    if (DBG) Slog.d(TAG, "Loaded client certificate " + Credentials
                            .USER_CERTIFICATE + client);
                    if (DBG) Slog.d(TAG, "It " + (mNeedsSoftwareKeystore ? "needs" :
                            "does not need" ) + " software key store");
                } else {
                    Slog.d(TAG, "could not generate certificate");
                }
            } else {
                Slog.e(TAG, "Could not load client certificate " + Credentials
                        .USER_CERTIFICATE + client);
                mNeedsSoftwareKeystore = true;
            }

        } catch(CertificateException e) {
            Slog.e(TAG, "Could not read certificates");
            mCaCert = null;
            mClientCertificate = null;
        }
        */

        return false;
    }

    /**
     * Checks if the network is a sim config.
     * @param config Config corresponding to the network.
     * @return true if it is a sim config, false otherwise.
     */
    public boolean isSimConfig(WifiConfiguration config) {
        return mWifiConfigStore.isSimConfig(config);
    }

    /**
     * Resets all sim networks from the network list.
     */
    public void resetSimNetworks() {
        mWifiConfigStore.resetSimNetworks(mConfiguredNetworks.valuesForCurrentUser());
    }

    boolean isNetworkConfigured(WifiConfiguration config) {
        // Check if either we have a network Id or a WifiConfiguration
        // matching the one we are trying to add.

        if(config.networkId != INVALID_NETWORK_ID) {
            return (mConfiguredNetworks.getForCurrentUser(config.networkId) != null);
        }

        return (mConfiguredNetworks.getByConfigKeyForCurrentUser(config.configKey()) != null);
    }

    /**
     * Checks if uid has access to modify the configuration corresponding to networkId.
     *
     * The conditions checked are, in descending priority order:
     * - Disallow modification if the the configuration is not visible to the uid.
     * - Allow modification if the uid represents the Device Owner app.
     * - Allow modification if both of the following are true:
     *   - The uid represents the configuration's creator or an app holding OVERRIDE_CONFIG_WIFI.
     *   - The modification is only for administrative annotation (e.g. when connecting) or the
     *     configuration is not lockdown eligible (which currently means that it was not last
     *     updated by the DO).
     * - Allow modification if configuration lockdown is explicitly disabled and the uid represents
     *   an app holding OVERRIDE_CONFIG_WIFI.
     * - In all other cases, disallow modification.
     */
    boolean canModifyNetwork(int uid, int networkId, boolean onlyAnnotate) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(networkId);

        if (config == null) {
            loge("canModifyNetwork: cannot find config networkId " + networkId);
            return false;
        }

        final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                DevicePolicyManagerInternal.class);

        final boolean isUidDeviceOwner = dpmi != null && dpmi.isActiveAdminWithPolicy(uid,
                DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

        if (isUidDeviceOwner) {
            return true;
        }

        final boolean isCreator = (config.creatorUid == uid);

        if (onlyAnnotate) {
            return isCreator || checkConfigOverridePermission(uid);
        }

        // Check if device has DPM capability. If it has and dpmi is still null, then we
        // treat this case with suspicion and bail out.
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
                && dpmi == null) {
            return false;
        }

        // WiFi config lockdown related logic. At this point we know uid NOT to be a Device Owner.

        final boolean isConfigEligibleForLockdown = dpmi != null && dpmi.isActiveAdminWithPolicy(
                config.creatorUid, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        if (!isConfigEligibleForLockdown) {
            return isCreator || checkConfigOverridePermission(uid);
        }

        final ContentResolver resolver = mContext.getContentResolver();
        final boolean isLockdownFeatureEnabled = Settings.Global.getInt(resolver,
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0) != 0;
        return !isLockdownFeatureEnabled && checkConfigOverridePermission(uid);
    }

    /**
     * Checks if uid has access to modify config.
     */
    boolean canModifyNetwork(int uid, WifiConfiguration config, boolean onlyAnnotate) {
        if (config == null) {
            loge("canModifyNetowrk recieved null configuration");
            return false;
        }

        // Resolve the correct network id.
        int netid;
        if (config.networkId != INVALID_NETWORK_ID){
            netid = config.networkId;
        } else {
            WifiConfiguration test =
                    mConfiguredNetworks.getByConfigKeyForCurrentUser(config.configKey());
            if (test == null) {
                return false;
            } else {
                netid = test.networkId;
            }
        }

        return canModifyNetwork(uid, netid, onlyAnnotate);
    }

    boolean checkConfigOverridePermission(int uid) {
        try {
            return (mFacade.checkUidPermission(
                    android.Manifest.permission.OVERRIDE_WIFI_CONFIG, uid)
                    == PackageManager.PERMISSION_GRANTED);
        } catch (RemoteException e) {
            return false;
        }
    }

    /** called when CS ask WiFistateMachine to disconnect the current network
     * because the score is bad.
     */
    void handleBadNetworkDisconnectReport(int netId, WifiInfo info) {
        /* TODO verify the bad network is current */
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config != null) {
            if ((info.is24GHz() && info.getRssi()
                    <= WifiQualifiedNetworkSelector.QUALIFIED_RSSI_24G_BAND)
                    || (info.is5GHz() && info.getRssi()
                    <= WifiQualifiedNetworkSelector.QUALIFIED_RSSI_5G_BAND)) {
                // We do not block due to bad RSSI since network selection should not select bad
                // RSSI candidate
            } else {
                // We got disabled but RSSI is good, so disable hard
                updateNetworkSelectionStatus(config,
                        WifiConfiguration.NetworkSelectionStatus.DISABLED_BAD_LINK);
            }
        }
        // Record last time Connectivity Service switched us away from WiFi and onto Cell
        lastUnwantedNetworkDisconnectTimestamp = System.currentTimeMillis();
    }

    int getMaxDhcpRetries() {
        return mFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                DEFAULT_MAX_DHCP_RETRIES);
    }

    void clearBssidBlacklist() {
        if (!mWifiStateMachine.useHalBasedAutoJoinOffload()) {
            if(DBG) {
                Log.d(TAG, "No blacklist allowed without epno enabled");
            }
            return;
        }
        mWifiConfigStore.clearBssidBlacklist();
    }

    void blackListBssid(String bssid) {
        if (!mWifiStateMachine.useHalBasedAutoJoinOffload()) {
            if(DBG) {
                Log.d(TAG, "No blacklist allowed without epno enabled");
            }
            return;
        }
        mWifiConfigStore.blackListBssid(bssid);
    }

    public boolean isBssidBlacklisted(String bssid) {
        return mWifiConfigStore.isBssidBlacklisted(bssid);
    }

    public boolean getEnableNewNetworkSelectionWhenAssociated() {
        return enableAutoJoinWhenAssociated.get();
    }
}
