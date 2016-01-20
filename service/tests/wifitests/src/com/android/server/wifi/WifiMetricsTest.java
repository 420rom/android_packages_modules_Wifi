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
 * limitations under the License
 */
package com.android.server.wifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.wifi.WifiInfo;
import android.util.Base64;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Unit tests for {@link com.android.server.wifi.WifiMetrics}.
 */
public class WifiMetricsTest {

    WifiMetrics mWifiMetrics;
    WifiMetricsProto.WifiLog mDeserializedWifiMetrics;
    @Mock WifiInfo mWifiInfo;

    @Before
    public void setUp() throws Exception {
        mWifiInfo = null;
        mDeserializedWifiMetrics = null;
        mWifiMetrics = new WifiMetrics();
    }

    /**
     * Test that startConnectionEvent and endConnectionEvent can be called repeatedly and out of
     * order. Only tests no exception occurs
     */
    @Test
    public void startAndEndConnectionEventSucceeds() throws Exception {
        mWifiInfo = mock(WifiInfo.class);
        when(mWifiInfo.getRssi()).thenReturn(77);
        //Start and end Connection event
        mWifiMetrics.startConnectionEvent(mWifiInfo,
                WifiMetricsProto.ConnectionEvent.ROAM_ENTERPRISE);
        mWifiMetrics.endConnectionEvent(88, WifiMetricsProto.ConnectionEvent.HLF_DHCP);
        //end Connection event without starting one
        mWifiMetrics.endConnectionEvent(99, WifiMetricsProto.ConnectionEvent.HLF_DHCP);
        //start two ConnectionEvents in a row
        mWifiMetrics.startConnectionEvent(mWifiInfo,
                WifiMetricsProto.ConnectionEvent.ROAM_ENTERPRISE);
        mWifiMetrics.startConnectionEvent(mWifiInfo,
                WifiMetricsProto.ConnectionEvent.ROAM_ENTERPRISE);
    }

    /**
     * Test WifiMetrics can be serialized and de-serialized
     */
    public void serializeDeserialize() throws Exception {
        byte[] serializedWifiMetrics = mWifiMetrics.toByteArray();
        mDeserializedWifiMetrics = WifiMetricsProto.WifiLog.parseFrom(
                serializedWifiMetrics);
    }

    @Test
    public void dumpHumanReadable() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        String[] args = new String[0];
        mWifiMetrics.dump(null, writer, args);
        writer.flush();
        assertTrue("stream.toString().contains(\"WifiMetrics\")",
                stream.toString().contains("WifiMetrics"));
    }

    @Test
    public void dumpProtoAndDeserialize() throws Exception {
        setAndIncrementMetrics();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        String[] args = new String[0];
        //Test proto dump, by passing in proto arg option
        args = new String[]{"proto"};
        mWifiMetrics.dump(null, writer, args);
        writer.flush();
        Pattern pattern = Pattern.compile(
                "(?<=WifiMetrics:\\n)([\\s\\S]*)(?=EndWifiMetrics)");
        Matcher matcher = pattern.matcher(stream.toString());
        assertTrue("Proto Byte string found in WifiMetrics.dump():\n" + stream.toString(),
                matcher.find());
        String protoByteString = matcher.group(1);
        byte[] protoBytes = Base64.decode(protoByteString, Base64.DEFAULT);
        mDeserializedWifiMetrics = WifiMetricsProto.WifiLog.parseFrom(protoBytes);
        assertDeserializedMetricsCorrect();
    }

    private static final int NUM_SAVED_NETWORKS = 1;
    private static final int NUM_OPEN_NETWORKS = 2;
    private static final int NUM_PERSONAL_NETWORKS = 3;
    private static final int NUM_ENTERPRISE_NETWORKS = 5;
    private static final boolean TEST_VAL_IS_LOCATION_ENABLED = true;
    private static final boolean IS_SCANNING_ALWAYS_ENABLED = true;
    private static final int NUM_WIFI_TOGGLED_VIA_SETTINGS = 7;
    private static final int NUM_WIFI_TOGGLED_VIA_AIRPLANE = 11;
    private static final int NUM_NEWTORKS_ADDED_BY_USER = 13;
    private static final int NUM_NEWTORKS_ADDED_BY_APPS = 17;
    private static final int NUM_EMPTY_SCAN_RESULTS = 19;
    private static final int NUM_NON_EMPTY_SCAN_RESULTS = 23;
    private static final int NUM_INCREMENTS = 10;

    /**
     * Set simple metrics, increment others
     */
    public void setAndIncrementMetrics() throws Exception {
        mWifiMetrics.setNumSavedNetworks(NUM_SAVED_NETWORKS);
        mWifiMetrics.setNumOpenNetworks(NUM_OPEN_NETWORKS);
        mWifiMetrics.setNumPersonalNetworks(NUM_PERSONAL_NETWORKS);
        mWifiMetrics.setNumEnterpriseNetworks(NUM_ENTERPRISE_NETWORKS);
        mWifiMetrics.setNumNetworksAddedByUser(NUM_NEWTORKS_ADDED_BY_USER);
        mWifiMetrics.setNumNetworksAddedByApps(NUM_NEWTORKS_ADDED_BY_APPS);
        mWifiMetrics.setIsLocationEnabled(TEST_VAL_IS_LOCATION_ENABLED);
        mWifiMetrics.setIsScanningAlwaysEnabled(IS_SCANNING_ALWAYS_ENABLED);

        for (int i = 0; i < NUM_WIFI_TOGGLED_VIA_AIRPLANE; i++) {
            mWifiMetrics.incrementAirplaneToggleCount();
        }
        for (int i = 0; i < NUM_WIFI_TOGGLED_VIA_SETTINGS; i++) {
            mWifiMetrics.incrementWifiToggleCount();
        }
        for (int i = 0; i < NUM_EMPTY_SCAN_RESULTS; i++) {
            mWifiMetrics.incrementEmptyScanResultCount();
        }
        for (int i = 0; i < NUM_NON_EMPTY_SCAN_RESULTS; i++) {
            mWifiMetrics.incrementNonEmptyScanResultCount();
        }

        //Test incrementing counts
        for (int i = 0; i < NUM_INCREMENTS; i++) {

            mWifiMetrics.incrementScanReturnEntry(WifiMetricsProto.WifiLog.SCAN_UNKNOWN);
            mWifiMetrics.incrementScanReturnEntry(WifiMetricsProto.WifiLog.SCAN_SUCCESS);
            mWifiMetrics.incrementScanReturnEntry(
                    WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED);
            mWifiMetrics.incrementScanReturnEntry(
                    WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION);
            mWifiMetrics.incrementWifiSystemScanStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN,
                    false);
            mWifiMetrics.incrementWifiSystemScanStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN,
                    true);
            mWifiMetrics.incrementWifiSystemScanStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED,
                    false);
            mWifiMetrics.incrementWifiSystemScanStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED,
                    true);
        }
    }

    /**
     * Assert that values in deserializedWifiMetrics match those set in 'setAndIncrementMetrics'
     */
    public void assertDeserializedMetricsCorrect() throws Exception {
        assertEquals("mDeserializedWifiMetrics.numSavedNetworks == NUM_SAVED_NETWORKS",
                mDeserializedWifiMetrics.numSavedNetworks, NUM_SAVED_NETWORKS);
        assertEquals("mDeserializedWifiMetrics.numOpenNetworks == NUM_OPEN_NETWORKS",
                mDeserializedWifiMetrics.numOpenNetworks, NUM_OPEN_NETWORKS);
        assertEquals("mDeserializedWifiMetrics.numPersonalNetworks == NUM_PERSONAL_NETWORKS",
                mDeserializedWifiMetrics.numPersonalNetworks, NUM_PERSONAL_NETWORKS);
        assertEquals("mDeserializedWifiMetrics.numEnterpriseNetworks "
                        + "== NUM_ENTERPRISE_NETWORKS",
                mDeserializedWifiMetrics.numEnterpriseNetworks, NUM_ENTERPRISE_NETWORKS);
        assertEquals("mDeserializedWifiMetrics.numNetworksAddedByUser "
                        + "== NUM_NEWTORKS_ADDED_BY_USER",
                mDeserializedWifiMetrics.numNetworksAddedByUser, NUM_NEWTORKS_ADDED_BY_USER);
        assertEquals("mDeserializedWifiMetrics.numNetworksAddedByApps "
                        + "== NUM_NEWTORKS_ADDED_BY_APPS",
                mDeserializedWifiMetrics.numNetworksAddedByApps, NUM_NEWTORKS_ADDED_BY_APPS);
        assertEquals("mDeserializedWifiMetrics.isLocationEnabled == TEST_VAL_IS_LOCATION_ENABLED",
                mDeserializedWifiMetrics.isLocationEnabled, TEST_VAL_IS_LOCATION_ENABLED);
        assertEquals("mDeserializedWifiMetrics.isScanningAlwaysEnabled "
                        + "== IS_SCANNING_ALWAYS_ENABLED",
                mDeserializedWifiMetrics.isScanningAlwaysEnabled, IS_SCANNING_ALWAYS_ENABLED);
        assertEquals("mDeserializedWifiMetrics.numWifiToggledViaSettings == "
                        + "NUM_WIFI_TOGGLED_VIA_SETTINGS",
                mDeserializedWifiMetrics.numWifiToggledViaSettings,
                NUM_WIFI_TOGGLED_VIA_SETTINGS);
        assertEquals("mDeserializedWifiMetrics.numWifiToggledViaAirplane == "
                        + "NUM_WIFI_TOGGLED_VIA_AIRPLANE",
                mDeserializedWifiMetrics.numWifiToggledViaAirplane,
                NUM_WIFI_TOGGLED_VIA_AIRPLANE);
        assertEquals("mDeserializedWifiMetrics.numEmptyScanResults == NUM_EMPTY_SCAN_RESULTS",
                mDeserializedWifiMetrics.numEmptyScanResults, NUM_EMPTY_SCAN_RESULTS);
        assertEquals("mDeserializedWifiMetrics.numNonEmptyScanResults == "
                        + "NUM_NON_EMPTY_SCAN_RESULTS",
                mDeserializedWifiMetrics.numNonEmptyScanResults, NUM_NON_EMPTY_SCAN_RESULTS);
    }

    /**
     * Combination of all other WifiMetrics unit tests, an internal-integration test, or functional
     * test
     */
    @Test
    public void setMetricsSerializeDeserializeAssertMetricsSame() throws Exception {
        setAndIncrementMetrics();
        startAndEndConnectionEventSucceeds();
        serializeDeserialize();
        assertDeserializedMetricsCorrect();
        assertEquals("mDeserializedWifiMetrics.connectionEvent.length == 4",
                mDeserializedWifiMetrics.connectionEvent.length, 4);
        //<TODO> test individual connectionEvents for correctness,
        // check scanReturnEntries & wifiSystemStateEntries counts and individual elements
        // pending their implementation</TODO>
    }
}
