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

import android.app.ActivityManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class provides the APIs to manage configured Wi-Fi networks.
 * It deals with the following:
 * - Maintaining a list of configured networks for quick access.
 * - Persisting the configurations to store when required.
 * - Supporting WifiManager Public API calls:
 *   > addOrUpdateNetwork()
 *   > removeNetwork()
 *   > enableNetwork()
 *   > disableNetwork()
 * - Handle user switching on multi-user devices.
 *
 * All network configurations retrieved from this class are copies of the original configuration
 * stored in the internal database. So, any updates to the retrieved configuration object are
 * meaningless and will not be reflected in the original database.
 * This is done on purpose to ensure that only WifiConfigManager can modify configurations stored
 * in the internal database. Any configuration updates should be triggered with appropriate helper
 * methods of this class using the configuration's unique networkId.
 *
 * NOTE: These API's are not thread safe and should only be used from WifiStateMachine thread.
 */
public class WifiConfigManagerNew {
    /**
     * String used to mask passwords to public interface.
     */
    @VisibleForTesting
    public static final String PASSWORD_MASK = "*";
    /**
     * Network Selection disable reason thresholds. These numbers are used to debounce network
     * failures before we disable them.
     * These are indexed using the disable reason constants defined in
     * {@link android.net.wifi.WifiConfiguration.NetworkSelectionStatus}.
     */
    @VisibleForTesting
    public static final int[] NETWORK_SELECTION_DISABLE_THRESHOLD = {
            -1, //  threshold for NETWORK_SELECTION_ENABLE
            1,  //  threshold for DISABLED_BAD_LINK
            5,  //  threshold for DISABLED_ASSOCIATION_REJECTION
            5,  //  threshold for DISABLED_AUTHENTICATION_FAILURE
            5,  //  threshold for DISABLED_DHCP_FAILURE
            5,  //  threshold for DISABLED_DNS_FAILURE
            1,  //  threshold for DISABLED_WPS_START
            6,  //  threshold for DISABLED_TLS_VERSION_MISMATCH
            1,  //  threshold for DISABLED_AUTHENTICATION_NO_CREDENTIALS
            1,  //  threshold for DISABLED_NO_INTERNET
            1,  //  threshold for DISABLED_BY_WIFI_MANAGER
            1   //  threshold for DISABLED_BY_USER_SWITCH
    };
    /**
     * Network Selection disable timeout for each kind of error. After the timeout milliseconds,
     * enable the network again.
     * These are indexed using the disable reason constants defined in
     * {@link android.net.wifi.WifiConfiguration.NetworkSelectionStatus}.
     */
    @VisibleForTesting
    public static final int[] NETWORK_SELECTION_DISABLE_TIMEOUT_MS = {
            Integer.MAX_VALUE,  // threshold for NETWORK_SELECTION_ENABLE
            15 * 60 * 1000,     // threshold for DISABLED_BAD_LINK
            5 * 60 * 1000,      // threshold for DISABLED_ASSOCIATION_REJECTION
            5 * 60 * 1000,      // threshold for DISABLED_AUTHENTICATION_FAILURE
            5 * 60 * 1000,      // threshold for DISABLED_DHCP_FAILURE
            5 * 60 * 1000,      // threshold for DISABLED_DNS_FAILURE
            0 * 60 * 1000,      // threshold for DISABLED_WPS_START
            Integer.MAX_VALUE,  // threshold for DISABLED_TLS_VERSION
            Integer.MAX_VALUE,  // threshold for DISABLED_AUTHENTICATION_NO_CREDENTIALS
            Integer.MAX_VALUE,  // threshold for DISABLED_NO_INTERNET
            Integer.MAX_VALUE,  // threshold for DISABLED_BY_WIFI_MANAGER
            Integer.MAX_VALUE   // threshold for DISABLED_BY_USER_SWITCH
    };
    /**
     * Max size of scan details to cache in {@link #mScanDetailCaches}.
     */
    @VisibleForTesting
    public static final int SCAN_CACHE_ENTRIES_MAX_SIZE = 192;
    /**
     * Once the size of the scan details in the cache {@link #mScanDetailCaches} exceeds
     * {@link #SCAN_CACHE_ENTRIES_MAX_SIZE}, trim it down to this value so that we have some
     * buffer time before the next eviction.
     */
    @VisibleForTesting
    public static final int SCAN_CACHE_ENTRIES_TRIM_SIZE = 128;
    /**
     * Flags to be passed in for |canModifyNetwork| to decide if the change is minor and can
     * bypass the lockdown checks.
     */
    private static final boolean ALLOW_LOCKDOWN_CHECK_BYPASS = true;
    private static final boolean DISALLOW_LOCKDOWN_CHECK_BYPASS = false;
    /**
     * Log tag for this class.
     */
    private static final String TAG = "WifiConfigManagerNew";
    /**
     * Disconnected/Connected PnoNetwork list sorting algorithm:
     * Place the configurations in descending order of their |numAssociation| values. If networks
     * have the same |numAssociation|, place the configurations with
     * |lastSeenInQualifiedNetworkSelection| set first.
     */
    private static final WifiConfigurationUtil.WifiConfigurationComparator sPnoListComparator =
            new WifiConfigurationUtil.WifiConfigurationComparator() {
                @Override
                public int compareNetworksWithSameStatus(WifiConfiguration a, WifiConfiguration b) {
                    if (a.numAssociation != b.numAssociation) {
                        return Long.compare(b.numAssociation, a.numAssociation);
                    } else {
                        boolean isConfigALastSeen =
                                a.getNetworkSelectionStatus()
                                        .getSeenInLastQualifiedNetworkSelection();
                        boolean isConfigBLastSeen =
                                b.getNetworkSelectionStatus()
                                        .getSeenInLastQualifiedNetworkSelection();
                        return Boolean.compare(isConfigBLastSeen, isConfigALastSeen);
                    }
                }
            };

    /**
     * List of external dependencies for WifiConfigManager.
     */
    private final Context mContext;
    private final FrameworkFacade mFacade;
    private final Clock mClock;
    private final UserManager mUserManager;
    private final BackupManagerProxy mBackupManagerProxy;
    private final WifiConfigStoreNew mWifiConfigStore;
    private final WifiKeyStore mWifiKeyStore;
    /**
     * Local log used for debugging any WifiConfigManager issues.
     */
    private final LocalLog mLocalLog =
            new LocalLog(ActivityManager.isLowRamDeviceStatic() ? 128 : 256);
    /**
     * Map of configured networks with network id as the key.
     */
    private final ConfigurationMap mConfiguredNetworks;
    /**
     * Stores a map of NetworkId to ScanDetailCache.
     */
    private final ConcurrentHashMap<Integer, ScanDetailCache> mScanDetailCaches;
    /**
     * Framework keeps a list of ephemeral SSIDs that where deleted by user,
     * so as, framework knows not to autoconnect again those SSIDs based on scorer input.
     * The list is never cleared up.
     * The SSIDs are encoded in a String as per definition of WifiConfiguration.SSID field.
     */
    private final Set<String> mDeletedEphemeralSSIDs;

    /**
     * Verbose logging flag. Toggled by developer options.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Current logged in user ID.
     */
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    /**
     * This is keeping track of the last network ID assigned. Any new networks will be assigned
     * |mLastNetworkId + 1| as network ID.
     */
    private int mLastNetworkId;

    /**
     * Create new instance of WifiConfigManager.
     */
    WifiConfigManagerNew(
            Context context, FrameworkFacade facade, Clock clock, UserManager userManager,
            WifiKeyStore wifiKeyStore, WifiConfigStoreNew wifiConfigStore) {
        mContext = context;
        mFacade = facade;
        mClock = clock;
        mUserManager = userManager;
        mBackupManagerProxy = new BackupManagerProxy();
        mWifiConfigStore = wifiConfigStore;
        mWifiKeyStore = wifiKeyStore;

        mConfiguredNetworks = new ConfigurationMap(userManager);
        mScanDetailCaches = new ConcurrentHashMap<>(16, 0.75f, 2);
        mDeletedEphemeralSSIDs = new HashSet<String>();
    }

    /**
     * Construct the string to be put in the |creationTime| & |updateTime| elements of
     * WifiConfiguration from the provided wall clock millis.
     *
     * @param wallClockMillis Time in milliseconds to be converted to string.
     */
    @VisibleForTesting
    public static String createDebugTimeStampString(long wallClockMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append("time=");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wallClockMillis);
        sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
        return sb.toString();
    }

    /**
     * Enable/disable verbose logging in WifiConfigManager & its helper classes.
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
        mWifiConfigStore.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiKeyStore.enableVerboseLogging(mVerboseLoggingEnabled);
    }

    /**
     * Helper method to mask all passwords/keys from the provided WifiConfiguration object. This
     * is needed when the network configurations are being requested via the public WifiManager
     * API's.
     * This currently masks the following elements: psk, wepKeys & enterprise config password.
     */
    private void maskPasswordsInWifiConfiguration(WifiConfiguration configuration) {
        if (!TextUtils.isEmpty(configuration.preSharedKey)) {
            configuration.preSharedKey = PASSWORD_MASK;
        }
        if (configuration.wepKeys != null) {
            for (int i = 0; i < configuration.wepKeys.length; i++) {
                if (!TextUtils.isEmpty(configuration.wepKeys[i])) {
                    configuration.wepKeys[i] = PASSWORD_MASK;
                }
            }
        }
        if (!TextUtils.isEmpty(configuration.enterpriseConfig.getPassword())) {
            configuration.enterpriseConfig.setPassword(PASSWORD_MASK);
        }
    }

    /**
     * Fetch the list of currently configured networks maintained in WifiConfigManager.
     *
     * This retrieves a copy of the internal configurations maintained by WifiConfigManager and
     * should be used for any public interfaces.
     *
     * @param savedOnly     Retrieve only saved networks.
     * @param maskPasswords Mask passwords or not.
     * @return List of WifiConfiguration objects representing the networks.
     */
    private List<WifiConfiguration> getConfiguredNetworks(
            boolean savedOnly, boolean maskPasswords) {
        List<WifiConfiguration> networks = new ArrayList<>();
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            if (savedOnly && config.ephemeral) {
                continue;
            }
            WifiConfiguration newConfig = new WifiConfiguration(config);
            if (maskPasswords) {
                maskPasswordsInWifiConfiguration(newConfig);
            }
            networks.add(newConfig);
        }
        return networks;
    }

    /**
     * Retrieves the list of all configured networks with passwords masked.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        return getConfiguredNetworks(false, true);
    }

    /**
     * Retrieves the list of all configured networks with the passwords in plaintext.
     *
     * WARNING: Don't use this to pass network configurations to external apps. Should only be
     * sent to system apps/wifi stack, when there is a need for passwords in plaintext.
     * TODO: Need to understand the current use case of this API.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getConfiguredNetworksWithPasswords() {
        return getConfiguredNetworks(false, false);
    }

    /**
     * Retrieves the list of all configured networks with the passwords masked.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getSavedNetworks() {
        return getConfiguredNetworks(true, true);
    }

    /**
     * Retrieves the configured network corresponding to the provided networkId with password
     * masked.
     *
     * @param networkId networkId of the requested network.
     * @return WifiConfiguration object if found, null otherwise.
     */
    public WifiConfiguration getConfiguredNetwork(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return null;
        }
        // Create a new configuration object with the passwords masked to send out to the external
        // world.
        WifiConfiguration network = new WifiConfiguration(config);
        maskPasswordsInWifiConfiguration(network);
        return network;
    }

    /**
     * Retrieves the configured network corresponding to the provided networkId with password
     * in plaintext.
     *
     * WARNING: Don't use this to pass network configurations to external apps. Should only be
     * sent to system apps/wifi stack, when there is a need for passwords in plaintext.
     *
     * @param networkId networkId of the requested network.
     * @return WifiConfiguration object if found, null otherwise.
     */
    public WifiConfiguration getConfiguredNetworkWithPassword(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return null;
        }
        // Create a new configuration object without the passwords masked to send out to the
        // external world.
        WifiConfiguration network = new WifiConfiguration(config);
        return network;
    }

    /**
     * Helper method to retrieve all the internal WifiConfiguration objects corresponding to all
     * the networks in our database.
     */
    private Collection<WifiConfiguration> getInternalConfiguredNetworks() {
        return mConfiguredNetworks.valuesForCurrentUser();
    }

    /**
     * Helper method to retrieve the internal WifiConfiguration object corresponding to the
     * provided configuration in our database.
     * This first attempts to find the network using the provided network ID in configuration,
     * else it attempts to find a matching configuration using the configKey.
     */
    private WifiConfiguration getInternalConfiguredNetwork(WifiConfiguration config) {
        WifiConfiguration internalConfig = mConfiguredNetworks.getForCurrentUser(config.networkId);
        if (internalConfig != null) {
            return internalConfig;
        }
        return mConfiguredNetworks.getByConfigKeyForCurrentUser(config.configKey());
    }

    /**
     * Helper method to retrieve the internal WifiConfiguration object corresponding to the
     * provided network ID in our database.
     */
    private WifiConfiguration getInternalConfiguredNetwork(int networkId) {
        return mConfiguredNetworks.getForCurrentUser(networkId);
    }

    /**
     * Helper method to check if the network is already configured internally or not.
     */
    private boolean isNetworkConfiguredInternally(WifiConfiguration config) {
        return getInternalConfiguredNetwork(config) != null;
    }

    /**
     * Helper method to check if the network is already configured internally or not.
     */
    private boolean isNetworkConfiguredInternally(int networkId) {
        return getInternalConfiguredNetwork(networkId) != null;
    }

    /**
     * Method to send out the configured networks change broadcast when a single network
     * configuration is changed.
     *
     * @param network WifiConfiguration corresponding to the network that was changed.
     * @param reason  The reason for the change, should be one of WifiManager.CHANGE_REASON_ADDED,
     *                WifiManager.CHANGE_REASON_REMOVED, or WifiManager.CHANGE_REASON_CHANGE.
     */
    private void sendConfiguredNetworkChangedBroadcast(
            WifiConfiguration network, int reason) {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, false);
        // Create a new WifiConfiguration with passwords masked before we send it out.
        WifiConfiguration broadcastNetwork = new WifiConfiguration(network);
        maskPasswordsInWifiConfiguration(broadcastNetwork);
        intent.putExtra(WifiManager.EXTRA_WIFI_CONFIGURATION, broadcastNetwork);
        intent.putExtra(WifiManager.EXTRA_CHANGE_REASON, reason);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Method to send out the configured networks change broadcast when multiple network
     * configurations are changed.
     */
    private void sendConfiguredNetworksChangedBroadcast() {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, true);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Checks if the app has the permission to override Wi-Fi network configuration or not.
     *
     * @param uid uid of the app.
     * @return true if the app does have the permission, false otherwise.
     */
    private boolean checkConfigOverridePermission(int uid) {
        try {
            int permission =
                    mFacade.checkUidPermission(
                            android.Manifest.permission.OVERRIDE_WIFI_CONFIG, uid);
            return (permission == PackageManager.PERMISSION_GRANTED);
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking for permission " + e);
            return false;
        }
    }

    /**
     * Checks if |uid| has permission to modify the provided configuration.
     *
     * @param config         WifiConfiguration object corresponding to the network to be modified.
     * @param uid            UID of the app requesting the modification.
     * @param ignoreLockdown Ignore the configuration lockdown checks for debug data updates.
     */
    private boolean canModifyNetwork(WifiConfiguration config, int uid, boolean ignoreLockdown) {
        final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                DevicePolicyManagerInternal.class);

        final boolean isUidDeviceOwner = dpmi != null && dpmi.isActiveAdminWithPolicy(uid,
                DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

        // If |uid| corresponds to the device owner, allow all modifications.
        if (isUidDeviceOwner) {
            return true;
        }

        final boolean isCreator = (config.creatorUid == uid);

        // Check if the |uid| is either the creator of the network or holds the
        // |OVERRIDE_CONFIG_WIFI| permission if the caller asks us to bypass the lockdown checks.
        if (ignoreLockdown) {
            return isCreator || checkConfigOverridePermission(uid);
        }

        // Check if device has DPM capability. If it has and |dpmi| is still null, then we
        // treat this case with suspicion and bail out.
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
                && dpmi == null) {
            Log.w(TAG, "Error retrieving DPMI service.");
            return false;
        }

        // WiFi config lockdown related logic. At this point we know uid is NOT a Device Owner.

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
     * Copy over public elements from an external WifiConfiguration object to the internal
     * configuration object if element has been set in the provided external WifiConfiguration.
     * The only exception is the hidden |IpConfiguration| parameters, these need to be copied over
     * for every update.
     *
     * This method updates all elements that are common to both network addition & update.
     * The following fields of {@link WifiConfiguration} are not copied from external configs:
     *  > networkId - These are allocated by Wi-Fi stack internally for any new configurations.
     *  > status - The status needs to be explicitly updated using
     *             {@link WifiManager#enableNetwork(int, boolean)} or
     *             {@link WifiManager#disableNetwork(int)}.
     *
     * @param externalConfig WifiConfiguration object provided from the external API.
     * @param internalConfig WifiConfiguration object in our internal map.
     */
    private void mergeWithInternalWifiConfiguration(
            WifiConfiguration externalConfig, WifiConfiguration internalConfig) {
        if (externalConfig.SSID != null) {
            internalConfig.SSID = externalConfig.SSID;
        }
        if (externalConfig.BSSID != null) {
            internalConfig.BSSID = externalConfig.BSSID;
        }
        internalConfig.hiddenSSID = externalConfig.hiddenSSID;
        if (externalConfig.preSharedKey != null) {
            internalConfig.preSharedKey = externalConfig.preSharedKey;
        }
        // Modify only wep keys are present in the provided configuration. This is a little tricky
        // because there is no easy way to tell if the app is actually trying to null out the
        // existing keys or not.
        if (externalConfig.wepKeys != null) {
            boolean hasWepKey = false;
            for (int i = 0; i < internalConfig.wepKeys.length; i++) {
                if (externalConfig.wepKeys[i] != null) {
                    internalConfig.wepKeys[i] = externalConfig.wepKeys[i];
                    hasWepKey = true;
                }
            }
            if (hasWepKey) {
                internalConfig.wepTxKeyIndex = externalConfig.wepTxKeyIndex;
            }
        }
        if (externalConfig.FQDN != null) {
            internalConfig.FQDN = externalConfig.FQDN;
        }
        if (externalConfig.providerFriendlyName != null) {
            internalConfig.providerFriendlyName = externalConfig.providerFriendlyName;
        }
        if (externalConfig.roamingConsortiumIds != null) {
            internalConfig.roamingConsortiumIds = externalConfig.roamingConsortiumIds.clone();
        }

        // Copy over all the auth/protocol/key mgmt parameters if set.
        if (externalConfig.allowedAuthAlgorithms != null
                && !externalConfig.allowedAuthAlgorithms.isEmpty()) {
            internalConfig.allowedAuthAlgorithms =
                    (BitSet) externalConfig.allowedAuthAlgorithms.clone();
        }
        if (externalConfig.allowedProtocols != null
                && !externalConfig.allowedProtocols.isEmpty()) {
            internalConfig.allowedProtocols = (BitSet) externalConfig.allowedProtocols.clone();
        }
        if (externalConfig.allowedKeyManagement != null
                && !externalConfig.allowedKeyManagement.isEmpty()) {
            internalConfig.allowedKeyManagement =
                    (BitSet) externalConfig.allowedKeyManagement.clone();
        }
        if (externalConfig.allowedPairwiseCiphers != null
                && !externalConfig.allowedPairwiseCiphers.isEmpty()) {
            internalConfig.allowedPairwiseCiphers =
                    (BitSet) externalConfig.allowedPairwiseCiphers.clone();
        }
        if (externalConfig.allowedGroupCiphers != null
                && !externalConfig.allowedGroupCiphers.isEmpty()) {
            internalConfig.allowedGroupCiphers =
                    (BitSet) externalConfig.allowedGroupCiphers.clone();
        }

        // Copy over the |IpConfiguration| parameters if set.
        if (externalConfig.getIpConfiguration() != null) {
            IpConfiguration.IpAssignment ipAssignment = externalConfig.getIpAssignment();
            if (ipAssignment != IpConfiguration.IpAssignment.UNASSIGNED) {
                internalConfig.setIpAssignment(ipAssignment);
                if (ipAssignment == IpConfiguration.IpAssignment.STATIC) {
                    internalConfig.setStaticIpConfiguration(
                            new StaticIpConfiguration(externalConfig.getStaticIpConfiguration()));
                }
            }
            IpConfiguration.ProxySettings proxySettings = externalConfig.getProxySettings();
            if (proxySettings != IpConfiguration.ProxySettings.UNASSIGNED) {
                internalConfig.setProxySettings(proxySettings);
                if (proxySettings == IpConfiguration.ProxySettings.PAC
                        || proxySettings == IpConfiguration.ProxySettings.STATIC) {
                    internalConfig.setHttpProxy(new ProxyInfo(externalConfig.getHttpProxy()));
                }
            }
        }

        // Copy over the |WifiEnterpriseConfig| parameters if set.
        if (externalConfig.enterpriseConfig != null) {
            internalConfig.enterpriseConfig =
                    new WifiEnterpriseConfig(externalConfig.enterpriseConfig);
        }
    }

    /**
     * Set all the exposed defaults in the newly created WifiConfiguration object.
     * These fields have a default value advertised in our public documentation. The only exception
     * is the hidden |IpConfiguration| parameters, these have a default value even though they're
     * hidden.
     *
     * @param configuration provided WifiConfiguration object.
     */
    private void setDefaultsInWifiConfiguration(WifiConfiguration configuration) {
        configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);

        configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);

        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);

        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);

        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);

        configuration.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        configuration.setProxySettings(IpConfiguration.ProxySettings.NONE);
    }

    /**
     * Create a new WifiConfiguration object by copying over parameters from the provided
     * external configuration and set defaults for the appropriate parameters.
     *
     * @param config provided external WifiConfiguration object.
     * @return New WifiConfiguration object with parameters merged from the provided external
     * configuration.
     */
    private WifiConfiguration createNewInternalWifiConfigurationFromExternal(
            WifiConfiguration config, int uid) {
        WifiConfiguration newConfig = new WifiConfiguration();

        // First allocate a new network ID for the configuration.
        newConfig.networkId = mLastNetworkId++;

        // First set defaults in the new configuration created.
        setDefaultsInWifiConfiguration(newConfig);

        // Copy over all the public elements from the provided configuration.
        mergeWithInternalWifiConfiguration(config, newConfig);

        // Copy over the hidden configuration parameters. These are the only parameters used by
        // system apps to indicate some property about the network being added.
        // These are only copied over for network additions and ignored for network updates.
        newConfig.requirePMF = config.requirePMF;
        newConfig.noInternetAccessExpected = config.noInternetAccessExpected;
        newConfig.ephemeral = config.ephemeral;
        newConfig.meteredHint = config.meteredHint;
        newConfig.useExternalScores = config.useExternalScores;
        newConfig.shared = config.shared;

        // Add debug information for network addition.
        newConfig.creatorUid = newConfig.lastUpdateUid = uid;
        newConfig.creatorName = newConfig.lastUpdateName =
                mContext.getPackageManager().getNameForUid(uid);
        newConfig.creationTime = newConfig.updateTime =
                createDebugTimeStampString(mClock.getWallClockMillis());

        return newConfig;
    }

    /**
     * Merges the provided external WifiConfiguration object with a copy of the existing internal
     * WifiConfiguration object.
     *
     * @param config provided external WifiConfiguration object.
     * @return Copy of existing WifiConfiguration object with parameters merged from the provided
     * configuration.
     */
    private WifiConfiguration updateExistingInternalWifiConfigurationFromExternal(
            WifiConfiguration config, int uid) {
        WifiConfiguration existingConfig =
                new WifiConfiguration(getInternalConfiguredNetwork(config));

        // Copy over all the public elements from the provided configuration.
        mergeWithInternalWifiConfiguration(config, existingConfig);

        // Add debug information for network update.
        existingConfig.lastUpdateUid = uid;
        existingConfig.lastUpdateName = mContext.getPackageManager().getNameForUid(uid);
        existingConfig.updateTime = createDebugTimeStampString(mClock.getWallClockMillis());

        return existingConfig;
    }

    /**
     * Add a network or update a network configuration to our database.
     * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
     * network configuration. Otherwise, the networkId should refer to an existing configuration.
     *
     * @param config provided WifiConfiguration object.
     * @param uid    UID of the app requesting the network addition/deletion.
     * @return NetworkUpdateResult object representing status of the update.
     */
    private NetworkUpdateResult addOrUpdateNetworkInternal(WifiConfiguration config, int uid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding/Updating network " + config.getPrintableSsid());
        }
        boolean newNetwork;
        WifiConfiguration existingInternalConfig;
        WifiConfiguration newInternalConfig;

        if (!isNetworkConfiguredInternally(config)) {
            newNetwork = true;
            existingInternalConfig = null;
            newInternalConfig = createNewInternalWifiConfigurationFromExternal(config, uid);
        } else {
            newNetwork = false;
            existingInternalConfig =
                    new WifiConfiguration(getInternalConfiguredNetwork(config));
            // Check for the app's permission before we let it update this network.
            if (!canModifyNetwork(existingInternalConfig, uid, DISALLOW_LOCKDOWN_CHECK_BYPASS)) {
                Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                        + config.configKey());
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
            newInternalConfig = updateExistingInternalWifiConfigurationFromExternal(config, uid);
        }

        // Update the keys for enterprise networks.
        if (config.enterpriseConfig != null
                && config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {
            if (!(mWifiKeyStore.updateNetworkKeys(config, existingInternalConfig))) {
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
        }

        // Reset the |hasEverConnected| flag if the credential parameters changed in this update.
        boolean hasCredentialChanged =
                newNetwork || WifiConfigurationUtil.hasCredentialChanged(
                        existingInternalConfig, newInternalConfig);
        if (hasCredentialChanged) {
            newInternalConfig.getNetworkSelectionStatus().setHasEverConnected(false);
        }

        // Add it to our internal map. This will replace any existing network configuration for
        // updates.
        mConfiguredNetworks.put(newInternalConfig);

        // Stage the backup of the SettingsProvider package which backs this up.
        mBackupManagerProxy.notifyDataChanged();

        // This is needed to inform IpManager about any IP configuration changes.
        boolean hasIpChanged =
                newNetwork || WifiConfigurationUtil.hasIpChanged(
                        existingInternalConfig, newInternalConfig);
        boolean hasProxyChanged =
                newNetwork || WifiConfigurationUtil.hasProxyChanged(
                        existingInternalConfig, newInternalConfig);
        NetworkUpdateResult result = new NetworkUpdateResult(hasIpChanged, hasProxyChanged);
        result.setIsNewNetwork(newNetwork);
        result.setNetworkId(newInternalConfig.networkId);

        localLog("addOrUpdateNetworkInternal: added/updated config."
                + " netId=" + newInternalConfig.networkId
                + " configKey=" + newInternalConfig.configKey()
                + " uid=" + Integer.toString(newInternalConfig.creatorUid)
                + " name=" + newInternalConfig.creatorName);
        return result;
    }

    /**
     * Add a network or update a network configuration to our database.
     * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
     * network configuration. Otherwise, the networkId should refer to an existing configuration.
     *
     * @param config provided WifiConfiguration object.
     * @param uid    UID of the app requesting the network addition/deletion.
     * @return NetworkUpdateResult object representing status of the update.
     */
    public NetworkUpdateResult addOrUpdateNetwork(WifiConfiguration config, int uid) {
        if (config == null) {
            Log.e(TAG, "Cannot add/update network with null config");
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        NetworkUpdateResult result = addOrUpdateNetworkInternal(config, uid);
        if (!result.isSuccess()) {
            Log.e(TAG, "Failed to add/update network " + config.getPrintableSsid());
            return result;
        }
        WifiConfiguration newConfig = getInternalConfiguredNetwork(result.getNetworkId());
        sendConfiguredNetworkChangedBroadcast(
                newConfig,
                result.isNewNetwork()
                        ? WifiManager.CHANGE_REASON_ADDED
                        : WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        // External modification, persist it immediately.
        saveToStore(true);
        return result;
    }

    /**
     * Removes the specified network configuration from our database.
     *
     * @param config provided WifiConfiguration object.
     * @return true if successful, false otherwise.
     */
    private boolean removeNetworkInternal(WifiConfiguration config) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing network " + config.getPrintableSsid());
        }
        // Remove any associated enterprise keys.
        if (config.enterpriseConfig != null
                && config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {
            mWifiKeyStore.removeKeys(config.enterpriseConfig);
        }

        mConfiguredNetworks.remove(config.networkId);
        mScanDetailCaches.remove(config.networkId);
        // Stage the backup of the SettingsProvider package which backs this up.
        mBackupManagerProxy.notifyDataChanged();

        localLog("removeNetworkInternal: removed config."
                + " netId=" + config.networkId
                + " configKey=" + config.configKey());
        return true;
    }

    /**
     * Removes the specified network configuration from our database.
     *
     * @param networkId network ID of the provided network.
     * @return true if successful, false otherwise.
     */
    public boolean removeNetwork(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        if (!removeNetworkInternal(config)) {
            Log.e(TAG, "Failed to remove network " + config.getPrintableSsid());
            return false;
        }
        sendConfiguredNetworkChangedBroadcast(config, WifiManager.CHANGE_REASON_REMOVED);
        // External modification, persist it immediately.
        saveToStore(true);
        return true;
    }

    /**
     * Helper method to mark a network enabled for network selection.
     */
    private void setNetworkSelectionEnabled(NetworkSelectionStatus status) {
        status.setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_ENABLED);
        status.setDisableTime(
                NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
        status.setNetworkSelectionDisableReason(NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);

        // Clear out all the disable reason counters.
        status.clearDisableReasonCounter();
    }

    /**
     * Helper method to mark a network temporarily disabled for network selection.
     */
    private void setNetworkSelectionTemporarilyDisabled(
            NetworkSelectionStatus status, int disableReason) {
        status.setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        // Only need a valid time filled in for temporarily disabled networks.
        status.setDisableTime(mClock.getElapsedSinceBootMillis());
        status.setNetworkSelectionDisableReason(disableReason);
    }

    /**
     * Helper method to mark a network permanently disabled for network selection.
     */
    private void setNetworkSelectionPermanentlyDisabled(
            NetworkSelectionStatus status, int disableReason) {
        status.setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        status.setDisableTime(
                NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
        status.setNetworkSelectionDisableReason(disableReason);
    }

    /**
     * Helper method to set the publicly exposed status for the network and send out the network
     * status change broadcast.
     */
    private void setNetworkStatus(WifiConfiguration config, int status) {
        config.status = status;
        sendConfiguredNetworkChangedBroadcast(config, WifiManager.CHANGE_REASON_CONFIG_CHANGE);
    }

    /**
     * Sets a network's status (both internal and public) according to the update reason and
     * its current state.
     *
     * This updates the network's {@link WifiConfiguration#mNetworkSelectionStatus} field and the
     * public {@link WifiConfiguration#status} field if the network is either enabled or
     * permanently disabled.
     *
     * @param config network to be updated.
     * @param reason reason code for update.
     * @return true if the input configuration has been updated, false otherwise.
     */
    private boolean setNetworkSelectionStatus(WifiConfiguration config, int reason) {
        NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (reason < 0 || reason >= NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX) {
            Log.e(TAG, "Invalid Network disable reason " + reason);
            return false;
        }
        if (reason == NetworkSelectionStatus.NETWORK_SELECTION_ENABLE) {
            setNetworkSelectionEnabled(networkStatus);
            setNetworkStatus(config, WifiConfiguration.Status.ENABLED);
        } else if (reason < NetworkSelectionStatus.DISABLED_TLS_VERSION_MISMATCH) {
            setNetworkSelectionTemporarilyDisabled(networkStatus, reason);
        } else {
            setNetworkSelectionPermanentlyDisabled(networkStatus, reason);
            setNetworkStatus(config, WifiConfiguration.Status.DISABLED);
        }
        localLog("setNetworkSelectionStatus: configKey=" + config.configKey()
                + " networkStatus=" + networkStatus.getNetworkStatusString() + " disableReason="
                + networkStatus.getNetworkDisableReasonString() + " at="
                + createDebugTimeStampString(mClock.getWallClockMillis()));
        return true;
    }

    /**
     * Update a network's status (both internal and public) according to the update reason and
     * its current state.
     *
     * @param config network to be updated.
     * @param reason reason code for update.
     * @return true if the input configuration has been updated, false otherwise.
     */
    private boolean updateNetworkSelectionStatus(WifiConfiguration config, int reason) {
        NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (reason != NetworkSelectionStatus.NETWORK_SELECTION_ENABLE) {
            networkStatus.incrementDisableReasonCounter(reason);
            // For network disable reasons, we should only update the status if we cross the
            // threshold.
            int disableReasonCounter = networkStatus.getDisableReasonCounter(reason);
            int disableReasonThreshold = NETWORK_SELECTION_DISABLE_THRESHOLD[reason];
            if (disableReasonCounter < disableReasonThreshold) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Disable counter for network " + config.getPrintableSsid()
                            + " for reason "
                            + NetworkSelectionStatus.getNetworkDisableReasonString(reason) + " is "
                            + networkStatus.getDisableReasonCounter(reason) + " and threshold is "
                            + disableReasonThreshold);
                }
                return true;
            }
        }
        return setNetworkSelectionStatus(config, reason);
    }

    /**
     * Update a network's status (both internal and public) according to the update reason and
     * its current state.
     *
     * Each network has 2 status:
     * 1. NetworkSelectionStatus: This is internal selection status of the network. This is used
     * for temporarily disabling a network for QNS.
     * 2. Status: This is the exposed status for a network. This is mostly set by
     * the public API's {@link WifiManager#enableNetwork(int, boolean)} &
     * {@link WifiManager#disableNetwork(int)}.
     *
     * @param networkId network ID of the network that needs the update.
     * @param reason    reason to update the network.
     * @return true if the input configuration has been updated, false otherwise.
     */
    public boolean updateNetworkSelectionStatus(int networkId, int reason) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        return updateNetworkSelectionStatus(config, reason);
    }

    /**
     * Attempt to re-enable a network for network selection, if this network was either:
     * a) Previously temporarily disabled, but its disable timeout has expired, or
     * b) Previously disabled because of a user switch, but is now visible to the current
     * user.
     *
     * @param config configuration for the network to be re-enabled for network selection. The
     *               network corresponding to the config must be visible to the current user.
     * @return true if the network identified by {@param config} was re-enabled for qualified
     * network selection, false otherwise.
     */
    private boolean tryEnableNetwork(WifiConfiguration config) {
        NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (networkStatus.isNetworkTemporaryDisabled()) {
            long timeDifferenceMs =
                    mClock.getElapsedSinceBootMillis() - networkStatus.getDisableTime();
            int disableReason = networkStatus.getNetworkSelectionDisableReason();
            long disableTimeoutMs = NETWORK_SELECTION_DISABLE_TIMEOUT_MS[disableReason];
            if (timeDifferenceMs >= disableTimeoutMs) {
                return updateNetworkSelectionStatus(
                        config, NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
            }
        } else if (networkStatus.isDisabledByReason(
                NetworkSelectionStatus.DISABLED_DUE_TO_USER_SWITCH)) {
            return updateNetworkSelectionStatus(
                    config, NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        }
        return false;
    }

    /**
     * Attempt to re-enable a network for network selection, if this network was either:
     * a) Previously temporarily disabled, but its disable timeout has expired, or
     * b) Previously disabled because of a user switch, but is now visible to the current
     * user.
     *
     * @param networkId the id of the network to be checked for possible unblock (due to timeout)
     * @return true if the network identified by {@param networkId} was re-enabled for qualified
     * network selection, false otherwise.
     */
    public boolean tryEnableNetwork(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        return tryEnableNetwork(config);
    }

    /**
     * Enable a network using the public {@link WifiManager#enableNetwork(int, boolean)} API.
     *
     * @param networkId network ID of the network that needs the update.
     * @param uid       uid of the app requesting the update.
     * @return true if it succeeds, false otherwise
     */
    public boolean enableNetwork(int networkId, int uid) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        if (!canModifyNetwork(config, uid, DISALLOW_LOCKDOWN_CHECK_BYPASS)) {
            Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                    + config.configKey());
            return false;
        }
        return updateNetworkSelectionStatus(
                networkId, WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
    }

    /**
     * Disable a network using the public {@link WifiManager#disableNetwork(int)} API.
     *
     * @param networkId network ID of the network that needs the update.
     * @param uid       uid of the app requesting the update.
     * @return true if it succeeds, false otherwise
     */
    public boolean disableNetwork(int networkId, int uid) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        if (!canModifyNetwork(config, uid, DISALLOW_LOCKDOWN_CHECK_BYPASS)) {
            Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                    + config.configKey());
            return false;
        }
        return updateNetworkSelectionStatus(
                networkId, NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER);
    }

    /**
     * Checks if the |uid| has the necessary permission to override wifi config and updates the last
     * connected UID for the provided configuration.
     *
     * @param networkId network ID corresponding to the network.
     * @param uid       uid of the app requesting the connection.
     * @return true if |uid| has the necessary permission to trigger connection to the
     * network, false otherwise.
     */
    public boolean checkAndUpdateLastConnectUid(int networkId, int uid) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        if (!canModifyNetwork(config, uid, ALLOW_LOCKDOWN_CHECK_BYPASS)) {
            Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                    + config.configKey());
            return false;
        }
        config.lastConnectUid = uid;
        return true;
    }

    /**
     * Updates a network configuration after a successful connection to it.
     * This method updates the following WifiConfiguration elements:
     * 1. Set the |lastConnected| timestamp.
     * 2. Increment |numAssociation| counter.
     * 3. Clear the disable reason counters in the associated |NetworkSelectionStatus|.
     * 4. Set the hasEverConnected| flag in the associated |NetworkSelectionStatus|.
     *
     * @param networkId network ID corresponding to the network.
     * @return true if the network was found, false otherwise.
     */
    public boolean updateNetworkAfterConnect(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        config.lastConnected = mClock.getWallClockMillis();
        config.numAssociation++;
        config.getNetworkSelectionStatus().clearDisableReasonCounter();
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        return true;
    }

    /**
     * Helper method to get the scan detail cache entry {@link #mScanDetailCaches} for the provided
     * network.
     *
     * @param networkId network ID corresponding to the network.
     * @return existing {@link ScanDetailCache} entry if one exists or null.
     */
    @VisibleForTesting
    public ScanDetailCache getScanDetailCacheForNetwork(int networkId) {
        return mScanDetailCaches.get(networkId);
    }

    /**
     * Helper method to get or create a scan detail cache entry {@link #mScanDetailCaches} for
     * the provided network.
     *
     * @param config configuration corresponding to the the network.
     * @return existing {@link ScanDetailCache} entry if one exists or a new instance created for
     * this network.
     */
    private ScanDetailCache getOrCreateScanDetailCacheForNetwork(WifiConfiguration config) {
        if (config == null) return null;
        ScanDetailCache cache = getScanDetailCacheForNetwork(config.networkId);
        if (cache == null && config.networkId != WifiConfiguration.INVALID_NETWORK_ID) {
            cache =
                    new ScanDetailCache(
                            config, SCAN_CACHE_ENTRIES_MAX_SIZE, SCAN_CACHE_ENTRIES_TRIM_SIZE);
            mScanDetailCaches.put(config.networkId, cache);
        }
        return cache;
    }

    /**
     * Saves the provided ScanDetail into the corresponding scan detail cache entry
     * {@link #mScanDetailCaches} for the provided network.
     *
     * @param config     configuration corresponding to the the network.
     * @param scanDetail new scan detail instance to be saved into the cache.
     */
    private void saveToScanDetailCacheForNetwork(
            WifiConfiguration config, ScanDetail scanDetail) {
        ScanResult scanResult = scanDetail.getScanResult();

        ScanDetailCache scanDetailCache = getOrCreateScanDetailCacheForNetwork(config);
        if (scanDetailCache == null) {
            Log.e(TAG, "Could not allocate scan cache for " + config.getPrintableSsid());
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

        // Add the scan detail to this network's scan detail cache.
        scanDetailCache.put(scanDetail);

        // Since we added a scan result to this configuration, re-attempt linking
        // TODO: linkConfiguration(config);
    }

    /**
     * Retrieves a saved network corresponding to the provided scan detail if one exists.
     *
     * @param scanDetail ScanDetail instance  to use for looking up the network.
     * @return WifiConfiguration object representing the network corresponding to the scanDetail,
     * null if none exists.
     */
    private WifiConfiguration getSavedNetworkForScanDetail(ScanDetail scanDetail) {
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) {
            Log.e(TAG, "No scan result found in scan detail");
            return null;
        }
        // Add the double quotes to the scan result SSID for comparison with the network configs.
        String ssidToCompare = "\"" + scanResult.SSID + "\"";
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            if (config.SSID == null || !config.SSID.equals(ssidToCompare)) {
                continue;
            }
            if (ScanResultUtil.doesScanResultEncryptionMatchWithNetwork(scanResult, config)) {
                localLog("getSavedNetworkFromScanDetail: Found " + config.configKey()
                        + " for " + scanResult.SSID + "[" + scanResult.capabilities + "]");
                return config;
            }
        }
        return null;
    }

    /**
     * Retrieves a saved network corresponding to the provided scan detail if one exists and caches
     * the provided |scanDetail| into the corresponding scan detail cache entry
     * {@link #mScanDetailCaches} for the retrieved network.
     *
     * @param scanDetail input a scanDetail from the scan result
     * @return WifiConfiguration object representing the network corresponding to the scanDetail,
     * null if none exists.
     */
    public WifiConfiguration getSavedNetworkForScanDetailAndCache(ScanDetail scanDetail) {
        WifiConfiguration network = getSavedNetworkForScanDetail(scanDetail);
        if (network == null) {
            return null;
        }
        saveToScanDetailCacheForNetwork(network, scanDetail);
        return getConfiguredNetworkWithPassword(network.networkId);
    }

    /**
     * Helper method to clear the {@link NetworkSelectionStatus#mCandidate},
     * {@link NetworkSelectionStatus#mCandidateScore} &
     * {@link NetworkSelectionStatus#mSeenInLastQualifiedNetworkSelection} for the provided network.
     *
     * This is invoked by QNS at the start of every selection procedure to clear all configured
     * networks' scan-result-candidates.
     *
     * @param networkId  network ID corresponding to the network.
     * @return true if the network was found, false otherwise.
     */
    public boolean clearNetworkCandidateScanResult(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        config.getNetworkSelectionStatus().setCandidate(null);
        config.getNetworkSelectionStatus().setCandidateScore(Integer.MIN_VALUE);
        config.getNetworkSelectionStatus().setSeenInLastQualifiedNetworkSelection(false);
        return true;
    }

    /**
     * Helper method to set the {@link NetworkSelectionStatus#mCandidate},
     * {@link NetworkSelectionStatus#mCandidateScore} &
     * {@link NetworkSelectionStatus#mSeenInLastQualifiedNetworkSelection} for the provided network.
     *
     * This is invoked by QNS when it sees a network during network selection procedure to set the
     * scan result candidate.
     *
     * @param networkId  network ID corresponding to the network.
     * @param scanResult Candidate ScanResult associated with this network.
     * @param score      Score assigned to the candidate.
     * @return true if the network was found, false otherwise.
     */
    public boolean setNetworkCandidateScanResult(int networkId, ScanResult scanResult, int score) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        config.getNetworkSelectionStatus().setCandidate(scanResult);
        config.getNetworkSelectionStatus().setCandidateScore(score);
        // Update the network selection status.
        config.getNetworkSelectionStatus().setSeenInLastQualifiedNetworkSelection(true);
        return true;
    }

    /**
     * Retrieves an updated list of priorities for all the saved networks before
     * enabling disconnected/connected PNO.
     *
     * PNO network list sent to the firmware has limited size. If there are a lot of saved
     * networks, this list will be truncated and we might end up not sending the networks
     * with the highest chance of connecting to the firmware.
     * So, re-sort the network list based on the frequency of connection to those networks
     * and whether it was last seen in the scan results.
     *
     * TODO (b/30399964): Recalculate the list whenever network status changes.
     * @return list of networks with updated priorities.
     */
    public ArrayList<WifiScanner.PnoSettings.PnoNetwork> retrievePnoNetworkList() {
        ArrayList<WifiScanner.PnoSettings.PnoNetwork> pnoList = new ArrayList<>();
        ArrayList<WifiConfiguration> wifiConfigurations =
                new ArrayList<>(getInternalConfiguredNetworks());
        // Remove any permanently disabled networks.
        Iterator<WifiConfiguration> iter = wifiConfigurations.iterator();
        while (iter.hasNext()) {
            WifiConfiguration config = iter.next();
            if (config.getNetworkSelectionStatus().isNetworkPermanentlyDisabled()) {
                iter.remove();
            }
        }
        Collections.sort(wifiConfigurations, sPnoListComparator);
        // Let's use the network list size - 1 as the highest priority and then go down from there.
        // So, the most frequently connected network has the highest priority now.
        int priority = wifiConfigurations.size() - 1;
        for (WifiConfiguration config : wifiConfigurations) {
            pnoList.add(WifiConfigurationUtil.createPnoNetwork(config, priority));
            priority--;
        }
        return pnoList;
    }

    /**
     * Read the config store and load the in-memory lists from the store data retrieved.
     * This reads all the network configurations from:
     * 1. Shared WifiConfigStore.xml
     * 2. User WifiConfigStore.xml
     * 3. PerProviderSubscription.conf
     */
    private void loadFromStore() {
        WifiConfigStoreData storeData;

        long readStartTime = mClock.getElapsedSinceBootMillis();
        try {
            storeData = mWifiConfigStore.read();
        } catch (Exception e) {
            Log.wtf(TAG, "Reading from new store failed " + e + ". All saved networks are lost!");
            return;
        }
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;
        Log.d(TAG, "Loading from store completed in " + readTime + " ms.");

        // Clear out all the existing in-memory lists and load the lists from what was retrieved
        // from the config store.
        mConfiguredNetworks.clear();
        mDeletedEphemeralSSIDs.clear();
        mScanDetailCaches.clear();
        for (WifiConfiguration configuration : storeData.configurations) {
            configuration.networkId = mLastNetworkId++;
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Adding network from store " + configuration.configKey());
            }
            mConfiguredNetworks.put(configuration);
        }
        for (String ssid : storeData.deletedEphemeralSSIDs) {
            mDeletedEphemeralSSIDs.add(ssid);
        }
        if (mConfiguredNetworks.sizeForAllUsers() == 0) {
            Log.w(TAG, "No stored networks found.");
        }
    }

    /**
     * Save the current snapshot of the in-memory lists to the config store.
     *
     * @param forceWrite Whether the write needs to be forced or not.
     * @return Whether the write was successful or not, this is applicable only for force writes.
     */
    private boolean saveToStore(boolean forceWrite) {
        ArrayList<WifiConfiguration> configurations = new ArrayList<>();
        // Don't persist ephemeral networks to store.
        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            if (!config.ephemeral) {
                configurations.add(config);
            }
        }
        WifiConfigStoreData storeData =
                new WifiConfigStoreData(configurations, mDeletedEphemeralSSIDs);

        long writeStartTime = mClock.getElapsedSinceBootMillis();
        try {
            mWifiConfigStore.write(forceWrite, storeData);
        } catch (Exception e) {
            Log.wtf(TAG, "Writing to store failed " + e + ". Saved networks maybe lost!");
            return false;
        }
        long writeTime = mClock.getElapsedSinceBootMillis() - writeStartTime;
        Log.d(TAG, "Writing to store completed in " + writeTime + " ms.");
        return true;
    }

    /**
     * Helper method for logging into local log buffer.
     */
    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(s);
        }
    }

    /**
     * Dump the local log buffer.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConfigManager");
        pw.println("WifiConfigManager - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiConfigManager - Log End ----");
    }
}
