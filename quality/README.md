# Robot Java 17 质量门

本目录是 Robot 原生配置，不修改 BD 管理的 Java 8/JH4J 模板。四个分析器已接入根 [pom.xml](../pom.xml) 的 `robot-quality` profile，POM 是唯一插件配置源。完整入口使用真实 Java 17 和校验过下载摘要的 Maven Wrapper，执行一次非追加的 `clean verify -Probot-quality`；得到本次真实报告前不能宣称门禁通过。

| 工具 | 固定版本 | 原生证据 |
| --- | --- | --- |
| Checkstyle | Maven 3.6.0 / engine 9.3 | 每个 Java 模块 `target/checkstyle-result.xml` |
| PMD | Maven 3.28.0 / PMD 7.17.0，targetJdk 17 | 每个 Java 模块 `target/pmd.xml` |
| SpotBugs | Maven 4.8.6.8，Max / Low | 每个 Java 模块 `target/spotbugsXml.xml` |
| Spotless | Maven 2.30.0 / google-java-format 1.15.0 AOSP | 同次成功 Maven 日志中每模块 `spotless:check` 实际执行 |
| JaCoCo | Maven 0.8.15 | 包含 Service/Controller 源码的模块 `target/site/jacoco/jacoco.xml` |

这些是固定兼容方案，未宣称最新版本。google-java-format 旧 1.7 不能用于 Java 17 源码；Java 17 内部 javac API 访问由 Spotless 集成处理。Spotless `check` 原生不提供 XML，不能将自造 XML 或脚本自报当成格式通过证据。需要修改格式时显式运行 `spotless:apply` 并审查 diff，不能用宽包排除或自动问题基线隐藏失败。

JaCoCo 严格按 CLASS 检查实际 `..service..` 包的全部业务类（含辅助类和内部类）：行覆盖率至少 70%，分支覆盖率至少 60%；`..controller..` 至少 50% 行覆盖率。按整数交叉乘法比较，不能四舍五入越线，也不能用包/模块平均值掩盖零覆盖业务类。原生零分支计数不要求分支比例；接口无执行体、常量或原生生成代码无可执行计数不产生虚假比例。Mapper/Entity/DTO/VO/config 没有统一比例要求，仍参加静态扫描，无包级排除。

参数数目门槛为 5；框架覆盖方法遵守其固定接口。已发布的四个不可变兼容值构造（`MenuTreeNode(13)`、`MenuButtonVO(6)`、`PermissionStateStore.CacheEvent(11)`、`PermissionStateStore.OutboxSnapshot(6)`）只委托 record canonical 构造，按源码文件、构造名称和参数个数精确 XPath 评审，不豁免业务方法。SpotBugs 对共享 Spring 基础设施 Bean 的误报必须精确到类、构造参数、字段和规则；调用方 DTO/集合的可变别名一律整改并验证输入、输出和 JSON 水合。

`scripts/check_java_quality.py` 要求每个 Java 源模块在同次日志中真实执行 `clean:clean`、Checkstyle/PMD/SpotBugs/Spotless 的 `check`，并只有一次 `Scanning for projects` 和 `BUILD SUCCESS`，不存在失败或质量工具跳过。报告缺失、不可解析、分析错误、发现问题、源码/配置之后未重建、业务 class 未编译或被 JaCoCo 遗漏均阻断。JSON 输出包含源码、POM、规则、日志和报告的 SHA-256 与逐类计数。

```bash
python3 scripts/check_java_quality.py --root . --maven-log target/verification/maven-verify.log
python3 -m unittest discover -s scripts -p 'test_java_quality.py'
```

日志必须使用新临时文件捕获实际 Maven 输出，开启 `set -euo pipefail`，成功后复制到 `target/verification/maven-verify.log` 再运行核验；不得追加旧日志，不能在 `clean` 前把日志放入会被清掉的 target 中。验证脚本测试的 XML 是解析器测试夹具，只证明拒绝与边界逻辑，不能代替真实工具执行或实际业务覆盖率。

配置来源：[Checkstyle Maven 参数](https://maven.apache.org/plugins/maven-checkstyle-plugin/check-mojo.html)、[PMD Maven 参数](https://maven.apache.org/plugins/maven-pmd-plugin/check-mojo.html)、[SpotBugs Maven](https://spotbugs.github.io/spotbugs-maven-plugin/)、[Spotless Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven)、[google-java-format 固定版本](https://github.com/google/google-java-format/tree/v1.15.0)、[JaCoCo XML 格式](https://www.jacoco.org/jacoco/trunk/doc/report.dtd)。
