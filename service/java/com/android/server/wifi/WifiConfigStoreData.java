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

import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.util.Pair;

import com.android.server.wifi.util.XmlUtil;
import com.android.server.wifi.util.XmlUtil.IpConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.NetworkSelectionStatusXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiEnterpriseConfigXmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Class to encapsulate all the data to be stored across all the stores. This is a snapshot
 * of all the settings passed from {@link WifiConfigManager} to persistent store.
 * Instances of this class are passed from/to WifiConfigManager and WifiConfigStore for
 * writing/parsing data to/from the store files.
 *
 * Note: Nesting of objects during serialization makes it hard to deserialize data especially
 * when we have elements added to the parent object in future revisions. So, when we serialize
 * {@link WifiConfiguration} objects (representing saved networks), we add separate sections in the
 * XML for each nested object (such as {@link IpConfiguration} and {@link NetworkSelectionStatus})
 * within WifiConfiguration object.
 */
public class WifiConfigStoreData {
    /**
     * Current config store data version. This will be incremented for any additions.
     */
    private static final int CURRENT_CONFIG_STORE_DATA_VERSION = 1;

    /** This list of older versions will be used to restore data from older config store. */
    /**
     * First version of the config store data format.
     */
    private static final int INITIAL_CONFIG_STORE_DATA_VERSION = 1;

    /**
     * List of XML section header tags in the config store data.
     */
    private static final String XML_TAG_DOCUMENT_HEADER = "WifiConfigStoreData";
    private static final String XML_TAG_VERSION = "Version";
    private static final String XML_TAG_SECTION_HEADER_NETWORK_LIST = "NetworkList";
    private static final String XML_TAG_SECTION_HEADER_NETWORK = "Network";
    private static final String XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION = "WifiConfiguration";
    private static final String XML_TAG_SECTION_HEADER_NETWORK_STATUS = "NetworkStatus";
    private static final String XML_TAG_SECTION_HEADER_IP_CONFIGURATION = "IpConfiguration";
    private static final String XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION =
            "WifiEnterpriseConfiguration";
    private static final String XML_TAG_SECTION_HEADER_BSSID_BLACKLIST = "BSSIDBlacklist";
    private static final String XML_TAG_SECTION_HEADER_DELETED_EPHEMERAL_SSID_LIST =
            "DeletedEphemeralSSIDList";
    private static final String XML_TAG_SECTION_HEADER_LAST_NETWORK_ID = "LastNetworkId";

    /**
     * List of saved networks visible to the current user to be stored (includes shared & private).
     */
    public List<WifiConfiguration> configurations;
    /**
     * List of blacklist bssids to be stored.
     */
    public Set<String> blackListBSSIDs;
    /**
     * List of deleted ephemeral ssids to be stored.
     */
    public Set<String> deletedEphemeralSSIDs;
    /**
     * Last network ID assigned.
     */
    public int lastNetworkId;

    /**
     * Create a new instance of store data to be written to the store files.
     *
     * @param configurations        list of saved networks to be stored.
     *                              See {@link WifiConfigManager#mConfiguredNetworks}.
     * @param blacklistBSSIDs       list of blacklist bssids to be stored.
     *                              See {@link WifiConfigStore#mBssidBlacklist}
     *                              (TODO: Move this member to WifiConfigManager as well).
     * @param deletedEphemeralSSIDs list of deleted ephemeral ssids to be stored.
     *                              See {@link WifiConfigManager#mDeletedEphemeralSSIDs}
     * @param lastNetworkId         last network ID assigned.
     */
    public WifiConfigStoreData(List<WifiConfiguration> configurations, Set<String> blacklistBSSIDs,
            Set<String> deletedEphemeralSSIDs, int lastNetworkId) {
        this.configurations = configurations;
        this.blackListBSSIDs = blacklistBSSIDs;
        this.deletedEphemeralSSIDs = deletedEphemeralSSIDs;
        this.lastNetworkId = lastNetworkId;
    }

    /**
     * Create a new instance of the store data parsed from the store file data.
     *
     * @param sharedDataBytes raw data retrieved from the shared store file.
     * @param userDataBytes   raw data retrieved from the user store file.
     * @return new instance of store data.
     */
    public static WifiConfigStoreData parseRawData(byte[] sharedDataBytes, byte[] userDataBytes) {
        SharedData sharedData = SharedData.parseRawData(sharedDataBytes);
        UserData userData = UserData.parseRawData(userDataBytes);

        return getStoreData(sharedData, userData);
    }

    /**
     * Create a WifiConfigStoreData instance from the retrieved UserData & SharedData instance.
     */
    private static WifiConfigStoreData getStoreData(SharedData sharedData, UserData userData) {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.addAll(sharedData.configurations);
        configurations.addAll(userData.configurations);
        return new WifiConfigStoreData(
                configurations, userData.blackListBSSIDs,
                userData.deletedEphemeralSSIDs, sharedData.lastNetworkId);

    }

    /**
     * Write the list of networks to the XML stream.
     *
     * @param out            XmlSerializer instance pointing to the XML stream.
     * @param configurations list of WifiConfiguration objects corresponding to the networks.
     */
    private static void writeNetworksToXml(
            XmlSerializer out, List<WifiConfiguration> configurations)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_LIST);
        for (WifiConfiguration configuration : configurations) {
            // Write this configuration data now.
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK);
            writeNetworkToXml(out, configuration);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK_LIST);
    }

    /**
     * Write a network to the XML stream.
     * Nested objects within the provided WifiConfiguration object are written into separate XML
     * sections.
     *
     * @param out           XmlSerializer instance pointing to the XML stream.
     * @param configuration WifiConfiguration object corresponding to the network.
     */
    private static void writeNetworkToXml(
            XmlSerializer out, WifiConfiguration configuration)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        WifiConfigurationXmlUtil.writeToXmlForConfigStore(out, configuration);
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);

        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_STATUS);
        NetworkSelectionStatusXmlUtil.writeToXml(out, configuration.getNetworkSelectionStatus());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK_STATUS);

        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);
        IpConfigurationXmlUtil.writeToXml(out, configuration.getIpConfiguration());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);

        // Store the enterprise configuration for enterprise networks.
        if (configuration.isEnterprise()) {
            XmlUtil.writeNextSectionStart(
                    out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
            WifiEnterpriseConfigXmlUtil.writeToXml(out, configuration.enterpriseConfig);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
        }
    }

    /**
     * Parses the list of networks from the provided XML stream.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @param dataVersion   version number parsed from incoming data.
     * @return list of WifiConfiguration objects corresponding to the networks if parsing is
     * successful, null otherwise.
     */
    private static List<WifiConfiguration> parseNetworksFromXml(
            XmlPullParser in, int outerTagDepth, int dataVersion)
            throws XmlPullParserException, IOException {
        // Find the configuration list section.
        XmlUtil.gotoNextSectionWithName(in, XML_TAG_SECTION_HEADER_NETWORK_LIST, outerTagDepth);
        // Find all the configurations within the configuration list section.
        int networkListTagDepth = outerTagDepth + 1;
        List<WifiConfiguration> configurations = new ArrayList<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(
                in, XML_TAG_SECTION_HEADER_NETWORK, networkListTagDepth)) {
            WifiConfiguration configuration =
                    parseNetworkFromXml(in, networkListTagDepth, dataVersion);
            if (configuration != null) {
                configurations.add(configuration);
            }
        }
        return configurations;
    }

    /**
     * Helper method to parse the WifiConfiguration object and validate the configKey parsed.
     */
    private static WifiConfiguration parseWifiConfigurationFromXmlAndValidateConfigKey(
            XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        Pair<String, WifiConfiguration> parsedConfig =
                WifiConfigurationXmlUtil.parseFromXml(in, outerTagDepth);
        if (parsedConfig == null || parsedConfig.first == null || parsedConfig.second == null) {
            throw new XmlPullParserException("XML parsing of wifi configuration failed");
        }
        String configKeyParsed = parsedConfig.first;
        WifiConfiguration configuration = parsedConfig.second;
        String configKeyCalculated = configuration.configKey();
        if (!configKeyParsed.equals(configKeyCalculated)) {
            throw new XmlPullParserException(
                    "Configuration key does not match. Retrieved: " + configKeyParsed
                            + ", Calculated: " + configKeyCalculated);
        }
        return configuration;
    }

    /**
     * Parses a network from the provided XML stream.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @param dataVersion   version number parsed from incoming data.
     * @return WifiConfiguration object corresponding to the network if parsing is successful,
     * null otherwise.
     */
    private static WifiConfiguration parseNetworkFromXml(
            XmlPullParser in, int outerTagDepth, int dataVersion)
            throws XmlPullParserException, IOException {
        // Any version migration needs to be handled here in future.
        if (dataVersion == INITIAL_CONFIG_STORE_DATA_VERSION) {
            WifiConfiguration configuration = null;

            int networkTagDepth = outerTagDepth + 1;
            XmlUtil.gotoNextSectionWithName(
                    in, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION, networkTagDepth);
            int configTagDepth = networkTagDepth + 1;
            configuration = parseWifiConfigurationFromXmlAndValidateConfigKey(in, configTagDepth);

            XmlUtil.gotoNextSectionWithName(
                    in, XML_TAG_SECTION_HEADER_NETWORK_STATUS, networkTagDepth);
            NetworkSelectionStatus status =
                    NetworkSelectionStatusXmlUtil.parseFromXml(in, configTagDepth);
            configuration.setNetworkSelectionStatus(status);

            XmlUtil.gotoNextSectionWithName(
                    in, XML_TAG_SECTION_HEADER_IP_CONFIGURATION, networkTagDepth);
            IpConfiguration ipConfiguration =
                    IpConfigurationXmlUtil.parseFromXml(in, configTagDepth);
            configuration.setIpConfiguration(ipConfiguration);

            // Check if there is an enterprise configuration section.
            if (XmlUtil.gotoNextSectionWithNameOrEnd(
                    in, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION, networkTagDepth)) {
                WifiEnterpriseConfig enterpriseConfig =
                        WifiEnterpriseConfigXmlUtil.parseFromXml(in, configTagDepth);
                configuration.enterpriseConfig = enterpriseConfig;
            }

            return configuration;
        }
        return null;
    }

    /**
     * Create raw byte array to be stored in the share store file.
     * This method serializes the data to a byte array in XML format.
     *
     * @return pair of byte array with the serialized output. The first element is the shared
     * raw data and the second element is the user raw data.
     */
    public byte[] createSharedRawData() {
        SharedData sharedData = getSharedData();
        return sharedData.createRawData();
    }

    /**
     * Create raw byte array to be stored in the user store file.
     * This method serializes the data to a byte array in XML format.
     *
     * @return byte array with the serialized output.
     */
    public byte[] createUserRawData() {
        UserData userData = getUserData();
        return userData.createRawData();
    }

    /**
     * Retrieve the shared data to be stored in the shared config store file.
     *
     * @return SharedData instance.
     */
    private SharedData getSharedData() {
        List<WifiConfiguration> sharedConfigurations = new ArrayList<>();
        for (WifiConfiguration configuration : configurations) {
            if (configuration.shared) {
                sharedConfigurations.add(configuration);
            }
        }
        return new SharedData(sharedConfigurations, lastNetworkId);
    }

    /**
     * Retrieve the user specific data to be stored in the user config store file.
     *
     * @return UserData instance.
     */
    private UserData getUserData() {
        List<WifiConfiguration> userConfigurations = new ArrayList<>();
        for (WifiConfiguration configuration : configurations) {
            if (!configuration.shared) {
                userConfigurations.add(configuration);
            }
        }
        return new UserData(userConfigurations, blackListBSSIDs, deletedEphemeralSSIDs);
    }

    /**
     * Class to encapsulate all the data to be stored in the shared store.
     */
    public static class SharedData {
        public List<WifiConfiguration> configurations;
        public int lastNetworkId;

        /**
         * Create a new instance of shared store data to be written to the store files.
         *
         * @param configurations list of shared saved networks to be stored.
         * @param lastNetworkId  last network ID assigned.
         */
        public SharedData(List<WifiConfiguration> configurations, int lastNetworkId) {
            this.configurations = configurations;
            this.lastNetworkId = lastNetworkId;
        }

        /**
         * Create a new instance of the shared store data parsed from the store file.
         * This method deserializes the provided byte array in XML format to a new SharedData
         * instance.
         *
         * @param sharedDataBytes raw data retrieved from the shared store file.
         * @return new instance of store data.
         */
        public static SharedData parseRawData(byte[] sharedDataBytes) {
            return new SharedData(null, 0);
        }

        /**
         * Create raw byte array to be stored in the store file.
         * This method serializes the data to a byte array in XML format.
         *
         * @return byte array with the serialized output.
         */
        public static byte[] createRawData() {
            return null;
        }
    }

    /**
     * Class to encapsulate all the data to be stored in the user specific store.
     */
    public static class UserData {
        public List<WifiConfiguration> configurations;
        public Set<String> blackListBSSIDs;
        public Set<String> deletedEphemeralSSIDs;

        /**
         * Create a new instance of user specific store data to be written to the store files.
         *
         * @param configurations        list of user specific saved networks to be stored.
         * @param blacklistBSSIDs       list of blacklist bssids to be stored.
         * @param deletedEphemeralSSIDs list of deleted ephemeral ssids to be stored.
         */
        public UserData(List<WifiConfiguration> configurations, Set<String> blacklistBSSIDs,
                Set<String> deletedEphemeralSSIDs) {
            this.configurations = configurations;
            this.blackListBSSIDs = blacklistBSSIDs;
            this.deletedEphemeralSSIDs = deletedEphemeralSSIDs;
        }

        /**
         * Create a new instance of the user store data parsed from the store file.
         * This method deserializes the provided byte array in XML format to a new UserData
         * instance.
         *
         * @param userDataBytes raw data retrieved from the user store file.
         * @return new instance of store data.
         */
        public static UserData parseRawData(byte[] userDataBytes) {
            return new UserData(null, null, null);
        }

        /**
         * Create raw byte array to be stored in the store file.
         * This method serializes the data to a byte array in XML format.
         *
         * @return byte array with the serialized output.
         */
        public static byte[] createRawData() {
            return null;
        }
    }
}


