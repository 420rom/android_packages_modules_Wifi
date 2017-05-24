/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.aware;

import static com.android.server.wifi.aware.WifiAwareMetrics.addNanHalStatusToHistogram;
import static com.android.server.wifi.aware.WifiAwareMetrics.histogramToProtoArray;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Mockito.when;

import android.hardware.wifi.V1_0.NanStatusType;
import android.util.SparseIntArray;

import com.android.server.wifi.Clock;
import com.android.server.wifi.nano.WifiMetricsProto;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test harness for WifiAwareMetrics
 */
public class WifiAwareMetricsTest {
    @Mock Clock mClock;
    @Rule public ErrorCollector collector = new ErrorCollector();

    private WifiAwareMetrics mDut;

    // Histogram definition: start[i] = b + p * m^i with s sub-buckets, i=0,...,n-1

    /**
     * Histogram of following buckets, start[i] = 0 + 1 * 10^i with 9 sub-buckets, i=0,...,5
     * 1 - 10: 9 sub-buckets each of width 1
     * 10 - 100: 10
     * 100 - 10e3: 10^2
     * 10e3 - 10e4: 10^3
     * 10e4 - 10e5: 10^4
     * 10e5 - 10e6: 10^5
     */
    private static final WifiAwareMetrics.HistParms HIST1 = new WifiAwareMetrics.HistParms(0, 1,
            10, 9, 6);

    /**
     * Histogram of following buckets, start[i] = -20 + 2 * 5^i with 40 sub-buckets, i=0,...,2
     * -18 - -10: 40 sub-bucket each of width 0.2
     * -10 - 30: 1
     * 30 - 230: 5
     */
    private static final WifiAwareMetrics.HistParms HIST2 = new WifiAwareMetrics.HistParms(-20, 2,
            5, 40, 3);

    /**
     * Pre-test configuration. Initialize and install mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setTime(0);

        mDut = new WifiAwareMetrics(mClock);
    }

    /**
     * Validates that recordEnableUsage() and recordDisableUsage() record valid metrics.
     */
    @Test
    public void testEnableDisableUsageMetrics() {
        WifiMetricsProto.WifiAwareLog log;

        // create 2 records
        setTime(5);
        mDut.recordEnableUsage();
        setTime(10);
        mDut.recordDisableUsage();
        setTime(11);
        mDut.recordEnableUsage();
        setTime(12);
        mDut.recordDisableUsage();

        setTime(14);
        log = mDut.consolidateProto();
        collector.checkThat(countAllHistogramSamples(log.histogramAwareAvailableDurationMs),
                equalTo(2));
        validateProtoHistBucket("Duration[0] #1", log.histogramAwareAvailableDurationMs[0], 1, 2,
                1);
        validateProtoHistBucket("Duration[1] #1", log.histogramAwareAvailableDurationMs[1], 5, 6,
                1);
        collector.checkThat(log.availableTimeMs, equalTo(6L));

        // create another partial record
        setTime(15);
        mDut.recordEnableUsage();

        setTime(17);
        log = mDut.consolidateProto();
        collector.checkThat(countAllHistogramSamples(log.histogramAwareAvailableDurationMs),
                equalTo(2));
        validateProtoHistBucket("Duration[0] #2", log.histogramAwareAvailableDurationMs[0], 1, 2,
                1);
        validateProtoHistBucket("Duration[1] #2", log.histogramAwareAvailableDurationMs[1], 5, 6,
                1);
        collector.checkThat(log.availableTimeMs, equalTo(8L)); // the partial record of 2ms


        // clear and continue that partial record (verify completed)
        mDut.clear();
        setTime(23);
        mDut.recordDisableUsage();

        log = mDut.consolidateProto();
        collector.checkThat(countAllHistogramSamples(log.histogramAwareAvailableDurationMs),
                equalTo(1));
        validateProtoHistBucket("Duration[0] #3", log.histogramAwareAvailableDurationMs[0], 8, 9,
                1);
        collector.checkThat(log.availableTimeMs, equalTo(6L)); // the remnant record of 6ms

        // clear and verify empty records
        mDut.clear();
        log = mDut.consolidateProto();
        collector.checkThat(countAllHistogramSamples(log.histogramAwareAvailableDurationMs),
                equalTo(0));
    }

    /**
     * Validate that the histogram configuration is initialized correctly: bucket starting points
     * and sub-bucket widths.
     */
    @Test
    public void testHistParamInit() {
        collector.checkThat("HIST1.mLog", HIST1.mLog, equalTo(Math.log(10)));
        collector.checkThat("HIST1.bb[0]", HIST1.bb[0], equalTo(1.0));
        collector.checkThat("HIST1.bb[1]", HIST1.bb[1], equalTo(10.0));
        collector.checkThat("HIST1.bb[2]", HIST1.bb[2], equalTo(100.0));
        collector.checkThat("HIST1.bb[3]", HIST1.bb[3], equalTo(1000.0));
        collector.checkThat("HIST1.bb[4]", HIST1.bb[4], equalTo(10000.0));
        collector.checkThat("HIST1.bb[5]", HIST1.bb[5], equalTo(100000.0));
        collector.checkThat("HIST1.sbw[0]", HIST1.sbw[0], equalTo(1.0));
        collector.checkThat("HIST1.sbw[1]", HIST1.sbw[1], equalTo(10.0));
        collector.checkThat("HIST1.sbw[2]", HIST1.sbw[2], equalTo(100.0));
        collector.checkThat("HIST1.sbw[3]", HIST1.sbw[3], equalTo(1000.0));
        collector.checkThat("HIST1.sbw[4]", HIST1.sbw[4], equalTo(10000.0));
        collector.checkThat("HIST1.sbw[5]", HIST1.sbw[5], equalTo(100000.0));

        collector.checkThat("HIST2.mLog", HIST1.mLog, equalTo(Math.log(10)));
        collector.checkThat("HIST2.bb[0]", HIST2.bb[0], equalTo(-18.0));
        collector.checkThat("HIST2.bb[1]", HIST2.bb[1], equalTo(-10.0));
        collector.checkThat("HIST2.bb[2]", HIST2.bb[2], equalTo(30.0));
        collector.checkThat("HIST2.sbw[0]", HIST2.sbw[0], equalTo(0.2));
        collector.checkThat("HIST2.sbw[1]", HIST2.sbw[1], equalTo(1.0));
        collector.checkThat("HIST2.sbw[2]", HIST2.sbw[2], equalTo(5.0));
    }

    /**
     * Validate that a set of values are bucketed correctly into the histogram, and that they are
     * converted to a primitive proto-buffer array correctly.
     */
    @Test
    public void testHistBucketing() {
        SparseIntArray hist1 = new SparseIntArray();
        SparseIntArray hist2 = new SparseIntArray();

        bucketValueAndVerify("HIST1: x=", -5, hist1, HIST1, 0, 1);
        bucketValueAndVerify("HIST1: x=", 0, hist1, HIST1, 0, 2);
        bucketValueAndVerify("HIST1: x=", 1, hist1, HIST1, 0, 3);
        bucketValueAndVerify("HIST1: x=", 9, hist1, HIST1, 8, 1);
        bucketValueAndVerify("HIST1: x=", 10, hist1, HIST1, 9, 1);
        bucketValueAndVerify("HIST1: x=", 99, hist1, HIST1, 17, 1);
        bucketValueAndVerify("HIST1: x=", 100, hist1, HIST1, 18, 1);
        bucketValueAndVerify("HIST1: x=", 989, hist1, HIST1, 26, 1);
        bucketValueAndVerify("HIST1: x=", 990, hist1, HIST1, 26, 2);
        bucketValueAndVerify("HIST1: x=", 999, hist1, HIST1, 26, 3);
        bucketValueAndVerify("HIST1: x=", 1000, hist1, HIST1, 27, 1);
        bucketValueAndVerify("HIST1: x=", 9899, hist1, HIST1, 35, 1);
        bucketValueAndVerify("HIST1: x=", 9900, hist1, HIST1, 35, 2);
        bucketValueAndVerify("HIST1: x=", 9999, hist1, HIST1, 35, 3);
        bucketValueAndVerify("HIST1: x=", 10000, hist1, HIST1, 36, 1);
        bucketValueAndVerify("HIST1: x=", 98999, hist1, HIST1, 44, 1);
        bucketValueAndVerify("HIST1: x=", 99000, hist1, HIST1, 44, 2);
        bucketValueAndVerify("HIST1: x=", 99999, hist1, HIST1, 44, 3);
        bucketValueAndVerify("HIST1: x=", 100000, hist1, HIST1, 45, 1);
        bucketValueAndVerify("HIST1: x=", 989999, hist1, HIST1, 53, 1);
        bucketValueAndVerify("HIST1: x=", 990000, hist1, HIST1, 53, 2);
        bucketValueAndVerify("HIST1: x=", 999999, hist1, HIST1, 53, 3);
        bucketValueAndVerify("HIST1: x=", 1000000, hist1, HIST1, 53, 4);
        bucketValueAndVerify("HIST1: x=", 1000001, hist1, HIST1, 53, 5);
        bucketValueAndVerify("HIST1: x=", 5000000, hist1, HIST1, 53, 6);
        bucketValueAndVerify("HIST1: x=", 10000000, hist1, HIST1, 53, 7);

        WifiMetricsProto.WifiAwareLog.HistogramBucket[] phb1 = histogramToProtoArray(hist1, HIST1);
        collector.checkThat("Number of buckets #1", phb1.length, equalTo(hist1.size()));
        validateProtoHistBucket("Bucket1[0]", phb1[0], 1, 2, 3);
        validateProtoHistBucket("Bucket1[1]", phb1[1], 9, 10, 1);
        validateProtoHistBucket("Bucket1[2]", phb1[2], 10, 20, 1);
        validateProtoHistBucket("Bucket1[3]", phb1[3], 90, 100, 1);
        validateProtoHistBucket("Bucket1[4]", phb1[4], 100, 200, 1);
        validateProtoHistBucket("Bucket1[5]", phb1[5], 900, 1000, 3);
        validateProtoHistBucket("Bucket1[6]", phb1[6], 1000, 2000, 1);
        validateProtoHistBucket("Bucket1[7]", phb1[7], 9000, 10000, 3);
        validateProtoHistBucket("Bucket1[8]", phb1[8], 10000, 20000, 1);
        validateProtoHistBucket("Bucket1[9]", phb1[9], 90000, 100000, 3);
        validateProtoHistBucket("Bucket1[10]", phb1[10], 100000, 200000, 1);
        validateProtoHistBucket("Bucket1[11]", phb1[11], 900000, 1000000, 7);

        bucketValueAndVerify("HIST2: x=", -20, hist2, HIST2, 0, 1);
        bucketValueAndVerify("HIST2: x=", -18, hist2, HIST2, 0, 2);
        bucketValueAndVerify("HIST2: x=", -17, hist2, HIST2, 5, 1);
        bucketValueAndVerify("HIST2: x=", -11, hist2, HIST2, 35, 1);
        bucketValueAndVerify("HIST2: x=", -10, hist2, HIST2, 40, 1);
        bucketValueAndVerify("HIST2: x=", 29, hist2, HIST2, 79, 1);
        bucketValueAndVerify("HIST2: x=", 30, hist2, HIST2, 80, 1);
        bucketValueAndVerify("HIST2: x=", 229, hist2, HIST2, 119, 1);
        bucketValueAndVerify("HIST2: x=", 230, hist2, HIST2, 119, 2);
        bucketValueAndVerify("HIST2: x=", 300, hist2, HIST2, 119, 3);
        bucketValueAndVerify("HIST2: x=", 1000000, hist2, HIST2, 119, 4);

        WifiMetricsProto.WifiAwareLog.HistogramBucket[] phb2 = histogramToProtoArray(hist2, HIST2);
        collector.checkThat("Number of buckets #2", phb2.length, equalTo(hist2.size()));
        validateProtoHistBucket("Bucket2[0]", phb2[0], -18, -17, 2);
        validateProtoHistBucket("Bucket2[1]", phb2[1], -17, -16, 1);
        validateProtoHistBucket("Bucket2[2]", phb2[2], -11, -10, 1);
        validateProtoHistBucket("Bucket2[3]", phb2[3], -10, -9, 1);
        validateProtoHistBucket("Bucket2[4]", phb2[4], 29, 30, 1);
        validateProtoHistBucket("Bucket2[5]", phb2[5], 30, 35, 1);
        validateProtoHistBucket("Bucket2[6]", phb2[6], 225, 230, 4);
    }

    /**
     * Validate the conversion to a NanStatusType proto raw histogram.
     */
    @Test
    public void testNanStatusTypeHistogram() {
        SparseIntArray statusHistogram = new SparseIntArray();

        addNanHalStatusToHistogram(NanStatusType.SUCCESS, statusHistogram);
        addNanHalStatusToHistogram(-1, statusHistogram);
        addNanHalStatusToHistogram(NanStatusType.ALREADY_ENABLED, statusHistogram);
        addNanHalStatusToHistogram(NanStatusType.SUCCESS, statusHistogram);
        addNanHalStatusToHistogram(NanStatusType.INTERNAL_FAILURE, statusHistogram);
        addNanHalStatusToHistogram(NanStatusType.SUCCESS, statusHistogram);
        addNanHalStatusToHistogram(NanStatusType.INTERNAL_FAILURE, statusHistogram);
        addNanHalStatusToHistogram(55, statusHistogram);
        addNanHalStatusToHistogram(65, statusHistogram);

        WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket[] sh = histogramToProtoArray(
                statusHistogram);
        collector.checkThat("Number of buckets", sh.length, equalTo(4));
        validateNanStatusProtoHistBucket("Bucket[SUCCESS]", sh[0],
                WifiMetricsProto.WifiAwareLog.SUCCESS, 3);
        validateNanStatusProtoHistBucket("Bucket[INTERNAL_FAILURE]", sh[1],
                WifiMetricsProto.WifiAwareLog.INTERNAL_FAILURE, 2);
        validateNanStatusProtoHistBucket("Bucket[ALREADY_ENABLED]", sh[2],
                WifiMetricsProto.WifiAwareLog.ALREADY_ENABLED, 1);
        validateNanStatusProtoHistBucket("Bucket[UNKNOWN_HAL_STATUS]", sh[3],
                WifiMetricsProto.WifiAwareLog.UNKNOWN_HAL_STATUS, 3);
    }

    // utilities

    /**
     * Mock the elapsed time since boot to the input argument.
     */
    private void setTime(long timeMs) {
        when(mClock.getElapsedSinceBootMillis()).thenReturn(timeMs);
    }

    /**
     * Sum all the 'count' entries in the histogram array.
     */
    private int countAllHistogramSamples(WifiMetricsProto.WifiAwareLog.HistogramBucket[] hba) {
        int sum = 0;
        for (WifiMetricsProto.WifiAwareLog.HistogramBucket hb: hba) {
            sum += hb.count;
        }
        return sum;
    }

    private void bucketValueAndVerify(String logPrefix, long value, SparseIntArray h,
            WifiAwareMetrics.HistParms hp, int expectedKey, int expectedValue) {
        WifiAwareMetrics.addLogValueToHistogram(value, h, hp);
        collector.checkThat(logPrefix + value, h.get(expectedKey), equalTo(expectedValue));
    }

    private void validateProtoHistBucket(String logPrefix,
            WifiMetricsProto.WifiAwareLog.HistogramBucket bucket, long start, long end, int count) {
        collector.checkThat(logPrefix + ": start", bucket.start, equalTo(start));
        collector.checkThat(logPrefix + ": end", bucket.end, equalTo(end));
        collector.checkThat(logPrefix + ": count", bucket.count, equalTo(count));
    }

    private void validateNanStatusProtoHistBucket(String logPrefix,
            WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket bucket, int type, int count) {
        collector.checkThat(logPrefix + ": type", bucket.nanStatusType, equalTo(type));
        collector.checkThat(logPrefix + ": count", bucket.count, equalTo(count));
    }
}
