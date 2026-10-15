# 认证与生产运行合同

本文记录当前源码的安全规则和部署前提。正式测试已纳入仓库，但新增源码、测试文件和中间构建成功均不等同定版验收通过。最终 Maven、质量报告、Redis/MySQL、制品与真实启动的执行证据由定版验收记录维护；本文不替代目标环境验收。

## 密码与敏感输出

新密码的 DTO 和 Service 共用规则：6–32 个 Java UTF-16 单元、合法 Unicode、UTF-8 编码不超过 72 字节。输入不截断、不规范化；编辑时仅 null/空串表示不修改密码，空白字符串不能作为新密码。登录还独立拒绝不能严格编码为 UTF-8 的输入，避免 JSON 中未配对 surrogate 被编码替换后匹配合法问号密码。超过 BCrypt 字节边界的输入按登录失败处理，不使用截断兼容。

BCrypt 摘要不记录原密码长度，无法从存量摘要辨认旧密码是否曾被截断；升级不能补回丢失的后缀熵。已有未知来源凭据仍需按历史账号策略核对、轮换或受控重置，不能据新写校验宣称全部历史密码已整改。

未知、启用、停用账号在未证明凭据时返回相同的 `40101`。只有密码正确才可显示停用原因；未知账号使用进程私有随机 dummy 摘要完成 BCrypt 校验。依赖故障保留 `500`，不伪装成错误密码。密码、摘要、内部 secret、access/refresh 和票据从模型字符串输出中隐藏；内部认证 JSON 保留必要字段，仍须通过内部信任边界保护。

`POST /auth/login-companies` 现在要求 `username` 和 `password`，使用与登录相同的 BCrypt、Unicode、账号/来源窗口及可靠失败审计。凭据证明前不读取或返回公司、租户、成员上下文；未知账号、错误密码和未证明的停用账号均为 `40101`，完整凭据证明后才可返回停用原因。该入口仅发现当前有效公司，不签发 token 或 loginTicket；无有效公司返回空清单，下游故障返回依赖错误。仅提交 username 的旧客户端收到参数错误，Robot_Admin 必须随本次升级一起部署。

## 公开入口和资源边界

Gateway 在转发前按实际累计字节读取有界 body，Servlet 对 JSON 提供独立前置检查。Auth 上限 64 KiB，System 上限 10 MiB；未知 Content-Length 同样检查，超限返回 HTTP 413，处理器与认证 RPC 不执行。接收阶段最多 16 个并发请求，超量返回 HTTP 429；正常结束、失败和取消释放容量，拒绝和取消释放 Netty pooled buffer。

Auth 用户名最多 30、密码验证输入最多 1024、上下文 ID 最多 64 个字符；票据与 refresh 输入须为 64 个字符。字段校验发生在反序列化之后，不能替代 body 边界。边缘代理仍须配置连接、上传超时与总资源预算；真实 chunked、HTTP/2、慢连接、峰值内存和吞吐需要独立入口验收。

认证门控采用共享 Redis 的 UTC 分钟固定窗口，默认账号 10、来源 200、全局 1000 次；配置 `robot.auth.attempt.account-limit/source-limit/total-limit` 均限定为 1–10000。先消耗全局容量再创建身份计数键；每个计数首次创建时设置精确 2 分钟 TTL，后续请求不续期。用户名和来源仅以摘要入键，不进入指标标签。各实例时钟须同步，固定窗口边界允许相邻两分钟的突发，不等于滑动窗口。

用户名窗口用于限制查库；MySQL `utf8mb4_general_ci` 可能将不同 Unicode 拼写解析到同一账号，因此不能以原始用户名摘要证明账号预算。已查得账号还须在 BCrypt 前通过 stored userId 的独立窗口，使用同一账号阈值，无论密码是否正确、账号启用或停用。该窗口只计可信 ID，不重复消耗来源/全局；未知账号仍由预门限制，合法历史用户名不做字符集收紧。可信 ID 缺失、超出 `VARCHAR(64)` 字符合同、非法 Unicode 或控制字符按供给故障拒绝，不进入 BCrypt。相比原路径，每个已知账号尝试增加一次 Redis 原子计数和最多一个 2 分钟 TTL 键；总体键分配仍受先执行的全局窗口限制。

来源取 Servlet `getRemoteAddr()`，忽略客户端 X-Forwarded-For。当前没有可信代理链解析；反向代理部署可能把多个用户聚合到代理地址，来源阈值须按真实拓扑校准。登录和公司发现共用账号窗口；refresh 与票据激活使用来源/全局窗口。计数存储失败时拒绝认证并返回固定依赖错误。

实际执行凭据检查的失败登录产生状态 0 审计：已知账号保留 System 返回的可信 userId，未知账号为空；未证明凭据时用户名为有界摘要标识，公司为空。IP 超过 45 个字符置空，User-Agent 去控制字符后最多 255 个字符，不存密码、token 或原始请求。失败审计的容量/存储错误传播并计数，不静默吞掉；限流拒绝用固定指标汇总，不逐请求制造无限审计事件。

## 会话 schema v2 与可靠审计

自定义认证键使用 `robot:<env>:auth:{sessions}:v2:*`，其中 `{sessions}` 是字面 Redis hash tag。旧 schema 全部重新认证，不桥接旧 refresh，不批量清空共享 Redis，旧索引按原 TTL 退出。Sa-Token 原生键仍需独立 Redis 实例或 database 做环境隔离；自定义前缀不能替代该边界，Redis Cluster 仅有 database 0，需独立实例/集群。Sa-Token 会话使用 Jackson 3 精确允许 `LoginUser` 的反序列化策略；HTTP、Feign 与自定义 Redis JSON 保持 Jackson 2 合同。

新族创建、轮换 CAS 和注销与成功审计在同一 Lua 调用提交。族键、审计 payload hash 与 due zset 处于同一 `{sessions}` slot；Lua 在修改状态前检查容量和存储类型。容量满时拒绝命令并保留原族，竞争输家没有成功审计；成功事件不得提前到 CAS 前。

审计队列最多 10000 项，编码载荷每项最多 2048 个字符。pending hash/zset 没有 TTL，是明确的持久运行存储合同，不能按年龄静默删除。后台每轮最多 20 项；System 按 UUID eventId 幂等持久化、返回成功后才 ACK，fallback 的失败结果不能冒充 ACK。临时失败延后 5 秒重试，重启继续投递；确认丢失会再次投递同一 eventId，payload 与 due 删除原子。异常类型、毒事件和满队列需要告警与人工恢复，不得通过无记录删除恢复容量。

CAS 后不等待审计 HTTP。旧索引和 Sa-Token 清理由单线程、256 项有界队列处理；进程退出或容量不足时依靠索引精确 TTL 退出。残留 access 索引只用于定位候选族，注销的每次 CAS 读取均核对请求摘要等于当前族 access 摘要。旧轮换 token 不能撤销后继，清理延迟不能复活旧凭据；详见 [会话生命周期](auth-session-lifecycle.md)。

Auth RPC 默认 connect 1 秒/read 2 秒且不重试，Redis command 1 秒。单次超时不是完整端到端预算证明，Gateway deadline、网络丢响应和排队须真实测量。CAS 结果不明只尝试撤销自己的候选，不恢复旧 refresh；无法恢复的丢响应要求重新认证。

## 生产启动与管理访问

`prod/pre/production` profile、对应环境名前缀或 `robot.production.enabled=true` 触发生产护栏。生产不能同时启用 local；`robot.env` 必须符合小写 `[a-z0-9]+(?:-[a-z0-9]+)*` 且不能为 local，不通过字符清洗产生重名环境。

Auth/System 需要至少 32 字符且非已知开发占位的内部、管理与 Redis secret，Redis TLS，以及显式 `robot.production.redis-isolated=true`。Gateway 需要独立管理 secret；三服务要求受控 bootstrap 已完成。System 的 JDBC URL 要求唯一、解码后精确的 `sslMode=VERIFY_IDENTITY`，非 root 运行账号及独立 Flyway 迁移账号/凭据。Nacos 配置启用时导入必须非 optional，必要配置获取失败不能继续开放业务。

部署变量包括 `ROBOT_ENV`、`ROBOT_INNER_SECRET`、`ROBOT_MANAGEMENT_SECRET`、`REDIS_SSL`、`ROBOT_REDIS_ISOLATED`、`ROBOT_BOOTSTRAP_COMPLETE`；数据源和 Flyway 由正式部署配置供给。System 还检查实际初始化和管理员交接状态、开发密码不能继续使用。声明本身不证明凭据已失效，历史 V2 checksum 保持不变。

生产 Actuator 除 health 路径外要求唯一且正确的 `X-Robot-Management-Token`，Servlet/Gateway 都检查管理 handler 和直接访问。生产 `/v3/api-docs*`、`/swagger-ui.html`、`/swagger-ui/**`、`/doc.html`、`/webjars/**` 返回 404。Boot 4 Gateway 管理端点要求显式 `management.endpoint.gateway.access=none`，exposure 不能含 gateway 或 `*`；旧 enabled=false 单项不能证明关闭。

管理 token 通过 TLS、独立管理网络和 Secret 供给；代码鉴权不替代真实网络隔离。Servlet 业务服务缺少 `SessionSubjectVerifier` 时启动失败，不能仅凭 Redis 登录快照开放新服务；纯非 Web 的 API client context 不强装 Servlet 策略。新增业务服务仍须明确可信身份受众、内部凭据权限与撤销合同。

## Redis 恢复与尚需验收的运行条件

Lua 原子性不等同磁盘/HA 持久性。只有确认包含最新已提交状态与 pending 的 AOF 恢复，才可在原 namespace 继续投递；AOF 策略、备份时点、复制与故障转移须由运行环境证明。恢复旧快照、无法确认恢复点或发生复制回退时，使用新的合法 `ROBOT_ENV` 和隔离存储迫使全部设备重新认证，不桥接旧 refresh。旧 pending 要单独核对、按 eventId 幂等重投，不能随 namespace 切换静默丢弃。

当前没有数据库逐族撤销账本，旧 Redis 状态恢复可能复活已消耗/撤销的凭据。数据库账号和成员安全版本只挡住相关版本已变化的状态，不能证明所有旧刷新族都失效。单 `{sessions}` slot 容量和吞吐、AOF 重启、HA 丢写/回滚、Cluster、真实入口及网络 TLS 必须独立验收；本机单主成功不能作为生产结论。

## 诊断与正式探针

HTTP 200 的 Result 失败用 `robot.http.business.results` 统计固定 8 类 outcome。审计使用无标签的 `robot.auth.audit.commit.unconfirmed`、`robot.auth.audit.append.rejected`、`robot.auth.audit.delivery.failures`、`robot.auth.audit.round.failures`；账号、事件、key 和消息不进入标签。意外异常仅记录固定 DATABASE/TIMEOUT/IO/STATE/OTHER 分类及 traceId，不打印原始消息、SQL、token、URI 或堆栈。看板、容量/毒事件阈值、接收方、采集缺失和恢复仍需真实运行记录。

正式入口为 `scripts/verify-auth-redis.sh full|prepare|verify`。必须显式声明 `ROBOT_VERIFY_REDIS_DISPOSABLE=1`、`ROBOT_VERIFY_REDIS_HOST=127.0.0.1`、独立端口及 `verify-` 加 12 位十六进制的唯一 namespace；探针以 SETNX 和 1 小时 TTL 保护 namespace 所有权。它使用本次编译测试类和最终 Auth JAR 运行库，不连接默认共享 Redis，不使用 KEYS/FLUSH，不启动或停止服务。full 检查真实 Lua/CAS、容量、TYPE、竞争、ACK 及实际 manager；prepare/verify 配合验收宿主控制的 AOF 重启。真实 System/MySQL 的幂等接收、最终质量与当前快照全量行为由统一入口分别证明。

full 还用两个真实 AuthCredentials/门控实例与真实 Redis 验证稳定 ID 预算、Unicode 用户名别名、来源/全局不重复计数、固定窗口恢复和失败事件 ACK；只有 System 账号供给与审计接收替换为明确的探针适配器。MySQL 排序规则事实和真实服务的用户名解析仍须在数据库/运行验收中独立证明。所有计数 key 都由唯一探针 namespace 和固定已知身份计算，检查新鲜度后使用，结束仅删除这组已知探针 key。
