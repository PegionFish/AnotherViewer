V 门共享测试向量（Android 端副本）
==================================

本目录内的 fixtures（含 MANIFEST.sha256）由服务器端生成脚本
    anotherviewer-web/src/test/resources/integrity/generate_fixtures.py
生成，并与 anotherviewer-web/src/test/resources/integrity/ 保持字节级相同。
两端测试套件消费同一批字节，判定结果必须一致。

口径以 MANIFEST.sha256 为准（sha256sum 格式）。
规格（V1 魔数 / V2 尾标记 / 判定语义 / 断言矩阵）见 contracts/integrity-vgate.md。

重新生成流程：跑上述脚本 -> 将产物（含 MANIFEST.sha256）字节级复制回本目录 ->
在两个目录各执行 `shasum -c MANIFEST.sha256` 校验通过。

测试加载方式（plain JVM 单元测试，resources 默认在测试 classpath 上）：
    getClass().getResourceAsStream("/integrity/valid.jpg")
