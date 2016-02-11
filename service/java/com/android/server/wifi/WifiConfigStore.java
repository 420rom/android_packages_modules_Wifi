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

import android.app.AppGlobals;
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
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.ArraySet;
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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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
 *     = IP and proxy configuration that is handled by WifiConfigStore and
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
public class WifiConfigStore extends IpConfigStore {

    private Context mContext;
    public static final String TAG = "WifiConfigStore";
    private static final boolean DBG = true;
    private static boolean VDBG = false;
    private static boolean VVDBG = false;

    private static final String SUPPLICANT_CONFIG_FILE = "/data/misc/wifi/wpa_supplicant.conf";
    private static final String SUPPLICANT_CONFIG_FILE_BACKUP = SUPPLICANT_CONFIG_FILE + ".tmp";
    private static final String PPS_FILE = "/data/misc/wifi/PerProviderSubscription.conf";

    /* configured networks with network id as the key */
    private final ConfigurationMap mConfiguredNetworks = new ConfigurationMap();

    /* A network id is a unique identifier for a network configured in the
     * supplicant. Network ids are generated when the supplicant reads
     * the configuration file at start and can thus change for networks.
     * We store the IP configuration for networks along with a unique id
     * that is generated from SSID and security type of the network. A mapping
     * from the generated unique id to network id of the network is needed to
     * map supplicant config to IP configuration. */

    /* Stores a map of NetworkId to ScanCache */
    private HashMap<Integer, ScanDetailCache> mScanDetailCaches;

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

    private static final String networkHistoryConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/networkHistory.txt";

    private static final String autoJoinConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/autojoinconfig.txt";

    /* Network History Keys */
    private static final String SSID_KEY = "SSID";
    static final String CONFIG_KEY = "CONFIG";
    private static final String CONFIG_BSSID_KEY = "CONFIG_BSSID";
    private static final String CHOICE_KEY = "CHOICE";
    private static final String CHOICE_TIME_KEY = "CHOICE_TIME";
    private static final String LINK_KEY = "LINK";
    private static final String BSSID_KEY = "BSSID";
    private static final String BSSID_KEY_END = "/BSSID";
    private static final String RSSI_KEY = "RSSI";
    private static final String FREQ_KEY = "FREQ";
    private static final String DATE_KEY = "DATE";
    private static final String MILLI_KEY = "MILLI";
    private static final String BLACKLIST_MILLI_KEY = "BLACKLIST_MILLI";
    private static final String NETWORK_ID_KEY = "ID";
    private static final String PRIORITY_KEY = "PRIORITY";
    private static final String DEFAULT_GW_KEY = "DEFAULT_GW";
    private static final String AUTH_KEY = "AUTH";
    private static final String BSSID_STATUS_KEY = "BSSID_STATUS";
    private static final String SELF_ADDED_KEY = "SELF_ADDED";
    private static final String FAILURE_KEY = "FAILURE";
    private static final String DID_SELF_ADD_KEY = "DID_SELF_ADD";
    private static final String PEER_CONFIGURATION_KEY = "PEER_CONFIGURATION";
    static final String CREATOR_UID_KEY = "CREATOR_UID_KEY";
    private static final String CONNECT_UID_KEY = "CONNECT_UID_KEY";
    private static final String UPDATE_UID_KEY = "UPDATE_UID";
    private static final String FQDN_KEY = "FQDN";
    private static final String SCORER_OVERRIDE_KEY = "SCORER_OVERRIDE";
    private static final String SCORER_OVERRIDE_AND_SWITCH_KEY = "SCORER_OVERRIDE_AND_SWITCH";
    private static final String VALIDATED_INTERNET_ACCESS_KEY = "VALIDATED_INTERNET_ACCESS";
    private static final String NO_INTERNET_ACCESS_REPORTS_KEY = "NO_INTERNET_ACCESS_REPORTS";
    private static final String EPHEMERAL_KEY = "EPHEMERAL";
    private static final String NUM_ASSOCIATION_KEY = "NUM_ASSOCIATION";
    private static final String DELETED_CRC32_KEY = "DELETED_CRC32";
    private static final String DELETED_EPHEMERAL_KEY = "DELETED_EPHEMERAL";
    private static final String CREATOR_NAME_KEY = "CREATOR_NAME";
    private static final String UPDATE_NAME_KEY = "UPDATE_NAME";
    private static final String USER_APPROVED_KEY = "USER_APPROVED";
    private static final String CREATION_TIME_KEY = "CREATION_TIME";
    private static final String UPDATE_TIME_KEY = "UPDATE_TIME";
    static final String SHARED_KEY = "SHARED";

    private static final String SEPARATOR = ":  ";
    private static final String NL = "\n";

    private static final String THRESHOLD_GOOD_RSSI_5_KEY
            = "THRESHOLD_GOOD_RSSI_5";
    private static final String THRESHOLD_LOW_RSSI_5_KEY
            = "THRESHOLD_LOW_RSSI_5";
    private static final String THRESHOLD_BAD_RSSI_5_KEY
            = "THRESHOLD_BAD_RSSI_5";
    private static final String THRESHOLD_GOOD_RSSI_24_KEY
            = "THRESHOLD_GOOD_RSSI_24";
    private static final String THRESHOLD_LOW_RSSI_24_KEY
            = "THRESHOLD_LOW_RSSI_24";
    private static final String THRESHOLD_BAD_RSSI_24_KEY
            = "THRESHOLD_BAD_RSSI_24";
    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS";

    private static final String MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY
            = "MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS";
    private static final String MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY
            = "MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS";

    private static final String A_BAND_PREFERENCE_RSSI_THRESHOLD_LOW_KEY =
            "A_BAND_PREFERENCE_RSSI_THRESHOLD_LOW";
    private static final String A_BAND_PREFERENCE_RSSI_THRESHOLD_KEY =
            "A_BAND_PREFERENCE_RSSI_THRESHOLD";
    private static final String G_BAND_PREFERENCE_RSSI_THRESHOLD_KEY =
            "G_BAND_PREFERENCE_RSSI_THRESHOLD";

    private static final String ENABLE_AUTOJOIN_WHILE_ASSOCIATED_KEY
            = "ENABLE_AUTOJOIN_WHILE_ASSOCIATED:   ";

    private static final String ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY
            = "ASSOCIATED_PARTIAL_SCAN_PERIOD";
    private static final String ASSOCIATED_FULL_SCAN_BACKOFF_KEY
            = "ASSOCIATED_FULL_SCAN_BACKOFF_PERIOD";
    private static final String ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED_KEY
            = "ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED";
    private static final String ONLY_LINK_SAME_CREDENTIAL_CONFIGURATIONS_KEY
            = "ONLY_LINK_SAME_CREDENTIAL_CONFIGURATIONS";

    private static final String ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED_KEY
            = "ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED";

    private static final String ENABLE_HAL_BASED_PNO
            = "ENABLE_HAL_BASED_PNO";

    // The three below configurations are mainly for power stats and CPU usage tracking
    // allowing to incrementally disable framework features
    private static final String ENABLE_AUTO_JOIN_WHILE_ASSOCIATED_KEY
            = "ENABLE_AUTO_JOIN_WHILE_ASSOCIATED";
    private static final String ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED_KEY
            = "ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED";
    private static final String ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY
            = "ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY";

    // This is the only variable whose contents will not be interpreted by wpa_supplicant. We use it
    // to store metadata that allows us to correlate a wpa_supplicant.conf entry with additional
    // information about the same network stored in other files. The metadata is stored as a
    // serialized JSON dictionary.
    public static final String ID_STRING_VAR_NAME = "id_str";
    public static final String ID_STRING_KEY_FQDN = "fqdn";
    public static final String ID_STRING_KEY_CREATOR_UID = "creatorUid";
    public static final String ID_STRING_KEY_CONFIG_KEY = "configKey";

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
    private static final Map<String, Object> sKeyMap = new HashMap<>();

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
    private final WpaConfigFileObserver mFileObserver;

    private WifiNative mWifiNative;
    private final KeyStore mKeyStore = KeyStore.getInstance();

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

    /**
     * Cached PNO list, it is updated when WifiConfiguration changes due to user input.
     */
    ArrayList<WifiNative.WifiPnoNetwork> mCachedPnoList
            = new ArrayList<WifiNative.WifiPnoNetwork>();

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
    private final PasspointManagementObjectManager mMOManager;
    private final boolean mEnableOsuQueries;
    private final SIMAccessor mSIMAccessor;

    private WifiStateMachine mWifiStateMachine;
    private FrameworkFacade mFacade;

    private class SupplicantSaver implements WifiEnterpriseConfig.SupplicantSaver {
        private final int mNetId;
        private final String mSetterSSID;

        SupplicantSaver(int netId, String setterSSID) {
            mNetId = netId;
            mSetterSSID = setterSSID;
        }

        @Override
        public boolean saveValue(String key, String value) {
            if (key.equals(WifiEnterpriseConfig.PASSWORD_KEY)
                    && value != null && value.equals("*")) {
                // No need to try to set an obfuscated password, which will fail
                return true;
            }
            if (key.equals(WifiEnterpriseConfig.REALM_KEY)
                    || key.equals(WifiEnterpriseConfig.PLMN_KEY)) {
                // No need to save realm or PLMN in supplicant
                return true;
            }
            // TODO: We need a way to clear values in wpa_supplicant as opposed to
            // mapping unset values to empty strings.
            if (value == null) {
                value = "\"\"";
            }
            if (!mWifiNative.setNetworkVariable(mNetId, key, value)) {
                loge(mSetterSSID + ": failed to set " + key + ": " + value);
                return false;
            }
            return true;
        }
    }

    private class SupplicantLoader implements WifiEnterpriseConfig.SupplicantLoader {
        private final int mNetId;

        SupplicantLoader(int netId) {
            mNetId = netId;
        }

        @Override
        public String loadValue(String key) {
            String value = mWifiNative.getNetworkVariable(mNetId, key);
            if (!TextUtils.isEmpty(value)) {
                if (!enterpriseConfigKeyShouldBeQuoted(key)) {
                    value = removeDoubleQuotes(value);
                }
                return value;
            } else {
                return null;
            }
        }
    }

    WifiConfigStore(Context c,  WifiStateMachine w, WifiNative wn, FrameworkFacade f) {
        mContext = c;
        mFacade = f;
        mWifiNative = wn;
        mWifiStateMachine = w;

        // A map for value setting in readAutoJoinConfig() - replacing the replicated code.
        sKeyMap.put(ENABLE_AUTO_JOIN_WHILE_ASSOCIATED_KEY, enableAutoJoinWhenAssociated);
        sKeyMap.put(ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED_KEY, enableFullBandScanWhenAssociated);
        sKeyMap.put(ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED_KEY, enableChipWakeUpWhenAssociated);
        sKeyMap.put(ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY, enableRssiPollWhenAssociated);
        sKeyMap.put(THRESHOLD_GOOD_RSSI_5_KEY, thresholdSaturatedRssi5);
        sKeyMap.put(THRESHOLD_LOW_RSSI_5_KEY, thresholdQualifiedRssi5);
        sKeyMap.put(THRESHOLD_BAD_RSSI_5_KEY, thresholdMinimumRssi5);
        sKeyMap.put(THRESHOLD_GOOD_RSSI_24_KEY, thresholdSaturatedRssi24);
        sKeyMap.put(THRESHOLD_LOW_RSSI_24_KEY, thresholdQualifiedRssi24);
        sKeyMap.put(THRESHOLD_BAD_RSSI_24_KEY, thresholdMinimumRssi24);
        sKeyMap.put(THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING_KEY,
                maxTxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING_KEY,
                maxRxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS_KEY, maxTxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS_KEY, maxRxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS_KEY, maxTxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS_KEY, maxRxPacketForNetworkSwitching);
        sKeyMap.put(WIFI_VERBOSE_LOGS_KEY, enableVerboseLogging);
        sKeyMap.put(ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY, wifiAssociatedShortScanIntervalMilli);
        sKeyMap.put(ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY, wifiAssociatedShortScanIntervalMilli);

        sKeyMap.put(ASSOCIATED_FULL_SCAN_BACKOFF_KEY, associatedFullScanBackoff);
        sKeyMap.put(ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED_KEY, alwaysEnableScansWhileAssociated);
        sKeyMap.put(MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY,
                maxNumPassiveChannelsForPartialScans);
        sKeyMap.put(MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY,
                maxNumActiveChannelsForPartialScans);
        sKeyMap.put(ENABLE_HAL_BASED_PNO, enableHalBasedPno);
        sKeyMap.put(ENABLE_HAL_BASED_PNO, enableSsidWhitelist);

        if (showNetworks) {
            mLocalLog = mWifiNative.getLocalLog();
            mFileObserver = new WpaConfigFileObserver();
            mFileObserver.startWatching();
        } else {
            mLocalLog = null;
            mFileObserver = null;
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

        mMOManager = new PasspointManagementObjectManager(new File(PPS_FILE), hs2on);
        mEnableOsuQueries = true;
        mAnqpCache = new AnqpCache();
        mSupplicantBridge = new SupplicantBridge(mWifiNative, this);
        mScanDetailCaches = new HashMap<>();

        mSIMAccessor = new SIMAccessor(mContext);
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

    class WpaConfigFileObserver extends FileObserver {

        public WpaConfigFileObserver() {
            super(SUPPLICANT_CONFIG_FILE, CLOSE_WRITE);
        }

        @Override
        public void onEvent(int event, String path) {
            if (event == CLOSE_WRITE) {
                File file = new File(SUPPLICANT_CONFIG_FILE);
                if (VDBG) localLog("wpa_supplicant.conf changed; new size = " + file.length());
            }
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
            mWifiNative.saveConfig();
            sendConfiguredNetworksChangedBroadcast();
        }
    }

    private boolean setNetworkPriorityNative(int netId, int priority) {
        return mWifiNative.setNetworkVariable(netId,
                WifiConfiguration.priorityVarName, Integer.toString(priority));
    }

    private boolean setSSIDNative(int netId, String ssid) {
        return mWifiNative.setNetworkVariable(netId, WifiConfiguration.ssidVarName,
                encodeSSID(ssid));
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
        if (!config.isVisibleToUser(mWifiStateMachine.getCurrentUserId())) {
            loge("selectNetwork " + Integer.toString(config.networkId) + ": Network config is not "
                    + "visible to current user.");
            return false;
        }

        // Reset the priority of each network at start or if it goes too high.
        if (mLastPriority == -1 || mLastPriority > 1000000) {
            for (WifiConfiguration config2 : mConfiguredNetworks.valuesForCurrentUser()) {
                if (updatePriorities) {
                    if (config2.networkId != INVALID_NETWORK_ID) {
                        config2.priority = 0;
                        setNetworkPriorityNative(config2.networkId, config.priority);
                    }
                }
            }
            mLastPriority = 0;
        }

        // Set to the highest priority and save the configuration.
        if (updatePriorities) {
            config.priority = ++mLastPriority;
            setNetworkPriorityNative(config.networkId, config.priority);
            buildPnoList();
        }

        if (config.isPasspoint()) {
            /* need to slap on the SSID of selected bssid to work */
            if (getScanDetailCache(config).size() != 0) {
                ScanDetail result = getScanDetailCache(config).getFirst();
                if (result == null) {
                    loge("Could not find scan result for " + config.BSSID);
                } else {
                    log("Setting SSID for " + config.networkId + " to" + result.getSSID());
                    setSSIDNative(config.networkId, result.getSSID());
                    config.SSID = result.getSSID();
                }

            } else {
                loge("Could not find bssid for " + config);
            }
        }

        mWifiNative.setHs20(config.isPasspoint());

        if (updatePriorities)
            mWifiNative.saveConfig();
        else
            mWifiNative.selectNetwork(config.networkId);

        updateLastConnectUid(config, uid);
        writeKnownNetworkHistory();

        /* Enable the given network while disabling all other networks */
        enableNetworkWithoutBroadcast(config.networkId, true);

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

        if (!config.isVisibleToUser(mWifiStateMachine.getCurrentUserId())) {
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        if (VDBG) localLogNetwork("WifiConfigStore: saveNetwork netId", config.networkId);
        if (VDBG) {
            logd("WifiConfigStore saveNetwork,"
                    + " size=" + Integer.toString(mConfiguredNetworks.sizeForAllUsers())
                    + " (for all users)"
                    + " SSID=" + config.SSID
                    + " Uid=" + Integer.toString(config.creatorUid)
                    + "/" + Integer.toString(config.lastUpdateUid));
        }

        if (mDeletedEphemeralSSIDs.remove(config.SSID)) {
            if (VDBG) {
                loge("WifiConfigStore: removed from ephemeral blacklist: " + config.SSID);
            }
            // NOTE: This will be flushed to disk as part of the addOrUpdateNetworkNative call
            // below, since we're creating/modifying a config.
        }

        boolean newNetwork = (config.networkId == INVALID_NETWORK_ID);
        NetworkUpdateResult result = addOrUpdateNetworkNative(config, uid);
        int netId = result.getNetworkId();

        if (VDBG) localLogNetwork("WifiConfigStore: saveNetwork got it back netId=", netId);

        /* enable a new network */
        if (newNetwork && netId != INVALID_NETWORK_ID) {
            if (VDBG) localLogNetwork("WifiConfigStore: will enable netId=", netId);

            mWifiNative.enableNetwork(netId, false);
            conf = mConfiguredNetworks.getForCurrentUser(netId);
            if (conf != null)
                conf.status = Status.ENABLED;
        }

        conf = mConfiguredNetworks.getForCurrentUser(netId);
        if (conf != null) {
            if (!conf.getNetworkSelectionStatus().isNetworkEnabled()) {
                if (VDBG) localLog("WifiConfigStore: re-enabling: " + conf.SSID);

                // reenable autojoin, since new information has been provided
                updateNetworkSelectionStatus(netId,
                        WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
                enableNetworkWithoutBroadcast(conf.networkId, false);
            }
            if (VDBG) {
                loge("WifiConfigStore: saveNetwork got config back netId="
                        + Integer.toString(netId)
                        + " uid=" + Integer.toString(config.creatorUid));
            }
        }

        mWifiNative.saveConfig();
        sendConfiguredNetworksChangedBroadcast(conf, result.isNewNetwork() ?
                WifiManager.CHANGE_REASON_ADDED : WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        return result;
    }

    /**
     * Firmware is roaming away from this BSSID, and this BSSID was on 5GHz, and it's RSSI was good,
     * this means we have a situation where we would want to remain on this BSSID but firmware
     * is not successful at it.
     * This situation is observed on a small number of Access Points, b/17960587
     * In that situation, blacklist this BSSID really hard so as framework will not attempt to
     * roam to it for the next 8 hours. We do not to keep flipping between 2.4 and 5GHz band..
     * TODO: review the blacklisting strategy so as to make it softer and adaptive
     * @param info
     */
    void driverRoamedFrom(WifiInfo info) {
        if (info != null && info.getBSSID() != null && ScanResult.is5GHz(info.getFrequency())
                && info.getRssi() > (thresholdSaturatedRssi5.get())) {
            WifiConfiguration config = getWifiConfiguration(info.getNetworkId());
            if (config != null) {
                if (getScanDetailCache(config) != null) {
                    ScanResult result = getScanDetailCache(config).get(info.getBSSID());
                    if (result != null) {
                        result.setAutoJoinStatus(ScanResult.AUTO_ROAM_DISABLED + 1);
                    }
                }
            }
        }
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

    void saveWifiConfigBSSID(WifiConfiguration config) {
        // Sanity check the config is valid
        if (config == null || (config.networkId == INVALID_NETWORK_ID &&
                config.SSID == null)) {
            return;
        }

        // If Network Selection specified a BSSID then write it in the network block
        WifiConfiguration.NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        String bssid = networkStatus.getNetworkSelectionBSSID();
        if (bssid != null) {
            localLog("saveWifiConfigBSSID Setting BSSID for " + config.configKey()
                    + " to " + bssid);
            if (!mWifiNative.setNetworkVariable(
                    config.networkId,
                    WifiConfiguration.bssidVarName,
                    bssid)) {
                loge("failed to set BSSID: " + bssid);
            } else if (bssid.equals("any")) {
                // Paranoia, we just want to make sure that we restore the config to normal
                mWifiNative.saveConfig();
            }
        }
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

        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);

        boolean remove = removeConfigAndSendBroadcastIfNeeded(netId);
        if (!remove) {
            //success but we dont want to remove the network from supplicant conf file
            return true;
        }
        if (mWifiNative.removeNetwork(netId)) {
            if (config != null && config.isPasspoint()) {
                writePasspointConfigs(config.FQDN, null);
            }
            mWifiNative.saveConfig();
            writeKnownNetworkHistory();
            return true;
        } else {
            loge("Failed to remove network " + netId);
            return false;
        }
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
        if (config == null || !config.isVisibleToUser(mWifiStateMachine.getCurrentUserId())) {
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
     * Get the Wifi PNO list
     *
     * @return list of WifiNative.WifiPnoNetwork
     */
    private void buildPnoList() {
        mCachedPnoList = new ArrayList<WifiNative.WifiPnoNetwork>();

        ArrayList<WifiConfiguration> sortedWifiConfigurations
                = new ArrayList<WifiConfiguration>(getConfiguredNetworks());
        Log.e(TAG, "buildPnoList sortedWifiConfigurations size " + sortedWifiConfigurations.size());
        if (sortedWifiConfigurations.size() != 0) {
            // Sort by descending priority
            Collections.sort(sortedWifiConfigurations, new Comparator<WifiConfiguration>() {
                public int compare(WifiConfiguration a, WifiConfiguration b) {
                    return a.priority - b.priority;
                }
            });
        }

        for (WifiConfiguration config : sortedWifiConfigurations) {
            // Initialize the RSSI threshold with sane value:
            // Use the 2.4GHz threshold since most WifiConfigurations are dual bands
            // There is very little penalty with triggering too soon, i.e. if PNO finds a network
            // that has an RSSI too low for us to attempt joining it.
            int threshold = thresholdMinimumRssi24.get();
            Log.e(TAG, "found sortedWifiConfigurations : " + config.configKey());
            WifiNative.WifiPnoNetwork network = new WifiNative.WifiPnoNetwork(config, threshold);
            mCachedPnoList.add(network);
        }
    }

    String[] getWhiteListedSsids(WifiConfiguration config) {
        int num_ssids = 0;
        String nonQuoteSSID;
        int length;
        if (enableSsidWhitelist.get() == false)
            return null;
        List<String> list = new ArrayList<String>();
        if (config == null)
            return null;
        if (config.linkedConfigurations == null) {
            return null;
        }
        if (config.SSID == null || TextUtils.isEmpty(config.SSID)) {
            return null;
        }
        for (String configKey : config.linkedConfigurations.keySet()) {
            // Sanity check that the linked configuration is still valid
            WifiConfiguration link = getWifiConfiguration(configKey);
            if (link == null) {
                continue;
            }

            if (!link.getNetworkSelectionStatus().isNetworkEnabled()) {
                continue;
            }

            if (link.hiddenSSID == true) {
                continue;
            }

            if (link.SSID == null || TextUtils.isEmpty(link.SSID)) {
                continue;
            }

            length = link.SSID.length();
            if (length > 2 && (link.SSID.charAt(0) == '"') && link.SSID.charAt(length - 1) == '"') {
                nonQuoteSSID = link.SSID.substring(1, length - 1);
            } else {
                nonQuoteSSID = link.SSID;
            }

            list.add(nonQuoteSSID);
        }

        if (list.size() != 0) {
            length = config.SSID.length();
            if (length > 2 && (config.SSID.charAt(0) == '"')
                    && config.SSID.charAt(length - 1) == '"') {
                nonQuoteSSID = config.SSID.substring(1, length - 1);
            } else {
                nonQuoteSSID = config.SSID;
            }

            list.add(nonQuoteSSID);
        }

        return (String[])list.toArray(new String[0]);
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
        if (config == null) {
            return false;
        }

        boolean ret = mWifiNative.removeNetwork(netId);
        if (ret) {
            removeConfigAndSendBroadcastIfNeeded(netId);
            if (config != null && config.isPasspoint()) {
                writePasspointConfigs(config.FQDN, null);
            }
        }
        return ret;
    }


    static private Long getChecksum(String source) {
        Checksum csum = new CRC32();
        csum.update(source.getBytes(), 0, source.getBytes().length);
        return csum.getValue();
    }

    private boolean removeConfigAndSendBroadcastIfNeeded(int netId) {
        WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config != null) {
            String key = config.configKey();
            if (VDBG) {
                loge("removeNetwork " + netId + " key=" + key + " config.id=" + config.networkId);
            }

            // cancel the last user choice
            if (key.equals(lastSelectedConfiguration)) {
                lastSelectedConfiguration = null;
            }

            // Remove any associated keys
            if (config.enterpriseConfig != null) {
                removeKeys(config.enterpriseConfig);
            }

            if (config.selfAdded || config.linkedConfigurations != null
                    || config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
                if (!TextUtils.isEmpty(config.SSID)) {
                    /* Remember that we deleted this PSK SSID */
                    if (config.SSID != null) {
                        Long csum = getChecksum(config.SSID);
                        mDeletedSSIDs.add(csum);
                        loge("removeNetwork " + netId
                                + " key=" + key
                                + " config.id=" + config.networkId
                                + "  crc=" + csum);
                    } else {
                        loge("removeNetwork " + netId
                                + " key=" + key
                                + " config.id=" + config.networkId);
                    }
                }
            }

            mConfiguredNetworks.remove(netId);
            mScanDetailCaches.remove(netId);

            writeIpAndProxyConfigurations();
            sendConfiguredNetworksChangedBroadcast(config, WifiManager.CHANGE_REASON_REMOVED);
            if (!config.ephemeral) {
                removeUserSelectionPreference(key);
            }
            writeKnownNetworkHistory();
        }
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

        mWifiNative.saveConfig();

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

        boolean ret = enableNetworkWithoutBroadcast(netId, disableOthers);
        if (disableOthers) {
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

    boolean enableNetworkWithoutBroadcast(int netId, boolean disableOthers) {
        final WifiConfiguration config = mConfiguredNetworks.getForCurrentUser(netId);
        if (config == null) {
            return false;
        }

        boolean ret = mWifiNative.enableNetwork(netId, disableOthers);

        config.status = Status.ENABLED;

        if (disableOthers) {
            markAllNetworksDisabledExcept(netId);
        }
        return ret;
    }

    void disableAllNetworks() {
        if (VDBG) localLog("disableAllNetworks");
        boolean networkDisabled = false;
        for (WifiConfiguration enabled : mConfiguredNetworks.getEnabledNetworksForCurrentUser()) {
            if(mWifiNative.disableNetwork(enabled.networkId)) {
                networkDisabled = true;
                enabled.status = Status.DISABLED;
            } else {
                loge("Disable network failed on " + enabled.networkId);
            }
        }

        if (networkDisabled) {
            sendConfiguredNetworksChangedBroadcast();
        }
    }
    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean disableNetwork(int netId) {
        boolean ret = mWifiNative.disableNetwork(netId);
        if (ret) {
            mWifiStateMachine.registerNetworkDisabled(netId);
        }
        return ret;
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
            //enable the network
            if (!mWifiNative.enableNetwork(config.networkId, false)) {
                localLog("fail to disable network: " + config.SSID + " With reason:"
                        + WifiConfiguration.NetworkSelectionStatus
                        .getNetworkDisableReasonString(reason));
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
                mWifiNative.disableNetwork(config.networkId);
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
        return mWifiNative.saveConfig();
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the access point
     * @param config WPS configuration
     * @return Wps result containing status and pin
     */
    WpsResult startWpsWithPinFromAccessPoint(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsRegistrar(config.BSSID, config.pin)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS pin method configuration with obtained
     * from the device
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsWithPinFromDevice(WpsInfo config) {
        WpsResult result = new WpsResult();
        result.pin = mWifiNative.startWpsPinDisplay(config.BSSID);
        /* WPS leaves all networks disabled */
        if (!TextUtils.isEmpty(result.pin)) {
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS push button configuration
     * @param config WPS configuration
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsPbc(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsPbc(config.BSSID)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS push button configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
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

        mLastPriority = 0;

        final Map<String, WifiConfiguration> configs = new HashMap<>();

        final SparseArray<Map<String, String>> networkExtras = new SparseArray<>();

        int last_id = -1;
        boolean done = false;
        while (!done) {

            String listStr = mWifiNative.listNetworks(last_id);
            if (listStr == null)
                return;

            String[] lines = listStr.split("\n");

            if (showNetworks) {
                localLog("WifiConfigStore: loadConfiguredNetworks:  ");
                for (String net : lines) {
                    localLog(net);
                }
            }

            // Skip the first line, which is a header
            for (int i = 1; i < lines.length; i++) {
                String[] result = lines[i].split("\t");
                // network-id | ssid | bssid | flags
                WifiConfiguration config = new WifiConfiguration();
                try {
                    config.networkId = Integer.parseInt(result[0]);
                    last_id = config.networkId;
                } catch(NumberFormatException e) {
                    loge("Failed to read network-id '" + result[0] + "'");
                    continue;
                }
                if (result.length > 3) {
                    if (result[3].indexOf("[CURRENT]") != -1)
                        config.status = WifiConfiguration.Status.CURRENT;
                    else if (result[3].indexOf("[DISABLED]") != -1)
                        config.status = WifiConfiguration.Status.DISABLED;
                    else
                        config.status = WifiConfiguration.Status.ENABLED;
                } else {
                    config.status = WifiConfiguration.Status.ENABLED;
                }

                readNetworkVariables(config);

                // Parse the serialized JSON dictionary in ID_STRING_VAR_NAME once and cache the
                // result for efficiency.
                Map<String, String> extras = mWifiNative.getNetworkExtra(config.networkId,
                        ID_STRING_VAR_NAME);
                if (extras == null) {
                    extras = new HashMap<String, String>();
                    // If ID_STRING_VAR_NAME did not contain a dictionary, assume that it contains
                    // just a quoted FQDN. This is the legacy format that was used in Marshmallow.
                    final String fqdn = Utils.unquote(mWifiNative.getNetworkVariable(
                            config.networkId, ID_STRING_VAR_NAME));
                    if (fqdn != null) {
                        extras.put(ID_STRING_KEY_FQDN, fqdn);
                        config.FQDN = fqdn;
                        // Mark the configuration as a Hotspot 2.0 network.
                        config.providerFriendlyName = "";
                    }
                }
                networkExtras.put(config.networkId, extras);

                Checksum csum = new CRC32();
                if (config.SSID != null) {
                    csum.update(config.SSID.getBytes(), 0, config.SSID.getBytes().length);
                    long d = csum.getValue();
                    if (mDeletedSSIDs.contains(d)) {
                        loge(" got CRC for SSID " + config.SSID + " -> " + d + ", was deleted");
                    }
                }

                if (config.priority > mLastPriority) {
                    mLastPriority = config.priority;
                }

                config.setIpAssignment(IpAssignment.DHCP);
                config.setProxySettings(ProxySettings.NONE);

                if (!WifiServiceImpl.isValid(config)) {
                    if (showNetworks) {
                        localLog("Ignoring network " + config.networkId + " because configuration "
                                + "loaded from wpa_supplicant.conf is not valid.");
                    }
                    continue;
                }

                // The configKey is explicitly stored in wpa_supplicant.conf, because config does
                // not contain sufficient information to compute it at this point.
                String configKey = extras.get(ID_STRING_KEY_CONFIG_KEY);
                if (configKey == null) {
                    // Handle the legacy case where the configKey is not stored in
                    // wpa_supplicant.conf but can be computed straight away.
                    configKey = config.configKey();
                }

                final WifiConfiguration duplicateConfig = configs.put(configKey, config);
                if (duplicateConfig != null) {
                    // The network is already known. Overwrite the duplicate entry.
                    if (showNetworks) {
                        localLog("Replacing duplicate network " + duplicateConfig.networkId
                                + " with " + config.networkId + ".");
                    }
                    // This can happen after the user manually connected to an AP and tried to use
                    // WPS to connect the AP later. In this case, the supplicant will create a new
                    // network for the AP although there is an existing network already.
                    mWifiNative.removeNetwork(duplicateConfig.networkId);
                }
            }

            done = (lines.length == 1);
        }

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
                mWifiNative.removeNetwork(config.networkId);
                continue;
            }
            mConfiguredNetworks.put(config);
        }

        readIpAndProxyConfigurations();
        readAutoJoinConfig();

        buildPnoList();

        sendConfiguredNetworksChangedBroadcast();

        if (showNetworks) {
            localLog("loadConfiguredNetworks loaded " + mConfiguredNetworks.sizeForAllUsers()
                    + " networks (for all users)");
        }

        if (mConfiguredNetworks.sizeForAllUsers() == 0) {
            // no networks? Lets log if the file contents
            logKernelTime();
            logContents(SUPPLICANT_CONFIG_FILE);
            logContents(SUPPLICANT_CONFIG_FILE_BACKUP);
            logContents(networkHistoryConfigFile);
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
        // TODO(b/26733972): This method assumes that the SSID is a unique identifier for network
        // configurations. That is wrong. There may be any number of networks with the same SSID.
        // There may also be any number of network configurations for the same network. The correct
        // unique identifier is the configKey. This method should be switched from SSID to configKey
        // (which is either stored in wpa_supplicant.conf directly or can be computed from the
        // information found in that file).
        Map<String, String> result = new HashMap<>();
        BufferedReader reader = null;
        if (VDBG) loge("readNetworkVariablesFromSupplicantFile key=" + key);

        try {
            reader = new BufferedReader(new FileReader(SUPPLICANT_CONFIG_FILE));
            boolean found = false;
            String networkSsid = null;
            String value = null;

            for (String line = reader.readLine(); line != null; line = reader.readLine()) {

                if (line.matches("[ \\t]*network=\\{")) {
                    found = true;
                    networkSsid = null;
                    value = null;
                } else if (line.matches("[ \\t]*\\}")) {
                    found = false;
                    networkSsid = null;
                    value = null;
                }

                if (found) {
                    String trimmedLine = line.trim();
                    if (trimmedLine.startsWith("ssid=")) {
                        networkSsid = trimmedLine.substring(5);
                    } else if (trimmedLine.startsWith(key + "=")) {
                        value = trimmedLine.substring(key.length() + 1);
                    }

                    if (networkSsid != null && value != null) {
                        result.put(networkSsid, value);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            if (VDBG) loge("Could not open " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } catch (IOException e) {
            if (VDBG) loge("Could not read " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Just ignore the fact that we couldn't close
            }
        }

        return result;
    }

    private String readNetworkVariableFromSupplicantFile(String ssid, String key) {
        long start = SystemClock.elapsedRealtimeNanos();
        Map<String, String> data = readNetworkVariablesFromSupplicantFile(key);
        long end = SystemClock.elapsedRealtimeNanos();

        if (VDBG) {
            loge("readNetworkVariableFromSupplicantFile ssid=[" + ssid + "] key=" + key
                    + " duration=" + (long)(end - start));
        }
        return data.get(ssid);
    }

    /* Mark all networks except specified netId as disabled */
    private void markAllNetworksDisabledExcept(int netId) {
        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            if(config != null && config.networkId != netId) {
                if (config.status != Status.DISABLED) {
                    config.status = Status.DISABLED;
                }
            }
        }
    }

    private void markAllNetworksDisabled() {
        markAllNetworksDisabledExcept(INVALID_NETWORK_ID);
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
                        networkExtras.get(config.networkId).get(ID_STRING_KEY_FQDN);
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

    public void writeKnownNetworkHistory() {

        /* Make a copy */
        final List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
            networks.add(new WifiConfiguration(config));
        }
        if (VDBG) {
            loge(" writeKnownNetworkHistory() num networks:"
                    + mConfiguredNetworks.valuesForCurrentUser());
        }

        mWriter.write(networkHistoryConfigFile, new DelayedDiskWrite.Writer() {
            public void onWriteCalled(DataOutputStream out) throws IOException {
                for (WifiConfiguration config : networks) {
                    //loge("onWriteCalled write SSID: " + config.SSID);
                   /* if (config.getLinkProperties() != null)
                        loge(" lp " + config.getLinkProperties().toString());
                    else
                        loge("attempt config w/o lp");
                    */
                    WifiConfiguration.NetworkSelectionStatus status =
                            config.getNetworkSelectionStatus();
                    if (VDBG) {
                        int numlink = 0;
                        if (config.linkedConfigurations != null) {
                            numlink = config.linkedConfigurations.size();
                        }
                        String disableTime;
                        if (config.getNetworkSelectionStatus().isNetworkEnabled()) {
                            disableTime = "";
                        } else {
                            disableTime = "Disable time: " + DateFormat.getInstance().format(
                                    config.getNetworkSelectionStatus().getDisableTime());
                        }
                        loge("saving network history: " + config.configKey()  + " gw: "
                                + config.defaultGwMacAddress + " Network Selection-status: "
                                + status.getNetworkStatusString()
                                + disableTime + " ephemeral=" + config.ephemeral
                                + " choice:" + status.getConnectChoice()
                                + " link:" + numlink
                                + " status:" + config.status
                                + " nid:" + config.networkId);
                    }

                    if (!WifiServiceImpl.isValid(config))
                        continue;

                    if (config.SSID == null) {
                        if (VDBG) {
                            loge("writeKnownNetworkHistory trying to write config with null SSID");
                        }
                        continue;
                    }
                    if (VDBG) {
                        loge("writeKnownNetworkHistory write config " + config.configKey());
                    }
                    out.writeUTF(CONFIG_KEY + SEPARATOR + config.configKey() + NL);

                    if (config.SSID != null) {
                        out.writeUTF(SSID_KEY + SEPARATOR + config.SSID + NL);
                    }
                    if (config.BSSID != null) {
                        out.writeUTF(CONFIG_BSSID_KEY + SEPARATOR + config.BSSID + NL);
                    } else {
                        out.writeUTF(CONFIG_BSSID_KEY + SEPARATOR + "null" + NL);
                    }
                    if (config.FQDN != null) {
                        out.writeUTF(FQDN_KEY + SEPARATOR + config.FQDN + NL);
                    }

                    out.writeUTF(PRIORITY_KEY + SEPARATOR +
                            Integer.toString(config.priority) + NL);
                    out.writeUTF(NETWORK_ID_KEY + SEPARATOR +
                            Integer.toString(config.networkId) + NL);
                    out.writeUTF(SELF_ADDED_KEY + SEPARATOR +
                            Boolean.toString(config.selfAdded) + NL);
                    out.writeUTF(DID_SELF_ADD_KEY + SEPARATOR +
                            Boolean.toString(config.didSelfAdd) + NL);
                    out.writeUTF(NO_INTERNET_ACCESS_REPORTS_KEY + SEPARATOR +
                            Integer.toString(config.numNoInternetAccessReports) + NL);
                    out.writeUTF(VALIDATED_INTERNET_ACCESS_KEY + SEPARATOR +
                            Boolean.toString(config.validatedInternetAccess) + NL);
                    out.writeUTF(EPHEMERAL_KEY + SEPARATOR +
                            Boolean.toString(config.ephemeral) + NL);
                    if (config.creationTime != null) {
                        out.writeUTF(CREATION_TIME_KEY + SEPARATOR + config.creationTime + NL);
                    }
                    if (config.updateTime != null) {
                        out.writeUTF(UPDATE_TIME_KEY + SEPARATOR + config.updateTime + NL);
                    }
                    if (config.peerWifiConfiguration != null) {
                        out.writeUTF(PEER_CONFIGURATION_KEY + SEPARATOR +
                                config.peerWifiConfiguration + NL);
                    }
                    out.writeUTF(SCORER_OVERRIDE_KEY + SEPARATOR +
                            Integer.toString(config.numScorerOverride) + NL);
                    out.writeUTF(SCORER_OVERRIDE_AND_SWITCH_KEY + SEPARATOR +
                            Integer.toString(config.numScorerOverrideAndSwitchedNetwork) + NL);
                    out.writeUTF(NUM_ASSOCIATION_KEY + SEPARATOR +
                            Integer.toString(config.numAssociation) + NL);
                    out.writeUTF(CREATOR_UID_KEY + SEPARATOR +
                            Integer.toString(config.creatorUid) + NL);
                    out.writeUTF(CONNECT_UID_KEY + SEPARATOR +
                            Integer.toString(config.lastConnectUid) + NL);
                    out.writeUTF(UPDATE_UID_KEY + SEPARATOR +
                            Integer.toString(config.lastUpdateUid) + NL);
                    out.writeUTF(CREATOR_NAME_KEY + SEPARATOR +
                            config.creatorName + NL);
                    out.writeUTF(UPDATE_NAME_KEY + SEPARATOR +
                            config.lastUpdateName + NL);
                    out.writeUTF(USER_APPROVED_KEY + SEPARATOR +
                            Integer.toString(config.userApproved) + NL);
                    out.writeUTF(SHARED_KEY + SEPARATOR + Boolean.toString(config.shared) + NL);
                    String allowedKeyManagementString =
                            makeString(config.allowedKeyManagement,
                                    WifiConfiguration.KeyMgmt.strings);
                    out.writeUTF(AUTH_KEY + SEPARATOR +
                            allowedKeyManagementString + NL);


                    if (status.getConnectChoice() != null) {
                        out.writeUTF(CHOICE_KEY + SEPARATOR + status.getConnectChoice() + NL);
                        out.writeUTF(CHOICE_TIME_KEY + SEPARATOR
                                + status.getConnectChoiceTimestamp() + NL);
                    }

                    if (config.linkedConfigurations != null) {
                        log("writeKnownNetworkHistory write linked "
                                + config.linkedConfigurations.size());

                        for (String key : config.linkedConfigurations.keySet()) {
                            out.writeUTF(LINK_KEY + SEPARATOR + key + NL);
                        }
                    }

                    String macAddress = config.defaultGwMacAddress;
                    if (macAddress != null) {
                        out.writeUTF(DEFAULT_GW_KEY + SEPARATOR + macAddress + NL);
                    }

                    if (getScanDetailCache(config) != null) {
                        for (ScanDetail scanDetail : getScanDetailCache(config).values()) {
                            ScanResult result = scanDetail.getScanResult();
                            out.writeUTF(BSSID_KEY + SEPARATOR +
                                    result.BSSID + NL);

                            out.writeUTF(FREQ_KEY + SEPARATOR +
                                    Integer.toString(result.frequency) + NL);

                            out.writeUTF(RSSI_KEY + SEPARATOR +
                                    Integer.toString(result.level) + NL);

                            out.writeUTF(BSSID_KEY_END + NL);
                        }
                    }
                    if (config.lastFailure != null) {
                        out.writeUTF(FAILURE_KEY + SEPARATOR + config.lastFailure + NL);
                    }
                    out.writeUTF(NL);
                    // Add extra blank lines for clarity
                    out.writeUTF(NL);
                    out.writeUTF(NL);
                }
                if (mDeletedSSIDs != null && mDeletedSSIDs.size() > 0) {
                    for (Long i : mDeletedSSIDs) {
                        out.writeUTF(DELETED_CRC32_KEY);
                        out.writeUTF(String.valueOf(i));
                        out.writeUTF(NL);
                    }
                }
                if (mDeletedEphemeralSSIDs != null && mDeletedEphemeralSSIDs.size() > 0) {
                    for (String ssid : mDeletedEphemeralSSIDs) {
                        out.writeUTF(DELETED_EPHEMERAL_KEY);
                        out.writeUTF(ssid);
                        out.writeUTF(NL);
                    }
                }
            }
        });
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
                if (selected.status == Status.DISABLED) {
                    mWifiNative.enableNetwork(netId, false);
                }
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

    /**
     * Adds information stored in networkHistory.txt to the given configs. The configs are provided
     * as a mapping from configKey to WifiConfiguration, because the WifiConfigurations themselves
     * do not contain sufficient information to compute their configKeys until after the information
     * that is stored in networkHistory.txt has been added to them.
     *
     * @param configs mapping from configKey to a WifiConfiguration that contains the information
     *         information read from wpa_supplicant.conf
     */
    private void readNetworkHistory(Map<String, WifiConfiguration> configs) {
        if (showNetworks) {
            localLog("readNetworkHistory() path:" + networkHistoryConfigFile);
        }

        try (DataInputStream in =
                     new DataInputStream(new BufferedInputStream(
                             new FileInputStream(networkHistoryConfigFile)))) {

            String bssid = null;
            String ssid = null;

            int freq = 0;
            int status = 0;
            long seen = 0;
            int rssi = WifiConfiguration.INVALID_RSSI;
            String caps = null;

            WifiConfiguration config = null;
            while (true) {
                String line = in.readUTF();
                if (line == null) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }

                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();

                if (key.equals(CONFIG_KEY)) {
                    config = configs.get(value);

                    // skip reading that configuration data
                    // since we don't have a corresponding network ID
                    if (config == null) {
                        localLog("readNetworkHistory didnt find netid for hash="
                                + Integer.toString(value.hashCode())
                                + " key: " + value);
                        mLostConfigsDbg.add(value);
                        continue;
                    } else {
                        // After an upgrade count old connections as owned by system
                        if (config.creatorName == null || config.lastUpdateName == null) {
                            config.creatorName =
                                mContext.getPackageManager().getNameForUid(Process.SYSTEM_UID);
                            config.lastUpdateName = config.creatorName;

                            if (DBG) Log.w(TAG, "Upgrading network " + config.networkId
                                    + " to " + config.creatorName);
                        }
                    }
                } else if (config != null) {
                    WifiConfiguration.NetworkSelectionStatus networkStatus =
                            config.getNetworkSelectionStatus();
                    switch (key) {
                        case SSID_KEY:
                            if (config.isPasspoint()) {
                                break;
                            }
                            ssid = value;
                            if (config.SSID != null && !config.SSID.equals(ssid)) {
                                loge("Error parsing network history file, mismatched SSIDs");
                                config = null; //error
                                ssid = null;
                            } else {
                                config.SSID = ssid;
                            }
                            break;
                        case CONFIG_BSSID_KEY:
                            config.BSSID = value.equals("null") ? null : value;
                            break;
                        case FQDN_KEY:
                            // Check for literal 'null' to be backwards compatible.
                            config.FQDN = value.equals("null") ? null : value;
                            break;
                        case DEFAULT_GW_KEY:
                            config.defaultGwMacAddress = value;
                            break;
                        case SELF_ADDED_KEY:
                            config.selfAdded = Boolean.parseBoolean(value);
                            break;
                        case DID_SELF_ADD_KEY:
                            config.didSelfAdd = Boolean.parseBoolean(value);
                            break;
                        case NO_INTERNET_ACCESS_REPORTS_KEY:
                            config.numNoInternetAccessReports = Integer.parseInt(value);
                            break;
                        case VALIDATED_INTERNET_ACCESS_KEY:
                            config.validatedInternetAccess = Boolean.parseBoolean(value);
                            break;
                        case CREATION_TIME_KEY:
                            config.creationTime = value;
                            break;
                        case UPDATE_TIME_KEY:
                            config.updateTime = value;
                            break;
                        case EPHEMERAL_KEY:
                            config.ephemeral = Boolean.parseBoolean(value);
                            break;
                        case CREATOR_UID_KEY:
                            config.creatorUid = Integer.parseInt(value);
                            break;
                        case BLACKLIST_MILLI_KEY:
                            networkStatus.setDisableTime(Long.parseLong(value));
                            break;
                        case SCORER_OVERRIDE_KEY:
                            config.numScorerOverride = Integer.parseInt(value);
                            break;
                        case SCORER_OVERRIDE_AND_SWITCH_KEY:
                            config.numScorerOverrideAndSwitchedNetwork = Integer.parseInt(value);
                            break;
                        case NUM_ASSOCIATION_KEY:
                            config.numAssociation = Integer.parseInt(value);
                            break;
                        case CONNECT_UID_KEY:
                            config.lastConnectUid = Integer.parseInt(value);
                            break;
                        case UPDATE_UID_KEY:
                            config.lastUpdateUid = Integer.parseInt(value);
                            break;
                        case FAILURE_KEY:
                            config.lastFailure = value;
                            break;
                        case PEER_CONFIGURATION_KEY:
                            config.peerWifiConfiguration = value;
                            break;
                        case CHOICE_KEY:
                            networkStatus.setConnectChoice(value);
                            break;
                        case CHOICE_TIME_KEY:
                            networkStatus.setConnectChoiceTimestamp(Long.parseLong(value));
                            break;
                        case LINK_KEY:
                            if (config.linkedConfigurations == null) {
                                config.linkedConfigurations = new HashMap<>();
                            }
                            else {
                                config.linkedConfigurations.put(value, -1);
                            }
                            break;
                        case BSSID_KEY:
                            status = 0;
                            ssid = null;
                            bssid = null;
                            freq = 0;
                            seen = 0;
                            rssi = WifiConfiguration.INVALID_RSSI;
                            caps = "";
                            break;
                        case RSSI_KEY:
                            rssi = Integer.parseInt(value);
                            break;
                        case FREQ_KEY:
                            freq = Integer.parseInt(value);
                            break;
                        case DATE_KEY:
                            /*
                             * when reading the configuration from file we don't update the date
                             * so as to avoid reading back stale or non-sensical data that would
                             * depend on network time.
                             * The date of a WifiConfiguration should only come from actual scan result.
                             *
                            String s = key.replace(FREQ_KEY, "");
                            seen = Integer.getInteger(s);
                            */
                            break;
                        case BSSID_KEY_END:
                            if ((bssid != null) && (ssid != null)) {

                                if (getScanDetailCache(config) != null) {
                                    WifiSsid wssid = WifiSsid.createFromAsciiEncoded(ssid);
                                    ScanDetail scanDetail = new ScanDetail(wssid, bssid,
                                            caps, rssi, freq, (long) 0, seen);
                                    getScanDetailCache(config).put(scanDetail);
                                    scanDetail.getScanResult().autoJoinStatus = status;
                                }
                            }
                            break;
                        case DELETED_CRC32_KEY:
                            mDeletedSSIDs.add(Long.parseLong(value));
                            break;
                        case DELETED_EPHEMERAL_KEY:
                            if (!TextUtils.isEmpty(value)) {
                                mDeletedEphemeralSSIDs.add(value);
                            }
                            break;
                        case CREATOR_NAME_KEY:
                            config.creatorName = value;
                            break;
                        case UPDATE_NAME_KEY:
                            config.lastUpdateName = value;
                            break;
                        case USER_APPROVED_KEY:
                            config.userApproved = Integer.parseInt(value);
                            break;
                        case SHARED_KEY:
                            config.shared = Boolean.parseBoolean(value);
                            break;
                    }
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "readNetworkHistory: failed to read, revert to default, " + e, e);
        } catch (EOFException e) {
            // do nothing
        } catch (IOException e) {
            Log.e(TAG, "readNetworkHistory: No config file, revert to default, " + e, e);
        }
    }

    private void readAutoJoinConfig() {
        try (BufferedReader reader = new BufferedReader(new FileReader(autoJoinConfigFile))) {
            for (String key = reader.readLine(); key != null; key = reader.readLine()) {
                Log.d(TAG, "readAutoJoinConfig line: " + key);

                int split = key.indexOf(':');
                if (split < 0) {
                    continue;
                }

                String name = key.substring(0, split);
                Object reference = sKeyMap.get(name);
                if (reference == null) {
                    continue;
                }

                try {
                    int value = Integer.parseInt(key.substring(split+1).trim());
                    if (reference.getClass() == AtomicBoolean.class) {
                        ((AtomicBoolean)reference).set(value != 0);
                    }
                    else {
                        ((AtomicInteger)reference).set(value);
                    }
                    Log.d(TAG,"readAutoJoinConfig: " + name + " = " + value);
                }
                catch (NumberFormatException nfe) {
                    Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                }
            }
        } catch (IOException e) {
            loge("readNetworkSelectionStatus: Error parsing configuration" + e);
        }
    }


    private void writeIpAndProxyConfigurations() {
        final SparseArray<IpConfiguration> networks = new SparseArray<IpConfiguration>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
            if (!config.ephemeral) {
                networks.put(configKey(config), config.getIpConfiguration());
            }
        }

        super.writeIpAndProxyConfigurations(ipConfigFile, networks);
    }

    private void readIpAndProxyConfigurations() {
        SparseArray<IpConfiguration> networks = super.readIpAndProxyConfigurations(ipConfigFile);

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

    /*
     * Convert string to Hexadecimal before passing to wifi native layer
     * In native function "doCommand()" have trouble in converting Unicode character string to UTF8
     * conversion to hex is required because SSIDs can have space characters in them;
     * and that can confuses the supplicant because it uses space charaters as delimiters
     */

    public static String encodeSSID(String str){
        return Utils.toHex(removeDoubleQuotes(str).getBytes(StandardCharsets.UTF_8));
    }

    private boolean saveConfigToSupplicant(WifiConfiguration config, int netId) {
        if (config.SSID != null && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.ssidVarName,
                    encodeSSID(config.SSID))) {
            loge("failed to set SSID: " + config.SSID);
            return false;
        }

        final Map<String, String> metadata = new HashMap<String, String>();
        if (config.isPasspoint()) {
            metadata.put(ID_STRING_KEY_FQDN, config.FQDN);
        }
        metadata.put(ID_STRING_KEY_CONFIG_KEY, config.configKey());
        metadata.put(ID_STRING_KEY_CREATOR_UID, Integer.toString(config.creatorUid));
        if (!mWifiNative.setNetworkExtra(netId, ID_STRING_VAR_NAME, metadata)) {
            loge("failed to set id_str: " + metadata.toString());
            return false;
        }

        //set selected BSSID to supplicant
        if (config.getNetworkSelectionStatus().getNetworkSelectionBSSID() != null) {
            String bssid = config.getNetworkSelectionStatus().getNetworkSelectionBSSID();
            if (!mWifiNative.setNetworkVariable(netId, WifiConfiguration.bssidVarName, bssid)) {
                loge("failed to set BSSID: " + bssid);
                return false;
            }
        }

        String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
        if (config.allowedKeyManagement.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.KeyMgmt.varName,
                    allowedKeyManagementString)) {
            loge("failed to set key_mgmt: " + allowedKeyManagementString);
            return false;
        }

        String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
        if (config.allowedProtocols.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.Protocol.varName,
                    allowedProtocolsString)) {
            loge("failed to set proto: " + allowedProtocolsString);
            return false;
        }

        String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms, WifiConfiguration.AuthAlgorithm.strings);
        if (config.allowedAuthAlgorithms.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.AuthAlgorithm.varName,
                    allowedAuthAlgorithmsString)) {
            loge("failed to set auth_alg: " + allowedAuthAlgorithmsString);
            return false;
        }

        String allowedPairwiseCiphersString = makeString(config.allowedPairwiseCiphers,
                WifiConfiguration.PairwiseCipher.strings);
        if (config.allowedPairwiseCiphers.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.PairwiseCipher.varName,
                    allowedPairwiseCiphersString)) {
            loge("failed to set pairwise: " + allowedPairwiseCiphersString);
            return false;
        }

        String allowedGroupCiphersString =
                makeString(config.allowedGroupCiphers, WifiConfiguration.GroupCipher.strings);
        if (config.allowedGroupCiphers.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.GroupCipher.varName,
                    allowedGroupCiphersString)) {
            loge("failed to set group: " + allowedGroupCiphersString);
            return false;
        }

        // Prevent client screw-up by passing in a WifiConfiguration we gave it
        // by preventing "*" as a key.
        if (config.preSharedKey != null && !config.preSharedKey.equals("*")
                && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.pskVarName,
                    config.preSharedKey)) {
            loge("failed to set psk");
            return false;
        }

        boolean hasSetKey = false;
        if (config.wepKeys != null) {
            for (int i = 0; i < config.wepKeys.length; i++) {
                // Prevent client screw-up by passing in a WifiConfiguration we gave it
                // by preventing "*" as a key.
                if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                    if (!mWifiNative.setNetworkVariable(
                                netId,
                                WifiConfiguration.wepKeyVarNames[i],
                                config.wepKeys[i])) {
                        loge("failed to set wep_key" + i + ": " + config.wepKeys[i]);
                        return false;
                    }
                    hasSetKey = true;
                }
            }
        }

        if (hasSetKey) {
            if (!mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.wepTxKeyIdxVarName,
                        Integer.toString(config.wepTxKeyIndex))) {
                loge("failed to set wep_tx_keyidx: " + config.wepTxKeyIndex);
                return false;
            }
        }

        if (!mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.priorityVarName,
                    Integer.toString(config.priority))) {
            loge(config.SSID + ": failed to set priority: " + config.priority);
            return false;
        }

        if (config.hiddenSSID && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.hiddenSSIDVarName,
                    Integer.toString(config.hiddenSSID ? 1 : 0))) {
            loge(config.SSID + ": failed to set hiddenSSID: " + config.hiddenSSID);
            return false;
        }

        if (config.requirePMF && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.pmfVarName,
                    "2")) {
            loge(config.SSID + ": failed to set requirePMF: " + config.requirePMF);
            return false;
        }

        if (config.updateIdentifier != null && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.updateIdentiferVarName,
                config.updateIdentifier)) {
            loge(config.SSID + ": failed to set updateIdentifier: " + config.updateIdentifier);
            return false;
        }

        if (config.enterpriseConfig != null
                && config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {

            WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;

            if (needsKeyStore(enterpriseConfig)) {
                try {
                    /* config passed may include only fields being updated.
                     * In order to generate the key id, fetch uninitialized
                     * fields from the currently tracked configuration
                     */
                    WifiConfiguration currentConfig = mConfiguredNetworks.getForCurrentUser(netId);
                    String keyId = config.getKeyIdForCredentials(currentConfig);

                    if (!installKeys(currentConfig != null
                            ? currentConfig.enterpriseConfig : null, enterpriseConfig, keyId)) {
                        loge(config.SSID + ": failed to install keys");
                        return false;
                    }
                } catch (IllegalStateException e) {
                    loge(config.SSID + " invalid config for key installation");
                    return false;
                }
            }

            if (!enterpriseConfig.saveToSupplicant(new SupplicantSaver(netId, config.SSID))) {
                removeKeys(enterpriseConfig);
                return false;
            }
        }

        return true;
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

        int netId = config.networkId;
        boolean newNetwork = false;
        boolean existingMO = false;
        // networkId of INVALID_NETWORK_ID means we want to create a new network
        if (netId == INVALID_NETWORK_ID) {
            WifiConfiguration savedConfig =
                    mConfiguredNetworks.getByConfigKeyForCurrentUser(config.configKey());
            if (savedConfig != null) {
                netId = savedConfig.networkId;
            } else {
                if (mMOManager.getHomeSP(config.FQDN) != null) {
                    loge("addOrUpdateNetworkNative passpoint " + config.FQDN
                            + " was found, but no network Id");
                    existingMO = true;
                }
                newNetwork = true;
                netId = mWifiNative.addNetwork();
                if (netId < 0) {
                    loge("Failed to add a network!");
                    return new NetworkUpdateResult(INVALID_NETWORK_ID);
                } else {
                    loge("addOrUpdateNetworkNative created netId=" + netId);
                }
            }
        }

        if (!saveConfigToSupplicant(config, netId)) {
            if (newNetwork) {
                mWifiNative.removeNetwork(netId);
                loge("Failed to set a network variable, removed network: " + netId);
            }
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        /* An update of the network variables requires reading them
         * back from the supplicant to update mConfiguredNetworks.
         * This is because some of the variables (SSID, wep keys &
         * passphrases) reflect different values when read back than
         * when written. For example, wep key is stored as * irrespective
         * of the value sent to the supplicant
         */
        WifiConfiguration currentConfig = mConfiguredNetworks.getForCurrentUser(netId);
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
        if (!config.isVisibleToUser(mWifiStateMachine.getCurrentUserId())) {
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
            intent.putExtra(WifiManager.EXTRA_PASSPOINT_ICON_DATA, mSupplicantBridge.retrieveIcon(iconEvent));
        } catch (IOException ioe) {
            /* Simply omit the icon data as a failure indication */
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);

    }

    public void notifyIconFailed(long bssid) {
        Intent intent = new Intent(WifiManager.PASSPOINT_ICON_RECEIVED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_PASSPOINT_ICON_BSSID, bssid);
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

    public void notifyANQPResponse(ScanDetail scanDetail,
                               Map<Constants.ANQPElementType, ANQPElement> anqpElements) {

        updateAnqpCache(scanDetail, anqpElements);
        if (anqpElements == null || anqpElements.isEmpty()) {
            return;
        }
        scanDetail.propagateANQPInfo(anqpElements);

        Map<HomeSP, PasspointMatch> matches = matchNetwork(scanDetail, false);
        Log.d(Utils.hs2LogTag(getClass()), scanDetail.getSSID() +
                " pass 2 matches: " + toMatchString(matches));

        cacheScanResultForPasspointConfigs(scanDetail, matches, null);
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
            scanResult.autoJoinStatus = result.autoJoinStatus;
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

    private boolean isEncryptionWep(ScanResult scan) {
        String scanResultEncrypt = scan.capabilities;
        return isEncryptionWep(scanResultEncrypt);
    }

    private boolean isEncryptionWep(WifiConfiguration config) {
        String configEncrypt = config.configKey();
        return isEncryptionWep(configEncrypt);
    }

    private boolean isEncryptionPsk(String encryption) {
        return encryption.contains("PSK");
    }

    private boolean isEncryptionPsk(ScanResult scan) {
        String scanResultEncrypt = scan.capabilities;
        return isEncryptionPsk(scanResultEncrypt);
    }

    private boolean isEncryptionPsk(WifiConfiguration config) {
        String configEncrypt = config.configKey();
        return isEncryptionPsk(configEncrypt);
    }

    private boolean isEncryptionEap(String encryption) {
        return encryption.contains("EAP");
    }

    private boolean isEncryptionEap(ScanResult scan) {
        String scanResultEncrypt = scan.capabilities;
        return isEncryptionEap(scanResultEncrypt);
    }

    private boolean isEncryptionEap(WifiConfiguration config) {
        String configEncrypt = config.configKey();
        return isEncryptionEap(configEncrypt);
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
                if (config.configKey().equals(lastSelectedConfiguration)) {
                    lastSelectedConfiguration = null;
                }
                if (config.enterpriseConfig != null) {
                    removeKeys(config.enterpriseConfig);
                }
                mConfiguredNetworks.remove(config.networkId);
                mScanDetailCaches.remove(config.networkId);
                mWifiNative.removeNetwork(config.networkId);
            }
            mWifiNative.saveConfig();
            writeKnownNetworkHistory();
        }

        final List<WifiConfiguration> hiddenConfigurations =
                mConfiguredNetworks.handleUserSwitch(mWifiStateMachine.getCurrentUserId());
        for (WifiConfiguration network : hiddenConfigurations) {
            if (mWifiNative.disableNetwork(network.networkId)) {
                network.status = Status.DISABLED;
            }
        }

        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            enableNetworkWithoutBroadcast(config.networkId, false);
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

    /** Returns true if a particular config key needs to be quoted when passed to the supplicant. */
    private boolean enterpriseConfigKeyShouldBeQuoted(String key) {
        switch (key) {
            case WifiEnterpriseConfig.EAP_KEY:
            case WifiEnterpriseConfig.ENGINE_KEY:
                return false;
            default:
                return true;
        }
    }

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    private void readNetworkVariables(WifiConfiguration config) {

        int netId = config.networkId;
        if (netId < 0)
            return;

        /*
         * TODO: maybe should have a native method that takes an array of
         * variable names and returns an array of values. But we'd still
         * be doing a round trip to the supplicant daemon for each variable.
         */
        String value;

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.ssidVarName);
        if (!TextUtils.isEmpty(value)) {
            if (value.charAt(0) != '"') {
                config.SSID = "\"" + WifiSsid.createFromHex(value).toString() + "\"";
                //TODO: convert a hex string that is not UTF-8 decodable to a P-formatted
                //supplicant string
            } else {
                config.SSID = value;
            }
        } else {
            config.SSID = null;
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.bssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.getNetworkSelectionStatus().setNetworkSelectionBSSID(value);
        } else {
            config.getNetworkSelectionStatus().setNetworkSelectionBSSID(null);
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.priorityVarName);
        config.priority = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.priority = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.hiddenSSIDVarName);
        config.hiddenSSID = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.hiddenSSID = Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.wepTxKeyIdxVarName);
        config.wepTxKeyIndex = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.wepTxKeyIndex = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        for (int i = 0; i < 4; i++) {
            value = mWifiNative.getNetworkVariable(netId,
                    WifiConfiguration.wepKeyVarNames[i]);
            if (!TextUtils.isEmpty(value)) {
                config.wepKeys[i] = value;
            } else {
                config.wepKeys[i] = null;
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.pskVarName);
        if (!TextUtils.isEmpty(value)) {
            config.preSharedKey = value;
        } else {
            config.preSharedKey = null;
        }

        readNetworkBitsetVariable(config.networkId, config.allowedProtocols,
                WifiConfiguration.Protocol.varName, WifiConfiguration.Protocol.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedKeyManagement,
                WifiConfiguration.KeyMgmt.varName, WifiConfiguration.KeyMgmt.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedAuthAlgorithms,
                WifiConfiguration.AuthAlgorithm.varName, WifiConfiguration.AuthAlgorithm.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedPairwiseCiphers,
                WifiConfiguration.PairwiseCipher.varName, WifiConfiguration.PairwiseCipher.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedGroupCiphers,
                WifiConfiguration.GroupCipher.varName, WifiConfiguration.GroupCipher.strings);

        if (config.enterpriseConfig == null) {
            config.enterpriseConfig = new WifiEnterpriseConfig();
        }
        config.enterpriseConfig.loadFromSupplicant(new SupplicantLoader(netId));

        if (migrateOldEapTlsNative(config.enterpriseConfig, netId)) {
            saveConfig();
        }

        migrateCerts(config.enterpriseConfig);
        // initializeSoftwareKeystoreFlag(config.enterpriseConfig, mKeyStore);
    }

    private static String removeDoubleQuotes(String string) {
        int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"')
                && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private static String makeString(BitSet set, String[] strings) {
        StringBuffer buf = new StringBuffer();
        int nextSetBit = -1;

        /* Make sure all set bits are in [0, strings.length) to avoid
         * going out of bounds on strings.  (Shouldn't happen, but...) */
        set = set.get(0, strings.length);

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            buf.append(strings[nextSetBit].replace('_', '-')).append(' ');
        }

        // remove trailing space
        if (set.cardinality() > 0) {
            buf.setLength(buf.length() - 1);
        }

        return buf.toString();
    }

    private int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++)
            if (string.equals(strings[i]))
                return i;

        // if we ever get here, we should probably add the
        // value to WifiConfiguration to reflect that it's
        // supported by the WPA supplicant
        loge("Failed to look-up a string: " + string);

        return -1;
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
        pw.println("Dump of WifiConfigStore");
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
            pw.println("WifiConfigStore - Log Begin ----");
            mLocalLog.dump(fd, pw, args);
            pw.println("WifiConfigStore - Log End ----");
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

    // Certificate and private key management for EnterpriseConfig
    static boolean needsKeyStore(WifiEnterpriseConfig config) {
        // Has no keys to be installed
        if (config.getClientCertificate() == null && config.getCaCertificate() == null)
            return false;
        return true;
    }

    static boolean isHardwareBackedKey(PrivateKey key) {
        return KeyChain.isBoundKeyAlgorithm(key.getAlgorithm());
    }

    static boolean hasHardwareBackedKey(Certificate certificate) {
        return KeyChain.isBoundKeyAlgorithm(certificate.getPublicKey().getAlgorithm());
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

    boolean isSimConfig(WifiConfiguration config) {
        if (config == null) {
            return false;
        }

        if (config.enterpriseConfig == null) {
            return false;
        }

        int method = config.enterpriseConfig.getEapMethod();
        return (method == WifiEnterpriseConfig.Eap.SIM
                || method == WifiEnterpriseConfig.Eap.AKA
                || method == WifiEnterpriseConfig.Eap.AKA_PRIME);
    }

    void resetSimNetworks() {
        for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
            if (isSimConfig(config)) {
                /* This configuration may have cached Pseudonym IDs; lets remove them */
                mWifiNative.setNetworkVariable(config.networkId, "identity", "NULL");
                mWifiNative.setNetworkVariable(config.networkId, "anonymous_identity", "NULL");
            }
        }
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
            return (AppGlobals.getPackageManager().checkUidPermission(
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
        mBssidBlacklist = new HashSet<String>();
        mWifiNative.clearBlacklist();
        mWifiNative.setBssidBlacklist(null);
    }

    void blackListBssid(String BSSID) {
        if (!mWifiStateMachine.useHalBasedAutoJoinOffload()) {
            if(DBG) {
                Log.d(TAG, "No blacklist allowed without epno enabled");
            }
            return;
        }
        if (BSSID == null)
            return;
        mBssidBlacklist.add(BSSID);
        // Blacklist at wpa_supplicant
        mWifiNative.addToBlacklist(BSSID);
        // Blacklist at firmware
        String list[] = new String[mBssidBlacklist.size()];
        int count = 0;
        for (String bssid : mBssidBlacklist) {
            list[count++] = bssid;
        }
        mWifiNative.setBssidBlacklist(list);
    }

    public boolean isBssidBlacklisted(String bssid) {
        return mBssidBlacklist.contains(bssid);
    }

    public boolean getEnableNewNetworkSelectionWhenAssociated() {
        return enableAutoJoinWhenAssociated.get();
    }

    boolean installKeys(WifiEnterpriseConfig oldConfig, WifiEnterpriseConfig config, String name) {
        boolean ret = true;
        String privKeyName = Credentials.USER_PRIVATE_KEY + name;
        String userCertName = Credentials.USER_CERTIFICATE + name;
        if (config.getClientCertificate() != null) {
            byte[] privKeyData = config.getClientPrivateKey().getEncoded();
            if (DBG) {
                if (isHardwareBackedKey(config.getClientPrivateKey())) {
                    Log.d(TAG, "importing keys " + name + " in hardware backed store");
                } else {
                    Log.d(TAG, "importing keys " + name + " in software backed store");
                }
            }
            ret = mKeyStore.importKey(privKeyName, privKeyData, Process.WIFI_UID,
                    KeyStore.FLAG_NONE);

            if (ret == false) {
                return ret;
            }

            ret = putCertInKeyStore(userCertName, config.getClientCertificate());
            if (ret == false) {
                // Remove private key installed
                mKeyStore.delete(privKeyName, Process.WIFI_UID);
                return ret;
            }
        }

        X509Certificate[] caCertificates = config.getCaCertificates();
        Set<String> oldCaCertificatesToRemove = new ArraySet<String>();
        if (oldConfig != null && oldConfig.getCaCertificateAliases() != null) {
            oldCaCertificatesToRemove.addAll(Arrays.asList(oldConfig.getCaCertificateAliases()));
        }
        List<String> caCertificateAliases = null;
        if (caCertificates != null) {
            caCertificateAliases = new ArrayList<String>();
            for (int i = 0; i < caCertificates.length; i++) {
                String alias = caCertificates.length == 1 ? name
                        : String.format("%s_%d", name, i);

                oldCaCertificatesToRemove.remove(alias);
                ret = putCertInKeyStore(Credentials.CA_CERTIFICATE + alias, caCertificates[i]);
                if (!ret) {
                    // Remove client key+cert
                    if (config.getClientCertificate() != null) {
                        mKeyStore.delete(privKeyName, Process.WIFI_UID);
                        mKeyStore.delete(userCertName, Process.WIFI_UID);
                    }
                    // Remove added CA certs.
                    for (String addedAlias : caCertificateAliases) {
                        mKeyStore.delete(Credentials.CA_CERTIFICATE + addedAlias, Process.WIFI_UID);
                    }
                    return ret;
                } else {
                    caCertificateAliases.add(alias);
                }
            }
        }
        // Remove old CA certs.
        for (String oldAlias : oldCaCertificatesToRemove) {
            mKeyStore.delete(Credentials.CA_CERTIFICATE + oldAlias, Process.WIFI_UID);
        }
        // Set alias names
        if (config.getClientCertificate() != null) {
            config.setClientCertificateAlias(name);
            config.resetClientKeyEntry();
        }

        if (caCertificates != null) {
            config.setCaCertificateAliases(
                    caCertificateAliases.toArray(new String[caCertificateAliases.size()]));
            config.resetCaCertificate();
        }

        return ret;
    }

    private boolean putCertInKeyStore(String name, Certificate cert) {
        try {
            byte[] certData = Credentials.convertToPem(cert);
            if (DBG) Log.d(TAG, "putting certificate " + name + " in keystore");
            return mKeyStore.put(name, certData, Process.WIFI_UID, KeyStore.FLAG_NONE);

        } catch (IOException e1) {
            return false;
        } catch (CertificateException e2) {
            return false;
        }
    }

    void removeKeys(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (DBG) Log.d(TAG, "removing client private key and user cert");
            mKeyStore.delete(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
            mKeyStore.delete(Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
        }

        String[] aliases = config.getCaCertificateAliases();
        // a valid ca certificate is configured
        if (aliases != null) {
            for (String ca: aliases) {
                if (!TextUtils.isEmpty(ca)) {
                    if (DBG) Log.d(TAG, "removing CA cert: " + ca);
                    mKeyStore.delete(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
                }
            }
        }
    }


    /** Migrates the old style TLS config to the new config style. This should only be used
     * when restoring an old wpa_supplicant.conf or upgrading from a previous
     * platform version.
     * @return true if the config was updated
     * @hide
     */
    boolean migrateOldEapTlsNative(WifiEnterpriseConfig config, int netId) {
        String oldPrivateKey = mWifiNative.getNetworkVariable(netId, OLD_PRIVATE_KEY_NAME);
        /*
         * If the old configuration value is not present, then there is nothing
         * to do.
         */
        if (TextUtils.isEmpty(oldPrivateKey)) {
            return false;
        } else {
            // Also ignore it if it's empty quotes.
            oldPrivateKey = removeDoubleQuotes(oldPrivateKey);
            if (TextUtils.isEmpty(oldPrivateKey)) {
                return false;
            }
        }

        config.setFieldValue(WifiEnterpriseConfig.ENGINE_KEY, WifiEnterpriseConfig.ENGINE_ENABLE);
        config.setFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY,
                WifiEnterpriseConfig.ENGINE_ID_KEYSTORE);

        /*
        * The old key started with the keystore:// URI prefix, but we don't
        * need that anymore. Trim it off if it exists.
        */
        final String keyName;
        if (oldPrivateKey.startsWith(WifiEnterpriseConfig.KEYSTORE_URI)) {
            keyName = new String(
                    oldPrivateKey.substring(WifiEnterpriseConfig.KEYSTORE_URI.length()));
        } else {
            keyName = oldPrivateKey;
        }
        config.setFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, keyName);

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.ENGINE_KEY,
                config.getFieldValue(WifiEnterpriseConfig.ENGINE_KEY, ""));

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.ENGINE_ID_KEY,
                config.getFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY, ""));

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY,
                config.getFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, ""));

        // Remove old private_key string so we don't run this again.
        mWifiNative.setNetworkVariable(netId, OLD_PRIVATE_KEY_NAME, EMPTY_VALUE);

        return true;
    }

    /** Migrate certs from global pool to wifi UID if not already done */
    void migrateCerts(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (!mKeyStore.contains(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID)) {
                mKeyStore.duplicate(Credentials.USER_PRIVATE_KEY + client, -1,
                        Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
                mKeyStore.duplicate(Credentials.USER_CERTIFICATE + client, -1,
                        Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
            }
        }

        String[] aliases = config.getCaCertificateAliases();
        // a valid ca certificate is configured
        if (aliases != null) {
            for (String ca : aliases) {
                if (!TextUtils.isEmpty(ca)
                        && !mKeyStore.contains(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID)) {
                    mKeyStore.duplicate(Credentials.CA_CERTIFICATE + ca, -1,
                            Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
                }
            }
        }
    }

    private void readNetworkBitsetVariable(int netId, BitSet variable, String varName,
            String[] strings) {
        String value = mWifiNative.getNetworkVariable(netId, varName);
        if (!TextUtils.isEmpty(value)) {
            variable.clear();
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index = lookupString(val, strings);
                if (0 <= index) {
                    variable.set(index);
                }
            }
        }
    }
}
