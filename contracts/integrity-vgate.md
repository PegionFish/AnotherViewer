# V 门字节规格（下载文件完整性，integrity-vgate）

> 自包含规格：实现代理只读本文档即可编码，无需另查设计稿。服务器端实现位于
> `anotherviewer-web/src/main/kotlin/**/service/integrity/VGate.kt`（包结构由实现代理按
> 服务器端既有包约定定），测试 `VGateTest`；Android 端实现位于
> `app/src/main/java/**/util/VGate.java`，测试 `VGateTest`。共享测试向量见 §5，
> 生成脚本 `anotherviewer-web/src/test/resources/integrity/generate_fixtures.py`。

## 1. 目标与范围

V 门 = 页图片文件**落盘前**的字节级校验门，对 JPEG / PNG / GIF / WebP 四种格式生效：

- **通过** → 允许落盘，并写入该文件的 SHA-256 基线（两端各自独立维护，不互相同步）。
- **不通过** → 拒收：**不落盘、不建 SHA-256 基线**（记一次拒收日志/计数，由实现层决定呈现）。

V 门只做**字节级启发式**检查（V1 魔数 + V2 尾标记），**不做完整解码**（不解析 IDAT /
VP8L / LZW 像素数据）。廉价、同步、无外部依赖是硬约束；语义级完整性由 SHA-256 基线巡检兜底。

## 2. V1 魔数（文件头）

按**内容嗅探**判定格式（与扩展名无关），四种魔数互斥，最多命中一种；全部不命中 → V1 拒。

| 格式  | 偏移    | 必须匹配的字节                                   | V1 最小文件长度 |
|-------|---------|--------------------------------------------------|-----------------|
| JPEG  | 0–1     | `FF D8`                                          | 2               |
| PNG   | 0–7     | `89 50 4E 47 0D 0A 1A 0A`                        | 8               |
| GIF   | 0–5     | `47 49 46 38 37 61`（`GIF87a`）或 `47 49 46 38 39 61`（`GIF89a`） | 6 |
| WebP  | 0–3, 8–11 | `RIFF`（偏移 0–3）+ `WEBP`（偏移 8–11）         | 12              |

- 文件为空，或长度小于命中格式的最小头长度 → V1 拒。
- 典型场景「服务器 HTTP 200 但返回 HTML 错误页」（`<html>...` 被存成 `.jpg`）：内容嗅探不命中
  任何魔数 → V1 拒，与扩展名无关。
- 实现建议：V1 先于 V2，且 sniff 结果（格式）作为 V2 选择尾规则的输入。

## 3. V2 尾标记（精确到流末尾）

尾窗口必须落在文件**真实末尾**——窗口越界（文件比尾标记还短）→ V2 拒。

| 格式  | 检查                                                                 | 最小文件长度（V2 生效前提） |
|-------|----------------------------------------------------------------------|------------------------------|
| JPEG  | 最后 2 字节 == `FF D9`（EOI）                                        | 4                            |
| PNG   | 最后 8 字节 == `49 45 4E 44 AE 42 60 82`（`IEND` 类型 + 其固定 CRC `AE 42 60 82`） | 16（头 8 + 尾 8） |
| GIF   | 最后 1 字节 == `3B`（trailer）                                       | 7                            |
| WebP  | 字节 4–7 的**小端 u32** == 文件总长 − 8（RIFF 容器长度自洽，天然覆盖长度 ≥ 12） | 12                    |

- PNG 补充（可选加强，非必需）：倒数第 12–9 字节是 IEND 的 4 字节长度前缀 `00 00 00 00`，
  实现可一并校验；规范要求以最后 8 字节为准。
- WebP 补充：该检查不区分 VP8 / VP8L / VP8X chunk，也不校验 chunk 内容——它是容器长度
  自洽性检查。追加任何垃圾都会使「RIFF 声明长度 ≠ 实际长度」成立 → V2 拒。
- 尾部有多余字节（合法图 + 追加垃圾，padded 场景）：JPEG/PNG/GIF 因末字节不再匹配尾标记而拒；
  WebP 因 RIFF 长度不再自洽而拒。一律 V2 拒。
- 已知盲区（有意接受）：若垃圾恰好使流末尾仍满足尾规则（例如截断点恰在 `FF D9` 后紧接的
  垃圾又以 `3B` 结尾等），V 门会放行。V 门是廉价闸门，不是解码器；此类残留由 SHA-256
  基线巡检兜底。fixture 不为盲区构造样本。

## 4. 判定语义

```
accept ⇔ V1 ∧ V2        （V1、V2 依次检查，V1 拒则不必看 V2）
```

- 任一不过 → 拒收：**不落盘、不建 SHA-256 基线**。
- 全过 → 落盘，并以落盘字节目标计算/记录 **SHA-256 基线**（十六进制小写，与
  MANIFEST.sha256 同算法）。
- **V3（传输层长度检查）**：`HTTP Content-Length == 实际写入字节数`。位置在**调用方的下载
  写循环**（服务器端：下载 HTTP 响应读取循环；Android 端：各自的页文件写入完成后）判定，
  不通过同样拒收不落盘。V3 属传输层，不在 VGate 字节检查内，也**不属字节 fixtures 范畴**
  （fixtures 无传输语义）；本节仅为定位描述。

## 5. 共享 fixtures（两端口径）

目录：服务器端 `anotherviewer-web/src/test/resources/integrity/`（生成脚本同目录）；
Android 端 `app/src/test/resources/integrity/` 为**字节级相同副本**。两端文件名相同、
SHA-256 相同；**口径以 `MANIFEST.sha256`（sha256sum 格式，`hash␠␠filename`）为准**。

| 文件                 | 格式  | 分类            | 字节数 | SHA-256                                                            | 期望判定      |
|----------------------|-------|-----------------|--------|--------------------------------------------------------------------|---------------|
| valid.jpg            | JPEG  | valid           | 159    | `24ac74130806ae02d7e4ee72881b9776019996c9f94ee1545ed4830459b737f`  | accept        |
| valid.png            | PNG   | valid           | 165    | `248b07a3d0e1e0f67d43d18065be8f74434549c0fdde6b0bfc08a7835d41909f` | accept        |
| valid.gif            | GIF   | valid           | 45     | `ae8d9134a06d1a510778b58afb8ea391a4ee99119c5b99c89ccaf89fe0cf3bec` | accept        |
| valid.webp           | WebP  | valid           | 54     | `4e42f6cdf475cc5d30ed4f0bc40aadc0761d8c6fbc8dc68526661ace6616977a` | accept        |
| truncated.jpg        | JPEG  | truncated       | 151    | `e8acc87c41a50a4948abad67b7c9df1f7251071be383db3ca80e993b7dbc3179` | reject (V2)   |
| truncated.png        | PNG   | truncated       | 157    | `d903213a3646486aa460a3e2d528ae499d43b239adc4ad939b7788250de9dd26` | reject (V2)   |
| html_disguised.jpg   | HTML  | html_disguised  | 155    | `d166867587f0e6dc3604df3fb247706998ac5b556ee081cbd02e796234644525` | reject (V1)   |
| padded.png           | PNG   | padded          | 181    | `0d5ff424c135630b9778045f8c9f28baf9a02966c2bcebc6fc0fe1d231ea0b6f` | reject (V2)   |
| empty.bin            | —     | empty           | 0      | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | reject (V1)   |

性质与生成：

- `valid.*` 为**最小但真实可解码**样本（8×8；已用 sips 与 PIL 双解码器验证）。各 ≤ 200 字节。
- `truncated.*`：对应合法样本掐掉末尾 8 字节（规格要求 ≥ 4）——V1 过、V2 拒。
- `html_disguised.jpg`：`<html>...` 错误页伪装 `.jpg` 扩展名（HTTP 200 返回 HTML 的场景）——V1 拒。
- `padded.png`：合法 PNG + 追加 16 字节垃圾（`PADDED-GARBAGE16`）——V1 过、V2 拒。
- `empty.bin`：0 字节——V1 拒。
- 脚本可重复运行、幂等输出（本机已验证两次运行字节一致）。重新生成后须把产物
  （含 MANIFEST.sha256）字节级复制回 Android 端目录，并在两端各跑
  `shasum -c MANIFEST.sha256` 校验。

## 6. 两端消费说明与断言矩阵

- **服务器端（Kotlin）**：测试类 `VGateTest` 从测试 classpath 加载
  `javaClass.getResourceAsStream("/integrity/<文件名>")`（资源即本目录，Gradle 默认打包
  `src/test/resources`）。
- **Android 端（Java）**：测试类 `VGateTest`（plain JVM 单元测试，非 Robolectric）从
  `app/src/test/resources/integrity/<文件名>` 经
  `getClass().getResourceAsStream("/integrity/<文件名>")` 加载。
- 包名由各端实现代理按既有包结构自定；本规格只锁定资源路径与字节。
- **断言矩阵**（两端对同一文件必须给出相同判定）：

| 输入                                  | V1  | V2  | 判定  | 后续动作            |
|---------------------------------------|-----|-----|-------|---------------------|
| valid.jpg / valid.png / valid.gif / valid.webp | 过 | 过  | accept | 落盘 + SHA-256 基线 |
| truncated.jpg / truncated.png         | 过  | 拒  | reject | 不落盘、不建基线    |
| html_disguised.jpg                    | 拒  | —   | reject | 不落盘、不建基线    |
| padded.png                            | 过  | 拒  | reject | 不落盘、不建基线    |
| empty.bin                             | 拒  | —   | reject | 不落盘、不建基线    |

建议（非强制）两端测试均另含一条「accept 后 SHA-256 基线值 == MANIFEST.sha256 中该文件
哈希」的断言，把基线算法与 fixtures 口径对齐。
