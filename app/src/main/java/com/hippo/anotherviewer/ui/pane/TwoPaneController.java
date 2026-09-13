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

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.hippo.anotherviewer.R;
import com.hippo.anotherviewer.client.data.GalleryInfo;
import com.hippo.anotherviewer.ui.MainActivity;

/**
 * 双屏双栏控制器：MainActivity 的薄钩子，只负责生命周期转发与
 * "列表点击送详情"一个入口。其余逻辑都在本类内完成：
 *
 * <ul>
 *     <li>持有 {@link FoldSplitDetector}，监听竖向分隔铰链；</li>
 *     <li>出现铰链时把左栏场景容器（{@code R.id.fragment_container}，
 *     原有 SceneFragment 体系原样不动）压窄到左屏宽度，点亮右栏
 *     {@code R.id.detail_pane}；</li>
 *     <li>右栏以普通 Fragment 方式承载 {@link GalleryDetailPaneFragment}，
 *     不进入 StageActivity 场景栈；</li>
 *     <li>铰链消失/收起时移除右栏、恢复容器宽度，左栏场景栈
 *     （含返回键语义）全程未被改动。</li>
 * </ul>
 *
 * <p>单屏（无分隔铰链）时 {@link #showGalleryDetail(GalleryInfo)}
 * 直接返回 false，调用方走原有单栏流程，默认路径零改动。
 */
public final class TwoPaneController implements FoldSplitDetector.Listener {

    private static final String PANE_FRAGMENT_TAG = "gallery_detail_pane";

    private final MainActivity mActivity;
    private final FoldSplitDetector mDetector;

    /** 当前生效的分割；null 表示单栏。 */
    @Nullable
    private FoldSplitDetector.Split mSplit;
    /** 双栏下最近一次要求展示的画廊；铰链重新出现时据此恢复右栏。 */
    @Nullable
    private GalleryInfo mPendingDetail;

    public TwoPaneController(@NonNull MainActivity activity) {
        mActivity = activity;
        mDetector = new FoldSplitDetector(activity, this);
    }

    /*---------------
     Life cycle（由 MainActivity 转发）
     ---------------*/

    public void start() {
        mDetector.start();
    }

    public void stop() {
        mDetector.stop();
    }

    public void destroy() {
        mDetector.stop();
        mSplit = null;
        mPendingDetail = null;
    }

    /** 当前是否处于双栏模式。 */
    public boolean isTwoPane() {
        return mSplit != null;
    }

    /**
     * 双栏模式下在右栏展示画廊详情。
     *
     * @return true 表示已交给右栏（调用方不要再走单栏 startScene 流程）；
     * false 表示当前不是双栏，调用方按原流程处理。
     */
    public boolean showGalleryDetail(@Nullable GalleryInfo galleryInfo) {
        if (mSplit == null || galleryInfo == null) {
            return false;
        }
        mPendingDetail = galleryInfo;
        reconcilePaneFragment();
        return true;
    }

    /*---------------
     FoldSplitDetector.Listener
     ---------------*/

    @Override
    public void onSplitChanged(@Nullable FoldSplitDetector.Split split) {
        if (split == null) {
            collapse();
        } else {
            expand(split);
        }
    }

    /*---------------
     Two pane layout
     ---------------*/

    private void expand(@NonNull FoldSplitDetector.Split split) {
        mSplit = split;

        View container = mActivity.findViewById(R.id.fragment_container);
        View pane = mActivity.findViewById(R.id.detail_pane);
        if (container == null || pane == null) {
            return;
        }
        // 左栏：DrawerLayout 的内容子视图宽度被强制为"父宽 - 左右边距"，
        // 压窄左栏必须用右外边距（右边距 = 窗口宽 - 铰链左侧宽度），
        // 左栏内容止步于铰链左缘，物理铰链遮挡区与右栏都不被其占用。
        ViewGroup.LayoutParams containerLp = container.getLayoutParams();
        if (containerLp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) containerLp;
            lp.leftMargin = 0;
            lp.rightMargin = Math.max(0, split.windowWidth - split.leftPaneWidth);
            container.setLayoutParams(lp);
        } else {
            // 防御：非 Margin 布局参数时退化为直接设宽
            containerLp.width = split.leftPaneWidth;
            container.setLayoutParams(containerLp);
        }

        // 右栏：普通 CoordinatorLayout 子视图，直接设宽度并靠右
        ViewGroup.LayoutParams paneLp = pane.getLayoutParams();
        paneLp.width = split.rightPaneWidth;
        pane.setLayoutParams(paneLp);
        pane.setVisibility(View.VISIBLE);

        reconcilePaneFragment();
    }

    private void collapse() {
        mSplit = null;
        mPendingDetail = null;

        removePaneFragment();

        View container = mActivity.findViewById(R.id.fragment_container);
        View pane = mActivity.findViewById(R.id.detail_pane);
        if (container != null) {
            ViewGroup.LayoutParams lp = container.getLayoutParams();
            // 恢复 activity_main.xml 里的占满布局
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.leftMargin = 0;
                mlp.rightMargin = 0;
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            container.setLayoutParams(lp);
        }
        if (pane != null) {
            pane.setVisibility(View.GONE);
        }
    }

    /** 保证右栏片段、提示文案与待展示画廊三者一致。 */
    private void reconcilePaneFragment() {
        FragmentManager fm = mActivity.getSupportFragmentManager();
        Fragment pane = fm.findFragmentByTag(PANE_FRAGMENT_TAG);

        if (mPendingDetail == null && pane instanceof GalleryDetailPaneFragment) {
            // 旋转恢复等场景：从还活着的面板回读当前画廊
            mPendingDetail = ((GalleryDetailPaneFragment) pane).getCurrentGallery();
        }

        showHint(pane == null);
        if (mPendingDetail == null) {
            return;
        }
        if (pane instanceof GalleryDetailPaneFragment) {
            ((GalleryDetailPaneFragment) pane).updateGallery(mPendingDetail);
        } else {
            FragmentTransaction transaction = fm.beginTransaction();
            transaction.replace(R.id.detail_pane,
                    GalleryDetailPaneFragment.newInstance(mPendingDetail), PANE_FRAGMENT_TAG);
            transaction.commitAllowingStateLoss();
            showHint(false);
        }
    }

    private void removePaneFragment() {
        FragmentManager fm = mActivity.getSupportFragmentManager();
        Fragment pane = fm.findFragmentByTag(PANE_FRAGMENT_TAG);
        if (pane != null) {
            fm.beginTransaction().remove(pane).commitAllowingStateLoss();
        }
        showHint(true);
    }

    /** 右栏空置提示：仅在没有面板片段时可见。 */
    private void showHint(boolean show) {
        View pane = mActivity.findViewById(R.id.detail_pane);
        if (pane == null) {
            return;
        }
        View hint = pane.findViewById(R.id.detail_pane_hint);
        if (hint != null) {
            hint.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}
