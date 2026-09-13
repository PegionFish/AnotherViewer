# Surface Duo / 折叠屏适配

**日期**: 2026-09-13 · **状态**: 已落地 · **相关提交**: 161dd6bd（阅读模拟并入 + Tier-2 修复）、e489ce0c（manifest）、ae1191ba（FoldPolicy）、e056f7db（双栏浏览）

本轮适配覆盖四块能力：阅读器铰链感知双页（纯策略切缝）、Surface Duo 阅读模拟、双屏展开两栏浏览、跨屏 manifest 声明；附带两项修复（单页 spread 跨缝、Tier-2 代理转发 Origin 被 CORS 秒拒）。

## 1. 背景与参考

微软双屏 SDK 指南（[Get the Surface Duo SDK](https://learn.microsoft.com/en-us/previous-versions/dual-screen/android/get-duo-sdk)，文档已归档）给出三条接入路径：

1. **dualscreen-library（双屏库）**：一组布局/控件/helper（Foldable Layout、Screen Manager、Bottom Navigation 等）；
2. **androidx.window（Jetpack Window Manager）**：提供铰链遮挡区与折叠状态的 API，支持 Surface Duo 及其他厂商折叠屏；
3. **display-mask + hinge angle**：Surface Duo 专属 API，需添加 Azure DevOps 私有 feed（`pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed`）才能拉取。

本项目选 **androidx.window 路线**：

- **已引入**：`app/build.gradle` 已有 `androidx.window:window:1.0.0` + `window-java:1.0.0`（与微软指南推荐版本一致），零新增依赖；
- **跨厂商**：`FoldingFeature` 语义覆盖 Surface Duo 与一般折叠屏（如 Pixel Fold），不为单一机型绑定；
- **微软归档库停维护**：dualscreen-library / display-mask 随双屏文档整体归档（已 NOINDEX），display-mask 还依赖 Azure DevOps 私有 feed，不引入。

Manifest 层面（e489ce0c，均有审计注释）：

- `android:resizeableActivity` 维持**缺省 true**：targetSdk ≥ 24 时缺省即可 span，显式写 true 冗余、写 false 会禁止 span，故不声明；
- `android.max_aspect` 2.1 → 2.4：该 meta 仅对不可 resize 应用生效（本应用被系统忽略），保留仅作 minSdk 覆盖范围内 Android 7.x 老设备兜底；
- 关键 activity 的 launchMode / configChanges / screenOrientation 审计确认不阻碍 span，span 时尺寸变化由 `screenSize` configChanges 承接，无需重启。

## 2. 适配清单

| 能力 | 入口 | 实现位置 | 测试 |
|---|---|---|---|
| **Surface Duo 阅读模拟** | 阅读菜单（点屏幕上部中间 1/3 呼出）快速开关 + 模拟机型选择；设置 → 阅读 亦有完整偏好（`duo_sim_enabled` / `duo_sim_profile` / `duo_sim_hinge_dp`，铰链空隙默认 24dp） | `widget/DuoSimProfiles`（面板规格与视口拟合、按宿主 xdpi/ydpi 的物理尺寸换算）、`widget/DuoSimOverlay`（黑边 letterbox 与铰链缝绘制）、`ui/GalleryActivity#applyDuoSim`（GL 视口重拟合 + 双页缝） | `DuoSimProfilesTest`（7 项） |
| **折叠切缝策略（纯逻辑）** | ——（策略类，阅读页与双栏检测共用） | `widget/FoldPolicy`：`isSeparating` 对齐 androidx.window 语义（遮挡 FULL 或 HALF_OPENED 才算分隔），`readerSplitX` 给出双页缝 x | `FoldPolicyTest`（9 项） |
| **阅读器真实折叠双页** | 自动：`WindowInfoTracker` 推送 `FoldingFeature` | `ui/GalleryActivity` 布局监听 → `FoldPolicy.readerSplitX` → `GalleryView.setSplitX` | `FoldPolicyTest` 间接覆盖 |
| **双屏展开两栏浏览** | 主页/搜索等 `GalleryListScene` 列表点击 | `ui/pane/FoldSplitDetector`（分隔铰链检测与几何切分）+ `ui/pane/TwoPaneController`（左栏压窄、右栏点亮）+ `ui/pane/GalleryDetailPaneFragment`（轻量详情面板）；`GalleryListScene.onItemClick` 双栏时拦截 | `TwoPaneSplitTest`（10 项） |

附带修复：

| 问题 | 修复 |
|---|---|
| 单页 spread（封面/末页）在双屏上骑缝 | `glgallery/SpreadLayoutManager`：splitX 存在时收进阅读方向的首页槽位（LTR 左面板 / RTL 右面板），无铰链的普通平板保持原居中行为（e206abb4） |
| Tier-2 代理转发站点 Origin 被服务器 CORS 秒拒 | `webui/WebUiTier2ProxyInterceptor` 丢弃站点 Referer/Origin（a87208e4），详见 §3.3 |

**模拟模式与真实折叠的关系**：模拟开启时**接管**真实折叠逻辑——`GalleryActivity` 的 `FoldingFeature` 监听首行检查 `Settings.getDuoSimEnabled()`，命中即按模拟布局拟合视口并返回，真实铰链几何不参与；关闭时视口与 splitX 复位，真实折叠屏由 `FoldingFeature` 下次推送接管。模拟只是视图层渲染变换（黑边留幅 + 铰链缝遮盖），不修改任何系统显示设置。两条路径共用同一套双页缝语义：缝中心 x → `GalleryView.setSplitX`。

阅读模拟行为细节：

- 横屏 + 双页布局（左右阅读方向）：视口拟合为「面板 | 铰链缝 | 面板」，每面板显示一整页，缝中心即双页分界；
- 竖屏或非双页布局（上下滚动等）：回退单面板，避免内容落进缝里；
- 阅读菜单摘要按宿主 xdpi/ydpi 实测换算，展示「展开态 ≈ X 吋（真机 Y 吋，Z%）· 单面板 ≈ W 吋」，所选配置的物理尺寸一眼可读；
- 模拟机型规格：Surface Duo 面板 1350×1800（5.6 吋，展开 8.1 吋）、Surface Duo 2 面板 1344×1892（5.8 吋，展开 8.3 吋）。

## 3. 行为变更说明

### 3.1 FLAT + 无遮挡折痕不再切双页缝

阅读页旧逻辑只看折痕方向（竖向即切缝），不区分物理铰链与玻璃折痕。改用 `FoldPolicy` 后：`occlusionType == FULL`（Surface Duo 一类物理铰链）或 `state == HALF_OPENED`（两屏成夹角）才算分隔并切缝；FLAT + NONE（如 Pixel Fold 内屏平放的玻璃折痕）是连续屏，内容跨折痕连续显示。这是**有意的行为变更**，语义对齐 `FoldingFeature#isSeparating()`。

### 3.2 单页 spread 有缝时收进单侧面板

封面/末页等单页 spread 原先以半宽槽位居中于整个视口，在物理双屏（真实折叠 splitX 或 Duo 模拟）上会骑在缝上、把第一页劈到两块面板。现改为：splitX 存在时单页放入阅读方向的首页槽位（LTR 左面板 / RTL 右面板），与后续 spread 的页面流向一致；无铰链的普通平板保持原居中行为不变。

### 3.3 Tier-2 代理不再转发站点 Origin

App 的 EH API 调用（api.php POST）自带 `Origin: https://exhentai.org`，Tier-2 拦截器原样转发后，服务器 Spring CORS 过滤器视其为非法跨源请求，2ms 内返回 403 `Invalid CORS request`——表现为页面浏览（GET 不带 Origin）正常但所有 API 调用失败。修复：App 侧直接 `removeHeader("Referer")` + `removeHeader("Origin")`；服务器端代理（`SiteProxyController`）本就以自身站点配置重建这两个头，**服务器无需任何改动**，顺带减少隐私信息外发。拦截器 27 项单测全过，服务器端实测 POST api.php 由 403(31B) 恢复 200(15KB gdata)。

## 4. 已知限制

以下均以当前代码行为为准：

1. **两栏只拦截列表点击入口**：仅 `GalleryListScene.onItemClick`（主页/搜索/订阅等列表场景）把详情送进右栏；收藏（FavoritesScene）、历史、下载、排行等其他列表入口未拦截，仍打开全屏详情场景。
2. **右栏是轻量只读面板**：`GalleryDetailPaneFragment` 只展示封面、标题、分类、评分、页数、日期、标签与「阅读」入口；下载、评论、收藏等重操作仍需进完整详情场景。
3. **铰链消失时详情不迁移**：两栏回退单栏时右栏面板直接移除、待展示画廊清空，右栏正在看的详情**不会**自动转成全屏详情场景，需重新点击列表项。
4. **返回键作用于左栏舞台**：右栏面板是普通 Fragment，不进 StageActivity 场景栈，按返回只影响左栏场景栈。
5. **双栏最小栏宽 280dp**：任一侧小于该值保守回退单栏（`FoldSplitDetector.MIN_PANE_WIDTH_DP`），铰链贴边或小屏折痕不强行分栏。
6. **模拟的局限**：模拟只在 GL 阅读视口生效（EGL 初始化失败的 fallback 页不模拟）；非双页布局下模拟退化为单面板；铰链缝宽度按设置项固定（默认 24dp），不随内容变化。

## 5. 验证方式

### 单元测试门

三个纯逻辑类不依赖设备，普通 JVM 单测即可：

```bash
./gradlew :app:testAppReleaseDebugUnitTest \
    --tests "com.hippo.anotherviewer.widget.FoldPolicyTest" \
    --tests "com.hippo.anotherviewer.widget.DuoSimProfilesTest" \
    --tests "com.hippo.anotherviewer.ui.pane.TwoPaneSplitTest"
```

- `FoldPolicyTest`（9 项）：isSeparating 语义、FULL 遮挡/HALF_OPENED 竖向折叠切缝、FLAT+NONE 玻璃折痕不切、横向折痕不切、缝贴边与退化 bounds 保守回退；
- `DuoSimProfilesTest`（7 项）：机型 sanitize、横屏「面板|缝|面板」拟合、宽度约束、竖屏单面板、精确比例填满、空父容器、物理尺寸换算；
- `TwoPaneSplitTest`（10 项）：竖向分隔铰链切分、连续折痕/横向折痕/窄栏/退化输入回退单栏、androidx.window 枚举映射、detector 回报与去重。

### 真机评估

在一台 8.8 吋平板宿主上评估（阅读菜单摘要按宿主 xdpi/ydpi 实测换算）：**Surface Duo 2 档展开态拟合后 ≈8.2 吋，约为真机 8.3 吋的 99%**，单面板相应缩至可读宽度；Surface Duo 一档真机展开 8.1 吋。结论：8.8 吋级设备上的模拟尺寸与真机 Duo 展开态基本一致，足以评估 Duo 双页阅读体验。无 Duo 真机时，也可配合微软归档的 Surface Duo 模拟器镜像验证真实 `FoldingFeature` 路径（真实与模拟路径在 splitX 语义上共用同一套双页缝逻辑）。
