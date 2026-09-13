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
public class DuoSimProfilesTest {

    @Test
    public void testSanitize() {
        assertEquals(DuoSimProfiles.DUO, DuoSimProfiles.sanitize(0));
        assertEquals(DuoSimProfiles.DUO2, DuoSimProfiles.sanitize(1));
        assertEquals(DuoSimProfiles.DUO, DuoSimProfiles.sanitize(-1));
        assertEquals(DuoSimProfiles.DUO, DuoSimProfiles.sanitize(99));
    }

    @Test
    public void testLandscapeTwoPanelsPlusGap() {
        // 8.8" 16:10 tablet, landscape 1920x1200, Duo 1 panels (1350x1800), 24px gap
        DuoSimProfiles.Layout l = DuoSimProfiles.fitLayout(1920, 1200, DuoSimProfiles.DUO, 24, true);
        assertTrue(l.verticalHinge);
        // scale bound by height: 1200/1800 = 2/3
        int panelW = (int) Math.round(1350 * 2.0 / 3);
        int panelH = (int) Math.round(1800 * 2.0 / 3);
        int gap = (int) Math.round(24 * 2.0 / 3);
        int contentW = 2 * panelW + gap;
        assertEquals(contentW, l.content.width());
        assertEquals(panelH, l.content.height());
        // centered
        assertEquals((1920 - contentW) / 2, l.content.left);
        assertEquals(0, l.content.top);
        // hinge sits between the panels, full panel height
        assertEquals(l.content.left + panelW, l.hinge.left);
        assertEquals(gap, l.hinge.width());
        assertEquals(l.content.top, l.hinge.top);
        assertEquals(l.content.bottom, l.hinge.bottom);
        // assembly is symmetric: seam (viewport center) is the hinge center
        assertEquals(l.hinge.centerX(), l.content.centerX());
    }

    @Test
    public void testLandscapeWidthBound() {
        // wide parent: assembly width wins
        // Duo 2 panels 1344x1892, gap 0
        DuoSimProfiles.Layout l = DuoSimProfiles.fitLayout(4000, 1300, DuoSimProfiles.DUO2, 0, true);
        int scaleNumerator = 1300; // height bound? 4000/2688=1.488 vs 1300/1892=0.687 -> height bound
        double scale = 1300.0 / 1892;
        assertEquals((int) Math.round(1344 * scale) * 2, l.content.width());
        assertEquals(1300, l.content.height());
        assertEquals(0, l.hinge.width());
    }

    @Test
    public void testPortraitSinglePanel() {
        // 8.8" 16:10 tablet, portrait 1200x1920, Duo 1 panel
        DuoSimProfiles.Layout l = DuoSimProfiles.fitLayout(1200, 1920, DuoSimProfiles.DUO, 24, false);
        assertFalse(l.verticalHinge);
        assertTrue(l.hinge.isEmpty());
        // scale bound by width: 1200/1350 = 8/9
        assertEquals(1200, l.content.width());
        assertEquals((int) Math.round(1800 * 8.0 / 9), l.content.height());
        assertEquals((1920 - l.content.height()) / 2, l.content.top);
    }

    @Test
    public void testExactAspectFillsParent() {
        // parent exactly matches two Duo panels + gap
        DuoSimProfiles.Layout l = DuoSimProfiles.fitLayout(2724, 1800, DuoSimProfiles.DUO, 24, true);
        assertEquals(new Rect(0, 0, 2724, 1800), l.content);
        assertEquals(new Rect(1350, 0, 1374, 1800), l.hinge);
    }

    @Test
    public void testEmptyParent() {
        DuoSimProfiles.Layout l = DuoSimProfiles.fitLayout(0, 0, DuoSimProfiles.DUO, 24, true);
        assertTrue(l.content.isEmpty());
        l = DuoSimProfiles.fitLayout(100, 100, DuoSimProfiles.DUO, 24, true);
        assertFalse(l.content.isEmpty());
    }

    @Test
    public void testMeasurePhysicalSize() {
        // 8.8" tablet panel: 1904x3040 @ 409.84 x 406.4 dpi (Legion Y700-class)
        DuoSimProfiles.Layout l = DuoSimProfiles.fitLayout(3040, 1904, DuoSimProfiles.DUO2, 56, true);
        DuoSimProfiles.Physical p = DuoSimProfiles.measure(l, 409.84406, 406.4);
        // height-bound scale = 1904/1892 = 1.00634; panel width = 1344*s = 1353 px
        assertEquals(1353.0 / 409.84406, p.panelWidthIn, 0.01);
        assertEquals(1904.0 / 406.4, p.panelHeightIn, 0.01);
        assertEquals((2.0 * 1353 + 56) / 409.84406, p.contentWidthIn, 0.01);
        // unfolded diagonal of the simulated Duo 2 on this panel ~ 8.2"
        assertEquals(Math.hypot(p.contentWidthIn, p.contentHeightIn), p.contentDiagonalIn(), 1e-9);
        assertTrue(p.contentDiagonalIn() > 8.1 && p.contentDiagonalIn() < 8.3);
        // single-panel portrait measure: panel == content
        DuoSimProfiles.Layout s = DuoSimProfiles.fitLayout(1904, 3040, DuoSimProfiles.DUO2, 56, false);
        DuoSimProfiles.Physical ps = DuoSimProfiles.measure(s, 409.84406, 406.4);
        assertEquals(ps.contentWidthIn, ps.panelWidthIn, 1e-9);
        assertTrue(ps.contentDiagonalIn() > 8.0 && ps.contentDiagonalIn() < 8.2);
    }
}
