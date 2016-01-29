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

import static com.android.server.wifi.ScanTestUtil.NativeScanSettingsBuilder;
import static com.android.server.wifi.ScanTestUtil.assertNativeScanSettingsEquals;
import static com.android.server.wifi.ScanTestUtil.assertScanDatasEquals;
import static com.android.server.wifi.ScanTestUtil.channelsToSpec;
import static com.android.server.wifi.ScanTestUtil.computeSingleScanNativeSettings;
import static com.android.server.wifi.ScanTestUtil.createRequest;
import static com.android.server.wifi.ScanTestUtil.createSingleScanNativeSettings;
import static com.android.server.wifi.ScanTestUtil.installWlanWifiNative;
import static com.android.server.wifi.ScanTestUtil.setupMockChannels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Protocol;
import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiScanningServiceImpl}.
 */
@SmallTest
public class WifiScanningServiceTest {
    public static final String TAG = "WifiScanningServiceTest";

    @Mock Context mContext;
    @Mock WifiNative mWifiNative;
    @Mock WifiScannerImpl mWifiScannerImpl;
    @Mock WifiScannerImpl.WifiScannerImplFactory mWifiScannerImplFactory;
    @Mock IBatteryStats mBatteryStats;
    MockLooper mLooper;
    WifiScanningServiceImpl mWifiScanningServiceImpl;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        setupMockChannels(mWifiNative,
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650, 5660});
        installWlanWifiNative(mWifiNative);

        mLooper = new MockLooper();
        when(mWifiScannerImplFactory.create(any(Context.class), any(Looper.class)))
                .thenReturn(mWifiScannerImpl);
        mWifiScanningServiceImpl = new WifiScanningServiceImpl(mContext, mLooper.getLooper(),
                mWifiScannerImplFactory, mBatteryStats);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
    }


    /**
     * Internal BroadcastReceiver that WifiScanningServiceImpl uses to listen for broadcasts
     * this is initialized by calling startServiceAndLoadDriver
     */
    BroadcastReceiver mBroadcastReceiver;

    private void sendWifiScanAvailable(int scanAvailable) {
        Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
        intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, scanAvailable);
        mBroadcastReceiver.onReceive(mContext, intent);
    }

    private WifiScanner.ScanSettings generateValidScanSettings() {
        return createRequest(WifiScanner.WIFI_BAND_BOTH, 30000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
    }

    private BidirectionalAsyncChannel connectChannel(Handler handler) {
        BidirectionalAsyncChannel controlChannel = new BidirectionalAsyncChannel();
        controlChannel.connect(mLooper.getLooper(), mWifiScanningServiceImpl.getMessenger(),
                handler);
        mLooper.dispatchAll();
        controlChannel.assertConnected();
        return controlChannel;
    }

    private Message verifyHandleMessageAndGetMessage(InOrder order, Handler handler) {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        order.verify(handler).handleMessage(messageCaptor.capture());
        return messageCaptor.getValue();
    }

    private void verifyScanResultsRecieved(InOrder order, Handler handler, int listenerId,
            WifiScanner.ScanData... expected) {
        Message scanResultMessage = verifyHandleMessageAndGetMessage(order, handler);
        assertScanResultsMessage(listenerId, expected, scanResultMessage);
    }

    private void assertScanResultsMessage(int listenerId, WifiScanner.ScanData[] expected,
            Message scanResultMessage) {
        assertEquals("what", WifiScanner.CMD_SCAN_RESULT, scanResultMessage.what);
        assertEquals("listenerId", listenerId, scanResultMessage.arg2);
        assertScanDatasEquals(expected,
                ((WifiScanner.ParcelableScanData) scanResultMessage.obj).getResults());
    }

    private void sendBackgroundScanRequest(BidirectionalAsyncChannel controlChannel,
            int scanRequestId, WifiScanner.ScanSettings settings) {
        controlChannel.sendMessage(Message.obtain(null, WifiScanner.CMD_START_BACKGROUND_SCAN, 0,
                        scanRequestId, settings));
    }

    private void sendSingleScanRequest(BidirectionalAsyncChannel controlChannel,
            int scanRequestId, WifiScanner.ScanSettings settings) {
        controlChannel.sendMessage(Message.obtain(null, WifiScanner.CMD_START_SINGLE_SCAN, 0,
                        scanRequestId, settings));
    }

    private void verifySuccessfulResponse(InOrder order, Handler handler, int arg2) {
        Message response = verifyHandleMessageAndGetMessage(order, handler);
        assertSuccessfulResponse(arg2, response);
    }

    private void assertSuccessfulResponse(int arg2, Message response) {
        if (response.what == WifiScanner.CMD_OP_FAILED) {
            WifiScanner.OperationResult result = (WifiScanner.OperationResult) response.obj;
            fail("response indicates failure, reason=" + result.reason
                    + ", description=" + result.description);
        } else {
            assertEquals("response.what", WifiScanner.CMD_OP_SUCCEEDED, response.what);
            assertEquals("response.arg2", arg2, response.arg2);
        }
    }

    private void verifyFailedResponse(InOrder order, Handler handler, int arg2,
            int expectedErrorReason, String expectedErrorDescription) {
        Message response = verifyHandleMessageAndGetMessage(order, handler);
        assertFailedResponse(arg2, expectedErrorReason, expectedErrorDescription, response);
    }

    private void assertFailedResponse(int arg2, int expectedErrorReason,
            String expectedErrorDescription, Message response) {
        if (response.what == WifiScanner.CMD_OP_SUCCEEDED) {
            fail("response indicates success");
        } else {
            assertEquals("response.what", WifiScanner.CMD_OP_FAILED, response.what);
            assertEquals("response.arg2", arg2, response.arg2);
            WifiScanner.OperationResult result = (WifiScanner.OperationResult) response.obj;
            assertEquals("response.obj.reason",
                    expectedErrorReason, result.reason);
            assertEquals("response.obj.description",
                    expectedErrorDescription, result.description);
        }
    }

    private WifiNative.ScanEventHandler verifyStartSingleScan(InOrder order,
            WifiNative.ScanSettings expected) {
        ArgumentCaptor<WifiNative.ScanSettings> scanSettingsCaptor =
                ArgumentCaptor.forClass(WifiNative.ScanSettings.class);
        ArgumentCaptor<WifiNative.ScanEventHandler> scanEventHandlerCaptor =
                ArgumentCaptor.forClass(WifiNative.ScanEventHandler.class);
        order.verify(mWifiScannerImpl).startSingleScan(scanSettingsCaptor.capture(),
                scanEventHandlerCaptor.capture());
        assertNativeScanSettingsEquals(expected, scanSettingsCaptor.getValue());
        return scanEventHandlerCaptor.getValue();
    }

    private void verifyStartBackgroundScan(InOrder order, WifiNative.ScanSettings expected) {
        ArgumentCaptor<WifiNative.ScanSettings> scanSettingsCaptor =
                ArgumentCaptor.forClass(WifiNative.ScanSettings.class);
        order.verify(mWifiScannerImpl).startBatchedScan(scanSettingsCaptor.capture(),
                any(WifiNative.ScanEventHandler.class));
        assertNativeScanSettingsEquals(expected, scanSettingsCaptor.getValue());
    }

    private static final int MAX_AP_PER_SCAN = 16;
    private void startServiceAndLoadDriver() {
        mWifiScanningServiceImpl.startService();
        when(mWifiScannerImpl.getScanCapabilities(any(WifiNative.ScanCapabilities.class)))
                .thenAnswer(new AnswerWithArguments() {
                        public boolean answer(WifiNative.ScanCapabilities capabilities) {
                            capabilities.max_scan_cache_size = Integer.MAX_VALUE;
                            capabilities.max_scan_buckets = 8;
                            capabilities.max_ap_cache_per_scan = MAX_AP_PER_SCAN;
                            capabilities.max_rssi_sample_size = 8;
                            capabilities.max_scan_reporting_threshold = 10;
                            capabilities.max_hotlist_bssids = 0;
                            capabilities.max_significant_wifi_change_aps = 0;
                            return true;
                        }
                    });
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(IntentFilter.class));
        mBroadcastReceiver = broadcastReceiverCaptor.getValue();

        sendWifiScanAvailable(WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();
    }

    @Test
    public void construct() throws Exception {
        verifyNoMoreInteractions(mContext, mWifiScannerImpl, mWifiScannerImpl,
                mWifiScannerImplFactory, mBatteryStats);
    }

    @Test
    public void startService() throws Exception {
        mWifiScanningServiceImpl.startService();
        verifyNoMoreInteractions(mWifiScannerImplFactory);

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler);
        sendBackgroundScanRequest(controlChannel, 122, generateValidScanSettings());
        mLooper.dispatchAll();
        verifyFailedResponse(order, handler, 122, WifiScanner.REASON_UNSPECIFIED, "not available");
    }

    @Test
    public void loadDriver() throws Exception {
        startServiceAndLoadDriver();
        verify(mWifiScannerImplFactory, times(1)).create(any(Context.class), any(Looper.class));

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler);
        when(mWifiScannerImpl.startBatchedScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);
        sendBackgroundScanRequest(controlChannel, 192, generateValidScanSettings());
        mLooper.dispatchAll();
        verifySuccessfulResponse(order, handler, 192);
    }

    @Test
    public void sendInvalidCommand() throws Exception {
        startServiceAndLoadDriver();

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler, mWifiScannerImpl);
        controlChannel.sendMessage(Message.obtain(null, Protocol.BASE_WIFI_MANAGER));
        mLooper.dispatchAll();
        verifyFailedResponse(order, handler, 0, WifiScanner.REASON_INVALID_REQUEST,
                "Invalid request");
    }

    private void doSuccessfulSingleScan(WifiScanner.ScanSettings requestSettings,
            WifiNative.ScanSettings nativeSettings, ScanResults results) {
        int requestId = 12;
        startServiceAndLoadDriver();

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler, mWifiScannerImpl);

        when(mWifiScannerImpl.startSingleScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);

        sendSingleScanRequest(controlChannel, requestId, requestSettings);

        mLooper.dispatchAll();
        WifiNative.ScanEventHandler eventHandler = verifyStartSingleScan(order, nativeSettings);
        verifySuccessfulResponse(order, handler, requestId);

        when(mWifiScannerImpl.getLatestSingleScanResults())
                .thenReturn(results.getScanData());
        eventHandler.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);

        mLooper.dispatchAll();
        verifyScanResultsRecieved(order, handler, 12, results.getScanData());
        verifyNoMoreInteractions(handler);
    }

    @Test
    public void sendSingleScanBandRequest() throws Exception {
        WifiScanner.ScanSettings requestSettings = createRequest(WifiScanner.WIFI_BAND_BOTH, 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        doSuccessfulSingleScan(requestSettings, computeSingleScanNativeSettings(requestSettings),
                ScanResults.create(0, 2400, 5150, 5175));
    }

    @Test
    public void sendSingleScanChannelsRequest() throws Exception {
        WifiScanner.ScanSettings requestSettings = createRequest(channelsToSpec(2400), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        doSuccessfulSingleScan(requestSettings, computeSingleScanNativeSettings(requestSettings),
                ScanResults.create(0, 2400, 2400, 2400));
    }

    @Test
    public void sendSingleScanRequestWhichFailsToStart() throws Exception {
        WifiScanner.ScanSettings requestSettings = createRequest(WifiScanner.WIFI_BAND_BOTH, 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId = 33;

        startServiceAndLoadDriver();

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler, mWifiScannerImpl);

        // scan fails
        when(mWifiScannerImpl.startSingleScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(false);

        sendSingleScanRequest(controlChannel, requestId, requestSettings);

        mLooper.dispatchAll();
        // Scan is successfully queue, but then fails to execute
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        order.verify(handler, times(2)).handleMessage(messageCaptor.capture());
        assertSuccessfulResponse(requestId, messageCaptor.getAllValues().get(0));
        assertFailedResponse(requestId, WifiScanner.REASON_UNSPECIFIED,
                "Failed to start single scan", messageCaptor.getAllValues().get(1));
    }

    @Test
    public void sendSingleScanRequestWhichFailsAfterStart() throws Exception {
        WifiScanner.ScanSettings requestSettings = createRequest(WifiScanner.WIFI_BAND_BOTH, 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId = 33;

        startServiceAndLoadDriver();

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler, mWifiScannerImpl);

        // successful start
        when(mWifiScannerImpl.startSingleScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);

        sendSingleScanRequest(controlChannel, requestId, requestSettings);

        // Scan is successfully queue
        mLooper.dispatchAll();
        WifiNative.ScanEventHandler eventHandler =
                verifyStartSingleScan(order, computeSingleScanNativeSettings(requestSettings));
        verifySuccessfulResponse(order, handler, requestId);

        // but then fails to execute
        eventHandler.onScanStatus(WifiNative.WIFI_SCAN_DISABLED);
        mLooper.dispatchAll();
        verifyFailedResponse(order, handler, requestId,
                WifiScanner.REASON_UNSPECIFIED, "Scan was interrupted");
    }

    // TODO Add more single scan tests
    // * disable wifi while scanning
    // * disable wifi while scanning with pending scan

    @Test
    public void sendSingleScanRequestAfterPreviousCompletes() {
        WifiScanner.ScanSettings requestSettings1 = createRequest(channelsToSpec(2400), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId1 = 12;
        ScanResults results1 = ScanResults.create(0, 2400);


        WifiScanner.ScanSettings requestSettings2 = createRequest(channelsToSpec(2450, 5175), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId2 = 13;
        ScanResults results2 = ScanResults.create(0, 2450);


        startServiceAndLoadDriver();

        when(mWifiScannerImpl.startSingleScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler, mWifiScannerImpl);

        // Run scan 1
        sendSingleScanRequest(controlChannel, requestId1, requestSettings1);

        mLooper.dispatchAll();
        WifiNative.ScanEventHandler eventHandler1 = verifyStartSingleScan(order,
                computeSingleScanNativeSettings(requestSettings1));
        verifySuccessfulResponse(order, handler, requestId1);

        // dispatch scan 1 results
        when(mWifiScannerImpl.getLatestSingleScanResults())
                .thenReturn(results1.getScanData());
        eventHandler1.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);

        mLooper.dispatchAll();
        verifyScanResultsRecieved(order, handler, requestId1, results1.getScanData());

        // Run scan 2
        sendSingleScanRequest(controlChannel, requestId2, requestSettings2);

        mLooper.dispatchAll();
        WifiNative.ScanEventHandler eventHandler2 = verifyStartSingleScan(order,
                computeSingleScanNativeSettings(requestSettings2));
        verifySuccessfulResponse(order, handler, requestId2);

        // dispatch scan 2 results
        when(mWifiScannerImpl.getLatestSingleScanResults())
                .thenReturn(results2.getScanData());
        eventHandler2.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);

        mLooper.dispatchAll();
        verifyScanResultsRecieved(order, handler, requestId2, results2.getScanData());
    }

    @Test
    public void sendSingleScanRequestWhilePreviousScanRunning() {
        WifiScanner.ScanSettings requestSettings1 = createRequest(channelsToSpec(2400), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId1 = 12;
        ScanResults results1 = ScanResults.create(0, 2400);

        WifiScanner.ScanSettings requestSettings2 = createRequest(channelsToSpec(2450, 5175), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId2 = 13;
        ScanResults results2 = ScanResults.create(0, 2450);


        startServiceAndLoadDriver();

        when(mWifiScannerImpl.startSingleScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder handlerOrder = inOrder(handler);
        InOrder nativeOrder = inOrder(mWifiScannerImpl);

        // Run scan 1
        sendSingleScanRequest(controlChannel, requestId1, requestSettings1);

        mLooper.dispatchAll();
        WifiNative.ScanEventHandler eventHandler1 = verifyStartSingleScan(nativeOrder,
                computeSingleScanNativeSettings(requestSettings1));
        verifySuccessfulResponse(handlerOrder, handler, requestId1);

        // Queue scan 2 (will not run because previous is in progress)
        sendSingleScanRequest(controlChannel, requestId2, requestSettings2);
        mLooper.dispatchAll();
        verifySuccessfulResponse(handlerOrder, handler, requestId2);

        // dispatch scan 1 results
        when(mWifiScannerImpl.getLatestSingleScanResults())
                .thenReturn(results1.getScanData());
        eventHandler1.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);

        mLooper.dispatchAll();
        verifyScanResultsRecieved(handlerOrder, handler, requestId1, results1.getScanData());

        // now that the first scan completed we expect the second one to start
        WifiNative.ScanEventHandler eventHandler2 = verifyStartSingleScan(nativeOrder,
                computeSingleScanNativeSettings(requestSettings2));

        // dispatch scan 2 results
        when(mWifiScannerImpl.getLatestSingleScanResults())
                .thenReturn(results2.getScanData());
        eventHandler2.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);

        mLooper.dispatchAll();
        verifyScanResultsRecieved(handlerOrder, handler, requestId2, results2.getScanData());
    }



    @Test
    public void sendMultipleSingleScanRequestWhilePreviousScanRunning() {
        WifiScanner.ScanSettings requestSettings1 = createRequest(channelsToSpec(2400), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId1 = 12;
        ScanResults results1 = ScanResults.create(0, 2400);

        WifiScanner.ScanSettings requestSettings2 = createRequest(channelsToSpec(2450, 5175), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId2 = 13;
        ScanResults results2 = ScanResults.create(0, 2450, 5175, 2450);

        WifiScanner.ScanSettings requestSettings3 = createRequest(channelsToSpec(5150), 0,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        int requestId3 = 15;
        ScanResults results3 = ScanResults.create(0, 5150, 5150, 5150, 5150);

        WifiNative.ScanSettings nativeSettings2and3 = createSingleScanNativeSettings(
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN, channelsToSpec(2450, 5175, 5150));
        ScanResults results2and3 = ScanResults.merge(results2, results3);


        startServiceAndLoadDriver();

        when(mWifiScannerImpl.startSingleScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder handlerOrder = inOrder(handler);
        InOrder nativeOrder = inOrder(mWifiScannerImpl);

        // Run scan 1
        sendSingleScanRequest(controlChannel, requestId1, requestSettings1);

        mLooper.dispatchAll();
        WifiNative.ScanEventHandler eventHandler1 = verifyStartSingleScan(nativeOrder,
                computeSingleScanNativeSettings(requestSettings1));
        verifySuccessfulResponse(handlerOrder, handler, requestId1);

        // Queue scan 2 (will not run because previous is in progress)
        sendSingleScanRequest(controlChannel, requestId2, requestSettings2);
        mLooper.dispatchAll();
        verifySuccessfulResponse(handlerOrder, handler, requestId2);

        // Queue scan 3 (will not run because previous is in progress)
        sendSingleScanRequest(controlChannel, requestId3, requestSettings3);
        mLooper.dispatchAll();
        verifySuccessfulResponse(handlerOrder, handler, requestId3);

        // dispatch scan 1 results
        when(mWifiScannerImpl.getLatestSingleScanResults())
                .thenReturn(results1.getScanData());
        eventHandler1.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);

        mLooper.dispatchAll();
        verifyScanResultsRecieved(handlerOrder, handler, requestId1, results1.getScanData());

        // now that the first scan completed we expect the second and third ones to start
        WifiNative.ScanEventHandler eventHandler2and3 = verifyStartSingleScan(nativeOrder,
                nativeSettings2and3);

        // dispatch scan 2 and 3 results
        when(mWifiScannerImpl.getLatestSingleScanResults())
                .thenReturn(results2and3.getScanData());
        eventHandler2and3.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);

        mLooper.dispatchAll();

        // unfortunatally the order that these events are dispatched is dependant on the order which
        // they are iterated through internally
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        handlerOrder.verify(handler, times(2)).handleMessage(messageCaptor.capture());
        int firstListenerId = messageCaptor.getAllValues().get(0).arg2;
        assertTrue(firstListenerId + " was neither " + requestId2 + " nor " + requestId3,
                firstListenerId == requestId2 || firstListenerId == requestId3);
        if (firstListenerId == requestId2) {
            assertScanResultsMessage(requestId2,
                    new WifiScanner.ScanData[] {results2.getScanData()},
                    messageCaptor.getAllValues().get(0));
            assertScanResultsMessage(requestId3,
                    new WifiScanner.ScanData[] {results3.getScanData()},
                    messageCaptor.getAllValues().get(1));
        } else {
            assertScanResultsMessage(requestId3,
                    new WifiScanner.ScanData[] {results3.getScanData()},
                    messageCaptor.getAllValues().get(0));
            assertScanResultsMessage(requestId2,
                    new WifiScanner.ScanData[] {results2.getScanData()},
                    messageCaptor.getAllValues().get(1));
        }
    }

    private void doSuccessfulBackgroundScan(WifiScanner.ScanSettings requestSettings,
            WifiNative.ScanSettings nativeSettings) {
        startServiceAndLoadDriver();

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler, mWifiScannerImpl);

        when(mWifiScannerImpl.startBatchedScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);

        sendBackgroundScanRequest(controlChannel, 12, requestSettings);
        mLooper.dispatchAll();
        verifyStartBackgroundScan(order, nativeSettings);
        verifySuccessfulResponse(order, handler, 12);
        verifyNoMoreInteractions(handler);
    }

    @Test
    public void sendBackgroundScanBandRequest() throws Exception {
        WifiScanner.ScanSettings requestSettings = createRequest(WifiScanner.WIFI_BAND_BOTH, 20000,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        WifiNative.ScanSettings nativeSettings = new NativeScanSettingsBuilder()
                .withBasePeriod(20000)
                .withMaxApPerScan(MAX_AP_PER_SCAN)
                .withMaxScansToCache(WifiScanningScheduler.DEFAULT_MAX_SCANS_TO_BATCH)
                .addBucketWithBand(20000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_BOTH)
                .build();
        doSuccessfulBackgroundScan(requestSettings, nativeSettings);
    }

    @Test
    public void sendBackgroundScanChannelsRequest() throws Exception {
        WifiScanner.ScanSettings requestSettings = createRequest(channelsToSpec(5150), 20000,
                0, 20, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
        WifiNative.ScanSettings nativeSettings = new NativeScanSettingsBuilder()
                .withBasePeriod(20000)
                .withMaxApPerScan(MAX_AP_PER_SCAN)
                .withMaxScansToCache(WifiScanningScheduler.DEFAULT_MAX_SCANS_TO_BATCH)
                .addBucketWithChannels(20000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN, 5150)
                .build();
        doSuccessfulBackgroundScan(requestSettings, nativeSettings);
    }
}
