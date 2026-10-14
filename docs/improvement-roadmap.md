# Robot Cloud 改进台账

最近复核：2026-10-14。完整审查的业务源码基线为 `29c42aae7dfbe9f72e2b7d2ee4d24ba209b2124f`，Admin 对比基线为 `2cb90509edd13b57595e7425813a992c842f2d8e`。原始 110 个证据文件完整且 SHA-256 一致；后续整改目录只有任务计划，Cloud 没有对应修复提交或验收记录。**REV01–REV24 均有待处理工作，本次文档归档不代表已修复。**

完整事实和验证边界见 [2026-10-14 全面审查](review-2026-10-14.md)，实施顺序、负例、兼容与回滚要求见 [执行计划](review-execution-plan.md)。本台账维护状态，不替代原报告或接口合同。旧 N01–N14 保留，下面给出与 REV 编号的映射；最新计划使用批次 0–7。

当前三服务、授权事实源、写命令事务、数据库 epoch 和可靠缓存通知继续保留。原审查默认入口通过 305 项 Java 测试、23 项 Python 测试，未执行 MySQL 升级；历史 308 项 Java 测试含 3 项不同范围的升级测试。两者以及历史双实例故障证据分别说明其执行范围，不证明全部发现已覆盖或当前生产环境已验收。

## 最新发现与当前状态（REV01–REV24）

“待实施”表示已有源码或独立实验依据，但没有修复和正式验收；“待决定”表示部分实现依赖明确的产品合同；“待环境验收”表示不能仅用源码或 H2 推导真实拓扑结果。三种状态均不能登记为完成。

| ID | 优先级 | 问题与所需结果 | 当前状态 / 先决输入 | 旧编号 |
| --- | --- | --- | --- | --- |
| REV01 | 条件 P0 | 全局菜单/按钮/字典写入不能改变普通角色的授权上界 | 待决定、待实施：平台目录的永久权威身份及展示字段委托范围 | N01 |
| REV02 | P1，生产前 | 生产配置、内部密钥、种子、管理访问和环境隔离可执行护栏 | 待实施、待环境验收：正式配置与 bootstrap 交付 | N02 |
| REV03 | P1 | 所有新密码写入同时执行字符和 UTF-8 字节限制 | 待实施：新写边界；待决定：历史超长凭据策略 | N03 |
| REV04 | P2 | 四个敏感模型及嵌套输出不含密码/摘要 | 待实施，保留必要 JSON 和内部认证字段 | N03 |
| REV05 | P2 | 实际流式请求字节和字段限制，不依赖 Content-Length | 待实施、待入口验收：chunked/HTTP2、取消与 DataBuffer 释放 | 新发现 |
| REV06 | P1/P2 | 跨实例认证失败窗口、恢复、脱敏审计与统一公开失败 | 待实施；公司发现合同及可信来源策略待决定 | N04 |
| REV07 | P2 | 菜单移到根级/编辑根级实际写入 parent_id=NULL | 待实施：精确字段清空，保留树锁与版本比较 | 新发现 |
| REV08 | P2 | 动态菜单路由具有稳定且全局唯一身份 | 待实施、待 Admin 验收：缓存和静态路由兼容 | 新发现 |
| REV09 | P2 | 资料字段省略/null/空串与持久化、版本推进一致 | 待决定：Patch 清空合同；随后实施和重读验证 | 新发现 |
| REV10 | P1，子项 P2 | Admin 用户/角色版本、临时授权、治理和首页真实配对 | 待实施、待联调；临时授权/未实现治理能力/首页合同待决定 | N05 |
| REV11 | P1 | 菜单/按钮/字典删除与 move 必需客户端版本和 CAS | 待实施、待 Admin 验收；冲突不留成功副作用 | N07 |
| REV12 | P1 验收项 | 角色/成员引用写入与对象删除协调，无新提交悬空关联 | 待真实 MySQL 屏障验真，再落实锁顺序及锁内重验 | N14 |
| REV13 | P1，依多公司产品 | 同账号在不同公司的部门事实可独立表达 | 待决定、待迁移：主部门/多部门、合法回填和单写切换 | N08 |
| REV14 | P1 | 会话 CAS 提交后的清理/审计不耗尽响应预算 | 待实施、待真实 Gateway 故障验收：有界可靠投递及丢响应恢复 | 新发现 |
| REV15 | P1 | Java 质量/类级覆盖率、域内架构和风险契约进入真实门禁 | 待实施，不以测试数量代替覆盖率 | N10 |
| REV16 | P2 | BD 支持 Robot 的 Sa-Token/合同/软删和新鲜收据 | 待包适配；保留原始失败、生成物快照及 J 门 pending | 新细化 |
| REV17 | P1 | 真实 CI、线性提交父数检查和 main/dev 保护 | 环境受阻：verify 因账号计费锁定未启动；父数门及保护待落实 | N06 |
| REV18 | P1 | 支持期与联合兼容升级、SBOM、漏洞/许可证和固定工具链 | 待实施：目标版本由执行时官方矩阵及回归确定 | N09 |
| REV19 | P2 | 身份读成本、epoch、树和列表优化有量化依据 | 待测量：数据规模、冷暖负载、SQL/延迟/撤销目标 | N11/N13 |
| REV20 | P2 | Outbox 恢复、毒事件、索引、分页与完成历史留存 | 待测量、待实施；pending 和已重放事件不能误归档 | N11 |
| REV21 | P2 | DB/锁/网络总预算与重试时钟一致，故障可回收 | 待实施、待故障验收：统一时间合同和超时预算 | 新细化 |
| REV22 | P1，生产前 | 当前制品运行、HA、SLO、发布、告警与备份恢复 | 待环境验收：目标拓扑、容量、RTO/RPO 和责任 | N12 |
| REV23 | P2，新增服务前 | 最小服务身份、凭据供给受众和必需主体核验装配 | 待扩展准入落实；当前 Auth/System 已有主体核验 | N13 |
| REV24 | P2 | 脱敏诊断分类和业务结果指标可定位 HTTP200 失败 | 待实施、待告警验收，不恢复原始秘密到日志 | 新发现 |

已复现的 P2 功能问题可以进入早期小批次；风险优先级不表示可以无限后移。REV01 的平台身份与 REV02 的生产边界仍是对应业务开放条件。REV12、容量和 HA 项保留待验性质，不将潜在竞争、静态查询数或未测延迟写成已发生事故。

## 实施前必须明确的决定

- 平台目录管理员的永久事实与公司超管的区别；普通运营可编辑哪些展示字段。
- 临时授权采用按人授现有角色，还是对角色临时附加资源；不可由实现者自行混合两种模型。
- 无首页角色的合法落地策略，以及治理接口实际可保存的能力范围。
- 资料字段省略、null、空串的含义；历史超长密码的兼容或重置方案。
- 多公司成员主部门/多部门模型、迁移回填依据和回滚窗口。
- 目标容量、入口/通知/撤销时延、恢复 RTO/RPO、允许故障范围及部署责任。

同一项可以先完成不依赖决定的准备工作，但产品决定、真实环境证据和代码实现分开登记。后续将状态改为完成时，须关联修复提交和实际行为、鉴权/事务负例、适用质量门及对应运行验收。

## 2026-10-09 旧计划与保留依据（N01–N14）

以下原计划基线为 `5667a84`，保留已有源码依据和验收细节。后续发现、当前状态与实施顺序以上述 REV 台账和执行计划为准，尤其不能沿用历史 CI 账号状态解释当前失败。

## 优先级与证据口径

- **P0**：在允许普通管理员维护全局资源或开放生产业务前完成。
- **P1**：下一轮交付重点；包含目标环境、前后端或兼容性验收。
- **P2**：根据容量、业务需求和运行目标安排。
- **源码确认**：当前入口、字段、查询或缺失的检查可直接定位；不等于已经在真实环境利用或压测。
- **本地行为确认**：使用当前实际编译类及依赖的独立探针观察到行为；不宣称生产中已经泄漏或发生故障。
- **环境待验**：需要真实拓扑或产品约束，不凭源码认定必然故障。

## 待办总览

| 编号 | 优先级 | 改进项 | 证据状态 | 完成标志 |
| --- | --- | --- | --- | --- |
| N01 | P0 | 全局资源目录写入与权限编码治理 | 源码数据流确认，尚未真实 HTTP 复现 | 普通目录编辑者不能改变授权语义，跨公司绑定受保护 |
| N02 | P0 | 生产配置、凭据与初始化护栏 | 源码确认 | 缺失或开发占位配置在生产启动时拒绝，合规启动通过 |
| N03 | P1 | 密码字节边界和敏感模型输出 | 本地实际依赖/编译类确认 | Unicode 边界、历史密码策略及输出负例通过 |
| N04 | P1 | 认证滥用防护与失败审计 | 源码确认 | 多实例限流、恢复、故障语义及脱敏审计通过 |
| N05 | P1 | Robot Admin 真实接口配对 | 配套工作区调用源码确认 | 用户/角色命令和旧页面冲突交互真实通过 |
| N06 | P1 | 托管 CI 和分支保护真正生效 | 10-09 历史：首次 job 未启动、保护未配置；当前见 REV17 | 目标提交真实运行通过，并验证失败不能合入 |
| N07 | P1 | 菜单、按钮、字典的删除版本 | 源码确认 | 旧快照删除冲突，无成功写入副作用 |
| N08 | P1 | 公司成员的部门归属模型 | 源码与 DDL 确认 | 同账号在不同公司能表达不同部门，回填可对账 |
| N09 | P1 | 依赖支持期与供应链门禁 | 本地 BOM + 官方维护说明 | 兼容升级、SBOM、依赖扫描和运行回归有证据 |
| N10 | P1/P2 | 扩展机器契约和实际数据库覆盖 | 当前明确子集 | 新业务端点与数据合同进入同一验证入口 |
| N11 | P2 | Outbox 留存、查询与恢复容量 | 查询形态确认，容量未压测 | 归档可对账、待发不清理、恢复达到已定时延目标 |
| N12 | P1（生产前） | HA、观测、部署及恢复验收 | 目标环境待验 | 撤销、告警、恢复和发布行为在目标环境被验证 |
| N13 | P2 | 请求读成本和内部业务边界 | 重复查询形态确认，收益待测 | 安全语义不退化，查询次数/延迟有对比 |
| N14 | P1 | 角色/成员引用的跨聚合并发一致性 | 源码检查顺序确认，并发结果待真实 MySQL 复现 | 引用写入与对象删除互斥，无新提交悬空绑定 |

## N01 全局资源目录写入与权限编码治理

**事实。** [MenuController](../robot-services/robot-system/src/main/java/com/robot/system/menu/controller/MenuController.java) 的更新入口要求 `sys:menu:update`；[MenuButtonController](../robot-services/robot-system/src/main/java/com/robot/system/menu/controller/MenuButtonController.java) 的更新要求 `sys:menu:save`。[MenuService](../robot-services/robot-system/src/main/java/com/robot/system/menu/service/MenuService.java) 在 `updateMenu` / `updateButton` 中接受新的 `permission`，`apply` 直接替换 `permCode`，没有服务侧永久平台管理员判断。页面权限码只检查格式；按钮另有按钮目录内的唯一性检查。

[RobotStpInterface](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/RobotStpInterface.java) 根据已绑定资源的**当前**权限编码计算业务权限；[PermissionGrantPolicy](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/PermissionGrantPolicy.java) 的授予上界针对资源 ID。因此，普通角色若获得目录编辑权限且绑定对应资源，改变资源的权限编码可能改变既有绑定的授权含义，绕过角色重绑的限制。全局资源还被不同公司的角色共同引用。按钮唯一性会限制部分目标编码，不能据此排除页面或尚未登记按钮编码的路径。

**执行建议。** 先定义平台管理员的权威身份与委托边界，把全局目录写入、权限编码/状态等授权语义变更收敛为永久平台管理员能力，在 Service 中校验，不能只依赖页面隐藏或 Controller 注解。现有永久角色按当前公司解析，不能默认把公司管理员等同于平台目录属主；过渡方案复用现有永久超管时也需明确其平台操作范围。若后续需要委托运营修改名称、图标等展示字段，拆分专用 DTO 与用例，不允许该入口修改权限编码、资源归属或授权启停。已绑定资源的权限编码不经普通 CRUD 改写；确需更名时走受控迁移、影响预览、绑定核对和成功审计。

**验收。** 普通目录编辑者、仅临时角色、跨公司操作者的授权语义修改均拒绝，资源、绑定、审计、epoch、Outbox 无变化。永久平台管理员合法操作仍保持整事务；审计或 Outbox 失败则全量回滚。同时覆盖目录新增、更新、删除、移动以及按钮入口，不能只修单个 PUT。

## N02 生产配置、凭据与初始化护栏

**事实。** 三服务默认 profile 为 `local`，内部服务密钥有开发回退；System 的 [application.yml](../robot-services/robot-system/src/main/resources/application.yml) 带开发数据源账号/密码及非 TLS JDBC 参数。[RobotSecurityProperties](../robot-common/robot-common-security/src/main/java/com/robot/common/security/config/RobotSecurityProperties.java) 和 Feign 拦截器也有开发密钥回退。`application-nacos.yml` 的配置导入为 `optional:`。[V2 开发种子](../robot-services/robot-system/src/main/resources/db/migration/V2__seed_data.sql) 自动建立开发管理员，当前没有首登强制改密。文档提醒尚未转成统一的启动校验。

**执行建议。** 先定义明确的生产运行模式，再统一校验内部凭据、环境标识、数据源权限、Redis 配置和管理端点访问方案。拒绝空值和已知开发占位值；生产 JDBC、运行账号和迁移账号独立配置。管理指标与 API 文档采用独立网络边界或认证，不假设路由 GlobalFilter 覆盖 Actuator。生产依赖远程配置时，验证必需配置加载失败会阻止开放业务。

初始化采用显式 bootstrap 状态和受控凭据交付。保持已发布 V2 的 checksum，不通过修改历史 SQL 或增加一个无效“关闭种子”开关解决已有迁移中的固定数据；为新环境、已有管理员以及历史种子分别设计禁用/改密/撤销流程。处理生产模式之前先核对既有管理员身份，不能直接删除真实业务账号。

**验收。** 不合规生产配置启动失败且日志不含秘密；合规配置正常启动；local 使用仍可验证。业务流量开放时开发管理员凭据不可用。验证后端直接访问、网关和反向代理后的管理端点边界。

## N03 密码字节边界和敏感模型输出

**事实。** [UserWriteValidation](../robot-services/robot-system/src/main/java/com/robot/system/user/service/UserWriteValidation.java) 与改密/重置 DTO 约束字符长度，未统一检查 UTF-8 字节。实际 `spring-security-crypto 6.2.8` 的 BCrypt 本地内存探针确认：两个字符长度合规、前 72 字节相同但后缀不同的密码仍能交叉匹配。应明确字节语义，[OWASP 密码存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) 也说明 BCrypt 输入上限。

实际编译类探针还确认 `PasswordChangeRequest`、`UserSaveRequest`、`RemoteUserDTO` 的 `toString()` 可包含密码或摘要；[SysUser](../robot-services/robot-system/src/main/java/com/robot/system/api/entity/SysUser.java) 同样使用未排除摘要字段的 Lombok 输出。这是可触发的暴露面，未证明当前默认日志已经泄漏。

**执行建议。** 统一密码策略，在所有新密码写入入口同时校验字符和 UTF-8 字节，不自动截断。为登录、旧密码验证及历史超长密码设计兼容窗口或受控重置，不能直接改变存量凭据解释。长期如升级哈希方案，应采用显式算法版本和登录后迁移，保留旧摘要验证与撤销语义。敏感模型补 `@ToString.Exclude` 或受控输出，保持必要的 JSON 输入和内部认证字段。

**验收。** ASCII、中文、emoji 的 71/72/73 字节边界；新建、编辑、本人改密、管理员重置、登录和历史账号策略。拒绝写命令不推进业务/安全版本、审计或 Outbox。实际模型与嵌套集合的字符串输出不含虚构秘密，正常 JSON 合同及内部认证仍通过。

## N04 认证滥用防护与失败审计

**事实。** [AuthService.login](../robot-services/robot-auth/src/main/java/com/robot/auth/authn/service/AuthService.java) 未实现应用级失败计数/限流；现有登录记录主要由成功会话签发产生。Sentinel 配置引用外部规则 dataId，不能证明认证入口已有有效规则。匿名 `/auth/login-companies` 仍按用户名返回公司上下文，是现有公开契约。

**执行建议。** 在 Auth 侧实现有界的账号/IP/设备等分层限制、短期失败窗口、恢复策略、失败审计和低基数指标；不能只靠源 IP，也不能永久锁住被恶意提交的账号。保留认证依赖故障和凭据错误的区别。公司发现是否改为密码验证后的 ticket 行为，须与 Admin 一起确定兼容期。规则依据 [OWASP 认证指南](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html) 设计，再按实际用户规模校准。

**验收。** 两 Auth 实例共享门控；切换 IP 不能绕过账号窗口；合法用户能恢复；未知账号、错误密码和公司发现行为有统一合同。限流存储故障、来源头伪造、计数容量及告警均有检查。不记录密码、token、原始请求或高基数用户名标签。

## N05 Robot Admin 真实接口配对

**事实。** 工作区 `Robot_Admin/src/api/user-manage.ts` 的删除只传 ID，状态请求只传 `{status}`，重置请求只传 `{password}`；`src/api/role-manage.ts` 删除也只传 ID。后端当前 [用户合同](../contracts/system/users.json) / [角色合同](../contracts/system/roles.json) 已要求对应版本。这里是工作区源码对比，尚未执行真实 Admin 页面联调。

**执行建议。** 用户删除/状态携带成员版本，密码重置携带账号和成员版本，角色删除携带角色版本；列表/详情类型及缓存保留这些字段。冲突时提示并重新读取，不自动用最新版本重试覆盖其他人的修改。批量命令逐项保留版本和结果。同步 mock 的范围和冲突语义，禁止用 mock 成功推导 remote 已可用。

**验收。** 两个浏览器打开同一详情，第一人更新后第二人启停/删除/重置收到冲突并刷新；有效版本成功；跨公司拒绝，旧会话撤销。登录公司激活、刷新、注销、菜单/按钮和授权编辑同时走真实 Gateway。

## N06 托管 CI 和分支保护真正生效

**事实。** workflow 和本地同入口已存在。2026-10-09 首轮 GitHub 回执显示 job 在执行步骤前被账号状态限制，这是历史记录。2026-10-14 原审查读取到基线 SHA 的三个托管 run 均为 failure，当时未取得 job/annotation；本次文档复核进一步读取到 verify job 的 [实际失败注释](https://api.github.com/repos/ChenyCHENYU/Robot_Cloud/check-runs/114446067171/annotations)，确认因账号计费锁定未启动、steps 为空。该原因来自当前基线的新读取证据；其他 run 的诊断和分支保护状态不能据此推导，托管验证仍未通过。

**执行建议。** 由仓库账号持有人处理计费锁定后，对目标提交重跑并核对实际步骤；长期使用内网 CI 时接入相同 `scripts/verify.sh --mysql-upgrade`，保留 XML、制品、迁移和源码摘要。使用仓库真实产生的 check 配置 `main` / `dev` 保护，审查和检查规则适配实际维护人数。临时 runner 隔离、迁移测试仅使用隔离库。新增提交父数检查见 REV17，不把 message lint 当作线性历史门。

**验收。** JDK17、MySQL8.0 的完整 job 成功且 artifact 可下载。构造失败行为/机器契约变更后，PR 不能合入；修复后可恢复。GitHub 与 Gitee 的同名主干 SHA 一致；不把 GitHub Actions 文件存在等同于 Gitee 已执行 CI。见 [Git 维护流程](git-workflow.md)。

## N07 菜单、按钮、字典的删除版本

**事实。** 用户/角色写命令已补客户端版本，但 [MenuService.deleteMenu/deleteButton](../robot-services/robot-system/src/main/java/com/robot/system/menu/service/MenuService.java) 和 [DictService.delete](../robot-services/robot-system/src/main/java/com/robot/system/dict/service/DictService.java) 的删除仍只取 ID。树锁或服务器现读版本可保证串行执行，不能表达用户依据旧页面发起的删除意图；菜单 move 版本目前也可选。

**执行建议。** 为资源删除及变更授权语义的命令补客户端 `expectedRevision`，DTO、Controller、Service CAS 和 Admin 同步。全局树控制行继续保护拓扑，与客户端资源版本分别承担职责。按既有约束保留资源引用保护，角色绑定、审计、epoch 和 Outbox 仍同事务。

**验收。** 缺失、负数、旧版本、两人竞争删除；一个成功，其余冲突。失败不解绑、不记成功审计、不推进 epoch。菜单树循环、子节点、按钮和字典引用保护回归通过。

## N08 公司成员的部门归属模型

**事实。** [V1 DDL](../robot-services/robot-system/src/main/resources/db/migration/V1__init_schema.sql) 的 `department_id` 在全局 `sys_user`，`sys_user_company` 无部门字段。[DataScopeService](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/DataScopeService.java) 按账号部门判断当前公司范围，归属不匹配会退到本人。一个账号在 A/B 公司各属于不同部门的模型无法表达；这属于产品/数据模型缺口，现有失败关闭不能直接描述为跨公司泄漏。

**执行建议。** 以公司成员为部门事实源，明确是否允许多部门和主部门，再追加 Expand 迁移。回填仅复制经当前公司归属核验的部门；不把 A 公司部门复制到所有成员。双读对账期间仍保持一个权威写入方，随后切换身份供给、数据范围、用户 VO/命令及 Admin，最后评估退役旧字段。

**验收。** 同账号的 A/B 公司部门互不影响；有效范围、非法/停用部门、父链异常及停用成员负例；账号资料与公司成员编辑版本独立。新增及升级库都通过，回填冲突有可审查报告和恢复步骤。

## N09 依赖支持期与供应链门禁

**事实。** 当前 BOM 为 Boot3.2.12 / Cloud2023.0.3 / Alibaba2023.0.3.3。Spring 官方已说明 [Boot3.2.12 是 3.2.x 最后一个开源支持版本](https://spring.io/blog/2024/11/21/spring-boot-3-2-12-available-now/)，[Cloud 官方兼容与支持说明](https://spring.io/projects/spring-cloud/) 也把 2023.0.x 标为结束支持。即使 Alibaba 对应分支继续维护，也不自动延长上游 Boot/Cloud 的 OSS 支持；分支映射不等于支持承诺。

**执行建议。** 独立升级分支先冻结依赖树、运行行为和制品，联合评估 Boot/Cloud/Alibaba、Redisson Spring Data 适配、MyBatis、Sa-Token、文档和 Flyway。以 [Alibaba 官方分支映射](https://github.com/alibaba/spring-cloud-alibaba)、执行时的上游支持表和正式 BOM 的交集确定候选，不能只按分支名或版本已发布判断支持状态。本文件不批准具体目标版本；落地时重新确认支持期、实际 GA/BOM 和安全通告，不能只替换 Boot 父版本。

新增 SBOM、依赖安全扫描与 Maven 工具链/Wrapper 固定，扫描异常和临时豁免带理由、责任人及期限；扫描发现具体漏洞后按真实依赖可达性处理，不凭旧版本断言本项目已被利用。

**验收。** JDK17 编译/运行、整个入口、已有库升级、最终 Mapper 装配、序列化快照、刷新族原子性、双实例缓存故障和真实 Nacos 路由均回归。支持期与兼容性变化同步技术文档。

## N10 扩展机器契约和实际数据库覆盖

当前机器 HTTP 合同集中用户/角色，数据库合同为六表子集。下一步覆盖认证、菜单/按钮、字典、部门、本人账号和治理接口，记录方法/路径/权限、版本来源、输入输出及错误语义。区别全局资源、当前公司资源和内部服务供给。

数据合同逐步覆盖实际索引、软删除、关联和治理字段；关键事务竞争及 MySQL 方言行为进入真实隔离 MySQL，H2 继续承担快速回归。新合同必须被 `scripts/verify.sh` 实际调用，不能只增加文件。前后端共同校验序列化和错误，API 文档生成不能替代行为合同。

## N11 Outbox 留存、查询与恢复容量

**事实。** [PermissionStateStore](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/PermissionStateStore.java) 每轮最多取100条、治理查询按时间排序；[CacheEventDispatcher](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/CacheEventDispatcher.java) 串行处理，多节点可能扫描相同事件。现有索引主要面向 pending，已完成记录没有自动归档/清理。当前没有大积压的执行计划和吞吐证据，不能据此断言现网已经慢。

**执行建议。** 先约定通知到达时延和保留期，在隔离库构造历史完成记录及长断网积压，保存 `EXPLAIN ANALYZE`、锁等待与恢复曲线，再决定新增索引、领取租约或有界并发。数据库持锁阶段不做 Redis 调用，继续保留 delivery revision 围栏。永久非法事件设计可审查的隔离状态，与暂时依赖故障分开；不无限吞异常或静默丢弃。

归档只处理到期已完成记录，分批可恢复并与重放审计对账；pending 和重放中的记录不得按 created_at 直接清理。业务成功审计与登录审计分别确定保留、访问和删除政策。

同批评估角色成员、临时授权历史和权限审计的分页：[RoleQueryService](../robot-services/robot-system/src/main/java/com/robot/system/role/service/RoleQueryService.java) 目前加载公司成员/角色绑定后拼完整集合，审计查询只返回最近有限记录。补公司约束的 join/exists、时间/对象过滤和游标，避免用内存分页包装无限历史。

**验收。** 大历史表的治理查询和 pending 扫描在目标预算内，停网后的积压按目标恢复；重复消费、迟到确认、同计划竞争、人工重放、重启和旧缓存回填全部继续通过。并发归档/重放不丢记录，归档恢复可核对。

## N12 HA、观测、部署及恢复验收

已有低基数 Outbox 指标；下一步接实际看板和告警，区分积压、最老年龄、快照过期、数据库失败、重试和发送时延。指标与阈值需要接收方及恢复步骤，不能只保留名称。补身份校验延迟、登录/刷新失败、连接池和 JVM 指标；标签不带账号/事件/完整 key。

根据实际部署方式验证 readiness/liveness、优雅下线、迁移单执行方、限制访问的管理端点、备份恢复和 DDL 部分失败。采集系统时间和时区：当前一些业务与重试使用 LocalDateTime，应规定统一时间合同并验证节点时钟偏差。

逐设备族注销目前主要持久在 Redis，账号/成员安全版本不等于逐族注销事实。实际主从切换、旧会话快照恢复、丢写及 ACK 丢失仍待验；依据退出登录的持久性目标决定是否增加数据库族撤销或安全世代。验收须确保已注销族不恢复，其他合法设备不被误撤销。单主 Redis 的缓存通知成功不能替代该证据。

## N13 请求读成本和内部业务边界

当前请求主体核验与权限读取会重复访问账号、成员、公司、租户及数据库 epoch，这是安全复验的成本。[SystemSessionSubjectVerifier](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/SystemSessionSubjectVerifier.java)、[EffectiveRoleResolver](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/EffectiveRoleResolver.java) 和 [RobotStpInterface](../robot-services/robot-system/src/main/java/com/robot/system/permission/service/RobotStpInterface.java) 可作为测量入口。

先记录真实 SQL 次数、p95/p99 和热点，再评估请求内复用已核验的主体事实或合并只读查询；事务命令仍重新检查关键版本/归属。不能简单缓存“超管=true”或延长 epoch 缓存来换吞吐，撤权、临时生效边界及跨公司负例必须继续成立。

权限 epoch 当前是全局单行，各公司的授权写入会争用同一控制行并切换所有公司的权限缓存命名空间。先用多公司真实写入和回源测量判断是否成为瓶颈，再评估全局资源版本与公司授权版本的组合；必须保留全局目录变更失效和撤权语义。部门树构建也可预建 parent→children 索引，并明确深度、循环与异常数据处理，避免每层重扫完整集合。

业务包可补跨包循环与允许依赖的架构检查，逐步把含糊的 `*` 导入、多用例 Service 和裸 SQL 职责收敛。每次重构只围绕实际变化保持事务边界，不再按行数拆 Bean。新增独立业务后按 [架构拆分准入](architecture.md#每次拆分的准入条件) 定数据属主、契约、受众、迁移和运行方案。扩大服务数量前，普通业务主体供给与含凭据摘要的认证供给要具有不同调用方授权。

## N14 角色/成员引用的跨聚合并发一致性

**事实。** [RoleCommandService.delete](../robot-services/robot-system/src/main/java/com/robot/system/role/service/RoleCommandService.java) 普通查询确认没有绑定后删除角色；[UserService](../robot-services/robot-system/src/main/java/com/robot/system/user/service/UserService.java) 在普通查询校验角色后插入成员绑定。[TemporaryAuthorizationCommandService](../robot-services/robot-system/src/main/java/com/robot/system/role/service/TemporaryAuthorizationCommandService.java) 也先查询角色和成员状态，再写临时关联。双方没有统一的被引用事实行锁；活跃唯一索引不等于引用有效性约束。

可达竞争窗口是：授予事务先读取有效角色，删除事务确认当时无绑定并提交，授予再写入关联。成员移出与临时授权同理。当前 Resolver 会过滤失效角色/成员，所以首先是聚合完整性和审计语义问题，不能直接宣称这条竞争已产生越权；尚需真实 MySQL 屏障用例确认具体隔离级别下的结果。

**执行建议。** 定义统一的对象锁和多对象锁顺序，让关联写入与删除/停用在同一事实行协调，并在锁内重新检查有效状态与授予上界。多个角色按稳定顺序加锁，保留业务、关联、成功审计、epoch、Outbox 的同事务。仅在事务尾推进全局 epoch 不足以代替前面的引用协调。

**验收。** 隔离真实 MySQL 用屏障复现角色删除 vs 成员绑定、角色删除 vs 临时授权、成员移出 vs 临时授权，并检查最终没有新提交的悬空活跃关联。拒绝或回滚事务不留下成功审计和事件；允许按序完成的先授权后移出场景保留两笔成功记录，最终授权已撤销。死锁重试与 HTTP 冲突语义明确，不用同一个 revision 的启停竞争测试替代跨聚合验证。

## 旧计划批次映射

下表保留旧 N 编号的组织关系；当前执行使用 [批次 0–7](review-execution-plan.md#2-批次安排)，其中新增的菜单、路由、请求字节、刷新时序、DB 预算和诊断项不能遗漏。

| 批次 | 范围 | 建议独立提交 | 出口 |
| --- | --- | --- | --- |
| A 授权与生产入口 | N01、N02 | 目录服务侧策略；生产 guard/bootstrap，各自提交 | 策略负例、生产/本地启动、整体回滚 |
| B 认证边界 | N03、N04 | 密码策略；敏感输出；认证门控/审计，各自提交 | 字节/历史策略、多实例、日志和故障行为 |
| C 交付闭环 | N05、N06、N07、N10、N14 | Admin/合同配对；CI/保护；资源CAS；引用竞争 | 真实页面、托管报告、旧快照与跨聚合竞争 |
| D 成员模型 | N08 | Expand迁移；回填/对账；单写切换 | 两公司部门、升级保全、恢复 |
| E 依赖维护 | N09 | 工具链/扫描；兼容升级 | 目标版本联合运行及恢复回归 |
| F 运行与演进 | N11、N12、N13 | 容量/留存；告警/HA；测量后重构 | 目标SLO、恢复、对账及制品绑定 |

每个批次开始先核对最新 `dev` 和未提交修改。实际功能从 `dev` 切短期分支，交付变更、行为与契约测试、迁移/配置影响、原始回执和未验项，通过审查后合回。未经验证的产品能力不标为完成；修复一项不顺带改写已发布 SQL 或拆散授权聚合事务。
