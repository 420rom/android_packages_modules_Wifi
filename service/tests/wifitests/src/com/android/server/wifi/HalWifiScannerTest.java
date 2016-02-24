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

import static com.android.server.wifi.ScanTestUtil.setupMockChannels;

import android.os.Handler;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;

import java.lang.reflect.Field;

/**
 * Unit tests for {@link com.android.server.wifi.HalWifiScannerImpl}.
 */
@SmallTest
public class HalWifiScannerTest extends BaseWifiScannerImplTest {

    @Before
    public void setUp() throws Exception {
        setupMockChannels(mWifiNative,
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650});
        mScanner = new HalWifiScannerImpl(mWifiNative, mLooper.getLooper());

        // TODO remove this once HalWifiScannerImpl wifi monitor registration is enabled
        Field eventHandlerField = HalWifiScannerImpl.class.getDeclaredField("mEventHandler");
        eventHandlerField.setAccessible(true);
        Handler eventHandler = (Handler) eventHandlerField.get(mScanner);
        WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_FAILED_EVENT, eventHandler);
        WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_RESULTS_EVENT, eventHandler);
    }
}
