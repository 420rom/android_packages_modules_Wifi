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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigManagerNew}.
 */
@SmallTest
public class WifiConfigManagerNewTest {

    private static final String TEST_BSSID = "0a:08:5c:67:89:00";
    private static final long TEST_WALLCLOCK_CREATION_TIME_MILLIS = 9845637;
    private static final long TEST_WALLCLOCK_UPDATE_TIME_MILLIS = 75455637;
    private static final long TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS = 29457631;
    private static final int TEST_CREATOR_UID = 5;
    private static final int TEST_UPDATE_UID = 4;
    private static final String TEST_CREATOR_NAME = "com.wificonfigmanagerNew.creator";
    private static final String TEST_UPDATE_NAME = "com.wificonfigmanagerNew.update";

    @Mock private Context mContext;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private Clock mClock;
    @Mock private UserManager mUserManager;
    @Mock private WifiKeyStore mWifiKeyStore;
    @Mock private WifiConfigStoreNew mWifiConfigStore;
    @Mock private PackageManager mPackageManager;

    private InOrder mContextConfigStoreMockOrder;

    private WifiConfigManagerNew mWifiConfigManager;

    /**
     * Setup the mocks and an instance of WifiConfigManagerNew before each test.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Set up the inorder for verifications. This is needed to verify that the broadcasts,
        // store writes for network updates followed by network additions are in the expected order.
        mContextConfigStoreMockOrder = inOrder(mContext, mWifiConfigStore);

        // Set up the package name stuff & permission override.
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doAnswer(new AnswerWithArguments() {
            public String answer(int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return TEST_CREATOR_NAME;
                } else if (uid == TEST_UPDATE_UID) {
                    return TEST_UPDATE_NAME;
                }
                fail("Unexpected UID: " + uid);
                return "";
            }
        }).when(mPackageManager).getNameForUid(anyInt());

        // Both the UID's in the test have the configuration override permission granted by
        // default. This maybe modified for particular tests if needed.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID || uid == TEST_UPDATE_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        when(mWifiKeyStore
                .updateNetworkKeys(any(WifiConfiguration.class), any(WifiConfiguration.class)))
                .thenReturn(true);

        mWifiConfigManager =
                new WifiConfigManagerNew(
                        mContext, mFrameworkFacade, mClock, mUserManager, mWifiKeyStore,
                        mWifiConfigStore);
        mWifiConfigManager.enableVerboseLogging(1);
    }

    /**
     * Verifies the addition of a single network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testAddSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the modification of a single network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testUpdateSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID for the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);
        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(openNetwork);

        // Now verify that the modification has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the addition of a single ephemeral network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * the {@link WifiConfigManagerNew#getSavedNetworks()} does not return this network.
     */
    @Test
    public void testAddSingleEphemeralNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        openNetwork.ephemeral = true;
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddEphemeralNetworkToWifiConfigManager(openNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        // Ensure that this is not returned in the saved network list.
        assertTrue(mWifiConfigManager.getSavedNetworks().isEmpty());
    }

    /**
     * Verifies that the modification of a single open network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} with a UID which
     * has no permission to modify the network fails.
     */
    @Test
    public void testUpdateSingleOpenNetworkFailedDueToPermissionDenied() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID of the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Deny permission for |UPDATE_UID|.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Update the same configuration and ensure that the operation failed.
        NetworkUpdateResult result = updateNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() == WifiConfiguration.INVALID_NETWORK_ID);
    }

    /**
     * Verifies that the modification of a single open network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} with the creator UID
     * should always succeed.
     */
    @Test
    public void testUpdateSingleOpenNetworkSuccessWithCreatorUID() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID of the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Deny permission for all UIDs.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Update the same configuration using the creator UID.
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(openNetwork, TEST_CREATOR_UID);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);

        // Now verify that the modification has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the addition of a single PSK network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * {@link WifiConfigManagerNew#getSavedNetworks()} masks the password.
     */
    @Test
    public void testAddSinglePskNetwork() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(pskNetwork);

        verifyAddNetworkToWifiConfigManager(pskNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        List<WifiConfiguration> retrievedSavedNetworks = mWifiConfigManager.getSavedNetworks();
        assertEquals(retrievedSavedNetworks.size(), 1);
        assertEquals(retrievedSavedNetworks.get(0).configKey(), pskNetwork.configKey());
        assertPasswordsMaskedInWifiConfiguration(retrievedSavedNetworks.get(0));
    }

    /**
     * Verifies the addition of a single WEP network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * {@link WifiConfigManagerNew#getSavedNetworks()} masks the password.
     */
    @Test
    public void testAddSingleWepNetwork() {
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(wepNetwork);

        verifyAddNetworkToWifiConfigManager(wepNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        List<WifiConfiguration> retrievedSavedNetworks = mWifiConfigManager.getSavedNetworks();
        assertEquals(retrievedSavedNetworks.size(), 1);
        assertEquals(retrievedSavedNetworks.get(0).configKey(), wepNetwork.configKey());
        assertPasswordsMaskedInWifiConfiguration(retrievedSavedNetworks.get(0));
    }

    /**
     * Verifies the modification of an IpConfiguration using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testUpdateIpConfiguration() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID of the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Update the same configuration and ensure that the IP configuration change flags
        // are not set.
        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(openNetwork);

        // Change the IpConfiguration now and ensure that the IP configuration flags are set now.
        assertAndSetNetworkIpConfiguration(
                openNetwork,
                WifiConfigurationTestUtil.createStaticIpConfigurationWithStaticProxy());
        verifyUpdateNetworkToWifiConfigManagerWithIpChange(openNetwork);

        // Now verify that all the modifications have been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the removal of a single network using
     * {@link WifiConfigManagerNew#removeNetwork(int)}
     */
    @Test
    public void testRemoveSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        verifyRemoveNetworkFromWifiConfigManager(openNetwork);

        // Ensure that configured network list is empty now.
        assertTrue(mWifiConfigManager.getConfiguredNetworks().isEmpty());
    }

    /**
     * Verifies the addition & update of multiple networks using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} and the
     * removal of networks using
     * {@link WifiConfigManagerNew#removeNetwork(int)}
     */
    @Test
    public void testAddUpdateRemoveMultipleNetworks() {
        List<WifiConfiguration> networks = new ArrayList<>();
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        networks.add(openNetwork);
        networks.add(pskNetwork);
        networks.add(wepNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);
        verifyAddNetworkToWifiConfigManager(pskNetwork);
        verifyAddNetworkToWifiConfigManager(wepNetwork);

        // Now verify that all the additions has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        // Modify all the 3 configurations and update it to WifiConfigManager.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);
        assertAndSetNetworkBSSID(pskNetwork, TEST_BSSID);
        assertAndSetNetworkIpConfiguration(
                wepNetwork,
                WifiConfigurationTestUtil.createStaticIpConfigurationWithPacProxy());

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(openNetwork);
        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(pskNetwork);
        verifyUpdateNetworkToWifiConfigManagerWithIpChange(wepNetwork);
        // Now verify that all the modifications has been effective.
        retrievedNetworks = mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        // Now remove all 3 networks.
        verifyRemoveNetworkFromWifiConfigManager(openNetwork);
        verifyRemoveNetworkFromWifiConfigManager(pskNetwork);
        verifyRemoveNetworkFromWifiConfigManager(wepNetwork);

        // Ensure that configured network list is empty now.
        assertTrue(mWifiConfigManager.getConfiguredNetworks().isEmpty());
    }

    /**
     * Verifies the update of network status using
     * {@link WifiConfigManagerNew#updateNetworkSelectionStatus(int, int)}.
     */
    @Test
    public void testNetworkSelectionStatus() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        // First set it to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Now set it to temporarily disabled. The threshold for association rejection is 5, so
        // disable it 5 times to actually mark it temporarily disabled.
        int assocRejectReason = NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION;
        int assocRejectThreshold =
                WifiConfigManagerNew.NETWORK_SELECTION_DISABLE_THRESHOLD[assocRejectReason];
        for (int i = 1; i <= assocRejectThreshold; i++) {
            verifyUpdateNetworkSelectionStatus(result.getNetworkId(), assocRejectReason, i);
        }

        // Now set it to permanently disabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER, 0);

        // Now set it back to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);
    }

    /**
     * Verifies the update of network status using
     * {@link WifiConfigManagerNew#updateNetworkSelectionStatus(int, int)} and ensures that
     * enabling a network clears out all the temporary disable counters.
     */
    @Test
    public void testNetworkSelectionStatusEnableClearsDisableCounters() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        // First set it to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Now set it to temporarily disabled 2 times for 2 different reasons.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION, 1);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION, 2);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE, 1);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE, 2);

        // Now set it back to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Ensure that the counters have all been reset now.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION, 1);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE, 1);
    }

    /**
     * Verifies the enabling of temporarily disabled network using
     * {@link WifiConfigManagerNew#tryEnableNetwork(int)}.
     */
    @Test
    public void testTryEnableNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        // First set it to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Now set it to temporarily disabled. The threshold for association rejection is 5, so
        // disable it 5 times to actually mark it temporarily disabled.
        int assocRejectReason = NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION;
        int assocRejectThreshold =
                WifiConfigManagerNew.NETWORK_SELECTION_DISABLE_THRESHOLD[assocRejectReason];
        for (int i = 1; i <= assocRejectThreshold; i++) {
            verifyUpdateNetworkSelectionStatus(result.getNetworkId(), assocRejectReason, i);
        }

        // Now let's try enabling this network without changing the time, this should fail and the
        // status remains temporarily disabled.
        assertFalse(mWifiConfigManager.tryEnableNetwork(result.getNetworkId()));
        NetworkSelectionStatus retrievedStatus =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId())
                        .getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkTemporaryDisabled());

        // Now advance time by the timeout for association rejection and ensure that the network
        // is now enabled.
        int assocRejectTimeout =
                WifiConfigManagerNew.NETWORK_SELECTION_DISABLE_TIMEOUT_MS[assocRejectReason];
        when(mClock.getElapsedSinceBootMillis())
                .thenReturn(TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS + assocRejectTimeout);

        assertTrue(mWifiConfigManager.tryEnableNetwork(result.getNetworkId()));
        retrievedStatus =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId())
                        .getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
    }

    /**
     * Verifies the enabling of network using
     * {@link WifiConfigManagerNew#enableNetwork(int, int)} and
     * {@link WifiConfigManagerNew#disableNetwork(int, int)}.
     */
    @Test
    public void testEnableDisableNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertTrue(mWifiConfigManager.enableNetwork(result.getNetworkId(), TEST_CREATOR_UID));
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        NetworkSelectionStatus retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
        verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.ENABLED);

        // Now set it disabled.
        assertTrue(mWifiConfigManager.disableNetwork(result.getNetworkId(), TEST_CREATOR_UID));
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkPermanentlyDisabled());
        verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.DISABLED);
    }

    /**
     * Verifies the enabling of network using
     * {@link WifiConfigManagerNew#enableNetwork(int, int)} with a UID which
     * has no permission to modify the network fails..
     */
    @Test
    public void testEnableDisableNetworkFailedDueToPermissionDenied() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertTrue(mWifiConfigManager.enableNetwork(result.getNetworkId(), TEST_CREATOR_UID));
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        NetworkSelectionStatus retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
        verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.ENABLED);

        // Deny permission for |UPDATE_UID|.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Now try to set it disabled with |TEST_UPDATE_UID|, it should fail and the network
        // should remain enabled.
        assertFalse(mWifiConfigManager.disableNetwork(result.getNetworkId(), TEST_UPDATE_UID));
        retrievedStatus =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId())
                        .getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
        assertEquals(WifiConfiguration.Status.ENABLED, retrievedNetwork.status);
    }

    /**
     * Verifies the updation of network's connectUid using
     * {@link WifiConfigManagerNew#checkAndUpdateLastConnectUid(int, int)}.
     */
    @Test
    public void testUpdateLastConnectUid() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertTrue(
                mWifiConfigManager.checkAndUpdateLastConnectUid(result.getNetworkId(), TEST_CREATOR_UID));
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        assertEquals(TEST_CREATOR_UID, retrievedNetwork.lastConnectUid);

        // Deny permission for |UPDATE_UID|.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Now try to update the last connect UID with |TEST_UPDATE_UID|, it should fail and
        // the lastConnectUid should remain the same.
        assertFalse(
                mWifiConfigManager.checkAndUpdateLastConnectUid(
                        result.getNetworkId(), TEST_UPDATE_UID));
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        assertEquals(TEST_CREATOR_UID, retrievedNetwork.lastConnectUid);
    }

    /**
     * Verifies that any configuration update attempt with an null config is gracefully
     * handled.
     * This invokes {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}.
     */
    @Test
    public void testAddOrUpdateNetworkWithNullConfig() {
        NetworkUpdateResult result = mWifiConfigManager.addOrUpdateNetwork(null, TEST_CREATOR_UID);
        assertFalse(result.isSuccess());
    }

    /**
     * Verifies that any configuration removal attempt with an invalid networkID is gracefully
     * handled.
     * This invokes {@link WifiConfigManagerNew#removeNetwork(int)}.
     */
    @Test
    public void testRemoveNetworkWithInvalidNetworkId() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Change the networkID to an invalid one.
        openNetwork.networkId++;
        assertFalse(mWifiConfigManager.removeNetwork(openNetwork.networkId));
    }

    /**
     * Verifies that any configuration update attempt with an invalid networkID is gracefully
     * handled.
     * This invokes {@link WifiConfigManagerNew#enableNetwork(int, int)},
     * {@link WifiConfigManagerNew#disableNetwork(int, int)},
     * {@link WifiConfigManagerNew#updateNetworkSelectionStatus(int, int)} and
     * {@link WifiConfigManagerNew#checkAndUpdateLastConnectUid(int, int)}.
     */
    @Test
    public void testChangeConfigurationWithInvalidNetworkId() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertFalse(mWifiConfigManager.enableNetwork(result.getNetworkId() + 1, TEST_CREATOR_UID));
        assertFalse(mWifiConfigManager.disableNetwork(result.getNetworkId() + 1, TEST_CREATOR_UID));
        assertFalse(mWifiConfigManager.updateNetworkSelectionStatus(
                result.getNetworkId() + 1, NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER));
        assertFalse(mWifiConfigManager.checkAndUpdateLastConnectUid(
                result.getNetworkId() + 1, TEST_CREATOR_UID));
    }

    /**
     * Verifies multiple modification of a single network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}.
     * This test is basically checking if the apps can reset some of the fields of the config after
     * addition. The fields being reset in this test are the |preSharedKey| and |wepKeys|.
     * 1. Create an open network initially.
     * 2. Modify the added network config to a WEP network config with all the 4 keys set.
     * 3. Modify the added network config to a WEP network config with only 1 key set.
     * 4. Modify the added network config to a PSK network config.
     */
    @Test
    public void testMultipleUpdatesSingleNetwork() {
        WifiConfiguration network = WifiConfigurationTestUtil.createOpenNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Now add |wepKeys| to the network. We don't need to update the |allowedKeyManagement|
        // fields for open to WEP conversion.
        String[] wepKeys =
                Arrays.copyOf(WifiConfigurationTestUtil.TEST_WEP_KEYS,
                        WifiConfigurationTestUtil.TEST_WEP_KEYS.length);
        int wepTxKeyIdx = WifiConfigurationTestUtil.TEST_WEP_TX_KEY_INDEX;
        assertAndSetNetworkWepKeysAndTxIndex(network, wepKeys, wepTxKeyIdx);

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));

        // Now empty out 3 of the |wepKeys[]| and ensure that those keys have been reset correctly.
        for (int i = 1; i < network.wepKeys.length; i++) {
            wepKeys[i] = "";
        }
        wepTxKeyIdx = 0;
        assertAndSetNetworkWepKeysAndTxIndex(network, wepKeys, wepTxKeyIdx);

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));

        // Now change the config to a PSK network config by resetting the remaining |wepKey[0]|
        // field and setting the |preSharedKey| and |allowedKeyManagement| fields.
        wepKeys[0] = "";
        wepTxKeyIdx = -1;
        assertAndSetNetworkWepKeysAndTxIndex(network, wepKeys, wepTxKeyIdx);
        network.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        assertAndSetNetworkPreSharedKey(network, WifiConfigurationTestUtil.TEST_PSK);

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));
    }

    /**
     * Verifies the modification of a WifiEnteriseConfig using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}.
     */
    @Test
    public void testUpdateWifiEnterpriseConfig() {
        WifiConfiguration network = WifiConfigurationTestUtil.createEapNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Set the |password| field in WifiEnterpriseConfig and modify the config to PEAP/GTC.
        network.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        assertAndSetNetworkEnterprisePassword(network, "test");

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));

        // Reset the |password| field in WifiEnterpriseConfig and modify the config to TLS/None.
        network.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        network.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        assertAndSetNetworkEnterprisePassword(network, "");

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));
    }

    /**
     * Verifies the modification of a single network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} by passing in nulls
     * in all the publicly exposed fields.
     */
    @Test
    public void testUpdateSingleNetworkWithNullValues() {
        WifiConfiguration network = WifiConfigurationTestUtil.createEapNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Save a copy of the original network for comparison.
        WifiConfiguration originalNetwork = new WifiConfiguration(network);

        // Now set all the public fields to null and try updating the network.
        network.allowedAuthAlgorithms = null;
        network.allowedProtocols = null;
        network.allowedKeyManagement = null;
        network.allowedPairwiseCiphers = null;
        network.allowedGroupCiphers = null;
        network.setIpConfiguration(null);
        network.enterpriseConfig = null;

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);

        // Copy over the updated debug params to the original network config before comparison.
        originalNetwork.lastUpdateUid = network.lastUpdateUid;
        originalNetwork.lastUpdateName = network.lastUpdateName;
        originalNetwork.updateTime = network.updateTime;

        // Now verify that there was no change to the network configurations.
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                originalNetwork,
                mWifiConfigManager.getConfiguredNetworkWithPassword(originalNetwork.networkId));
    }

    /**
     * Verifies that the modification of a single network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} does not modify
     * existing configuration if there is a failure.
     */
    @Test
    public void testUpdateSingleNetworkFailureDoesNotModifyOriginal() {
        WifiConfiguration network = WifiConfigurationTestUtil.createEapNetwork();
        network.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        verifyAddNetworkToWifiConfigManager(network);

        // Save a copy of the original network for comparison.
        WifiConfiguration originalNetwork = new WifiConfiguration(network);

        // Now modify the network's EAP method.
        network.enterpriseConfig =
                WifiConfigurationTestUtil.createTLSWifiEnterpriseConfigWithNonePhase2();

        // Fail this update because of cert installation failure.
        when(mWifiKeyStore
                .updateNetworkKeys(any(WifiConfiguration.class), any(WifiConfiguration.class)))
                .thenReturn(false);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(network, TEST_UPDATE_UID);
        assertTrue(result.getNetworkId() == WifiConfiguration.INVALID_NETWORK_ID);

        // Now verify that there was no change to the network configurations.
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                originalNetwork,
                mWifiConfigManager.getConfiguredNetworkWithPassword(originalNetwork.networkId));
    }

    /**
     * Verifies the matching of networks with different encryption types with the
     * corresponding scan detail using
     * {@link WifiConfigManagerNew#getSavedNetworkForScanDetailAndCache(ScanDetail)}.
     * The test also verifies that the provided scan detail was cached,
     */
    @Test
    public void testMatchScanDetailToNetworksAndCache() {
        // Create networks of different types and ensure that they're all matched using
        // the corresponding ScanDetail correctly.
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createOpenNetwork());
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createWepNetwork());
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createPskNetwork());
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createEapNetwork());
    }

    /**
     * Verifies that scan details with wrong SSID/authentication types are not matched using
     * {@link WifiConfigManagerNew#getSavedNetworkForScanDetailAndCache(ScanDetail)}
     * to the added networks.
     */
    @Test
    public void testNoMatchScanDetailToNetwork() {
        // First create networks of different types.
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration eapNetwork = WifiConfigurationTestUtil.createEapNetwork();

        // Now add them to WifiConfigManager.
        verifyAddNetworkToWifiConfigManager(openNetwork);
        verifyAddNetworkToWifiConfigManager(wepNetwork);
        verifyAddNetworkToWifiConfigManager(pskNetwork);
        verifyAddNetworkToWifiConfigManager(eapNetwork);

        // Now create dummy scan detail corresponding to the networks.
        ScanDetail openNetworkScanDetail = createScanDetailForNetwork(openNetwork);
        ScanDetail wepNetworkScanDetail = createScanDetailForNetwork(wepNetwork);
        ScanDetail pskNetworkScanDetail = createScanDetailForNetwork(pskNetwork);
        ScanDetail eapNetworkScanDetail = createScanDetailForNetwork(eapNetwork);

        // Now mix and match parameters from different scan details.
        openNetworkScanDetail.getScanResult().SSID =
                wepNetworkScanDetail.getScanResult().SSID;
        wepNetworkScanDetail.getScanResult().capabilities =
                pskNetworkScanDetail.getScanResult().capabilities;
        pskNetworkScanDetail.getScanResult().capabilities =
                eapNetworkScanDetail.getScanResult().capabilities;
        eapNetworkScanDetail.getScanResult().capabilities =
                openNetworkScanDetail.getScanResult().capabilities;

        // Try to lookup a saved network using the modified scan details. All of these should fail.
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(openNetworkScanDetail));
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(wepNetworkScanDetail));
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(pskNetworkScanDetail));
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(eapNetworkScanDetail));

        // All the cache's should be empty as well.
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(openNetwork.networkId));
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(wepNetwork.networkId));
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(pskNetwork.networkId));
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(eapNetwork.networkId));
    }

    /**
     * Verifies that scan detail cache is trimmed down when the size of the cache for a network
     * exceeds {@link WifiConfigManagerNew#SCAN_CACHE_ENTRIES_MAX_SIZE}.
     */
    @Test
    public void testScanDetailCacheTrimForNetwork() {
        // Add a single network.
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        verifyAddNetworkToWifiConfigManager(openNetwork);

        ScanDetailCache scanDetailCache;
        String testBssidPrefix = "00:a5:b8:c9:45:";

        // Modify |BSSID| field in the scan result and add copies of scan detail
        // |SCAN_CACHE_ENTRIES_MAX_SIZE| times.
        int scanDetailNum = 1;
        for (; scanDetailNum <= WifiConfigManagerNew.SCAN_CACHE_ENTRIES_MAX_SIZE; scanDetailNum++) {
            // Create dummy scan detail caches with different BSSID for the network.
            ScanDetail scanDetail =
                    createScanDetailForNetwork(
                            openNetwork, String.format("%s%02x", testBssidPrefix, scanDetailNum));
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail));

            // The size of scan detail cache should keep growing until it hits
            // |SCAN_CACHE_ENTRIES_MAX_SIZE|.
            scanDetailCache =
                    mWifiConfigManager.getScanDetailCacheForNetwork(openNetwork.networkId);
            assertEquals(scanDetailNum, scanDetailCache.size());
        }

        // Now add the |SCAN_CACHE_ENTRIES_MAX_SIZE + 1| entry. This should trigger the trim.
        ScanDetail scanDetail =
                createScanDetailForNetwork(
                        openNetwork, String.format("%s%02x", testBssidPrefix, scanDetailNum));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail));

        // Retrieve the scan detail cache and ensure that the size was trimmed down to
        // |SCAN_CACHE_ENTRIES_TRIM_SIZE + 1|. The "+1" is to account for the new entry that
        // was added after the trim.
        scanDetailCache = mWifiConfigManager.getScanDetailCacheForNetwork(openNetwork.networkId);
        assertEquals(WifiConfigManagerNew.SCAN_CACHE_ENTRIES_TRIM_SIZE + 1, scanDetailCache.size());
    }

    /**
     * This method sets defaults in the provided WifiConfiguration object if not set
     * so that it can be used for comparison with the configuration retrieved from
     * WifiConfigManager.
     */
    private void setDefaults(WifiConfiguration configuration) {
        if (configuration.allowedAuthAlgorithms.isEmpty()) {
            configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        }
        if (configuration.allowedProtocols.isEmpty()) {
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        }
        if (configuration.allowedKeyManagement.isEmpty()) {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        }
        if (configuration.allowedPairwiseCiphers.isEmpty()) {
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        }
        if (configuration.allowedGroupCiphers.isEmpty()) {
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        }
        if (configuration.getIpAssignment() == IpConfiguration.IpAssignment.UNASSIGNED) {
            configuration.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        }
        if (configuration.getProxySettings() == IpConfiguration.ProxySettings.UNASSIGNED) {
            configuration.setProxySettings(IpConfiguration.ProxySettings.NONE);
        }
    }

    /**
     * Modifies the provided configuration with creator uid, package name
     * and time.
     */
    private void setCreationDebugParams(WifiConfiguration configuration) {
        configuration.creatorUid = configuration.lastUpdateUid = TEST_CREATOR_UID;
        configuration.creatorName = configuration.lastUpdateName = TEST_CREATOR_NAME;
        configuration.creationTime = configuration.updateTime =
                WifiConfigManagerNew.createDebugTimeStampString(
                        TEST_WALLCLOCK_CREATION_TIME_MILLIS);
    }

    /**
     * Modifies the provided configuration with update uid, package name
     * and time.
     */
    private void setUpdateDebugParams(WifiConfiguration configuration) {
        configuration.lastUpdateUid = TEST_UPDATE_UID;
        configuration.lastUpdateName = TEST_UPDATE_NAME;
        configuration.updateTime =
                WifiConfigManagerNew.createDebugTimeStampString(TEST_WALLCLOCK_UPDATE_TIME_MILLIS);
    }

    private void assertNotEquals(Object expected, Object actual) {
        if (actual != null) {
            assertFalse(actual.equals(expected));
        } else {
            assertNotNull(expected);
        }
    }

    /**
     * Modifies the provided WifiConfiguration with the specified bssid value. Also, asserts that
     * the existing |BSSID| field is not the same value as the one being set
     */
    private void assertAndSetNetworkBSSID(WifiConfiguration configuration, String bssid) {
        assertNotEquals(bssid, configuration.BSSID);
        configuration.BSSID = bssid;
    }

    /**
     * Modifies the provided WifiConfiguration with the specified |IpConfiguration| object. Also,
     * asserts that the existing |mIpConfiguration| field is not the same value as the one being set
     */
    private void assertAndSetNetworkIpConfiguration(
            WifiConfiguration configuration, IpConfiguration ipConfiguration) {
        assertNotEquals(ipConfiguration, configuration.getIpConfiguration());
        configuration.setIpConfiguration(ipConfiguration);
    }

    /**
     * Modifies the provided WifiConfiguration with the specified |wepKeys| value and
     * |wepTxKeyIndex|.
     */
    private void assertAndSetNetworkWepKeysAndTxIndex(
            WifiConfiguration configuration, String[] wepKeys, int wepTxKeyIdx) {
        assertNotEquals(wepKeys, configuration.wepKeys);
        assertNotEquals(wepTxKeyIdx, configuration.wepTxKeyIndex);
        configuration.wepKeys = Arrays.copyOf(wepKeys, wepKeys.length);
        configuration.wepTxKeyIndex = wepTxKeyIdx;
    }

    /**
     * Modifies the provided WifiConfiguration with the specified |preSharedKey| value.
     */
    private void assertAndSetNetworkPreSharedKey(
            WifiConfiguration configuration, String preSharedKey) {
        assertNotEquals(preSharedKey, configuration.preSharedKey);
        configuration.preSharedKey = preSharedKey;
    }

    /**
     * Modifies the provided WifiConfiguration with the specified enteprise |password| value.
     */
    private void assertAndSetNetworkEnterprisePassword(
            WifiConfiguration configuration, String password) {
        assertNotEquals(password, configuration.enterpriseConfig.getPassword());
        configuration.enterpriseConfig.setPassword(password);
    }

    /**
     * Returns whether the provided network was in the store data or not.
     */
    private boolean isNetworkInConfigStoreData(WifiConfiguration configuration) {
        try {
            ArgumentCaptor<WifiConfigStoreData> storeDataCaptor =
                    ArgumentCaptor.forClass(WifiConfigStoreData.class);
            mContextConfigStoreMockOrder.verify(mWifiConfigStore)
                    .write(anyBoolean(), storeDataCaptor.capture());

            WifiConfigStoreData storeData = storeDataCaptor.getValue();

            boolean foundNetworkInStoreData = false;
            for (WifiConfiguration retrievedConfig : storeData.configurations) {
                if (retrievedConfig.configKey().equals(configuration.configKey())) {
                    foundNetworkInStoreData = true;
                }
            }
            return foundNetworkInStoreData;
        } catch (Exception e) {
            fail("Exception encountered during write " + e);
        }
        return false;
    }

    /**
     * Verifies that the provided network was not present in the last config store write.
     */
    private void verifyNetworkNotInConfigStoreData(WifiConfiguration configuration) {
        assertFalse(isNetworkInConfigStoreData(configuration));
    }

    /**
     * Verifies that the provided network was present in the last config store write.
     */
    private void verifyNetworkInConfigStoreData(WifiConfiguration configuration) {
        assertTrue(isNetworkInConfigStoreData(configuration));
    }

    private void assertPasswordsMaskedInWifiConfiguration(WifiConfiguration configuration) {
        if (!TextUtils.isEmpty(configuration.preSharedKey)) {
            assertEquals(WifiConfigManagerNew.PASSWORD_MASK, configuration.preSharedKey);
        }
        if (configuration.wepKeys != null) {
            for (int i = 0; i < configuration.wepKeys.length; i++) {
                if (!TextUtils.isEmpty(configuration.wepKeys[i])) {
                    assertEquals(WifiConfigManagerNew.PASSWORD_MASK, configuration.wepKeys[i]);
                }
            }
        }
        if (!TextUtils.isEmpty(configuration.enterpriseConfig.getPassword())) {
            assertEquals(
                    WifiConfigManagerNew.PASSWORD_MASK,
                    configuration.enterpriseConfig.getPassword());
        }
    }

    /**
     * Verifies that the network was present in the network change broadcast and returns the
     * change reason.
     */
    private int verifyNetworkInBroadcastAndReturnReason(WifiConfiguration configuration) {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<UserHandle> userHandleCaptor = ArgumentCaptor.forClass(UserHandle.class);
        mContextConfigStoreMockOrder.verify(mContext)
                .sendBroadcastAsUser(intentCaptor.capture(), userHandleCaptor.capture());

        assertEquals(userHandleCaptor.getValue(), UserHandle.ALL);
        Intent intent = intentCaptor.getValue();

        int changeReason = intent.getIntExtra(WifiManager.EXTRA_CHANGE_REASON, -1);
        WifiConfiguration retrievedConfig =
                (WifiConfiguration) intent.getExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
        assertEquals(retrievedConfig.configKey(), configuration.configKey());

        // Verify that all the passwords are masked in the broadcast configuration.
        assertPasswordsMaskedInWifiConfiguration(retrievedConfig);

        return changeReason;
    }

    /**
     * Verifies that we sent out an add broadcast with the provided network.
     */
    private void verifyNetworkAddBroadcast(WifiConfiguration configuration) {
        assertEquals(
                verifyNetworkInBroadcastAndReturnReason(configuration),
                WifiManager.CHANGE_REASON_ADDED);
    }

    /**
     * Verifies that we sent out an update broadcast with the provided network.
     */
    private void verifyNetworkUpdateBroadcast(WifiConfiguration configuration) {
        assertEquals(
                verifyNetworkInBroadcastAndReturnReason(configuration),
                WifiManager.CHANGE_REASON_CONFIG_CHANGE);
    }

    /**
     * Verifies that we sent out a remove broadcast with the provided network.
     */
    private void verifyNetworkRemoveBroadcast(WifiConfiguration configuration) {
        assertEquals(
                verifyNetworkInBroadcastAndReturnReason(configuration),
                WifiManager.CHANGE_REASON_REMOVED);
    }

    /**
     * Adds the provided configuration to WifiConfigManager and modifies the provided configuration
     * with creator/update uid, package name and time. This also sets defaults for fields not
     * populated.
     * These fields are populated internally by WifiConfigManager and hence we need
     * to modify the configuration before we compare the added network with the retrieved network.
     */
    private NetworkUpdateResult addNetworkToWifiConfigManager(WifiConfiguration configuration) {
        when(mClock.getWallClockMillis()).thenReturn(TEST_WALLCLOCK_CREATION_TIME_MILLIS);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(configuration, TEST_CREATOR_UID);
        setDefaults(configuration);
        setCreationDebugParams(configuration);
        configuration.networkId = result.getNetworkId();
        return result;
    }

    /**
     * Add network to WifiConfigManager and ensure that it was successful.
     */
    private NetworkUpdateResult verifyAddNetworkToWifiConfigManager(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = addNetworkToWifiConfigManager(configuration);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());

        verifyNetworkAddBroadcast(configuration);
        // Verify that the config store write was triggered with this new configuration.
        verifyNetworkInConfigStoreData(configuration);
        return result;
    }

    /**
     * Add ephemeral network to WifiConfigManager and ensure that it was successful.
     */
    private NetworkUpdateResult verifyAddEphemeralNetworkToWifiConfigManager(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = addNetworkToWifiConfigManager(configuration);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());

        verifyNetworkAddBroadcast(configuration);
        // Ephemeral networks should not be persisted.
        verifyNetworkNotInConfigStoreData(configuration);
        return result;
    }

    /**
     * Updates the provided configuration to WifiConfigManager and modifies the provided
     * configuration with update uid, package name and time.
     * These fields are populated internally by WifiConfigManager and hence we need
     * to modify the configuration before we compare the added network with the retrieved network.
     */
    private NetworkUpdateResult updateNetworkToWifiConfigManager(WifiConfiguration configuration) {
        when(mClock.getWallClockMillis()).thenReturn(TEST_WALLCLOCK_UPDATE_TIME_MILLIS);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(configuration, TEST_UPDATE_UID);
        setUpdateDebugParams(configuration);
        return result;
    }

    /**
     * Update network to WifiConfigManager config change and ensure that it was successful.
     */
    private NetworkUpdateResult verifyUpdateNetworkToWifiConfigManager(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = updateNetworkToWifiConfigManager(configuration);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertFalse(result.isNewNetwork());

        verifyNetworkUpdateBroadcast(configuration);
        // Verify that the config store write was triggered with this new configuration.
        verifyNetworkInConfigStoreData(configuration);
        return result;
    }

    /**
     * Update network to WifiConfigManager without IP config change and ensure that it was
     * successful.
     */
    private NetworkUpdateResult verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = verifyUpdateNetworkToWifiConfigManager(configuration);
        assertFalse(result.hasIpChanged());
        assertFalse(result.hasProxyChanged());
        return result;
    }

    /**
     * Update network to WifiConfigManager with IP config change and ensure that it was
     * successful.
     */
    private NetworkUpdateResult verifyUpdateNetworkToWifiConfigManagerWithIpChange(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = verifyUpdateNetworkToWifiConfigManager(configuration);
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());
        return result;
    }

    /**
     * Removes network from WifiConfigManager and ensure that it was successful.
     */
    private void verifyRemoveNetworkFromWifiConfigManager(
            WifiConfiguration configuration) {
        assertTrue(mWifiConfigManager.removeNetwork(configuration.networkId));

        verifyNetworkRemoveBroadcast(configuration);
        // Verify if the config store write was triggered without this new configuration.
        verifyNetworkNotInConfigStoreData(configuration);
    }

    /**
     * Verifies the provided network's public status and ensures that the network change broadcast
     * has been sent out.
     */
    private void verifyUpdateNetworkStatus(WifiConfiguration configuration, int status) {
        assertEquals(status, configuration.status);
        verifyNetworkUpdateBroadcast(configuration);
    }

    /**
     * Verifies the network's selection status update.
     *
     * For temporarily disabled reasons, the method ensures that the status has changed only if
     * disable reason counter has exceeded the threshold.
     *
     * For permanently disabled/enabled reasons, the method ensures that the public status has
     * changed and the network change broadcast has been sent out.
     */
    private void verifyUpdateNetworkSelectionStatus(
            int networkId, int reason, int temporaryDisableReasonCounter) {
        when(mClock.getElapsedSinceBootMillis())
                .thenReturn(TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS);

        // Fetch the current status of the network before we try to update the status.
        WifiConfiguration retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(networkId);
        NetworkSelectionStatus currentStatus = retrievedNetwork.getNetworkSelectionStatus();
        int currentDisableReason = currentStatus.getNetworkSelectionDisableReason();

        // First set the status to the provided reason.
        assertTrue(mWifiConfigManager.updateNetworkSelectionStatus(networkId, reason));

        // Now fetch the network configuration and verify the new status of the network.
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(networkId);

        NetworkSelectionStatus retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        int retrievedDisableReason = retrievedStatus.getNetworkSelectionDisableReason();
        long retrievedDisableTime = retrievedStatus.getDisableTime();
        int retrievedDisableReasonCounter = retrievedStatus.getDisableReasonCounter(reason);
        int disableReasonThreshold =
                WifiConfigManagerNew.NETWORK_SELECTION_DISABLE_THRESHOLD[reason];

        if (reason == NetworkSelectionStatus.NETWORK_SELECTION_ENABLE) {
            assertEquals(reason, retrievedDisableReason);
            assertTrue(retrievedStatus.isNetworkEnabled());
            assertEquals(
                    NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP,
                    retrievedDisableTime);
            verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.ENABLED);
        } else if (reason < NetworkSelectionStatus.DISABLED_TLS_VERSION_MISMATCH) {
            // For temporarily disabled networks, we need to ensure that the current status remains
            // until the threshold is crossed.
            assertEquals(temporaryDisableReasonCounter, retrievedDisableReasonCounter);
            if (retrievedDisableReasonCounter < disableReasonThreshold) {
                assertEquals(currentDisableReason, retrievedDisableReason);
                assertEquals(
                        currentStatus.getNetworkSelectionStatus(),
                        retrievedStatus.getNetworkSelectionStatus());
            } else {
                assertEquals(reason, retrievedDisableReason);
                assertTrue(retrievedStatus.isNetworkTemporaryDisabled());
                assertEquals(
                        TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS, retrievedDisableTime);
            }
        } else if (reason < NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX) {
            assertEquals(reason, retrievedDisableReason);
            assertTrue(retrievedStatus.isNetworkPermanentlyDisabled());
            assertEquals(
                    NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP,
                    retrievedDisableTime);
            verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.DISABLED);
        }
    }

    /**
     * Creates a scan detail corresponding to the provided network and BSSID value.
     */
    private ScanDetail createScanDetailForNetwork(WifiConfiguration configuration, String bssid) {
        String caps;
        if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            caps = "[WPA2-PSK-CCMP]";
        } else if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            caps = "[WPA2-EAP-CCMP]";
        } else if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                && WifiConfigurationUtil.hasAnyValidWepKey(configuration.wepKeys)) {
            caps = "[WEP]";
        } else {
            caps = "[]";
        }
        WifiSsid ssid = WifiSsid.createFromAsciiEncoded(configuration.getPrintableSsid());
        // Fill in 0's in the fields we don't care about.
        return new ScanDetail(
                ssid, bssid, caps, 0, 0, SystemClock.uptimeMillis(), System.currentTimeMillis());
    }

    /**
     * Creates a scan detail corresponding to the provided network and fixed BSSID value.
     */
    private ScanDetail createScanDetailForNetwork(WifiConfiguration configuration) {
        return createScanDetailForNetwork(configuration, TEST_BSSID);
    }

    /**
     * Adds the provided network and then creates a scan detail corresponding to the network. The
     * method then creates a ScanDetail corresponding to the network and ensures that the network
     * is properly matched using
     * {@link WifiConfigManagerNew#getSavedNetworkForScanDetailAndCache(ScanDetail)} and also
     * verifies that the provided scan detail was cached,
     */
    private void verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
            WifiConfiguration network) {
        // First add the provided network.
        verifyAddNetworkToWifiConfigManager(network);

        // Now create a dummy scan detail corresponding to the network.
        ScanDetail scanDetail = createScanDetailForNetwork(network);
        ScanResult scanResult = scanDetail.getScanResult();

        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail);

        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, retrievedNetwork);

        // Now retrieve the scan detail cache and ensure that the new scan detail is in cache.
        ScanDetailCache retrievedScanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(network.networkId);
        assertEquals(1, retrievedScanDetailCache.size());
        ScanResult retrievedScanResult = retrievedScanDetailCache.get(scanResult.BSSID);

        ScanTestUtil.assertScanResultEquals(scanResult, retrievedScanResult);
    }
}
