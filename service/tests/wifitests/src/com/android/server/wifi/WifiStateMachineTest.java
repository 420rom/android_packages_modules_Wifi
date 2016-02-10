/*
 * Copyright (C) 2015 The Android Open Source Project
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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.ip.IpManager;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.p2p.IWifiP2pManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.INetworkManagementService;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IState;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link com.android.server.wifi.WifiStateMachine}.
 */
@SmallTest
public class WifiStateMachineTest {
    public static final String TAG = "WifiStateMachineTest";

    private static <T> T mockWithInterfaces(Class<T> class1, Class<?>... interfaces) {
        return mock(class1, withSettings().extraInterfaces(interfaces));
    }

    private static <T, I> IBinder mockService(Class<T> class1, Class<I> iface) {
        T tImpl = mockWithInterfaces(class1, iface);
        IBinder binder = mock(IBinder.class);
        when(((IInterface) tImpl).asBinder()).thenReturn(binder);
        when(binder.queryLocalInterface(iface.getCanonicalName()))
                .thenReturn((IInterface) tImpl);
        return binder;
    }

    private void enableDebugLogs() {
        mWsm.enableVerboseLogging(1);
    }

    private class TestIpManager extends IpManager {
        TestIpManager(Context context, String ifname, IpManager.Callback callback) {
            // Call test-only superclass constructor.
            super(ifname, callback);
        }

        @Override
        public void startProvisioning() {}

        @Override
        public void stop() {}

        @Override
        public void confirmConfiguration() {}
    }

    private FrameworkFacade getFrameworkFacade() throws InterruptedException {
        FrameworkFacade facade = mock(FrameworkFacade.class);

        when(facade.makeBaseLogger()).thenReturn(mock(BaseWifiLogger.class));
        when(facade.getService(Context.NETWORKMANAGEMENT_SERVICE)).thenReturn(
                mockWithInterfaces(IBinder.class, INetworkManagementService.class));

        IBinder p2pBinder = mockService(WifiP2pServiceImpl.class, IWifiP2pManager.class);
        when(facade.getService(Context.WIFI_P2P_SERVICE)).thenReturn(p2pBinder);

        WifiP2pServiceImpl p2pm = (WifiP2pServiceImpl) p2pBinder.queryLocalInterface(
                IWifiP2pManager.class.getCanonicalName());

        final Object sync = new Object();
        synchronized (sync) {
            mP2pThread = new HandlerThread("WifiP2pMockThread") {
                @Override
                protected void onLooperPrepared() {
                    synchronized (sync) {
                        sync.notifyAll();
                    }
                }
            };

            mP2pThread.start();
            sync.wait();
        }

        Handler handler = new Handler(mP2pThread.getLooper());
        when(p2pm.getP2pStateMachineMessenger()).thenReturn(new Messenger(handler));

        IBinder batteryStatsBinder = mockService(BatteryStats.class, IBatteryStats.class);
        when(facade.getService(BatteryStats.SERVICE_NAME)).thenReturn(batteryStatsBinder);

        when(facade.makeIpManager(any(Context.class), anyString(), any(IpManager.Callback.class)))
                .then(new AnswerWithArguments() {
                    public IpManager answer(
                            Context context, String ifname, IpManager.Callback callback) {
                        return new TestIpManager(context, ifname, callback);
                    }
                });

        return facade;
    }

    private Context getContext() throws Exception {
        PackageManager pkgMgr = mock(PackageManager.class);
        when(pkgMgr.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)).thenReturn(true);

        Context context = mock(Context.class);
        when(context.getPackageManager()).thenReturn(pkgMgr);
        when(context.getContentResolver()).thenReturn(mock(ContentResolver.class));

        MockResources resources = new com.android.server.wifi.MockResources();
        when(context.getResources()).thenReturn(resources);

        ContentResolver cr = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(cr);

        when(context.getSystemService(Context.POWER_SERVICE)).thenReturn(
                new PowerManager(context, mock(IPowerManager.class), new Handler()));

        mAlarmManager = new MockAlarmManager();
        when(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(
                mAlarmManager.getAlarmManager());

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
                mock(ConnectivityManager.class));

        return context;
    }

    private Resources getMockResources() {
        MockResources resources = new MockResources();
        resources.setBoolean(R.bool.config_wifi_enable_wifi_firmware_debugging, false);
        return resources;
    }

    private IState getCurrentState() throws
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = StateMachine.class.getDeclaredMethod("getCurrentState");
        method.setAccessible(true);
        return (IState) method.invoke(mWsm);
    }

    private static HandlerThread getWsmHandlerThread(WifiStateMachine wsm) throws
            NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Field field = StateMachine.class.getDeclaredField("mSmThread");
        field.setAccessible(true);
        return (HandlerThread) field.get(wsm);
    }

    private static void stopLooper(final Looper looper) throws Exception {
        new Handler(looper).post(new Runnable() {
            @Override
            public void run() {
                looper.quitSafely();
            }
        });
    }

    private void wait(int delayInMs) throws InterruptedException {
        Looper looper = mWsmThread.getLooper();
        final Handler handler = new Handler(looper);
        synchronized (handler) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (handler) {
                        handler.notifyAll();
                    }
                }
            }, delayInMs);

            handler.wait();
        }
    }

    private void dumpState() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mWsm.dump(null, writer, null);
        writer.flush();
        Log.d(TAG, "WifiStateMachine state -" + stream.toString());
    }

    private static ScanDetail getGoogleGuestScanDetail(int rssi) {
        ScanResult.InformationElement ie[] = new ScanResult.InformationElement[1];
        ie[0] = ScanResults.generateSsidIe(sSSID);
        NetworkDetail nd = new NetworkDetail(sBSSID, ie, new ArrayList<String>(), sFreq);
        ScanDetail detail = new ScanDetail(nd, sWifiSsid, sBSSID, "", rssi, sFreq,
                Long.MAX_VALUE /* needed so that scan results aren't rejected because
                                  there older than scan start */);
        return detail;
    }

    private ArrayList<ScanDetail> getMockScanResults() {
        ScanResults sr = ScanResults.create(0, 2412, 2437, 2462, 5180, 5220, 5745, 5825);
        ArrayList<ScanDetail> list = sr.getScanDetailArrayList();

        int rssi = -65;
        list.add(getGoogleGuestScanDetail(rssi));
        return list;
    }

    static final String   sSSID = "\"GoogleGuest\"";
    static final WifiSsid sWifiSsid = WifiSsid.createFromAsciiEncoded(sSSID);
    static final String   sHexSSID = sWifiSsid.getHexString().replace("0x", "").replace("22", "");
    static final String   sBSSID = "01:02:03:04:05:06";
    static final int      sFreq = 2437;

    WifiStateMachine mWsm;
    HandlerThread mWsmThread;
    HandlerThread mP2pThread;
    HandlerThread mSyncThread;
    AsyncChannel  mWsmAsyncChannel;
    MockAlarmManager mAlarmManager;
    MockWifiMonitor mWifiMonitor;

    @Mock WifiNative mWifiNative;
    @Mock SupplicantStateTracker mSupplicantStateTracker;
    @Mock WifiMetrics mWifiMetrics;

    public WifiStateMachineTest() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Setting up ...");

        // Ensure looper exists
        MockLooper looper = new MockLooper();

        MockitoAnnotations.initMocks(this);

        /** uncomment this to enable logs from WifiStateMachines */
        // enableDebugLogs();

        TestUtil.installWlanWifiNative(mWifiNative);
        mWifiMonitor = new MockWifiMonitor();
        mWifiMetrics = mock(WifiMetrics.class);
        FrameworkFacade factory = getFrameworkFacade();
        Context context = getContext();

        Resources resources = getMockResources();
        when(context.getResources()).thenReturn(resources);

        when(factory.getIntegerSetting(context,
                Settings.Global.WIFI_FREQUENCY_BAND,
                WifiManager.WIFI_FREQUENCY_BAND_AUTO)).thenReturn(
                WifiManager.WIFI_FREQUENCY_BAND_AUTO);

        when(factory.makeApConfigStore(Mockito.eq(context)))
                .thenCallRealMethod();

        when(factory.makeSupplicantStateTracker(
                any(Context.class), any(WifiStateMachine.class), any(WifiConfigStore.class),
                any(Handler.class))).thenReturn(mSupplicantStateTracker);

        mWsm = new WifiStateMachine(context, null, factory, mWifiMetrics);
        mWsmThread = getWsmHandlerThread(mWsm);

        final Object sync = new Object();
        synchronized (sync) {
            mSyncThread = new HandlerThread("SynchronizationThread");
            final AsyncChannel channel = new AsyncChannel();
            mSyncThread.start();
            Handler handler = new Handler(mSyncThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                                mWsmAsyncChannel = channel;
                                synchronized (sync) {
                                    sync.notifyAll();
                                    Log.d(TAG, "Successfully connected " + this);
                                }
                            } else {
                                Log.d(TAG, "Failed to connect Command channel " + this);
                            }
                            break;
                        case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                            Log.d(TAG, "Command channel disconnected" + this);
                            break;
                    }
                }
            };

            channel.connect(context, handler, mWsm.getMessenger());
            sync.wait();
        }

        /* Now channel is supposed to be connected */
    }

    @After
    public void cleanUp() throws Exception {

        if (mSyncThread != null) stopLooper(mSyncThread.getLooper());
        if (mWsmThread != null) stopLooper(mWsmThread.getLooper());
        if (mP2pThread != null) stopLooper(mP2pThread.getLooper());

        mWsmThread = null;
        mP2pThread = null;
        mSyncThread = null;
        mWsmAsyncChannel = null;
        mWsm = null;
    }

    @Test
    public void createNew() throws Exception {
        assertEquals("InitialState", getCurrentState().getName());

        mWsm.sendMessage(WifiStateMachine.CMD_BOOT_COMPLETED);
        wait(200);
        assertEquals("InitialState", getCurrentState().getName());
    }

    @Test
    public void loadComponents() throws Exception {

        when(mWifiNative.loadDriver()).thenReturn(true);
        when(mWifiNative.startHal()).thenReturn(true);
        when(mWifiNative.startSupplicant(anyBoolean())).thenReturn(true);

        mWsm.setSupplicantRunning(true);
        wait(200);
        assertEquals("SupplicantStartingState", getCurrentState().getName());

        when(mWifiNative.setBand(anyInt())).thenReturn(true);
        when(mWifiNative.setDeviceName(anyString())).thenReturn(true);
        when(mWifiNative.setManufacturer(anyString())).thenReturn(true);
        when(mWifiNative.setModelName(anyString())).thenReturn(true);
        when(mWifiNative.setModelNumber(anyString())).thenReturn(true);
        when(mWifiNative.setSerialNumber(anyString())).thenReturn(true);
        when(mWifiNative.setConfigMethods(anyString())).thenReturn(true);
        when(mWifiNative.setDeviceType(anyString())).thenReturn(true);
        when(mWifiNative.setSerialNumber(anyString())).thenReturn(true);
        when(mWifiNative.setScanningMacOui(any(byte[].class))).thenReturn(true);
        when(mWifiNative.enableBackgroundScan(anyBoolean(),
                any(ArrayList.class))).thenReturn(true);

        mWsm.sendMessage(WifiMonitor.SUP_CONNECTION_EVENT);
        wait(200);
        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    @Test
    public void loadComponentsFailure() throws Exception {
        when(mWifiNative.loadDriver()).thenReturn(false);
        when(mWifiNative.startHal()).thenReturn(false);
        when(mWifiNative.startSupplicant(anyBoolean())).thenReturn(false);

        mWsm.setSupplicantRunning(true);
        wait(200);
        assertEquals("InitialState", getCurrentState().getName());

        when(mWifiNative.loadDriver()).thenReturn(true);
        mWsm.setSupplicantRunning(true);
        wait(200);
        assertEquals("InitialState", getCurrentState().getName());

        when(mWifiNative.startHal()).thenReturn(true);
        mWsm.setSupplicantRunning(true);
        wait(200);
        assertEquals("InitialState", getCurrentState().getName());
    }

    @Test
    public void addNetwork() throws Exception {

        loadComponents();

        final HashMap<String, String> nameToValue = new HashMap<String, String>();

        when(mWifiNative.addNetwork()).thenReturn(0);
        when(mWifiNative.setNetworkVariable(anyInt(), anyString(), anyString()))
                .then(new AnswerWithArguments() {
                    public boolean answer(int netId, String name, String value) {
                        if (netId != 0) {
                            Log.d(TAG, "Can't set var " + name + " for " + netId);
                            return false;
                        }

                        Log.d(TAG, "Setting var " + name + " to " + value + " for " + netId);
                        nameToValue.put(name, value);
                        return true;
                    }
                });

        when(mWifiNative.setNetworkExtra(anyInt(), anyString(), (Map<String, String>) anyObject()))
                .then(new AnswerWithArguments() {
                    public boolean answer(int netId, String name, Map<String, String> values) {
                        if (netId != 0) {
                            Log.d(TAG, "Can't set extra " + name + " for " + netId);
                            return false;
                        }

                        Log.d(TAG, "Setting extra for " + netId);
                        return true;
                    }
                });

        when(mWifiNative.getNetworkVariable(anyInt(), anyString()))
                .then(new AnswerWithArguments() {
                    public String answer(int netId, String name) throws Throwable {
                        if (netId != 0) {
                            Log.d(TAG, "Can't find var " + name + " for " + netId);
                            return null;
                        }
                        String value = nameToValue.get(name);
                        if (value != null) {
                            Log.d(TAG, "Returning var " + name + " to " + value + " for " + netId);
                        } else {
                            Log.d(TAG, "Can't find var " + name + " for " + netId);
                        }
                        return value;
                    }
                });

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        mWsm.syncAddOrUpdateNetwork(mWsmAsyncChannel, config);
        wait(200);

        verify(mWifiNative).addNetwork();
        verify(mWifiNative).setNetworkVariable(0, "ssid", sHexSSID);

        List<WifiConfiguration> configs = mWsm.syncGetConfiguredNetworks(-1, mWsmAsyncChannel);
        assertEquals(1, configs.size());

        WifiConfiguration config2 = configs.get(0);
        assertEquals("\"GoogleGuest\"", config2.SSID);
        assertTrue(config2.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE));
    }

    @Test
    public void scan() throws Exception {

        addNetwork();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);

        mWsm.startScan(-1, 0, null, null);
        wait(200);

        verify(mWifiNative).scan(null);

        when(mWifiNative.getScanResults()).thenReturn(getMockScanResults());
        mWsm.sendMessage(WifiMonitor.SCAN_RESULTS_EVENT);

        wait(200);
        List<ScanResult> results = mWsm.syncGetScanResultsList();
        assertEquals(8, results.size());
    }

    @Test
    public void connect() throws Exception {

        addNetwork();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mWsm.syncEnableNetwork(mWsmAsyncChannel, 0, true);
        wait(200);

        verify(mWifiNative).enableNetwork(0, true);

        mWsm.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        wait(200);

        assertEquals("ObtainingIpState", getCurrentState().getName());

        DhcpResults dhcpResults = new DhcpResults();
        dhcpResults.setGateway("1.2.3.4");
        dhcpResults.setIpAddress("192.168.1.100", 0);
        dhcpResults.addDns("8.8.8.8");
        dhcpResults.setLeaseDuration(3600);

        mWsm.sendMessage(WifiStateMachine.CMD_IPV4_PROVISIONING_SUCCESS, 0, 0, dhcpResults);
        wait(200);

        assertEquals("ConnectedState", getCurrentState().getName());
    }

    @Test
    public void testDhcpFailure() throws Exception {
        addNetwork();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mWsm.syncEnableNetwork(mWsmAsyncChannel, 0, true);
        wait(200);

        verify(mWifiNative).enableNetwork(0, true);

        mWsm.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);

        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        wait(200);

        assertEquals("ObtainingIpState", getCurrentState().getName());

        mWsm.sendMessage(WifiStateMachine.CMD_IPV4_PROVISIONING_FAILURE, 0, 0, null);
        wait(200);

        assertEquals("DisconnectingState", getCurrentState().getName());
    }

    @Test
    public void testBadNetworkEvent() throws Exception {
        addNetwork();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mWsm.syncEnableNetwork(mWsmAsyncChannel, 0, true);
        wait(200);

        verify(mWifiNative).enableNetwork(0, true);

        mWsm.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, 0, 0, sBSSID);

        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        wait(200);

        assertEquals("DisconnectedState", getCurrentState().getName());
    }


    @Test
    public void disconnect() throws Exception {
        connect();

        mWsm.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, -1, 3, "01:02:03:04:05:06");
        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.DISCONNECTED));
        wait(200);

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    @Test
    public void handleUserSwitch() throws Exception {
        assertEquals(UserHandle.USER_SYSTEM, mWsm.getCurrentUserId());

        mWsm.handleUserSwitch(10);
        wait(200);

        assertEquals(10, mWsm.getCurrentUserId());
    }

    @Test
    public void iconQueryTest() throws Exception {
        /* enable wi-fi */
        addNetwork();

        long bssid = 0x1234567800FFL;
        String filename = "iconFileName.png";
        String command = "REQ_HS20_ICON " + Utils.macToString(bssid) + " " + filename;

        when(mWifiNative.doCustomSupplicantCommand(command)).thenReturn("OK");

        boolean result = mWsm.syncQueryPasspointIcon(mWsmAsyncChannel, bssid, filename);

        verify(mWifiNative).doCustomSupplicantCommand(command);
        assertEquals(true, result);
    }
}
