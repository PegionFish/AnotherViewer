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
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 铰链/折痕的双页切缝决策策略。纯逻辑类，输入全部是原始/简单类型，
 * 不依赖 androidx.window，单元测试可直接伪造输入。
 *
 * <p>语义对齐 Jetpack Window Manager（androidx.window 1.0.0）的
 * FoldingFeature#isSeparating()：遮挡完全（OCCLUSION_FULL，物理铰链，
 * 如 Surface Duo）或两屏成夹角（STATE_HALF_OPENED）时，折叠两侧是
 * 独立显示区域，双页阅读应在缝处一屏一页；而 STATE_FLAT 且无遮挡
 * （普通玻璃折痕，如 Pixel Fold 内屏平放）是一块连续屏，内容应跨
 * 折痕连续显示，不切缝。
 *
 * <p>行为变更说明：阅读页旧逻辑只看折痕方向（竖向即切缝），不区分
 * FLAT 玻璃折痕与真实分隔铰链；改用本策略后，FLAT+NONE 的连续屏
 * 折痕不再切缝，这是有意的行为变更。
 */
public final class FoldPolicy {

    /** 折痕方向：竖向（铰链线竖直，左右两屏），对应 FoldingFeature.Orientation.VERTICAL。 */
    public static final int ORIENTATION_VERTICAL = 0;
    /** 折痕方向：横向（铰链线水平，上下两屏），对应 FoldingFeature.Orientation.HORIZONTAL。 */
    public static final int ORIENTATION_HORIZONTAL = 1;

    /** 折叠状态：展平（连续屏），对应 FoldingFeature.State.FLAT。 */
    public static final int STATE_FLAT = 0;
    /** 折叠状态：半开（两屏成夹角），对应 FoldingFeature.State.HALF_OPENED。 */
    public static final int STATE_HALF_OPENED = 1;

    /** 遮挡类型：无遮挡（玻璃折痕），对应 FoldingFeature.OcclusionType.NONE。 */
    public static final int OCCLUSION_NONE = 0;
    /** 遮挡类型：完全遮挡（物理铰链，如 Surface Duo），对应 FoldingFeature.OcclusionType.FULL。 */
    public static final int OCCLUSION_FULL = 1;

    @IntDef({ORIENTATION_VERTICAL, ORIENTATION_HORIZONTAL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Orientation {
    }

    @IntDef({STATE_FLAT, STATE_HALF_OPENED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    @IntDef({OCCLUSION_NONE, OCCLUSION_FULL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface OcclusionType {
    }

    private FoldPolicy() {
    }

    /**
     * 折叠是否分隔两侧显示区域（对齐 androidx.window 的
     * FoldingFeature#isSeparating() 语义）：物理铰链（完全遮挡）或
     * 半开夹角即分隔；FLAT 且无遮挡为连续屏，不分隔。
     */
    public static boolean isSeparating(@OcclusionType int occlusionType, @State int state) {
        return occlusionType == OCCLUSION_FULL || state == STATE_HALF_OPENED;
    }

    /**
     * 阅读页双页切缝决策：仅当"竖向折叠 && 分隔"时返回缝的 x 坐标
     * （bounds 中心的 {@code (left + right) >> 1}，与 Rect#centerX() 一致），
     * 否则返回 -1，表示阅读页不切缝、内容连续显示。
     *
     * @param left          折叠特征 bounds 的左边界（窗口坐标系）
     * @param top           折叠特征 bounds 的上边界
     * @param right         折叠特征 bounds 的右边界
     * @param bottom        折叠特征 bounds 的下边界
     * @param orientation   折痕方向
     * @param state         折叠状态
     * @param occlusionType 遮挡类型
     * @param screenWidth   屏（窗口）宽度，用于剔除贴边、无分页意义的缝
     */
    public static int readerSplitX(int left, int top, int right, int bottom,
            @Orientation int orientation, @State int state,
            @OcclusionType int occlusionType, int screenWidth) {
        // 横向折痕（上下两屏）不存在左右分页缝。
        if (orientation != ORIENTATION_VERTICAL) {
            return -1;
        }
        // 连续屏的玻璃折痕（FLAT+NONE）不切缝，内容跨折痕连续。
        if (!isSeparating(occlusionType, state)) {
            return -1;
        }
        // bounds 无效时无从取缝。
        if (right <= left || bottom <= top) {
            return -1;
        }
        int centerX = (left + right) >> 1;
        // 缝贴边意味着某一侧宽度为 0，无法双页，视为无缝；屏宽非法时同样保守回退。
        if (screenWidth <= 0 || centerX <= 0 || centerX >= screenWidth) {
            return -1;
        }
        return centerX;
    }

    /**
     * {@link #readerSplitX(int, int, int, int, int, int, int, int)} 的 Rect 重载，
     * 方便直接传入 FoldingFeature#getBounds()。
     */
    public static int readerSplitX(@Nullable Rect bounds,
            @Orientation int orientation, @State int state,
            @OcclusionType int occlusionType, int screenWidth) {
        if (bounds == null) {
            return -1;
        }
        return readerSplitX(bounds.left, bounds.top, bounds.right, bounds.bottom,
                orientation, state, occlusionType, screenWidth);
    }
}
