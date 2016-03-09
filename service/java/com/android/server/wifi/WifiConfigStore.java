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

import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.Status;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.os.FileObserver;
import android.os.Process;
import android.os.SystemClock;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.wifi.hotspot2.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

/**
 * This class provides the API's to save/load/modify network configurations from a persistent
 * config database.
 * We use wpa_supplicant as our config database currently, but will be migrating to a different
 * one sometime in the future.
 * We use keystore for certificate/key management operations.
 *
 * NOTE: This class should only be used from WifiConfigManager!!!
 */
public class WifiConfigStore {

    public static final String TAG = "WifiConfigStore";
    // This is the only variable whose contents will not be interpreted by wpa_supplicant. We use it
    // to store metadata that allows us to correlate a wpa_supplicant.conf entry with additional
    // information about the same network stored in other files. The metadata is stored as a
    // serialized JSON dictionary.
    public static final String ID_STRING_VAR_NAME = "id_str";
    public static final String ID_STRING_KEY_FQDN = "fqdn";
    public static final String ID_STRING_KEY_CREATOR_UID = "creatorUid";
    public static final String ID_STRING_KEY_CONFIG_KEY = "configKey";
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
    public static final String EMPTY_VALUE = "NULL";

    public static final String SUPPLICANT_CONFIG_FILE = "/data/misc/wifi/wpa_supplicant.conf";
    public static final String SUPPLICANT_CONFIG_FILE_BACKUP = SUPPLICANT_CONFIG_FILE + ".tmp";

    private static final boolean DBG = true;
    private static boolean VDBG = false;

    private final LocalLog mLocalLog;
    private final WpaConfigFileObserver mFileObserver;
    private final WifiNative mWifiNative;
    private final KeyStore mKeyStore;
    private final boolean mShowNetworks;
    private final HashSet<String> mBssidBlacklist = new HashSet<String>();

    WifiConfigStore(WifiNative wifiNative, KeyStore keyStore, LocalLog localLog,
            boolean showNetworks, boolean verboseDebug) {
        mWifiNative = wifiNative;
        mKeyStore = keyStore;
        mShowNetworks = showNetworks;

        if (mShowNetworks) {
            mLocalLog = localLog;
            mFileObserver = new WpaConfigFileObserver();
            mFileObserver.startWatching();
        } else {
            mLocalLog = null;
            mFileObserver = null;
        }
        VDBG = verboseDebug;
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

    /*
     * Convert string to Hexadecimal before passing to wifi native layer
     * In native function "doCommand()" have trouble in converting Unicode character string to UTF8
     * conversion to hex is required because SSIDs can have space characters in them;
     * and that can confuses the supplicant because it uses space charaters as delimiters
     */
    private static String encodeSSID(String str) {
        return Utils.toHex(removeDoubleQuotes(str).getBytes(StandardCharsets.UTF_8));
    }

    // Certificate and private key management for EnterpriseConfig
    private static boolean needsKeyStore(WifiEnterpriseConfig config) {
        return (!(config.getClientCertificate() == null && config.getCaCertificate() == null));
    }

    private static boolean isHardwareBackedKey(PrivateKey key) {
        return KeyChain.isBoundKeyAlgorithm(key.getAlgorithm());
    }

    private static boolean hasHardwareBackedKey(Certificate certificate) {
        return KeyChain.isBoundKeyAlgorithm(certificate.getPublicKey().getAlgorithm());
    }

    private static boolean needsSoftwareBackedKeyStore(WifiEnterpriseConfig config) {
        java.lang.String client = config.getClientCertificateAlias();
        if (!TextUtils.isEmpty(client)) {
            // a valid client certificate is configured

            // BUGBUG: keyStore.get() never returns certBytes; because it is not
            // taking WIFI_UID as a parameter. It always looks for certificate
            // with SYSTEM_UID, and never finds any Wifi certificates. Assuming that
            // all certificates need software keystore until we get the get() API
            // fixed.
            return true;
        }
        return false;
    }

    private int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++) {
            if (string.equals(strings[i])) {
                return i;
            }
        }
        loge("Failed to look-up a string: " + string);
        return -1;
    }

    private void readNetworkBitsetVariable(int netId, BitSet variable, String varName,
            String[] strings) {
        String value = mWifiNative.getNetworkVariable(netId, varName);
        if (!TextUtils.isEmpty(value)) {
            variable.clear();
            String[] vals = value.split(" ");
            for (String val : vals) {
                int index = lookupString(val, strings);
                if (0 <= index) {
                    variable.set(index);
                }
            }
        }
    }

    /**
     * Migrates the old style TLS config to the new config style. This should only be used
     * when restoring an old wpa_supplicant.conf or upgrading from a previous
     * platform version.
     *
     * @return true if the config was updated
     * @hide
     */
    private boolean migrateOldEapTlsNative(WifiEnterpriseConfig config, int netId) {
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

    /**
     * Migrate certs from global pool to wifi UID if not already done
     */
    private void migrateCerts(WifiEnterpriseConfig config) {
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

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    public void readNetworkVariables(WifiConfiguration config) {
        if (config == null) {
            return;
        }
        if (VDBG) localLog("readNetworkVariables: " + config.networkId);
        int netId = config.networkId;
        if (netId < 0) {
            return;
        }
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
    }

    /**
     * Load all the configured networks from wpa_supplicant.
     *
     * @param configs       Map of configuration key to configuration objects corresponding to all
     *                      the networks.
     * @param networkExtras Map of extra configuration parameters stored in wpa_supplicant.conf
     * @return Max priority of all the configs.
     */
    public int loadNetworks(Map<String, WifiConfiguration> configs,
            SparseArray<Map<String, String>> networkExtras) {
        int lastPriority = 0;
        int last_id = -1;
        boolean done = false;
        while (!done) {
            String listStr = mWifiNative.listNetworks(last_id);
            if (listStr == null) {
                return lastPriority;
            }
            String[] lines = listStr.split("\n");
            if (mShowNetworks) {
                localLog("loadNetworks:  ");
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
                } catch (NumberFormatException e) {
                    loge("Failed to read network-id '" + result[0] + "'");
                    continue;
                }
                // Ignore the supplicant status, start all networks disabled.
                config.status = WifiConfiguration.Status.DISABLED;
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
                    /* TODO(rpius)
                    if (mDeletedSSIDs.contains(d)) {
                        loge(" got CRC for SSID " + config.SSID + " -> " + d + ", was deleted");
                    } */
                }
                if (config.priority > lastPriority) {
                    lastPriority = config.priority;
                }
                config.setIpAssignment(IpAssignment.DHCP);
                config.setProxySettings(ProxySettings.NONE);
                if (!WifiServiceImpl.isValid(config)) {
                    if (mShowNetworks) {
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
                    if (mShowNetworks) {
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
        return lastPriority;
    }

    /**
     * Install keys for given enterprise network.
     *
     * @param existingConfig Existing config corresponding to the network already stored in our
     *                       database. This maybe null if it's a new network.
     * @param config         Config corresponding to the network.
     * @return true if successful, false otherwise.
     */
    private boolean installKeys(WifiEnterpriseConfig existingConfig, WifiEnterpriseConfig config,
            String name) {
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

            if (!ret) {
                return ret;
            }

            ret = putCertInKeyStore(userCertName, config.getClientCertificate());
            if (!ret) {
                // Remove private key installed
                mKeyStore.delete(privKeyName, Process.WIFI_UID);
                return ret;
            }
        }

        X509Certificate[] caCertificates = config.getCaCertificates();
        Set<String> oldCaCertificatesToRemove = new ArraySet<String>();
        if (existingConfig != null && existingConfig.getCaCertificateAliases() != null) {
            oldCaCertificatesToRemove.addAll(
                    Arrays.asList(existingConfig.getCaCertificateAliases()));
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

    /**
     * Remove enterprise keys from the network config.
     *
     * @param config Config corresponding to the network.
     */
    private void removeKeys(WifiEnterpriseConfig config) {
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
            for (String ca : aliases) {
                if (!TextUtils.isEmpty(ca)) {
                    if (DBG) Log.d(TAG, "removing CA cert: " + ca);
                    mKeyStore.delete(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
                }
            }
        }
    }

    /**
     * Save an entire network configuration to wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @param netId  Net Id of the network.
     * @return true if successful, false otherwise.
     */
    private boolean saveNetwork(WifiConfiguration config, int netId) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("saveNetwork: " + netId);
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
        return true;
    }

    /**
     * Update/Install keys for given enterprise network.
     *
     * @param config         Config corresponding to the network.
     * @param existingConfig Existing config corresponding to the network already stored in our
     *                       database. This maybe null if it's a new network.
     * @return true if successful, false otherwise.
     */
    private boolean updateNetworkKeys(WifiConfiguration config, WifiConfiguration existingConfig) {
        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;
        if (needsKeyStore(enterpriseConfig)) {
            try {
                /* config passed may include only fields being updated.
                 * In order to generate the key id, fetch uninitialized
                 * fields from the currently tracked configuration
                 */
                String keyId = config.getKeyIdForCredentials(existingConfig);

                if (!installKeys(existingConfig != null
                        ? existingConfig.enterpriseConfig : null, enterpriseConfig, keyId)) {
                    loge(config.SSID + ": failed to install keys");
                    return false;
                }
            } catch (IllegalStateException e) {
                loge(config.SSID + " invalid config for key installation");
                return false;
            }
        }
        if (!enterpriseConfig.saveToSupplicant(
                new SupplicantSaver(config.networkId, config.SSID))) {
            removeKeys(enterpriseConfig);
            return false;
        }
        return true;
    }

    /**
     * Add or update a network configuration to wpa_supplicant.
     *
     * @param config         Config corresponding to the network.
     * @param existingConfig Existing config corresponding to the network saved in our database.
     * @return true if successful, false otherwise.
     */
    public boolean addOrUpdateNetwork(WifiConfiguration config, WifiConfiguration existingConfig) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("addOrUpdateNetwork: " + config.networkId);
        int netId = config.networkId;
        boolean newNetwork = false;
        /*
         * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */
        if (netId == WifiConfiguration.INVALID_NETWORK_ID) {
            newNetwork = true;
            netId = mWifiNative.addNetwork();
            if (netId < 0) {
                loge("Failed to add a network!");
                return false;
            } else {
                logi("addOrUpdateNetwork created netId=" + netId);
            }
            // Save the new network ID to the config
            config.networkId = netId;
        }
        if (!saveNetwork(config, netId)) {
            if (newNetwork) {
                mWifiNative.removeNetwork(netId);
                loge("Failed to set a network variable, removed network: " + netId);
            }
            return false;
        }
        if (config.enterpriseConfig != null
                && config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {
            return updateNetworkKeys(config, existingConfig);
        }
        return true;
    }

    /**
     * Remove the specified network and save config
     *
     * @param config Config corresponding to the network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean removeNetwork(WifiConfiguration config) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("removeNetwork: " + config.networkId);
        if (!mWifiNative.removeNetwork(config.networkId)) {
            loge("Remove network in wpa_supplicant failed on " + config.networkId);
            return false;
        }
        // Remove any associated keys
        if (config.enterpriseConfig != null) {
            removeKeys(config.enterpriseConfig);
        }
        return true;
    }

    /**
     * Enable a network in wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return true if successful, false otherwise.
     */
    public boolean enableNetwork(WifiConfiguration config) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("enableNetwork: " + config.networkId);
        if (!mWifiNative.enableNetworkWithoutConnect(config.networkId)) {
            loge("Enable network in wpa_supplicant failed on " + config.networkId);
            return false;
        }
        config.status = Status.ENABLED;
        return true;
    }

    /**
     * Select a network in wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return true if successful, false otherwise.
     */
    public boolean selectNetwork(WifiConfiguration config, Collection<WifiConfiguration> configs) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("selectNetwork: " + config.networkId);
        if (!mWifiNative.selectNetwork(config.networkId)) {
            loge("Select network in wpa_supplicant failed on " + config.networkId);
            return false;
        }
        config.status = Status.ENABLED;
        markAllNetworksDisabledExcept(config.networkId, configs);
        return true;
    }

    /**
     * Disable a network in wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return true if successful, false otherwise.
     */
    boolean disableNetwork(WifiConfiguration config) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("disableNetwork: " + config.networkId);
        if (!mWifiNative.disableNetwork(config.networkId)) {
            loge("Disable network in wpa_supplicant failed on " + config.networkId);
            return false;
        }
        config.status = Status.DISABLED;
        return true;
    }

    /**
     * Set priority for a network in wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return true if successful, false otherwise.
     */
    public boolean setNetworkPriority(WifiConfiguration config, int priority) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("setNetworkPriority: " + config.networkId);
        if (!mWifiNative.setNetworkVariable(config.networkId,
                WifiConfiguration.priorityVarName, Integer.toString(priority))) {
            loge("Set priority of network in wpa_supplicant failed on " + config.networkId);
            return false;
        }
        config.priority = priority;
        return true;
    }

    /**
     * Set SSID for a network in wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return true if successful, false otherwise.
     */
    public boolean setNetworkSSID(WifiConfiguration config, String ssid) {
        if (config == null) {
            return false;
        }
        if (VDBG) localLog("setNetworkSSID: " + config.networkId);
        if (!mWifiNative.setNetworkVariable(config.networkId, WifiConfiguration.ssidVarName,
                encodeSSID(ssid))) {
            loge("Set SSID of network in wpa_supplicant failed on " + config.networkId);
            return false;
        }
        config.SSID = ssid;
        return true;
    }

    /**
     * Set BSSID for a network in wpa_supplicant from network selection.
     *
     * @param config Config corresponding to the network.
     * @param bssid  BSSID to be set.
     * @return true if successful, false otherwise.
     */
    public boolean setNetworkBSSID(WifiConfiguration config, String bssid) {
        // Sanity check the config is valid
        if (config == null
                || (config.networkId == WifiConfiguration.INVALID_NETWORK_ID
                && config.SSID == null)) {
            return false;
        }
        if (VDBG) localLog("setNetworkBSSID: " + config.networkId);
        if (!mWifiNative.setNetworkVariable(config.networkId, WifiConfiguration.bssidVarName,
                bssid)) {
            loge("Set BSSID of network in wpa_supplicant failed on " + config.networkId);
            return false;
        }
        config.getNetworkSelectionStatus().setNetworkSelectionBSSID(bssid);
        return true;
    }

    /**
     * Enable/Disable HS20 parameter in wpa_supplicant.
     *
     * @param enable Enable/Disable the parameter.
     */
    public void enableHS20(boolean enable) {
        mWifiNative.setHs20(enable);
    }

    /**
     * Enables all the networks in the provided list in wpa_supplicant.
     *
     * @param configs Collection of configs which needs to be enabled.
     * @return true if successful, false otherwise.
     */
    public boolean enableAllNetworks(Collection<WifiConfiguration> configs) {
        if (VDBG) localLog("enableAllNetworksNative");
        boolean networkEnabled = false;
        for (WifiConfiguration config : configs) {
            if (config != null && !config.ephemeral
                    && !config.getNetworkSelectionStatus().isNetworkEnabled()) {
                if (enableNetwork(config)) {
                    networkEnabled = true;
                }
            }
        }
        saveConfig();
        return networkEnabled;
    }

    /**
     * Disables all the networks in the provided list in wpa_supplicant.
     *
     * @param configs Collection of configs which needs to be enabled.
     * @return true if successful, false otherwise.
     */
    public boolean disableAllNetworks(Collection<WifiConfiguration> configs) {
        if (VDBG) localLog("disableAllNetworks");
        boolean networkDisabled = false;
        for (WifiConfiguration enabled : configs) {
            if (disableNetwork(enabled)) {
                networkDisabled = true;
            }
        }
        saveConfig();
        return networkDisabled;
    }

    /**
     * Save the current configuration to wpa_supplicant.conf.
     */
    public boolean saveConfig() {
        return mWifiNative.saveConfig();
    }

    /**
     * Read network variables from wpa_supplicant.conf.
     *
     * @param key The parameter to be parsed.
     * @return Map of corresponding ssid to the value of the param requested.
     */
    public Map<String, String> readNetworkVariablesFromSupplicantFile(String key) {
        // TODO(b/26733972): This method assumes that the SSID is a unique identifier for network
        // configurations. That is wrong. There may be any number of networks with the same SSID.
        // There may also be any number of network configurations for the same network. The correct
        // unique identifier is the configKey. This method should be switched from SSID to configKey
        // (which is either stored in wpa_supplicant.conf directly or can be computed from the
        // information found in that file).
        Map<String, String> result = new HashMap<>();
        BufferedReader reader = null;
        if (VDBG) localLog("readNetworkVariablesFromSupplicantFile key=" + key);
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

    /**
     * Read network variable from wpa_supplicant.conf
     *
     * @param ssid SSID of the corresponding network
     * @param key  key of the variable to be read.
     * @return Value corresponding to the key in conf file.
     */
    public String readNetworkVariableFromSupplicantFile(String ssid, String key) {
        long start = SystemClock.elapsedRealtimeNanos();
        Map<String, String> data = readNetworkVariablesFromSupplicantFile(key);
        long end = SystemClock.elapsedRealtimeNanos();

        if (VDBG) {
            localLog("readNetworkVariableFromSupplicantFile ssid=[" + ssid + "] key=" + key
                    + " duration=" + (long) (end - start));
        }
        return data.get(ssid);
    }

    /**
     * Checks if the network is a sim config.
     *
     * @param config Config corresponding to the network.
     * @return true if it is a sim config, false otherwise.
     */
    public boolean isSimConfig(WifiConfiguration config) {
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

    /**
     * Resets all sim networks from the provided network list.
     *
     * @param configs List of all the networks.
     */
    public void resetSimNetworks(Collection<WifiConfiguration> configs) {
        if (VDBG) localLog("resetSimNetworks");
        for (WifiConfiguration config : configs) {
            if (isSimConfig(config)) {
                /* This configuration may have cached Pseudonym IDs; lets remove them */
                mWifiNative.setNetworkVariable(config.networkId, "identity", "NULL");
                mWifiNative.setNetworkVariable(config.networkId, "anonymous_identity", "NULL");
            }
        }
    }

    /**
     * Clear BSSID blacklist in wpa_supplicant.
     */
    public void clearBssidBlacklist() {
        if (VDBG) localLog("clearBlacklist");
        mBssidBlacklist.clear();
        mWifiNative.clearBlacklist();
        mWifiNative.setBssidBlacklist(null);
    }

    /**
     * Add a BSSID to the blacklist.
     *
     * @param bssid bssid to be added.
     */
    public void blackListBssid(String bssid) {
        if (bssid == null) {
            return;
        }
        if (VDBG) localLog("blackListBssid: " + bssid);
        mBssidBlacklist.add(bssid);
        // Blacklist at wpa_supplicant
        mWifiNative.addToBlacklist(bssid);
        // Blacklist at firmware
        String[] list = mBssidBlacklist.toArray(new String[mBssidBlacklist.size()]);
        mWifiNative.setBssidBlacklist(list);
    }

    /**
     * Checks if the provided bssid is blacklisted or not.
     *
     * @param bssid bssid to be checked.
     * @return true if present, false otherwise.
     */
    public boolean isBssidBlacklisted(String bssid) {
        return mBssidBlacklist.contains(bssid);
    }

    /* Mark all networks except specified netId as disabled */
    private void markAllNetworksDisabledExcept(int netId, Collection<WifiConfiguration> configs) {
        for (WifiConfiguration config : configs) {
            if (config != null && config.networkId != netId) {
                if (config.status != Status.DISABLED) {
                    config.status = Status.DISABLED;
                }
            }
        }
    }

    private void markAllNetworksDisabled(Collection<WifiConfiguration> configs) {
        markAllNetworksDisabledExcept(WifiConfiguration.INVALID_NETWORK_ID, configs);
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the access point
     *
     * @param config WPS configuration
     * @return Wps result containing status and pin
     */
    public WpsResult startWpsWithPinFromAccessPoint(WpsInfo config,
            Collection<WifiConfiguration> configs) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsRegistrar(config.BSSID, config.pin)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled(configs);
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
     *
     * @return WpsResult indicating status and pin
     */
    public WpsResult startWpsWithPinFromDevice(WpsInfo config,
            Collection<WifiConfiguration> configs) {
        WpsResult result = new WpsResult();
        result.pin = mWifiNative.startWpsPinDisplay(config.BSSID);
        /* WPS leaves all networks disabled */
        if (!TextUtils.isEmpty(result.pin)) {
            markAllNetworksDisabled(configs);
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS push button configuration
     *
     * @param config WPS configuration
     * @return WpsResult indicating status and pin
     */
    public WpsResult startWpsPbc(WpsInfo config,
            Collection<WifiConfiguration> configs) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsPbc(config.BSSID)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled(configs);
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS push button configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    protected void logd(String s) {
        Log.d(TAG, s);
    }

    protected void logi(String s) {
        Log.i(TAG, s);
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

    protected void log(String s) {
        Log.d(TAG, s);
    }

    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(TAG + ": " + s);
        }
    }

    private void localLogAndLogcat(String s) {
        localLog(s);
        Log.d(TAG, s);
    }

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

        /**
         * Returns true if a particular config key needs to be quoted when passed to the supplicant.
         */
        private boolean enterpriseConfigKeyShouldBeQuoted(String key) {
            switch (key) {
                case WifiEnterpriseConfig.EAP_KEY:
                case WifiEnterpriseConfig.ENGINE_KEY:
                    return false;
                default:
                    return true;
            }
        }
    }

    // TODO(rpius): Remove this.
    private class WpaConfigFileObserver extends FileObserver {

        WpaConfigFileObserver() {
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
}
