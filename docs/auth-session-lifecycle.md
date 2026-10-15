# 认证会话生命周期

本实现保留 `/auth/login`、`/auth/context/activate`、`/auth/context/switch`、`/auth/refresh-token` 与 `/auth/logout` 的公开请求/响应字段。`token`、`refreshToken` 和 `loginTicket` 为不透明字符串；客户端不能解释内部状态或拼接公司身份。本文说明当前实现，最终执行证据见定版验收记录；新增测试尚待统一入口对当前源码快照验证。

`POST /auth/login-companies` 的请求升级为 `username` 与 `password`。先通过完整凭据验证，再读取有效成员公司；未知账号、错误密码、未证明的停用账号共用 `40101` 和可靠失败审计。发现成功不创建会话或票据，正式登录仍需重新验证凭据和当前成员。旧 username-only 调用不再支持，Admin 与 Cloud 需同步升级。

## 会话归属与存储合同

公司、成员上下文、部门与角色快照保存在 Sa-Token token Session，账户 Session 不保存这些业务属性。相同 userId 的两个 token 可分别保持公司 A/B；切换公司只轮换当前设备，其他设备保留原上下文。可信部门来自成员上下文，不能从全局账号或请求公司字段推导。

每次完成登录/票据激活建立随机刷新族。权威键为 `robot:<env>:auth:{sessions}:v2:family:<familyId>`；`{sessions}` 为字面 hash tag，确保权威状态和审计使用同一个 Redis slot。族状态含 userId、contextId、账号/成员安全版本、当前 access 原文和摘要、refresh 摘要及登录时凭据指纹，并保留可信审计身份。access 原文仅用于 Sa-Token 生命周期清理；refresh 原文只在生成时返回，key/value 使用 SHA-256 摘要。任何模型字符串和日志都不能泄露这些秘密。

族、refresh 摘要索引和 access 摘要索引精确 7 天 TTL，轮换后从当前提交重新计时，不附加缓存抖动。Sa-Token access 默认 1800 秒，access 索引较长以支持当前 access 自然过期后的注销。旧索引异步清理，失败时按原 TTL 退出；索引只是候选定位，只有摘要仍匹配当前族才有权限。登录票据随机 32 字节、编码为 64 个十六进制字符，精确 5 分钟 TTL，键为摘要，内容绑定账号、安全版本及密码摘要指纹。

schema v2 不兼容旧 schema，全部旧设备重新认证，不恢复或桥接旧 refresh。环境名是规范小写且仅使用字母、数字和分隔连字符；Sa-Token 原生键另用独立 Redis 实例/database 隔离，Cluster 需独立实例/集群。HTTP/Feign/自定义 Redis JSON 仍为 Jackson 2 合同，Sa-Token 会话使用 Jackson 3 精确类型白名单。

## 创建、轮换与提交点

1. refresh 读取当前族并比对 refresh 摘要，再由 System 复验账号、有效公司成员、两个安全版本和密码指纹。临时 RPC 故障不消费旧凭据，可重试；确定失效仅对仍精确匹配原快照的族 CAS 撤销，过时失败请求不能覆盖后继。撤销标记精确 7 天 TTL。
2. 候选 access 签发后写候选摘要索引；新族创建或轮换通过 Lua 比较完整旧状态，并与成功审计一起原子提交。Lua 在改状态前检查审计容量和相关 Redis TYPE。多个实例竞争只有一个提交者；输家撤销自己的候选，不能恢复旧族或注销赢家。
3. CAS 提交后返回当前新凭据，旧 token/索引由有界后台队列清理。请求态验证要求 access 摘要仍等于当前族，Sa-Token 映射清理失败不能继续接受旧 access。
4. 提交结果不明时只尝试把自己的候选状态 CAS 成撤销标记，并清理候选；绝不以 SET 恢复旧状态。响应丢失可能要求重新认证，不承诺透明重试返回同一新 refresh。
5. loginTicket 先完成 System 复验和公司选择，再原子读取删除；仅消费成功者可签发。下游故障保留票据，消费后进程中断或签发失败不重建票据，客户端需重新登录。

SETNX 仅用于随机新族身份的冲突保护，不是分布式锁。轮换和注销依赖 CAS，票据依赖原子消费，不依赖 JVM synchronized 或会到期的锁租约。公司切换先核验原成员仍有效，再将新成员写入同一设备的族；其他设备不受影响。

## 注销、并发与请求态验证

`POST /auth/logout` 支持持有当前 access 原文、但 access 已自然过期的客户端。先从 access 摘要索引定位候选族；每次读取、包括 CAS 重试，都要求请求摘要等于当前族 access 摘要，才允许原子删除族并写入成功注销审计。族不存在/已撤销时幂等返回；旧轮换 token 只清理自己的 access 与索引，不能注销后继。持续竞争达到重试上限返回冲突，不覆盖获胜者。

并发以提交顺序解释：注销先赢，旧 refresh 的 CAS 失败；refresh 先赢，旧 access 的注销不能删除新状态。即使旧索引清理失败、后台清理阻塞，或 refresh 在注销读取与 CAS 之间获胜，重试仍必须核对当前摘要。当前 access 自然过期不改变这一归属检查，过期且已被轮换的旧 access 没有后继的注销权。

普通业务请求依次验证 Sa-Token、当前族摘要和可信主体。Servlet 服务必须装配 `SessionSubjectVerifier`；缺少策略启动失败。System 复验账号/成员启用、有效关系与安全版本，Redis 状态缺失/格式无效/撤销/故障均拒绝访问。账号改密/停用以及成员安全变更的版本由对应事务维护。

已通过验证、正在执行的业务请求不承诺被并发注销即时取消；敏感写操作仍须自己的事务、版本和权限检查。注销响应不依赖非权威清理完成，族与事件原子提交才是撤销点。

## 审计交付与清理故障

族创建、refresh、switch 和 logout 的成功审计与状态提交使用一个 Lua 命令。竞争输家不写成功事件；固定 UUID eventId 是 System 幂等交付身份。队列容量 10000、单条编码载荷最多 2048 个字符，容量或类型异常在状态修改前拒绝。失败登录单独入同一有界队列，拒绝必须传播并计数。

pending payload hash 和 due zset 无 TTL，不能静默按年龄删除。后台每轮最多 20 项，System 落库成功才 ACK；故障 5 秒后重试，确认丢失重投同一 eventId，重启继续消费。ACK 原子移除 payload 与 due；fallback 不能返回成功来丢弃事件。真实数据库幂等、队列满、毒事件和恢复告警需分别验收。

CAS 后不等待审计 HTTP，旧索引/Sa-Token 清理由单线程、256 项有界队列执行。队列满或退出依靠精确 TTL 兜底；族权威校验阻断旧 token，不依靠清理及时性保证撤销。跨服务调用和 Redis 均有界超时，但真实 Gateway 总耗时、断网/丢响应与峰值排队仍须测量。

## 恢复与验收边界

Lua 的原子性不能证明 AOF、备份或 HA 没有丢写。确认包含最新提交和 pending 的 AOF 恢复可继续当前 namespace 投递；旧快照、未知恢复点或复制回退须切换新的合法 `ROBOT_ENV` 和隔离存储，使所有设备重新认证。旧 pending 单独核对并按 eventId 幂等补投，不能直接删除或桥接旧凭据。

当前没有数据库逐族撤销账本，恢复旧 Redis 族可能复活已经消耗/撤销的凭据。数据库账号/成员版本只拒绝版本已变化的状态；单 slot 吞吐、AOF 重启、HA 回退、Cluster 和真实入口验收均不能以单主内存测试代替。

行为回归包括同账号多设备、公司切换、并发 CAS、下游故障、确定失效、票据消费、候选补偿，以及旧索引清理失败/阻塞和注销 CAS 重试的归属边界。正式真实 Redis 入口 `scripts/verify-auth-redis.sh full|prepare|verify` 只接受显式独立 loopback 实例与唯一探针 namespace，调用真实 helper/queue/manager；AOF 重启由宿主控制，System/MySQL 幂等另验。当前快照的最终构建、测试、覆盖率、质量报告及制品启动结果由统一验收给出；运行前提详见 [安全硬化合同](security-hardening.md)。
