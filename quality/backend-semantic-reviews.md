# Robot 原生发现的精确语义复核

BD 0.37.0 的完整 native scan 和 B2–B32 review 仍真实执行；`bd-native.json`、`bd-robot-review.json` 保留全部发现、severity、fingerprint、原始失败和工具回执。项目层不修改 managed 规则、现有问题 baseline、目录阈值、warning budget 0 或 Java/业务代码，也不将原生结果改写成成功。

`scripts/check_backend_rules.mjs` 在原始扫描之后生成独立 `target/verification/bd-robot-semantic-review.json`：记录原始报告文件 SHA256、逐发现分类、源文件 SHA256、同包关系与有效阻断项。派生门使用包自身的 `evaluateQualityGate`、原有 config/baseline/coverage，只扣除下列有精确证据的已复核发现。其它错误、未知 gate reason、未复核 B6/B25、source/hash/inventory 漂移、截断/不完整报告继续阻断。输入稳定性和实际 Maven/MySQL/运行验收仍分别证明。

## 三个字段的同一声明注解

| 精确类/字段 | 原始发现 | 当前证据 |
| --- | --- | --- |
| `com.robot.system.api.entity.SysUser.passwordHash` | B25 error | `@ToString.Exclude` 与 String 字段位于同一声明；BCrypt 摘要不能进入模型字符串 |
| `com.robot.system.api.entity.SysUser.email` | B25 error | 同一声明真实 Lombok exclusion，联系值不进入模型字符串 |
| `com.robot.system.api.dto.user.UserSaveRequest.emailPresent` | B25 error | boolean 仅表示提交状态，且同一声明已有真实 exclusion |

原扫描只检查字段前面的若干行，遗漏同一行的注解。复核词法器保留位置，将注释、普通字符串和 text block 分离，定位精确顶层类的直属单字段声明；验证实际 `lombok.ToString.Exclude` 或明确 import 后的 `ToString.Exclude`。前置或同一声明的注解有效；相邻字段、方法局部、嵌套其它类、评论/字符串、错误 import/同名遮蔽、错误行、缺注解均无效。有显式 `toString()` 的类不自动采纳这项证明。

只接受 JSON 中固定的三组 path/class/field/type 与审核时的源 hash，不能扩成“所有敏感字段自动忽略”。新增或变更须重新审核具体源码；执行器不自动刷新 policy 或 baseline。该静态证明不替代实际字符串输出、JSON、异常日志及敏感字段隐私的 Java 正式回归。

## 同域认证测试的包内可见性

唯一已复核 B6 advisory 是 `robot-services/robot-auth/src/test/java/com/robot/auth/authn/service` 中确切的 23 个 Java 文件。全部属于 `com.robot.auth.authn.service`：多个测试共同使用 package-private `AuthStateTestSupport`，并直接覆盖生产 package-private 协作者 `AuthFamilyStates`、`AuthLogoutAudit`。为了文件数量把它们搬到不同 Java 包会改变内部可见性；这不是生产服务业务域耦合的证据。

复核同时记录测试目录全部 23 文件与当前 main service 目录全部 14 文件的 SHA256、package、共享 fixture 的实际消费者及内部协作者。数量、名单、内容或 package 漂移，fixture 改为 public、失去直接消费者，均不能沿用旧复核。任何 `src/main` 或其它目录的 B6 仍阻断；20 文件的建议和 warning budget 0 保持不变。

这些输入固定在 [backend-semantic-reviews.json](backend-semantic-reviews.json)，分类实现见 [backend_semantic_review.mjs](../scripts/tooling/backend_semantic_review.mjs)，负例见 [backend_semantic_review.test.mjs](../scripts/tooling/backend_semantic_review.test.mjs)。完整原生 B1/Sa-Token 识别与 B17 框架模板限制继续可见；项目 RBD 适配和本次语义复核均不冒充 native 所有规则、Java 质量门或生产验收通过。
