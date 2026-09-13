/*
 * Copyright 2026 Pegion Fish
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.anotherviewer.ui.pane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.graphics.Rect;

import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowLayoutInfo;

import com.hippo.anotherviewer.widget.FoldPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * 双栏分割判定测试：纯几何决策 + FoldingFeature 映射 + 监听回报。
 * 判定语义复用 FoldPolicy（竖向折叠 && 分隔才构成双栏）。
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class TwoPaneSplitTest {

    // Surface Duo 展开总宽（含铰链），铰链约 52px
    private static final int W = 2772;
    private static final int H = 1800;
    private static final int MIN = FoldSplitDetector.MIN_PANE_WIDTH_DP;

    private static FoldSplitDetector.Split split(int left, int top, int right, int bottom,
            int orientation, int state, int occlusion, int windowWidth, int minPaneWidth) {
        return FoldSplitDetector.computeSplit(left, top, right, bottom,
                orientation, state, occlusion, windowWidth, minPaneWidth);
    }

    @Test
    public void testVerticalFullOcclusionHingeSplits() {
        // Surface Duo：物理铰链（完全遮挡），即使展平也分隔
        FoldSplitDetector.Split s = split(1344, 0, 1396, H,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W, MIN);
        assertEquals(1344, s.leftPaneWidth);
        assertEquals(W - 1396, s.rightPaneWidth);
        assertEquals(W, s.windowWidth);
    }

    @Test
    public void testVerticalHalfOpenedFoldSplits() {
        // Pixel Fold 类：玻璃折痕半开（无遮挡）也分隔
        FoldSplitDetector.Split s = split(1080, 0, 1120, 2208,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_NONE, 2208, MIN);
        assertEquals(1080, s.leftPaneWidth);
        assertEquals(2208 - 1120, s.rightPaneWidth);
    }

    @Test
    public void testFlatNoOcclusionCreaseStaysSinglePane() {
        // FLAT + 无遮挡：连续屏玻璃折痕，不构成双栏
        assertNull(split(1080, 0, 1120, 2208,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_NONE, 2208, MIN));
    }

    @Test
    public void testHorizontalFoldStaysSinglePane() {
        // 上下两屏：不存在左右双栏
        assertNull(split(0, 860, W, 920,
                FoldPolicy.ORIENTATION_HORIZONTAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W, MIN));
    }

    @Test
    public void testTooNarrowSideStaysSinglePane() {
        // 铰链贴近左边缘：左栏小于最小宽度，保守回退单栏
        assertNull(split(100, 0, 200, H,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W, 300));
        // 贴近右边缘：右栏过窄同样回退
        assertNull(split(W - 150, 0, W - 50, H,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W, 300));
        // 恰好等于最小宽度：允许
        FoldSplitDetector.Split s = split(300, 0, W - 300, H,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W, 300);
        assertEquals(300, s.leftPaneWidth);
        assertEquals(300, s.rightPaneWidth);
    }

    @Test
    public void testDegenerateInputStaysSinglePane() {
        // 无效 bounds / 窗口宽度非法 / 无效方向值
        assertNull(split(1120, 0, 1080, H,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W, MIN));
        assertNull(split(1344, 0, 1396, 0,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W, MIN));
        assertNull(split(1344, 0, 1396, H,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, 0, MIN));
        assertNull(split(1344, 0, 1396, H,
                99, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W, MIN));
    }

    @Test
    public void testFoldingFeatureEnumMapping() {
        assertEquals(FoldPolicy.ORIENTATION_VERTICAL,
                FoldSplitDetector.mapOrientation(FoldingFeature.Orientation.VERTICAL));
        assertEquals(FoldPolicy.ORIENTATION_HORIZONTAL,
                FoldSplitDetector.mapOrientation(FoldingFeature.Orientation.HORIZONTAL));
        assertEquals(FoldPolicy.STATE_FLAT,
                FoldSplitDetector.mapState(FoldingFeature.State.FLAT));
        assertEquals(FoldPolicy.STATE_HALF_OPENED,
                FoldSplitDetector.mapState(FoldingFeature.State.HALF_OPENED));
        assertEquals(FoldPolicy.OCCLUSION_FULL,
                FoldSplitDetector.mapOcclusion(FoldingFeature.OcclusionType.FULL));
        assertEquals(FoldPolicy.OCCLUSION_NONE,
                FoldSplitDetector.mapOcclusion(FoldingFeature.OcclusionType.NONE));
    }

    @Test
    public void testDetectorReportsSeparatingHinge() {
        RecordingListener listener = new RecordingListener();
        Activity activity = Robolectric.setupActivity(Activity.class);
        // content view 与 FoldingFeature bounds 同处窗口坐标系
        activity.findViewById(android.R.id.content).layout(0, 0, W, H);
        FoldSplitDetector detector = new FoldSplitDetector(activity, listener);

        // Surface Duo 式物理铰链：应回报分割
        FakeFoldingFeature hinge = new FakeFoldingFeature(
                new Rect(1344, 0, 1396, H),
                FoldingFeature.Orientation.VERTICAL,
                FoldingFeature.State.FLAT,
                FoldingFeature.OcclusionType.FULL);
        detector.onLayoutInfoChanged(new WindowLayoutInfo(Collections.singletonList(hinge)));
        assertEquals(1344, listener.split.leftPaneWidth);
        assertEquals(W - 1396, listener.split.rightPaneWidth);
    }

    @Test
    public void testDetectorIgnoresContinuousCrease() {
        RecordingListener listener = new RecordingListener();
        Activity activity = Robolectric.setupActivity(Activity.class);
        activity.findViewById(android.R.id.content).layout(0, 0, W, H);
        FoldSplitDetector detector = new FoldSplitDetector(activity, listener);

        // FLAT + 无遮挡：连续屏，应回报 null（单栏）
        FakeFoldingFeature crease = new FakeFoldingFeature(
                new Rect(1344, 0, 1396, H),
                FoldingFeature.Orientation.VERTICAL,
                FoldingFeature.State.FLAT,
                FoldingFeature.OcclusionType.NONE);
        detector.onLayoutInfoChanged(new WindowLayoutInfo(Collections.singletonList(crease)));
        assertNull(listener.split);
    }

    @Test
    public void testDetectorDedupesRepeatedReports() {
        RecordingListener listener = new RecordingListener();
        Activity activity = Robolectric.setupActivity(Activity.class);
        activity.findViewById(android.R.id.content).layout(0, 0, W, H);
        FoldSplitDetector detector = new FoldSplitDetector(activity, listener);

        FakeFoldingFeature hinge = new FakeFoldingFeature(
                new Rect(1344, 0, 1396, H),
                FoldingFeature.Orientation.VERTICAL,
                FoldingFeature.State.FLAT,
                FoldingFeature.OcclusionType.FULL);
        WindowLayoutInfo info = new WindowLayoutInfo(Collections.singletonList(hinge));
        detector.onLayoutInfoChanged(info);
        int reports = listener.reportCount;
        // 相同状态重复推送不应再次回调
        detector.onLayoutInfoChanged(info);
        assertEquals(reports, listener.reportCount);
    }

    /** 记录型监听器。 */
    private static class RecordingListener implements FoldSplitDetector.Listener {
        FoldSplitDetector.Split split;
        int reportCount;

        @Override
        public void onSplitChanged(FoldSplitDetector.Split split) {
            this.split = split;
            reportCount++;
        }
    }

    /** 伪造 FoldingFeature：接口实现，无需真机。 */
    private static class FakeFoldingFeature implements FoldingFeature {
        final Rect bounds;
        final Orientation orientation;
        final State state;
        final OcclusionType occlusionType;

        FakeFoldingFeature(Rect bounds, Orientation orientation,
                           State state, OcclusionType occlusionType) {
            this.bounds = bounds;
            this.orientation = orientation;
            this.state = state;
            this.occlusionType = occlusionType;
        }

        @Override
        public Rect getBounds() {
            return bounds;
        }

        @Override
        public boolean isSeparating() {
            return occlusionType == OcclusionType.FULL || state == State.HALF_OPENED;
        }

        @Override
        public OcclusionType getOcclusionType() {
            return occlusionType;
        }

        @Override
        public Orientation getOrientation() {
            return orientation;
        }

        @Override
        public State getState() {
            return state;
        }
    }
}
