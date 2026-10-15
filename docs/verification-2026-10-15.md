# 2026-10-15 本轮优化验收

本轮基于2026-10-14全面审查的REV01–REV24完成Cloud和配套Admin整改。旧审查、旧验收和中途失败保留为当时事实。此记录只描述本次实际执行范围；版本冻结对应最终同步提交，未执行生产部署。

## 统一门禁

| 范围 | 实际结果 |
| --- | --- |
| 工具链 | Java17.0.20.1；固定Maven3.9.16；Boot4.1.1；Flyway12.4.0；MySQL8.4.11 |
| 完整入口 | `bash scripts/verify.sh --mysql-upgrade` 成功，115套XML、679测试，失败/错误/跳过均0 |
| 真实MySQL | 20项；新建/V10与V5升级、20表固定golden、真实屏障竞争、平台交接/事务回滚、V13导航7例 |
| 数据合同 | 20表、248列、98索引列；V1–V10与已发布基线字节一致，新增V11–V13；V13修补期间V1–V12未变 |
| 原生Java质量 | 9个Java模块执行Checkstyle、PMD、SpotBugs、Spotless；83个受检业务类逐类达标：70 service与13 controller |
| 覆盖率阈值 | service行≥70%且分支≥60%；controller行≥50%；不以包均值替代、缺失/陈旧/遗漏报告阻断 |
| 工具负例 | Python53+19=72；Node工具63，均成功；故障脚本UTC修补后独立复跑19项运行工具回归 |
| 供应链 | 实际SBOM239个外部Maven版本，完整OSV和许可检查0阻断；查询时间2026-10-14T18:37:15Z |
| 制品 | 17 Mapper、13迁移资源、根MIT及驱动原始许可通知完成打包核验 |
| Admin | `bun run verify`成功：175 Bun、31 Vue，类型/样式/预算及demo/application构建通过；lint0错误，10个既有warning |
| 浏览器fixture | 23项HTTP fixture通过；与真实Cloud联调分别计数 |

V13拒绝测试验证MySQL错误码1242/21000与后置绑定CHECK错误3819/HY000，核对业务DML回滚和Flyway失败历史；不会把任意异常当成预期拒绝。JaCoCo对第三方JSQLParser生成方法有两条插桩告警，83个受检业务类均有有效报告；不宣称第三方依赖覆盖率通过。

## 当前不可变制品

| 服务 | SHA-256 |
| --- | --- |
| Gateway | `01e3b03cb9aab0f359250f571e75a5f3423ad15e07f7e7d585146aa39a54f592` |
| Auth | `ca3dc791995ade7ed86e696cd2b39ebbd29a7affed7f21a4d5dcc625db351dc9` |
| System A/B | `eb643e8cd3826ea78fe57f421feeef588db55d313c40e668f3ab1d5e83a788fc` |

四个应用使用同次JAR不可变副本与真实PID/readiness绑定；System A/B共享自建MySQL和Redis。只使用loopback、自建实例和随机`robot_iteration_rc_*`库；两家公司、同账号两个成员及不同主部门是SQL夹具，不冒充业务写命令验收。

## 实际运行与规模

- Auth最终JAR实际Redis21项及full/prepare/verify通过；Redis7.4.11、AOF always有序重启，证明设备族、无TTL待审计事件和ACK恢复。未证明HA、未知旧快照或RPO=0。
- 500测试成员、分页50；A/B/Gateway各3 warmup+40顺序，Gateway8 workers总160请求全部成功；精确清理500账号/成员/角色绑定并注销。
- 顺序p95：A49.470ms、B39.353ms、Gateway41.085ms；Gateway并发p95=140.044ms、p99=208.949ms，160请求耗时1.354s。不同执行顺序与JIT温度不能支持入口间因果比较；这是有限本地测量，无旧制品对照，不代表生产容量/SLO。
- 四类身份事实SELECT均数19/HTTP请求，全SELECT约25/请求，区间含后台工作。主体单次四事实复用不等于整个HTTP只有4条SQL。
- 501可归档候选，跨A预览/B确认首批500，500围栏及500成功审计、剩1独立批；pending、quarantine、近期3种排除均保持。500确认0.458s。
- 100通知实际全部完成1.850s，attempts=0、quarantine=0、deliveryRevision=1；保留两实例真实调度指标，不编造单轮或HA吞吐结论。
- 501本公司历史+1其他公司同过滤行：A/B第51页各total501/list1，六页交替完整遍历501项，无漏重或串公司。
- EXPLAIN保留小规模下ALL/filesort的实际选择，不force index，不称所有索引命中。
- 最终用户/角色命令19/19、旧权限序列化缓存回填18/18通过，精确cleanup与logout成功。API按约定保留平台账号和软删历史，由隔离库最终回收。

真实浏览器当前制品114/114检查、76请求通过，无请求拦截、mock响应或注入会话。公司2票据激活、资料null清空/服务端重读/恢复、活动记录、安全只读、治理能力及临授分页均实际完成；以随机自有成员及已有角色通过UI创建临授、UTC请求/本地显示、服务端重读和精确版本撤回。finally资料恢复、grant撤回、成员/角色清理与登出全部成功；平台账号按合同留至隔离库回收。application入口SHA256为`42e0526b3f0a7720c0864457db49f894cfe4e0d4483e29cc48bc4782128eabf5`。

两台System同JAR逐台有序重启成功：同伴31/28次请求全部成功，两条受控pending事件重启后精确保留并最终投递。耗时11.906/13.108s仅描述此夹具，不作为生产恢复目标。性能、规模和UI使用默认3s Redis预算；随后为确定性held-ACK测试将隔离System预算切为20s并记录新PID/配置。

双System故障15/15通过：offline失败分类与恢复、版本单调、同plan并发单胜者、ACK丢失、held ACK与新版本/重放、旧worker条件更新为0。superseded指标增量1；代理release/online、登出、旧token双节点拒绝和双health UP全部通过。事件来自SQL夹具，不替代真实业务事务回滚（另由正式Java/MySQL验收证明）。

四个应用日志对本次夹具密码、inner/management密钥及本次access/refresh五个值精确扫描，匹配0；只保存日志哈希与计数，敏感值仅驻内存并登出。不声称穷举全部可能载荷。

## 规则与失败证据

同runId `41f534f8-623d-4673-80ea-462e2f5e1907`完成full B1–B32和B2–B32 review。原始81 error/1 warning保留：77项B1为扫描器不识别Sa-Token，3项B25为同声明真实Lombok排除注解漏识别，B6为23个同域认证测试，1项B17是既有隔离测试故意删除审计表验证回滚。原始报告仍exit1，不改baseline或零警告预算。

Robot8项静态适配覆盖79端点通过；固定源哈希语义复核4项后，项目有效门0新增/0阻断、1既有精确B17。63项工具负例通过。原生包J门和宿主自动加载未由这些静态收据证明，Java门使用上表真实报告。导航数据合同使用明确的seedMigrationFile元数据，不冒充表结构或CRUD生成合同；导航/全结构机器合同仍由正式MySQL测试核验。

真实启动曾暴露bootstrap JDBC Boolean读取问题，已加入真实MySQL交接回归。新增V13首次执行因排序规则1267失败，修复语句类型并加强具体错误码断言后完整统一门成功。规模工具旧hex-collation、开关DOM语义选择器与性能观察槽饱和的失败均保留，未改写为通过；观察槽饱和时测试拒绝测量，自建MySQL有序重启并扩大统计槽50000后复测成功。故障脚本曾按CLI数据库本地时区写入未来事件，现每次CLI会话显式UTC，新增回归并复跑19项；Java、POM、质量配置和已验证制品输入保持不变。运行夹具Redis已退出导致的一次启动失败也单独保留，健康恢复后使用同一制品重新启动。

## 交付边界

原生回执、输入/制品哈希、真实XML与运行日志在工作区output保留；Git只提交源码、合同及此验证摘要，不提交凭据、运行收据或缓存。最终推送和两端dev/main SHA以真实Git回执为准。

GitHub旧verify因账户账单锁定未启动，用户要求本地验收后提交推送，不等待恢复。最终托管状态、未读取确认的分支保护及宿主自动加载不能登记通过。生产Nacos、HA、告警接收、容量、备份RTO/RPO仍需要目标环境；单主有序重启不证明这些范围。旧或未知Redis快照采用新环境世代并全部重新登录，历史无时区临授先受控确认UTC解释，不猜测转换。

原生同runId聚合status实际为`executionStatus=failed`、`validationStatus=stale`、`stale/planStale=true`、`outdatedChecker=false`，早期回执仍保留。最终四份执行收据分别记录完整输入快照、检查期间无变化；项目静态适配/精确语义门通过不覆盖聚合status，也不把其J门pending改成通过。
