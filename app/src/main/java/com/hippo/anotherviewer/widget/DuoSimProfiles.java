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

import android.graphics.Rect;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Panel profiles and viewport fitting for the in-reader Surface Duo
 * reading simulation. Pure logic, unit tested.
 *
 * <p>The Duo is a physical dual-screen device: in the book posture the
 * reader shows one whole page on each panel, separated by the hinge gap —
 * pages never continue across the hinge as if it were one display. The
 * simulation therefore fits "panel | hinge gap | panel" into the screen
 * (landscape), and falls back to a single panel (portrait), where the
 * reader already shows one page per screen. No system display settings
 * are touched.
 */
public final class DuoSimProfiles {

    /** Surface Duo: 1350x1800 panel @ 360dpi, 5.6". */
    public static final int DUO = 0;
    /** Surface Duo 2: 1344x1892 panel @ 360dpi, 5.8". */
    public static final int DUO2 = 1;

    @IntDef({DUO, DUO2})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Profile {
    }

    private DuoSimProfiles() {
    }

    public static int sanitize(int profile) {
        return profile == DUO2 ? DUO2 : DUO;
    }

    public static int panelWidth(int profile) {
        return profile == DUO2 ? 1344 : 1350;
    }

    public static int panelHeight(int profile) {
        return profile == DUO2 ? 1892 : 1800;
    }

    /** Fitted simulation geometry on a parent of pw x ph pixels. */
    public static final class Layout {
        /** Whole simulated area: both panels plus the hinge gap (landscape),
         * or the single panel (portrait). The reader viewport matches this rect. */
        public final Rect content;
        /** Hinge gap between the two panels; empty rect when absent. */
        public final Rect hinge;
        public final boolean verticalHinge;

        Layout(Rect content, Rect hinge, boolean verticalHinge) {
            this.content = content;
            this.hinge = hinge;
            this.verticalHinge = verticalHinge;
        }
    }

    /**
     * @param dual true for the book posture: two portrait panels side by
     *             side with a hinge gap between them, one page each;
     *             false for a single panel. The gap scales together with
     *             the panels so the assembly always fits.
     */
    public static Layout fitLayout(int pw, int ph, int profile, int gapPx, boolean dual) {
        profile = sanitize(profile);
        gapPx = Math.max(0, gapPx);
        if (pw <= 0 || ph <= 0) {
            return new Layout(new Rect(), new Rect(), true);
        }
        if (dual) {
            double assemblyW = 2.0 * panelWidth(profile) + gapPx;
            double scale = Math.min(pw / assemblyW, (double) ph / panelHeight(profile));
            int panelW = (int) Math.round(panelWidth(profile) * scale);
            int panelH = (int) Math.round(panelHeight(profile) * scale);
            int gap = (int) Math.round(gapPx * scale);
            int contentW = 2 * panelW + gap;
            int contentH = panelH;
            int left = (pw - contentW) / 2;
            int top = (ph - contentH) / 2;
            Rect content = new Rect(left, top, left + contentW, top + contentH);
            Rect hinge = new Rect(left + panelW, top, left + panelW + gap, top + contentH);
            return new Layout(content, hinge, true);
        } else {
            double scale = Math.min((double) pw / panelWidth(profile),
                    (double) ph / panelHeight(profile));
            int w = (int) Math.round(panelWidth(profile) * scale);
            int h = (int) Math.round(panelHeight(profile) * scale);
            int left = (pw - w) / 2;
            int top = (ph - h) / 2;
            return new Layout(new Rect(left, top, left + w, top + h), new Rect(), false);
        }
    }
}
