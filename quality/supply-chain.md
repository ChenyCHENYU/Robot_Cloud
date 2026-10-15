# 供应链门与本轮审查

本工程使用根 POM 的 CycloneDX 2.9.3 聚合运行时与 provided 依赖，生成 `target/bom.json`。`scripts/verify.sh` 完成真实 Maven `clean verify -Probot-quality` 后执行 `scripts/check_supply_chain.py`，依赖列表来自实际 SBOM，网络失败、覆盖不完整、漏洞、未知许可证或过期审查都会阻断。政策登记的13个精确 `com.robot` 模块及版本作为第一方代码另行接受源码质量门，不伪装成外部包扫描结果。

## 真实扫描记录

2026-10-14 12:53:42 UTC 联网 OSV 复扫覆盖 **239 个外部 Maven 固定版本，0 个已报告漏洞、0 个许可证阻断项**，没有漏洞豁免。输入 SBOM SHA-256 为 `292cdb85b73d0630b7b7803e8907d042823428a6af524a8e282be2185dc7e1ae`。这份 SBOM 来自本轮真实构建；当时该次整体 Maven 验证尚未完成，扫描通过不能替代功能、源码质量和运行门。最终门禁仍需扫描同次成功构建生成的新 SBOM，核对输入哈希。

外部证据保存于本次工作区 `output/robot-cloud-release-remediation-2026-10-14/supply-chain/`：`fresh-bom.json`、`fresh-scan-final.json`、`fresh-osv-final/index.json` 与 3 次真实请求/响应。OSV 没有统一全库快照时间，报告记录查询时间和实际公告修改时间；零结果表示该查询时点未报告命中，不能推导将来的风险。CI 保存 `target/verification/supply-chain.json` 及同目录 `supply-chain-osv/` 原始响应。

扫描器遵循 [OSV querybatch 分页协议](https://google.github.io/osv.dev/post-v1-querybatch/)，为每个查询继续读取其 `next_page_token`；数量不符、异常结果、重复 token、详情身份不匹配和无法确认的撤回状态均失败。SPDX 按 `WITH`、`AND`、`OR` 与括号优先级解析，见 [SPDX 表达式规范](https://spdx.github.io/spdx-spec/v2.3.1/SPDX-license-expressions/)。SBOM 的无序多个 license 条目不自动当成双许可 OR，只有有证据的精确制品审查才能纠正元数据。

## 漏洞修复依据

初始真实 SBOM 的 Jackson 2.21.5、Jackson 3.1.5 和 Tomcat 11.0.24 共命中 **17 个制品/公告组合**。本轮已分别升级到 Jackson 2.21.7、3.1.7 和 Tomcat 11.0.26，新的真实 SBOM 复扫没有这些命中。部分问题需要特定反序列化或容器配置路径；存在修复版本时采用升级，不以当前未直接调用该路径作为豁免。

| 初始公告 | 影响与采用的修复 |
| --- | --- |
| [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) / CVE-2026-89425 | Jackson core 的 DataInput 错误 token 无界累积；两代 core 升级到 2.21.7 / 3.1.7。 |
| [GHSA-p6pp-m3f8-5c89](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) / CVE-2026-89407 | NumberInput 正则处理可能造成二次复杂度；采用相同 core 修复版本。 |
| [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) / CVE-2026-91777 | 前向对象引用处理可能造成二次复杂度；两代 databind 升级到 2.21.7 / 3.1.7。 |
| [GHSA-gx83-3vf8-gh7j](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-gx83-3vf8-gh7j) / CVE-2026-83557 | 不安全多态基类型 Comparable 的限制不足；采用相同 databind 修复版本。 |
| [GHSA-q4xh-88c3-wmh7](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7) / CVE-2026-68497 | Duration / XMLGregorianCalendar 数值处理可造成资源耗尽；采用相同 databind 修复版本。 |
| [GHSA-wjgm-6hv5-3cvf](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf) / CVE-2026-19032 | Path 反序列化未限制 FileSystemProvider scheme；采用相同 databind 修复版本。 |
| [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) / CVE-2026-91776 | 未知原始类型 ID 的缓存无界增长；采用相同 databind 修复版本。 |
| [Tomcat 官方安全公告](https://tomcat.apache.org/security-11.html) / CVE-2026-65905、CVE-2026-65182、CVE-2026-68525 | DIGEST 重放和请求/FORM 安全约束绕过问题在 11.0.25 修复；采用 11.0.26，并对 core、websocket、el 一起锁定。 |

两代 Jackson 并存是 Boot 4 官方 Jackson 2 过渡桥的实际依赖结果，不能只扫描其中一个坐标族。版本与桥接策略见 [技术选型](../docs/tech-selection.md)。

## 许可证条件与精确元数据

`quality/supply-chain-policy.json` 保留当前实际出现且已审查的许可证标识；SPDX ID 或表达式不在允许范围时阻断。MIT-0 是无署名要求的 MIT 变体，MulanPSL-2.0 必须保留其许可证及相关声明，依据 [MIT-0](https://spdx.org/licenses/MIT-0.html) 和 [MulanPSL-2.0](https://spdx.org/licenses/MulanPSL-2.0.html)。许可门只判断输入和审查范围；发行包仍需保留 Apache/BSD/MIT/EPL/LGPL 等依赖各自的通知和适用义务。

- **Bouncy Castle 1.85.2**：SBOM 名称 `Bouncy Castle Licence` 依据 [官方说明](https://www.bouncycastle.org/about/license/) 和该 jar 的 `META-INF/LICENSE.md` 精确解释为 MIT。保留版权和许可声明。许可证文本 SHA-256：`0e01f1549c9022f406392ac2947d32223b7c2e977d21ea2f8c182fdeb4dae5fd`。
- **MySQL Connector/J 9.7.0**：SBOM 的名称精确解释为 `GPL-2.0-only WITH Universal-FOSS-exception-1.0`。该制品的 [官方 LICENSE](https://raw.githubusercontent.com/mysql/mysql-connector-j/release/9.x/LICENSE) 与 [Oracle UFOSS 条款](https://oss.oracle.com/licenses/universal-foss-exception/) 允许与附完整对应源码、采用 OSI/FSF 自由许可的其他 FOSS 经接口互操作。根据仓库实际 [MIT LICENSE](../LICENSE)，本轮审查限定于现 Robot_Cloud MIT 源码发行与未修改驱动；这属于对条款的项目适用性判断。发行时保留驱动自身 GPLv2、UFOSS 和第三方声明，并提供对应源码或符合 GPLv2 的源码提供方式。UFOSS 不等同于 Classpath Exception，不授权把驱动或闭源衍生发行统一改为 MIT。驱动 LICENSE SHA-256：`75d3a92b08c7bf9476bab5a8661e3abedc081463d85c93251aab60a5e0ad92ce`。

上述两项规则绑定精确包、版本、原元数据、证据、责任人和 2026-10-14 审查日期，最迟于 **2027-01-12** 复审；MySQL 规则还核对根 LICENSE 哈希。升级版本、元数据或项目许可证改变均需重新审查，不批量默认豁免。真正的漏洞/许可例外还必须绑定 finding、理由、证据与责任人，最长 90 天；当前 `exceptions` 为空。

运行扫描器负例：`python3 scripts/test_supply_chain.py`。本轮 23 项已通过，覆盖分页遗漏、未知/损坏响应、详情身份、网络失败、SPDX 优先级、伪双许可、精确规则版本与过期边界、UFOSS/MIT 条件、第一方精确身份和不可追加的 HTTP 证据。
