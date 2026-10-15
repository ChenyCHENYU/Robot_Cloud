# 部署、验证与恢复

当前采用 Java17 / Boot4.1.1，版本事实见 [技术选择](tech-selection.md)。本次是 Cloud/Admin 配对升级，追加 V11–V13 并切换会话 schema2，不能与旧应用混跑；当前验收状态见 [改进台账](improvement-roadmap.md)。

## 网络与配置

Gateway 默认8080对接反向代理TLS；Auth9201、System9202、MySQL/Redis仅允许应用内网访问。Gateway拒绝`/inner/**`并清洗外部全部`X-Inner-*`，服务仍验证主体、族及权限。Nacos配置/发现与Sentinel按profile显式启用，配置文件存在不代表实际可用。

| 配置 | 含义与约束 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | local关闭Nacos/Sentinel；prod/production/pre或显式生产环境信号启用护栏，不能混入local |
| `ROBOT_ENV` | 所有实例一致、格式`[a-z0-9]+(?:-[a-z0-9]+)*`；生产非local，冷恢复安全世代须使用新值 |
| `ROBOT_INNER_SECRET` | Auth/System可信调用共享密钥；生产至少32字符、非开发占位，从密钥系统注入 |
| `ROBOT_MANAGEMENT_SECRET` | 独立管理密钥；生产访问非health Actuator用`X-Robot-Management-Token`，不复用登录token |
| `ROBOT_BOOTSTRAP_COMPLETE` | 显式交接完成声明；System还检查真实种子账号状态/凭据，声明不能替代实际交接 |
| `MYSQL_JDBC_URL` | 生产明确单一`sslMode=VERIFY_IDENTITY`及可信CA/主机；重复/伪装TLS参数拒绝。保留`connectTimeout=3000&socketTimeout=8000` |
| `MYSQL_USERNAME/PASSWORD` | 专用DML账号，生产禁止root；密码至少32字符，禁止提交或输出 |
| `MYSQL_MIGRATION_USERNAME/PASSWORD` | 与运行账号分离的DDL身份；由唯一迁移执行方持有。应用不迁移时显式关闭Flyway |
| `REDIS_HOST/PORT/DATABASE/PASSWORD` | Auth/System同存储；生产TLS、至少32字符凭据及ACL/网络限制 |
| `REDIS_SSL` / `ROBOT_REDIS_ISOLATED` | 生产均true；声明须对应真实独立Redis实例/数据库，自定义key环境段不隔离Sa-Token自身key |
| `SPRING_DATA_REDIS_USERNAME` | 可选ACL用户名；空/空白用户名和密码归一为null，不发送空AUTH |
| `ROBOT_AUTH_URI/ROBOT_SYSTEM_URI` | local网关直连；nacos按服务名发现 |
| `ROBOT_SYSTEM_URL` | local Auth→System Feign目标 |
| `NACOS_SERVER_ADDR/NACOS_NAMESPACE` | 独立控制面/环境；启用远端配置的生产必须用必需`nacos:`导入，拒绝`optional:nacos:` |
| `ROBOT_CORS_ORIGINS` | 部署为Admin准确origin，限制浏览器来源 |
| `DEBUG/LOGGING_LEVEL_*` | 认证环境关闭载荷调试；应用脱敏规则不能控制所有第三方DEBUG输出 |

生产文档路径（含springdoc新路径）返回404，Gateway的`management.endpoint.gateway.access=none`，禁止暴露`gateway`或`*`。管理网络仍需限制；启动护栏不替代证书、DB权限、Redis隔离和控制面的实际验收。共享内部密钥目前是一个信任域，新增服务须审查内部接口受众，不能据此宣称已有逐服务mTLS身份。

## 本地启动和首次平台交接

设置真实JDK17，执行固定Wrapper；Enforcer拒绝其他主版本或不匹配Maven。

```bash
export JAVA_HOME=/path/to/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -version
bash scripts/verify.sh
```

local仍需独立MySQL/Redis。新库使用utf8mb4/utf8mb4_general_ci；非空无Flyway历史库先对账，默认不baseline、不repair。按System→Auth→Gateway启动同次制品：

```bash
java -jar robot-services/robot-system/target/robot-system-1.0.0-SNAPSHOT.jar
java -jar robot-services/robot-auth/target/robot-auth-1.0.0-SNAPSHOT.jar
java -jar robot-gateway/target/robot-gateway-1.0.0-SNAPSHOT.jar
```

`admin/admin123`仅用于隔离local开发。全局目录写入需要`sys_user.platform_admin_flag=1`，公司SUPER_ADMIN不等于平台管理员。可信启动交接显式注入：

- `ROBOT_PLATFORM_ADMIN_BOOTSTRAP=true`。
- `ROBOT_PLATFORM_ADMIN_USER_ID`为已有、启用且具备合法公司身份的账号ID。
- 可选`ROBOT_PLATFORM_ADMIN_BOOTSTRAP_PASSWORD`为受控新密码，字符/严格UTF8均合法，不可用admin123。不要放在命令参数、Git或日志中。

首次交接在事务和协调锁内只授予一个平台身份，安全版本、成功审计、epoch/Outbox同时提交；已交接的同账号重复启动不会重复改密，不能用启动配置追加另一个管理员。成功后移除启用开关和交接密码。生产护栏检查开发凭据是否仍可用，不能把历史种子删除当作正常交接。平台管理员后续治理须另行设计受控用例，普通User DTO不接受该标志。

## 统一验证与升级

```bash
export ROBOT_VERIFY_MYSQL_HOST=127.0.0.1
export ROBOT_VERIFY_MYSQL_PORT="<isolated-port>"
export ROBOT_VERIFY_MYSQL_USER="<isolated-test-user>"
# 密码从受控环境注入
bash scripts/verify.sh --mysql-upgrade
```

默认入口执行Python负例、Java17 `clean verify -Probot-quality`、实际Checkstyle/PMD/SpotBugs/Spotless和逐CLASS JaCoCo、CycloneDX→完整OSV/许可证政策、Mapper装配和制品哈希。网络失败、未知许可、未修漏洞、陈旧/缺失/跳过报告均阻断，见 [质量规则](../quality/README.md)及 [供应链](../quality/supply-chain.md)。测试数量以当前XML为准；没有执行的检查不能标绿。

MySQL入口只创建`robot_verify_<随机ID>`并清理自己创建的库，拒绝非loopback或指定已有业务库。固定golden核对20表全部列/索引属性；新建、V10升级、V5历史升级及真实屏障竞争分别验证。已发布V1–V10不改写；V11新增平台事实/公司成员主部门/登录事件幂等，V12新增通知隔离/归档与查询索引，V13补齐权限治理导航并验证既有属主、冲突和接口权限不扩大。

上线前在备份副本完整演练，暂停业务写和认证、排空请求，唯一迁移方执行迁移，统一切换Cloud/Admin版本和安全世代，再做实际业务冒烟。不能滚动混跑旧会话格式；所有客户端重新登录。主部门由成员事实读取，无双写全局部门；回退前必须核对多公司差异，不承诺旧镜像直接兼容新数据。MySQL DDL可能隐式提交，失败需核对Flyway状态和已执行DDL，不能假定整段自动回滚或直接repair。

## 生产授权时间的 UTC 交接

新临时授权以 UTC 解释无偏移时间，数据库连接会话须实际为 `+00:00`，授权 Clock 使用 UTC。`SystemProductionBootstrapGuard` 在生产护栏中检查持久合同：`sys_cache_epoch` 必须有字节精确的 `scope = authorization-time-utc-v1` 且 `version >= 1`。这是临时授权时间解释的确认 marker，不是权限 epoch、字典版本或配置布尔开关；普通 HTTP 不维护它。

没有合格 marker 时，护栏统计 `sys_temp_authorization` 中全部 `delete_flag=1 AND status_flag=1` 记录，包含未来、当前和已到期的时间窗。只要仍有这类存量，或统计结果不明，就拒绝生产启动；不会按当前时间过滤、删除记录、自动改写时间或凭 JVM 默认时区确认。已存在合格 marker 后可在后续重启保留新的 UTC 授权，不会因存在正常授权再次要求空库。

只有上述存量为零才允许条件初始化 marker 为 1：插入语句再次检查没有该类记录，并在写后重新读取合格 marker。并发出现授权、写入失败、已有无效版本或排序等价但非精确的 scope，均不能被自动提升为已确认合同。上游应按 readiness 开放流量，不能在启动检查完成前对外接入业务。

有存量时，先停止新旧写入并保存备份，在副本核对历史写入节点的 JVM 时区、JDBC/数据库会话时区以及授权人原本指定的生效/到期时刻。记录每条窗口的时间解释和边界对账结果，必要调整须使用受控、可审计的版本化变更；不能将历史无偏移 DATETIME 直接当作 UTC 或统一减去固定小时数。完成授权和复核后才由受控交接维护精确 marker，再重新启动并验证生产护栏。Outbox 与历史审计时间也需按原写入合同独立对账，这个 marker 不证明全部历史时间已经转换。

只读预检可在有权限的副本执行；不要只用默认大小写不敏感排序比较来确认 marker：

```sql
SELECT scope, version FROM sys_cache_epoch
 WHERE CAST(scope AS BINARY) = CAST('authorization-time-utc-v1' AS BINARY)
   AND version >= 1;
SELECT COUNT(*) AS unconfirmed_legacy_grants FROM sys_temp_authorization
 WHERE delete_flag = 1 AND status_flag = 1;
```

上述运行合同描述源码行为；目标库历史时间对账、交接授权和生产启动结果仍需单独留证。

## 请求预算和可靠投递

| 层 | 当前预算/边界 |
| --- | --- |
| Gateway | 连接3秒，响应15秒；实际流式字节上限，有限并发聚合与取消释放 |
| Auth→System | connect1秒/read2秒，不自动重试认证写/一次性消费 |
| Auth Redis | 命令1秒；状态与可靠审计单次Lua同槽提交，队列满/错误类型在状态改变前拒绝 |
| System DB | Hikari取连接5秒，JDBC connect3秒/socket8秒，SQL/锁5秒；写事务声明期限，控制操作另有10秒预算 |
| DB时钟 | 连接会话UTC，Outbox到期/重排用DB相对时间；应用JSON时间按UTC合同解释并要求节点时钟同步 |

这些是分层上限，不能相加或等同于一个全链路取消期限；异常/锁占用/网络回收仍以故障验收为准。Auth可靠审计在Redis AOF/容量保障下重试，System仅在真实`Result.success`后幂等确认eventId；Feign降级不会假ACK。CAS之后异步清理有界，旧索引不能用于读取业务、刷新或注销后继族。认证失败按账号及实际远端连接地址共用窗口，不信任XFF；经过Gateway时Auth看到代理地址，共享来源窗口需按实际拓扑调整。主页面使用login→必要时ticket激活，不为每次输入密码触发BCrypt。

## Outbox治理和观测

数据库epoch是权限事实源。业务、审计、epoch与`sys_cache_outbox`同事务，调度器重试单调Redis通知；响应成功不等于通知已送达。默认5秒扫描、指数退避且上限300秒，毒事件进入QUARANTINED，不从待处理事实消失。

全局通知治理同时要求专属权限、当前公司永久超管及独立平台管理员。重放先预览、显式确认同planHash，锁内核对当前版本和操作者；只重排通知，不重执行业务。逻辑归档只处理完成且达到30–3650天保留期的历史，批量1–500，审计/版本同事务；pending、隔离和漂移事件不能归档。详见 [API合同](api-contract.md)。

Prometheus包含pending/due/隔离、最老年龄、快照年龄、重试/分类/投递耗时、在途与结果；业务结果另覆盖HTTP200中失败。双实例共享积压取max而非求和。部署须指定阈值、接收人和处理责任；低基数标签不包含账号、事件、token、key或任意异常消息。诊断仅受控分类及traceId/failureId。

## 恢复边界

会话族撤销和Auth审计队列依赖Redis持久状态，当前没有独立数据库逐族撤销账本。

- 确认完整的最新AOF恢复、进程重启，可在同namespace继续，须对账pending和幂等投递。
- 旧/未知快照、异步复制回退或可能丢写的灾难恢复，停流量并采用新的`ROBOT_ENV`与隔离存储安全世代，强制所有用户重新登录；先前已注销族不能沿旧namespace复活。
- 旧pending审计须受控提取、核对eventId后补投，切换namespace不会自动迁移它；禁止FLUSHDB或清空其他环境。
- 普通单设备注销/切换不影响其他合法设备；灾难恢复中的全量重新登录是明确的恢复策略，不能称为逐设备RPO0或无感HA。

`verify-auth-redis.sh`仅接受明确声明的自有loopback Redis、高位端口和随机verify namespace；full/prepare/verify分别验证原子性与AOF恢复。`scripts/runtime/`为双System/TCP断网、应用后丢ACK、迟到ACK、重放围栏及旧缓存回填探针，SQL fixture不冒充HTTP业务写。当前制品、样本规模、恢复结果与限制须随回执保存。目标生产拓扑、容量、RTO/RPO、Nacos/Sentinel和真正主从/cluster切换未定义时不推断通过。

历史验收 [2026-10-09](verification-2026-10-09.md)只保留当时范围。托管CI未启动或分支保护未验证须明确记录；本地门禁成功和远程SHA一致不等于已经部署上线。
