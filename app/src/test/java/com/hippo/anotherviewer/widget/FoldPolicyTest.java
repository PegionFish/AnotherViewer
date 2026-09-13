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

package com.hippo.anotherviewer.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class FoldPolicyTest {

    // Pixel Fold-class unfolded inner screen width
    private static final int W = 2208;

    @Test
    public void testSeparatingSemantics() {
        // 物理铰链（FULL 遮挡）即使展平也分隔（Surface Duo 摊平使用）
        assertTrue(FoldPolicy.isSeparating(FoldPolicy.OCCLUSION_FULL, FoldPolicy.STATE_FLAT));
        // 两屏成夹角即分隔，与遮挡无关
        assertTrue(FoldPolicy.isSeparating(FoldPolicy.OCCLUSION_NONE, FoldPolicy.STATE_HALF_OPENED));
        assertTrue(FoldPolicy.isSeparating(FoldPolicy.OCCLUSION_FULL, FoldPolicy.STATE_HALF_OPENED));
        // FLAT + NONE：连续屏玻璃折痕，不分隔
        assertFalse(FoldPolicy.isSeparating(FoldPolicy.OCCLUSION_NONE, FoldPolicy.STATE_FLAT));
    }

    @Test
    public void testFullOcclusionVerticalFoldSplits() {
        // Surface Duo style: 52px physical hinge in the middle, occluded
        Rect hinge = new Rect(1074, 0, 1126, 1800);
        assertEquals(hinge.centerX(), FoldPolicy.readerSplitX(hinge,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W));
        // int 重载与 Rect 重载一致
        assertEquals(hinge.centerX(), FoldPolicy.readerSplitX(
                hinge.left, hinge.top, hinge.right, hinge.bottom,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_FULL, W));
    }

    @Test
    public void testHalfOpenedVerticalFoldSplits() {
        // Pixel Fold style: glass crease held at an angle, no occlusion
        Rect fold = new Rect(1080, 0, 1120, 2208);
        assertEquals(fold.centerX(), FoldPolicy.readerSplitX(fold,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_NONE, W));
    }

    @Test
    public void testFlatNoOcclusionCreaseDoesNotSplit() {
        // 内屏平放：连续屏折痕不切缝（有意的行为变更）
        Rect fold = new Rect(1080, 0, 1120, 2208);
        assertEquals(-1, FoldPolicy.readerSplitX(fold,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_NONE, W));
    }

    @Test
    public void testHorizontalFoldDoesNotSplit() {
        // 上下两屏：不存在左右分页缝，即使分隔也不切
        Rect fold = new Rect(0, 1060, W, 1120);
        assertEquals(-1, FoldPolicy.readerSplitX(fold,
                FoldPolicy.ORIENTATION_HORIZONTAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        assertEquals(-1, FoldPolicy.readerSplitX(fold,
                FoldPolicy.ORIENTATION_HORIZONTAL, FoldPolicy.STATE_FLAT,
                FoldPolicy.OCCLUSION_NONE, W));
    }

    @Test
    public void testUnknownOrientationDoesNotSplit() {
        Rect fold = new Rect(1080, 0, 1120, 2208);
        // 防御：非法方向值一律不切
        assertEquals(-1, FoldPolicy.readerSplitX(fold, 99,
                FoldPolicy.STATE_HALF_OPENED, FoldPolicy.OCCLUSION_FULL, W));
    }

    @Test
    public void testNoFoldGivesMinusOne() {
        // 无折叠：null bounds 或空 bounds
        assertEquals(-1, FoldPolicy.readerSplitX((Rect) null,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        assertEquals(-1, FoldPolicy.readerSplitX(new Rect(),
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        // 空 bounds 的 int 形式
        assertEquals(-1, FoldPolicy.readerSplitX(0, 0, 0, 0,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
    }

    @Test
    public void testSeamAtOrOutsideEdgesRejected() {
        // 缝中心 <= 0：左侧面板宽度为 0，无分页意义
        assertEquals(-1, FoldPolicy.readerSplitX(new Rect(-100, 0, 100, 2000),
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        assertEquals(-1, FoldPolicy.readerSplitX(new Rect(-200, 0, 0, 2000),
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        // 缝中心 >= 屏宽：右侧面板宽度为 0
        assertEquals(-1, FoldPolicy.readerSplitX(new Rect(W, 0, W + 60, 2000),
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        assertEquals(-1, FoldPolicy.readerSplitX(new Rect(W - 30, 0, W + 30, 2000),
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        // 屏宽非法：保守回退为无缝
        Rect fold = new Rect(1080, 0, 1120, 2000);
        assertEquals(-1, FoldPolicy.readerSplitX(fold,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, 0));
        assertEquals(-1, FoldPolicy.readerSplitX(fold,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, -1));
        // 稍微离开边缘即可切缝
        assertEquals(fold.centerX(), FoldPolicy.readerSplitX(fold,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
    }

    @Test
    public void testDegenerateBoundsRejected() {
        // right <= left 或 bottom <= top：无效 bounds
        assertEquals(-1, FoldPolicy.readerSplitX(new Rect(1120, 0, 1080, 2000),
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
        assertEquals(-1, FoldPolicy.readerSplitX(1080, 2000, 1120, 0,
                FoldPolicy.ORIENTATION_VERTICAL, FoldPolicy.STATE_HALF_OPENED,
                FoldPolicy.OCCLUSION_FULL, W));
    }
}
