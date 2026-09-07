# 执行日志（2026-09-06 PWA 批次）——未跟踪工作文档，永不 commit

编排者按 docs/plan-2026-09-06-pwa-multiplatform.md 执行。
每卡一行：ID / 结论 / commit hash。
**隔离形态**：与手册批次（另一 Agent 在飞）同仓不同 worktree——本批全部工作在
`/home/bob/AnotherViewer-pwa`（分支 `pwa-multiplatform`，基于 f9ffe899），
主树 /home/bob/AnotherViewer 留给手册批次；收口 rebase 回 BiLi_PC_Gamer 后统一 push。

## W0 自举

- 通读 plan 全文 + CONTEXT.md ✅
- §9 冲突判定：手册批次**在飞**（期间实测新增 commit 818ce45b=W1-B2、f9ffe899=W2-B1，
  exec-log 实时更新）→ 按用户指令切 worktree 隔离，互不干涉；本批不动后端/平台代码。
- 基线门（在主树跑，内容与 f9ffe899 一致）：
  - 前端 vitest：1097 用例全绿 ✅
  - 前端 typecheck：exit 0 ✅
  - 后端 :anotherviewer-web:test：928 用例 0 失败 ✅
    （首跑与前端 npm test 并发时 BUILD FAILED 一次，判定资源竞争偶发；
     隔离强制重跑 cleanTest+test 绿，排除代码归因）
- 锚点漂移备案：HEAD 已从 a891f520 前进到 f9ffe899；cdcab82c 对
  useEnhancedImage.ts +23 行（C2 锁文件）、7a2bad84 对 ReaderView、
  122bbb6d 仅后端——派发卡一律要求 grep 重定位。
- 本批 sw.js 语义基点：手册批次 W3-F6（opaque 跳过）**未落**，C5 从 v2 现状起改。

## 卡片记录

| 卡 | 结论 | commit |
|----|------|--------|
| W0 自举 | ✅ 见上；worktree=/home/bob/AnotherViewer-pwa，branch=pwa-multiplatform@f9ffe899 | （无 commit） |
| C1 server 连接核心 | ✅ 首派限流退卡，重派完成。server.ts（编排者按冻结契约预置，C1 核验零改动）+21 用例 spec；client.ts baseURL 派生+applyServerBase()，拦截器零改动；全量 1191 绿 | 41e02263 |
| C2 URL 构造点接入 | ✅ 4 处代码命中全部改经 resolveApiUrl（复核 grep 无遗漏、无锁外命中）；4 spec +8 用例含逐字节锚点；useEnhancedImage 代次守卫未动；typecheck 绿 | 68c9276c |
| C3 WS 端点接入 | ✅ 首派限流退卡（源码半成品留在树），重派复核源码+补 spec 2 用例（同源 '/ws'、远程 http(s)://x/ws）；仲裁时并行瞬态红已澄清 | 2234050d |
| C4 视口框架 composable | ✅ 契约全量落地+EVAL_TOOLBAR_H=56；60 用例（矩阵/fit 精确值/锁定冻结/query 往返 16 组合/非法 query）；typecheck 绿 | 51c08f5b |
| C5 SW 远程 API 缓存 | ✅ 规则 3 去 origin 门、v2→v3（全批唯一 bump）、PWA.md 策略表/版本漂移/边界同步；verify-remote-sw.mjs 双服务 harness 全 PASS（含停 stub 命中缓存） | 92ffeff9 |
| C6 manifest 清理 | ✅ 删 form_factor+增比例评估 shortcut；verify-manifest.mjs PASS（本地+BASE 模式）；**裁决**：既有 manifest.spec 断言 form_factor 存在与卡面冲突，编排者改写为反向锚定并入本卡 | 508ec5f8 |
| W1→W2 波间门 | ✅ 前端 1191/1191 全绿、typecheck 0、gradle up-to-date 绿（后端本波零改动） | — |
| C7 Setup 向导 | ✅ SetupView（裸 fetch 探活三分支/保存序列/混合内容警告）+SetupHintCard（Home 非阻塞，z-index 85）+路由 /setup、/eval+设置页入口；13 用例；偏离：GeneralSettings 用同构原生 button（锁外 spec 冻结 PrefRow=20）、status 无 version 字段不虚构；全量 1226 绿 | 002b82ce |
| C8 EvalView 评估台 | ✅ iframe 真视口+EvalToolbar 受控组件+22 用例（query 挂载/锁定 resize/query 回写/iframe 绑定断言）；偏离：新标签打开用同源直读路径替代 postMessage（应答端在锁外文件，握手必超时）；全量绿 | f72be92f |
| C9+C10 文档+HTTPS 资产 | ✅ 合并单代理（共锁 deployment.md）。pwa-install.md 七节齐备；deployment.md 纯追加 165 行（轨道 B+CORS/WS env+nginx 附录）；gen-lan-cert.sh（PEM 产出修正卡面 pkcs12 笔误、无 mkcert 报错路径实测 exit 1、dry-run 门）；caddy conf 静态审查过（本机无 caddy）；deploy/certs/ 先行入 ignore | 4f1fc5aa |
| W2→W3 波间门 | ✅ 前端 1226/1226、typecheck 0、**worktree 内** gradle 928/0 绿。教训：Bash 每次重置回主仓目录，gradle 门必须显式 cd worktree——首跑误入主仓撞上对方批次在飞后端（955 用例 26 败，非本分支产物），且 cleanTest 动了主仓构建目录（无源码影响，对方 INT-1 自会重建）；已备案 | — |
| C11 比例矩阵 e2e+对拍 | ✅ **32/32 对拍 diffRatio=0**（8 组合×4 路由；方案「24/24」为笔误）。偏离备案：宿主视口 1920×1400（竖屏组合高>可用高会被裁）、vite 加 --host 127.0.0.1、对拍时隐藏宿主角标、/preferences stub 特判、scale=100 而非 fit（transform 不改布局，100% 保证逐像素对齐） | 2f649118 |
| INT-1 全量门 | ✅ typecheck 0、vitest 1226/1226、gradle（worktree）绿、./build.sh 成功产出 bootJar | — |
| INT-2 视觉基线 | ✅ ROUTES 增 /setup、/eval 两条件；比对模式先跑：229 屏中仅 home 7 屏 diff（diff 图确证=SetupHintCard 新 UI，其余逐像素一致）→ 刷新基线 → 253/253 全绿 | ced199a1 |
| INT-3 部署 | ⚠ **按用户指令收窄**：测试仅在本 Arch 工作站；141 上对方批次在飞（其 INT-3 未做），本批**不部署 8081 jar、不动 141 基础设施**——部署全套资产（jar、gen-lan-cert.sh、caddy conf、deployment.md 步骤）已就绪，待两批合并后协调执行 | — |
| INT-4a 首轮（直连 8090 jar） | ⚠ 前端逻辑全过（manifest 9/9、SW 注册/受控、离线壳[预热口径]、setup 保存序列、设置入口、eval 角标/锁定 8/8）；**发现两处后端 M-4 安全头阻断**：X-Frame-Options: DENY→/eval iframe 拒显；CSP connect-src 'self'→远程模式与跨主机探活被拦（CORS 正常也无效） | — |
| 头改写方案 | ✅ 红线不动后端：deploy/caddy-anotherviewer.conf 增 header 块（XFO→SAMEORIGIN；CSP 原策略保留、connect-src 追加 http: https:），deployment.md 增「0. 安全响应头改写」节+nginx 附录等价指令，pwa-install.md 修正 degraded 矩阵/边界（轨道 A 不解 XFO、首访离线需二次预热）；本地 node 反代逐字模拟 conf 后 curl 验证头生效 | 8c2a3074 |
| INT-4b browser-use 亲自（8443 头改写反代） | ✅ 首页 app-layout/SW 受控/manifest 挂载；/setup 归一化预览实时→测试连接「服务器可达（HTTP 200）·无需登录」（跨主机探活通）→保存重载 server-base/configured 落盘、引导卡消失、**远程模式渲染应用非登录页**；/eval?ratio=4:3&orient=port 角标「768×1024·4:3·竖屏·适应 65%」、iframe 内真实应用渲染出竖屏抽屉断点行为（截图入会话证据）；走查后已清 localStorage | — |
| INT-4a 复跑（8443 代理） | ✅ **/eval 25/25（iframe 内容 8/8，上轮 0/8）、远程模式 6/6（跨源 API 200+SW v3-api 缓存命中、渲染应用非登录页）、/setup 6/6（探活「可达 HTTP 200」）、SW/离线壳 5/6（唯一 FAIL=首访立即离线的既有 SW 行为，已在 pwa-install.md 备案）**。新发现：后端 `/gallery/search` 空库也固定 ~60s 返回（疑似 EH 搜索超时窗）＞前端 axios 30s → 首页列表恒错误重试态，**同源同样命中**，属平台既有问题记录上报（疑与 EH 会话相关，归后端批次） | — |
| INT-5 | **rebase+push 完成**。rebase 到 BiLi_PC_Gamer@a13209f0（对方 15 commit 增量）：①C2 撞修改/删除——DownloadItem.vue 被对方 A4 重构删除，裁决删除胜出，**补卡**：新共享行 AppListRow.vue:138 原样迁移了手拼代理 URL，已接 resolveApiUrl+补 spec（02ca4a59）；②C1 撞 client.ts（对方 W3-F6 的 401 收口+AbortController）——保留双方，spec 两 describe 并存（6e9c5a4d 清残留标记）；③sw.js/router/App.vue/GeneralSettings 自动合并，逐一核查语义正确（v3+去 origin 门共存、/setup /eval 在 catch-all 前、引导卡在位）。post-rebase 门：vitest 1206/1206、typecheck 0、worktree gradle 绿。**push=origin/pwa-multiplatform**（不动对方正检出的 BiLi_PC_Gamer 引用；合并=快进一条命令待协调：`git merge pwa-multiplatform`） | 02ca4a59、6e9c5a4d |
| C7 Setup 向导 | | |
| C8 EvalView 评估台 | | |
| C9+C10 文档+HTTPS 资产（合并单代理：共锁 deployment.md） | | |
| W2→W3 波间全量门 | | |
| C11 比例矩阵 e2e+对拍 | | |
| INT-1 全量门 | | |
| INT-2 视觉基线 | | |
| INT-3 部署（141） | | |
| INT-4 四端验证 | | |
| INT-5 收口 push | | |
