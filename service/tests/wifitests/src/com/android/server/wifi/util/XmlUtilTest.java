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

package com.android.server.wifi.util;

import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.wifi.WifiConfigurationTestUtil;
import com.android.server.wifi.util.XmlUtil.IpConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.NetworkSelectionStatusXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiEnterpriseConfigXmlUtil;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link com.android.server.wifi.util.XmlUtil}.
 */
@SmallTest
public class XmlUtilTest {

    private static final int TEST_NETWORK_ID = -1;
    private static final int TEST_UID = 1;
    private static final String TEST_PACKAGE_NAME = "XmlUtilPackage";
    private static final String TEST_SSID = "\"XmlUtilSSID\"";
    private static final String TEST_PSK = "XmlUtilPsk";
    private static final String[] TEST_WEP_KEYS =
            {"XmlUtilWep1", "XmlUtilWep2", "XmlUtilWep3", "XmlUtilWep3"};
    private static final int TEST_WEP_TX_KEY_INDEX = 1;
    private static final String TEST_FQDN = "XmlUtilFQDN";
    private static final String TEST_PROVIDER_FRIENDLY_NAME = "XmlUtilFriendlyName";
    private static final String TEST_STATIC_IP_LINK_ADDRESS = "192.168.48.2";
    private static final int TEST_STATIC_IP_LINK_PREFIX_LENGTH = 8;
    private static final String TEST_STATIC_IP_GATEWAY_ADDRESS = "192.168.48.1";
    private static final String[] TEST_STATIC_IP_DNS_SERVER_ADDRESSES =
            new String[]{"192.168.48.1", "192.168.48.10"};
    private static final String TEST_STATIC_PROXY_HOST = "192.168.48.1";
    private static final int TEST_STATIC_PROXY_PORT = 8000;
    private static final String TEST_STATIC_PROXY_EXCLUSION_LIST = "";
    private static final String TEST_PAC_PROXY_LOCATION = "http://";
    private static final String TEST_DUMMY_CONFIG_KEY = "XmlUtilDummyConfigKey";
    private static final String TEST_IDENTITY = "XmlUtilTestIdentity";
    private static final String TEST_ANON_IDENTITY = "XmlUtilTestAnonIdentity";
    private static final String TEST_PASSWORD = "XmlUtilTestPassword";
    private static final String TEST_CLIENT_CERT = "XmlUtilTestClientCert";
    private static final String TEST_CA_CERT = "XmlUtilTestCaCert";
    private static final String TEST_SUBJECT_MATCH = "XmlUtilTestSubjectMatch";
    private static final String TEST_ENGINE = "XmlUtilTestEngine";
    private static final String TEST_ENGINE_ID = "XmlUtilTestEngineId";
    private static final String TEST_PRIVATE_KEY_ID = "XmlUtilTestPrivateKeyId";
    private static final String TEST_ALTSUBJECT_MATCH = "XmlUtilTestAltSubjectMatch";
    private static final String TEST_DOM_SUFFIX_MATCH = "XmlUtilTestDomSuffixMatch";
    private static final String TEST_CA_PATH = "XmlUtilTestCaPath";
    private static final int TEST_EAP_METHOD = WifiEnterpriseConfig.Eap.PEAP;
    private static final int TEST_PHASE2_METHOD = WifiEnterpriseConfig.Phase2.MSCHAPV2;
    private final String mXmlDocHeader = "XmlUtilTest";

    /**
     * Verify that a open WifiConfiguration is serialized & deserialized correctly.
     */
    @Test
    public void testOpenWifiConfigurationSerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeWifiConfiguration(createOpenNetwork());
    }

    /**
     * Verify that a open hidden WifiConfiguration is serialized & deserialized correctly.
     */
    @Test
    public void testOpenHiddenWifiConfigurationSerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeWifiConfiguration(createOpenHiddenNetwork());
    }

    /**
     * Verify that a psk WifiConfiguration is serialized & deserialized correctly.
     */
    @Test
    public void testPskWifiConfigurationSerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeWifiConfiguration(createPskNetwork());
    }

    /**
     * Verify that a psk hidden WifiConfiguration is serialized & deserialized correctly.
     */
    @Test
    public void testPskHiddenWifiConfigurationSerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeWifiConfiguration(createPskHiddenNetwork());
    }

    /**
     * Verify that a WEP WifiConfiguration is serialized & deserialized correctly.
     */
    @Test
    public void testWepWifiConfigurationSerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeWifiConfiguration(createWepNetwork());
    }

    /**
     * Verify that a EAP WifiConfiguration is serialized & deserialized correctly only for
     * ConfigStore.
     */
    @Test
    public void testEapWifiConfigurationSerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeWifiConfigurationForConfigStore(createEapNetwork());
    }

    /**
     * Verify that a static IpConfiguration with PAC proxy is serialized & deserialized correctly.
     */
    @Test
    public void testStaticIpConfigurationWithPacProxySerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeIpConfiguration(createStaticIpConfigurationWithPacProxy());
    }

    /**
     * Verify that a static IpConfiguration with static proxy is serialized & deserialized correctly.
     */
    @Test
    public void testStaticIpConfigurationWithStaticProxySerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeIpConfiguration(createStaticIpConfigurationWithStaticProxy());
    }

    /**
     * Verify that a partial static IpConfiguration with PAC proxy is serialized & deserialized
     * correctly.
     */
    @Test
    public void testPartialStaticIpConfigurationWithPacProxySerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeIpConfiguration(createPartialStaticIpConfigurationWithPacProxy());
    }

    /**
     * Verify that a DHCP IpConfiguration with PAC proxy is serialized & deserialized
     * correctly.
     */
    @Test
    public void testDHCPIpConfigurationWithPacProxySerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeIpConfiguration(createDHCPIpConfigurationWithPacProxy());
    }

    /**
     * Verify that a DHCP IpConfiguration with Static proxy is serialized & deserialized
     * correctly.
     */
    @Test
    public void testDHCPIpConfigurationWithStaticProxySerializeDeserialize()
            throws IOException, XmlPullParserException {
        serializeDeserializeIpConfiguration(createDHCPIpConfigurationWithStaticProxy());
    }

    /**
     * Verify that a EAP WifiConfiguration is serialized & deserialized correctly for config store.
     * This basically exercises all the elements being serialized in config store.
     */
    @Test
    public void testEapWifiConfigurationSerializeDeserializeForConfigStore()
            throws IOException, XmlPullParserException {
        WifiConfiguration configuration = createEapNetwork();
        configuration.linkedConfigurations = new HashMap<>();
        configuration.linkedConfigurations.put(TEST_DUMMY_CONFIG_KEY, Integer.valueOf(1));
        configuration.defaultGwMacAddress = TEST_STATIC_IP_GATEWAY_ADDRESS;
        configuration.validatedInternetAccess = true;
        configuration.noInternetAccessExpected = true;
        configuration.userApproved = WifiConfiguration.USER_UNSPECIFIED;
        configuration.meteredHint = true;
        configuration.useExternalScores = true;
        configuration.numAssociation = 5;
        configuration.lastUpdateUid = configuration.lastConnectUid = configuration.creatorUid;
        configuration.creatorName = configuration.lastUpdateName = TEST_PACKAGE_NAME;
        configuration.creationTime = "04-04-2016";

        serializeDeserializeWifiConfigurationForConfigStore(configuration);
    }

    /**
     * Verify that an enabled network selection status object is serialized & deserialized
     * correctly.
     */
    @Test
    public void testEnabledNetworkSelectionStatusSerializeDeserialize()
            throws IOException, XmlPullParserException {
        NetworkSelectionStatus status = new NetworkSelectionStatus();
        status.setNetworkSelectionStatus(NetworkSelectionStatus.NETWORK_SELECTION_ENABLED);
        status.setNetworkSelectionDisableReason(NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        status.setConnectChoice(TEST_DUMMY_CONFIG_KEY);
        status.setConnectChoiceTimestamp(867889);
        status.setHasEverConnected(true);
        serializeDeserializeNetworkSelectionStatus(status);
    }

    /**
     * Verify that a temporarily disabled network selection status object is serialized &
     * deserialized correctly.
     */
    @Test
    public void testTemporarilyDisabledNetworkSelectionStatusSerializeDeserialize()
            throws IOException, XmlPullParserException {
        NetworkSelectionStatus status = new NetworkSelectionStatus();
        status.setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        status.setNetworkSelectionDisableReason(
                NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION);
        serializeDeserializeNetworkSelectionStatus(status);
    }

    /**
     * Verify that a WifiEnterpriseConfig object is serialized & deserialized correctly.
     */
    @Test
    public void testWifiEnterpriseConfigSerializeDeserialize()
            throws IOException, XmlPullParserException {
        WifiEnterpriseConfig config = new WifiEnterpriseConfig();
        config.setFieldValue(WifiEnterpriseConfig.IDENTITY_KEY, TEST_IDENTITY);
        config.setFieldValue(WifiEnterpriseConfig.ANON_IDENTITY_KEY, TEST_ANON_IDENTITY);
        config.setFieldValue(WifiEnterpriseConfig.PASSWORD_KEY, TEST_PASSWORD);
        config.setFieldValue(WifiEnterpriseConfig.CLIENT_CERT_KEY, TEST_CLIENT_CERT);
        config.setFieldValue(WifiEnterpriseConfig.CA_CERT_KEY, TEST_CA_CERT);
        config.setFieldValue(WifiEnterpriseConfig.SUBJECT_MATCH_KEY, TEST_SUBJECT_MATCH);
        config.setFieldValue(WifiEnterpriseConfig.ENGINE_KEY, TEST_ENGINE);
        config.setFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY, TEST_ENGINE_ID);
        config.setFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, TEST_PRIVATE_KEY_ID);
        config.setFieldValue(WifiEnterpriseConfig.ALTSUBJECT_MATCH_KEY, TEST_ALTSUBJECT_MATCH);
        config.setFieldValue(WifiEnterpriseConfig.DOM_SUFFIX_MATCH_KEY, TEST_DOM_SUFFIX_MATCH);
        config.setFieldValue(WifiEnterpriseConfig.CA_PATH_KEY, TEST_CA_CERT);
        config.setEapMethod(TEST_EAP_METHOD);
        config.setPhase2Method(TEST_PHASE2_METHOD);
        serializeDeserializeWifiEnterpriseConfig(config);
    }

    private WifiConfiguration createOpenNetwork() {
        return WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, TEST_SSID,
                true, true, null, null,
                WifiConfigurationTestUtil.SECURITY_NONE);
    }

    private WifiConfiguration createOpenHiddenNetwork() {
        WifiConfiguration configuration = createOpenNetwork();
        configuration.hiddenSSID = true;
        return configuration;
    }

    private WifiConfiguration createPskNetwork() {
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, TEST_SSID,
                        true, true, null, null,
                        WifiConfigurationTestUtil.SECURITY_PSK);
        configuration.preSharedKey = TEST_PSK;
        return configuration;
    }

    private WifiConfiguration createPskHiddenNetwork() {
        WifiConfiguration configuration = createPskNetwork();
        configuration.hiddenSSID = true;
        return configuration;
    }

    private WifiConfiguration createWepNetwork() {
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, TEST_SSID,
                        true, true, null, null,
                        WifiConfigurationTestUtil.SECURITY_WEP);
        configuration.wepKeys = TEST_WEP_KEYS;
        configuration.wepTxKeyIndex = TEST_WEP_TX_KEY_INDEX;
        return configuration;
    }

    private WifiConfiguration createEapNetwork() {
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, TEST_SSID,
                        true, true, TEST_FQDN, TEST_PROVIDER_FRIENDLY_NAME,
                        WifiConfigurationTestUtil.SECURITY_EAP);
        return configuration;
    }

    private StaticIpConfiguration createStaticIpConfiguration() {
        StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
        LinkAddress linkAddress =
                new LinkAddress(NetworkUtils.numericToInetAddress(TEST_STATIC_IP_LINK_ADDRESS),
                        TEST_STATIC_IP_LINK_PREFIX_LENGTH);
        staticIpConfiguration.ipAddress = linkAddress;
        InetAddress gatewayAddress =
                NetworkUtils.numericToInetAddress(TEST_STATIC_IP_GATEWAY_ADDRESS);
        staticIpConfiguration.gateway = gatewayAddress;
        for (String dnsServerAddress : TEST_STATIC_IP_DNS_SERVER_ADDRESSES) {
            staticIpConfiguration.dnsServers.add(
                    NetworkUtils.numericToInetAddress(dnsServerAddress));
        }
        return staticIpConfiguration;
    }

    private StaticIpConfiguration createPartialStaticIpConfiguration() {
        StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
        LinkAddress linkAddress =
                new LinkAddress(NetworkUtils.numericToInetAddress(TEST_STATIC_IP_LINK_ADDRESS),
                        TEST_STATIC_IP_LINK_PREFIX_LENGTH);
        staticIpConfiguration.ipAddress = linkAddress;
        // Only set the link address, don't set the gateway/dns servers.
        return staticIpConfiguration;
    }

    private IpConfiguration createStaticIpConfigurationWithPacProxy() {
        StaticIpConfiguration staticIpConfiguration = createStaticIpConfiguration();
        ProxyInfo proxyInfo = new ProxyInfo(TEST_PAC_PROXY_LOCATION);
        return new IpConfiguration(IpConfiguration.IpAssignment.STATIC,
                IpConfiguration.ProxySettings.PAC, staticIpConfiguration, proxyInfo);
    }

    private IpConfiguration createStaticIpConfigurationWithStaticProxy() {
        StaticIpConfiguration staticIpConfiguration = createStaticIpConfiguration();
        ProxyInfo proxyInfo =
                new ProxyInfo(TEST_STATIC_PROXY_HOST,
                        TEST_STATIC_PROXY_PORT,
                        TEST_STATIC_PROXY_EXCLUSION_LIST);
        return new IpConfiguration(IpConfiguration.IpAssignment.STATIC,
                IpConfiguration.ProxySettings.STATIC, staticIpConfiguration, proxyInfo);
    }

    private IpConfiguration createPartialStaticIpConfigurationWithPacProxy() {
        StaticIpConfiguration staticIpConfiguration = createPartialStaticIpConfiguration();
        ProxyInfo proxyInfo = new ProxyInfo(TEST_PAC_PROXY_LOCATION);
        return new IpConfiguration(IpConfiguration.IpAssignment.STATIC,
                IpConfiguration.ProxySettings.PAC, staticIpConfiguration, proxyInfo);
    }

    private IpConfiguration createDHCPIpConfigurationWithPacProxy() {
        ProxyInfo proxyInfo = new ProxyInfo(TEST_PAC_PROXY_LOCATION);
        return new IpConfiguration(IpConfiguration.IpAssignment.DHCP,
                IpConfiguration.ProxySettings.PAC, null, proxyInfo);
    }

    private IpConfiguration createDHCPIpConfigurationWithStaticProxy() {
        ProxyInfo proxyInfo =
                new ProxyInfo(TEST_STATIC_PROXY_HOST,
                        TEST_STATIC_PROXY_PORT,
                        TEST_STATIC_PROXY_EXCLUSION_LIST);
        return new IpConfiguration(IpConfiguration.IpAssignment.DHCP,
                IpConfiguration.ProxySettings.STATIC, null, proxyInfo);
    }

    private byte[] serializeWifiConfigurationForBackup(WifiConfiguration configuration)
            throws IOException, XmlPullParserException {
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());
        XmlUtil.writeDocumentStart(out, mXmlDocHeader);
        WifiConfigurationXmlUtil.writeToXmlForBackup(out, configuration);
        XmlUtil.writeDocumentEnd(out, mXmlDocHeader);
        return outputStream.toByteArray();
    }

    private byte[] serializeWifiConfigurationForConfigStore(
            WifiConfiguration configuration)
            throws IOException, XmlPullParserException {
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());
        XmlUtil.writeDocumentStart(out, mXmlDocHeader);
        WifiConfigurationXmlUtil.writeToXmlForConfigStore(out, configuration);
        XmlUtil.writeDocumentEnd(out, mXmlDocHeader);
        return outputStream.toByteArray();
    }

    private Pair<String, WifiConfiguration> deserializeWifiConfiguration(byte[] data)
            throws IOException, XmlPullParserException {
        // Deserialize the configuration object.
        final XmlPullParser in = Xml.newPullParser();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        in.setInput(inputStream, StandardCharsets.UTF_8.name());
        XmlUtil.gotoDocumentStart(in, mXmlDocHeader);
        return WifiConfigurationXmlUtil.parseFromXml(in, in.getDepth());
    }

    /**
     * This helper method tests the serialization for backup/restore.
     */
    private void serializeDeserializeWifiConfigurationForBackupRestore(
            WifiConfiguration configuration)
            throws IOException, XmlPullParserException {
        Pair<String, WifiConfiguration> retrieved;
        // Test serialization/deserialization for config store.
        retrieved =
                deserializeWifiConfiguration(
                        serializeWifiConfigurationForBackup(configuration));
        assertEquals(retrieved.first, retrieved.second.configKey());
        WifiConfigurationTestUtil.assertConfigurationEqualForBackup(
                configuration, retrieved.second);
    }

    /**
     * This helper method tests the serialization for config store.
     */
    private void serializeDeserializeWifiConfigurationForConfigStore(
            WifiConfiguration configuration)
            throws IOException, XmlPullParserException {
        // Reset enterprise config because this needs to be serialized/deserialized separately.
        configuration.enterpriseConfig = new WifiEnterpriseConfig();
        Pair<String, WifiConfiguration> retrieved;
        // Test serialization/deserialization for config store.
        retrieved =
                deserializeWifiConfiguration(
                        serializeWifiConfigurationForConfigStore(configuration));
        assertEquals(retrieved.first, retrieved.second.configKey());
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigStore(
                configuration, retrieved.second);
    }

    /**
     * This helper method tests both the serialization for backup/restore and config store.
     */
    private void serializeDeserializeWifiConfiguration(WifiConfiguration configuration)
            throws IOException, XmlPullParserException {
        Pair<String, WifiConfiguration> retrieved;
        // Test serialization/deserialization for backup first.
        serializeDeserializeWifiConfigurationForBackupRestore(configuration);

        // Test serialization/deserialization for config store.
        serializeDeserializeWifiConfigurationForConfigStore(configuration);
    }

    private void serializeDeserializeIpConfiguration(IpConfiguration configuration)
            throws IOException, XmlPullParserException {
        // Serialize the configuration object.
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());
        XmlUtil.writeDocumentStart(out, mXmlDocHeader);
        IpConfigurationXmlUtil.writeToXml(out, configuration);
        XmlUtil.writeDocumentEnd(out, mXmlDocHeader);

        // Deserialize the configuration object.
        final XmlPullParser in = Xml.newPullParser();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        in.setInput(inputStream, StandardCharsets.UTF_8.name());
        XmlUtil.gotoDocumentStart(in, mXmlDocHeader);
        IpConfiguration retrievedConfiguration =
                IpConfigurationXmlUtil.parseFromXml(in, in.getDepth());
        assertEquals(configuration, retrievedConfiguration);
    }

    private void serializeDeserializeNetworkSelectionStatus(NetworkSelectionStatus status)
            throws IOException, XmlPullParserException {
        // Serialize the configuration object.
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());
        XmlUtil.writeDocumentStart(out, mXmlDocHeader);
        NetworkSelectionStatusXmlUtil.writeToXml(out, status);
        XmlUtil.writeDocumentEnd(out, mXmlDocHeader);

        // Deserialize the configuration object.
        final XmlPullParser in = Xml.newPullParser();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        in.setInput(inputStream, StandardCharsets.UTF_8.name());
        XmlUtil.gotoDocumentStart(in, mXmlDocHeader);
        NetworkSelectionStatus retrievedStatus =
                NetworkSelectionStatusXmlUtil.parseFromXml(in, in.getDepth());
        WifiConfigurationTestUtil.assertNetworkSelectionStatusEqualForConfigStore(
                status, retrievedStatus);
    }

    private void serializeDeserializeWifiEnterpriseConfig(WifiEnterpriseConfig config)
            throws IOException, XmlPullParserException {
        // Serialize the configuration object.
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());
        XmlUtil.writeDocumentStart(out, mXmlDocHeader);
        WifiEnterpriseConfigXmlUtil.writeToXml(out, config);
        XmlUtil.writeDocumentEnd(out, mXmlDocHeader);

        // Deserialize the configuration object.
        final XmlPullParser in = Xml.newPullParser();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        in.setInput(inputStream, StandardCharsets.UTF_8.name());
        XmlUtil.gotoDocumentStart(in, mXmlDocHeader);
        WifiEnterpriseConfig retrievedConfig =
                WifiEnterpriseConfigXmlUtil.parseFromXml(in, in.getDepth());
        WifiConfigurationTestUtil.assertWifiEnterpriseConfigEqualForConfigStore(
                config, retrievedConfig);
    }
}
