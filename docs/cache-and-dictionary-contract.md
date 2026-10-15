# 缓存提交与字典契约

本文以当前CommittedCacheChanges、PermissionStateStore、CacheOutboxStore、CacheEventDispatcher与DictService为准。PermissionStateStore维护epoch与协调锁，内部CacheOutboxStore维护通知SQL和围栏，拆分不改变同事务提交。SQL仅供有权限的隔离环境只读预检；实际 MySQL/Redis 与双 System 故障验证结果见 [部署文档](deployment.md)，Redis HA 仍需单独演练。

本轮新增V11–V13，已发布V1–V10保持不变。全局菜单/按钮/字典写同时要求独立平台管理员事实；目录删除必须提交客户端expectedRevision，不能从数据库代填。授权模型已确定为按用户临时授予已有角色，公司成员主部门独立。数据库固定golden覆盖全部20表结构。

## 提交点与读取时序

1. 权限类业务写、强审计、sys_cache_epoch递增和sys_cache_outbox入队在同一数据库事务；任一失败全部回滚。字典写与其epoch/outbox也同事务，但不冒充权限审计。
2. 当前请求读已提交数据库epoch再构造Redis版本键。权限epoch未初始化直接失败，不用Redis临时数字代替；未知字典scope可从0起步。
3. outbox调度只读已提交pending，Redis调用不持有数据库事务。权限通知单调advanceVersionTo，字典通知删除前一版本键；多节点重复投递幂等，迟到权限通知不能倒退版本。
4. 成功确认completed_at、失败记录attempts/next_attempt_at/受控错误类别和failureId，分别用独立事务；条件更新同时比较delivery_revision，旧worker不能覆盖重放后的新状态。投递失败不恢复旧授权，也不撤销已提交业务。

提交后重新读取epoch的请求转向新命名空间；早已读取旧epoch的在途请求不承诺中途取消或跨MySQL/Redis线性一致。旧缓存键由TTL及投递清理，不需要全量枚举用户。当前使用system主数据源，没有证明把epoch读取移到有延迟的从库仍安全。

| 数据 | 当前键/期限 |
| --- | --- |
| 权限载荷 | robot:{env}:system:permissions:{company}:{user}:db-v{DB epoch}；最多30分钟且不越过临时授权下一生效/到期边界，精确TTL，无正抖动 |
| 权限通知 | robot:{env}:system:perm-version；7天TTL，仅提示，不是权威epoch |
| 字典载荷 | robot:{env}:system:dict:{SHA256(lowercase(typeCode))}:db-v{DB epoch}；30分钟加缓存抖动；不存在2分钟占位 |
| 字典epoch | scope=dictionary:{typeCode}；MySQL utf8mb4_general_ci按现有大小写不敏感合同处理 |

字典支持最长60字符、冒号和点等合法typeCode，不能将原始code直接塞入最多64字符的Redis动态段。缓存serializer允许明确的com.robot载荷、集合和LocalDateTime类型，不能把第三方注入类型当成正常缓存。

## 字典请求、状态与版本

| 字段 | type类型 | item项 |
| --- | --- | --- |
| type | type（默认） | 必须item |
| parentId | 不用于类型归属 | 必须当前类型ID；跨类型移动按两个类型ID排序加锁 |
| code | 类型编码，60字符以内，字母开头，后续字母数字_.:-；兼容typeCode | 独立项编码，60字符以内；旧请求未给code时由value兼容回填 |
| name/label | name类型名称 | name/label/dictLabel均为显示标签兼容输入 |
| value | 不用于类型 | 独立存储值；兼容dictValue，label不是存储值 |
| status | 0停用/1启用 | 0停用/1启用，类型停用时项不输出但自身状态保留 |
| sort/remark | remark可写，树按类型编码/ID排序 | sort0–9999，remark可写；项按sort/ID排序 |
| revision | 新增服务端0，更新必需 | 新增服务端0，更新必需 |

兼容名称/value字段同时提交必须一致；未知id、审计、deleteFlag等不能制造治理数据。类型/项PUT及status必须提交revision，缺版本返回code=400，陈旧或更新0行code=40902；这些是Result业务码，不默认HTTP409。

示例为新增项：

```json
{"type":"item","parentId":"dict-type-id","code":"enabled","dictLabel":"启用","value":"1","sort":0,"status":1,"remark":"状态"}
```

更新相同对象时补读取到的revision。树返回code/name/label/value/dictLabel/dictValue/status/revision等合同；by-code仅返回启用类型下启用项的label/value，不存在或停用类型返回[]。新项不能放入停用类型，项跨类型移动不能移入停用类型。未删除类型编码唯一；同类型未删除项的code和value分别唯一，status=0仍占业务键；比较遵循当前MySQL列排序规则。

类型有未删除子项时不能删。项删除先读快照并锁旧类型，再以id+typeId+revision条件软删；若已被其他事务移走/更新，0行冲突而非删除新归属项。跨类型成功移动同时推进旧/新scope。类型重命名同时推进旧/新编码scope，负缓存不能跨已提交新增或重命名持续生效。

## V6–V10迁移前只读预检

在V1–V5存量库的备份副本执行。先核对数据库身份、MySQL版本、排序规则、Flyway历史及结构；若不符合已知基线，停止自动升级并制定协调方案。表不存在时不要运行依赖该表的后续SQL，也不要盲目baseline或修改已发布checksum。

```sql
SELECT DATABASE() AS current_database, VERSION() AS mysql_version,
       @@collation_database AS database_collation;
SELECT table_name FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('flyway_schema_history','sys_user','sys_dict_type',
                      'sys_cache_epoch','sys_cache_outbox');
-- 仅在确认历史表存在后执行：
SELECT installed_rank, version, description, script, checksum, success
  FROM flyway_schema_history ORDER BY installed_rank;
SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_order
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name IN ('sys_user','sys_role_button','sys_dict_type',
                      'sys_dict_item','sys_role','sys_user_company',
                      'sys_user_role','sys_role_menu')
 GROUP BY table_name, index_name, non_unique ORDER BY table_name, index_name;
```

V7/V8需要旧索引uk_sys_user_username、uk_sys_role_button、uk_sys_dict_type_code；提前核实是否与V1/V5一致。以下查询有任何结果都需业务裁决；不要自动合并账号、删除权限或清理成员。GROUP BY使用库当前排序规则，覆盖新唯一索引的大小写/尾部空格比较方式。

```sql
-- V7：活跃账号与精确按钮绑定。
SELECT username, COUNT(*) AS copies FROM sys_user
 WHERE delete_flag = 1 GROUP BY username HAVING COUNT(*) > 1;
SELECT role_id, button_id, COUNT(*) AS copies FROM sys_role_button
 WHERE delete_flag = 1 GROUP BY role_id, button_id HAVING COUNT(*) > 1;

-- V8：item_code由item_value回填，先检查同类型存储值重复。
SELECT type_code, COUNT(*) AS copies FROM sys_dict_type
 WHERE delete_flag = 1 GROUP BY type_code HAVING COUNT(*) > 1;
SELECT type_id, item_value, COUNT(*) AS copies FROM sys_dict_item
 WHERE delete_flag = 1 GROUP BY type_id, item_value HAVING COUNT(*) > 1;
SELECT id, type_code FROM sys_dict_type WHERE delete_flag = 1
 AND NOT REGEXP_LIKE(type_code COLLATE utf8mb4_bin, '^[A-Za-z][A-Za-z0-9_.:-]{0,59}$');
SELECT id, type_id, item_value FROM sys_dict_item WHERE delete_flag = 1
 AND (item_value IS NULL OR CHAR_LENGTH(TRIM(item_value)) = 0);
SELECT i.id, i.type_id FROM sys_dict_item i LEFT JOIN sys_dict_type t ON t.id = i.type_id
 WHERE i.delete_flag = 1 AND (t.id IS NULL OR t.delete_flag <> 1);

-- V9：status=0但未删除的记录也在唯一约束范围内。
SELECT company_id, role_code, COUNT(*) AS copies FROM sys_role
 WHERE delete_flag = 1 GROUP BY company_id, role_code HAVING COUNT(*) > 1;
SELECT company_id, user_id, COUNT(*) AS copies FROM sys_user_company
 WHERE delete_flag = 1 GROUP BY company_id, user_id HAVING COUNT(*) > 1;
SELECT user_id, COUNT(*) AS primary_copies FROM sys_user_company
 WHERE delete_flag = 1 AND primary_flag = 1
 GROUP BY user_id HAVING COUNT(*) > 1;
SELECT company_id, user_id, role_id, COUNT(*) AS copies FROM sys_user_role
 WHERE delete_flag = 1 GROUP BY company_id, user_id, role_id HAVING COUNT(*) > 1;
SELECT role_id, menu_id, COUNT(*) AS copies FROM sys_role_menu
 WHERE delete_flag = 1 GROUP BY role_id, menu_id HAVING COUNT(*) > 1;
SELECT ur.id, ur.company_id, ur.role_id FROM sys_user_role ur
 LEFT JOIN sys_role r ON r.id = ur.role_id
 WHERE ur.delete_flag = 1 AND (r.id IS NULL OR r.company_id <> ur.company_id);
```

类型编码不合法、空项值和孤儿不一定导致DDL本身失败，但会与新请求/缓存合同冲突，必须记录并裁决。已完成V8的环境额外检查同类型活跃item_code重复；上面的V8回填预检不适用于替代已有新字段的对账。

## 迁移后核验与运行验收

V7/V8先建立新唯一索引再移除旧索引；新约束建立失败时不得跳过或强删历史数据。以下SQL只在对应迁移已完成的副本执行；生成列与唯一索引仍需真实MySQL演练，包括第二次软删重建。MySQL DDL隐式提交，Flyway失败不能当作整段已回滚。部署时只有一个受控迁移执行者，避免多个新旧应用节点同时改变schema。

```sql
-- V6完成后：permissions/menu-tree必须存在且permissions正数。
SELECT scope, version FROM sys_cache_epoch
 WHERE scope IN ('permissions','menu-tree');
SELECT t.id, t.type_code FROM sys_dict_type t
 LEFT JOIN sys_cache_epoch e ON e.scope = CONCAT('dictionary:', t.type_code)
 WHERE t.delete_flag = 1 AND e.scope IS NULL;
-- V7完成后：撤销版本必须非空且非负。
SELECT id, security_version FROM sys_user
 WHERE security_version IS NULL OR security_version < 0;
SELECT id, security_version FROM sys_user_company
 WHERE security_version IS NULL OR security_version < 0;
-- V8完成后：独立code有效、无活跃重复。
SELECT id, item_code FROM sys_dict_item WHERE item_code IS NULL;
SELECT type_id, item_code, COUNT(*) AS copies FROM sys_dict_item
 WHERE delete_flag = 1 GROUP BY type_id, item_code HAVING COUNT(*) > 1;
SHOW CREATE TABLE sys_dict_type;
SHOW CREATE TABLE sys_dict_item;
SHOW CREATE TABLE sys_user_company;
-- 持续观测，不修改待发或已完成记录。
SELECT event_kind, COUNT(*) AS pending, MIN(created_at) AS oldest,
       MAX(attempts) AS most_attempts FROM sys_cache_outbox
 WHERE completed_at IS NULL GROUP BY event_kind;
SELECT COUNT(*) AS completed, MIN(completed_at) AS first_completed
 FROM sys_cache_outbox WHERE completed_at IS NOT NULL;
```

V7–V9的生成列只对delete_flag=1生成业务值，否则NULL；status停用不释放唯一键。验收索引须与迁移文件逐项比对，不能只看数量；权限/字典版本增长、事务回滚和缓存读取结果需共同检查。

隔离基础设施环境至少覆盖：

1. 真实MySQL新库V1–V13、V10及V5存量库升级；重复预检、历史软删重建、唯一primary、生成列/排序规则、并发移动、失败DDL恢复和纯DML导航迁移回滚。
2. Redis失败后outbox保持pending，恢复投递并完成；业务/审计回滚无事件、通知乱序不倒退、Redis清空后不复用旧授权命名空间。
3. 字典负缓存后新增、类型禁用再启用、重命名、跨类型移动和旧删除交错，旧/新编码查询符合已提交状态。
4. 真实Redis单key Lua、两auth实例refresh/logout竞争、TTL和HA故障切换/旧快照恢复；单主CAS与内存模型不能证明撤销持久性。
5. Robot_Admin读取并提交revision，版本冲突重新读取后由用户决定是否重试；不能静默代填当前版本。

本地H2/MVC及serializer回归不覆盖上述真实环境全部行为。V12已提供完成历史的受控逻辑归档，记录仍保留在表中；物理数据生命周期、备份与容量告警由部署策略决定，不能以pending归零推断生产长期容量已验收。

## V10 通知围栏与运维重放

`delivery_revision` 初值0，每次条件确认、失败重排和人工重放递增。worker 在事务外调用 Redis，返回后以读取时围栏更新；同一事件被另一 worker 或运维推进后，旧更新0行，记 superseded，不覆盖新计划。多节点可能重复发送，通知本身仍需幂等；围栏不是分布式 exactly-once 保证。

`sys_cache_outbox_replay_log` 只追加操作者、公司、受控原因与前后围栏。独立平台管理员且具备当前公司永久超管身份先查询和预览，再明确确认planHash；Service行锁内重算哈希，漂移拒绝，安排投递和成功审计同事务。审计失败回滚安排，重放不递增业务epoch、不修改通知版本、不清空失败次数。

## V12 隔离、逻辑归档与时间

无法合法投递的毒事件登记quarantined_at，状态QUARANTINED，仍计入未解决pending，调度扫描跳过；经过受控重放可重新排队。ARCHIVED只表示已完成历史隐藏，不表示业务删除或epoch回退。归档限定30–3650天保留期、单批1–500，预览摘要绑定操作者/候选/版本/保留期；锁内重新核验，漂移整批回滚并保留追加审计。pending、隔离、新重放和未到保留期事件不归档。

到期判断及退避使用MySQL相对时间和UTC会话，JDBC4.2直接读取LocalDateTime，不经JVM默认时区的Timestamp转换。新授权Clock与审计写入明确UTC；无偏移JSON字符串表示UTC，客户端选择转换后提交。历史无时区数据不能凭字段名批量改写，切换前在备份副本对账原写入节点时区。

生产还要求`sys_cache_epoch`中存在字节精确的`authorization-time-utc-v1` scope且version>=1，确认临时授权采用UTC解释；该marker独立于`permissions`和字典epoch，不通过缓存通知或普通HTTP推进。缺少合格marker时，启动护栏检查全部delete_flag=1且status_flag=1的临时授权，包含已到期记录；有任何存量或统计不明便拒绝启动，不自动删授予或改写历史时间。

仅上述存量为零时允许条件初始化version=1，并在插入时再次检查无存量、写后复验精确marker。已有低于1的版本、排序等价但非精确的scope或并发授予不能靠重复启动自动确认；合格marker已持久化后，正常的新UTC授予不阻止后续重启。存量环境须停写、备份、逐项核对旧节点/连接时区与业务预期时刻，经受控授权完成必要调整和marker交接。它只确认临时授权解释，不证明所有Outbox或历史审计时间已转换，流程见 [生产UTC交接](deployment.md#生产授权时间的-utc-交接)。

V12索引覆盖due、recent、archive以及公司审计/临时授权分页。实际规模和SQL计划以当前制品隔离测量为准；索引存在不能代替测量，也不能宣称物理表因逻辑归档自动缩小。

失败持久化仅允许枚举 category 和随机 failureId，历史旧错误对外映射 LEGACY_FAILURE；事件 API 不输出 cache_key 或原始错误。指标读取已提交数据库快照，抓取只读内存，需同时检查快照年龄。详细端点、指标与事故步骤见 [API 契约](api-contract.md)和 [部署与运维](deployment.md)。

通知 key 的单调性以现存 key 为边界；跨7天TTL后旧通知重放可能重建历史提示值。权限结果仍读取数据库 epoch，不使用该提示值决定授权。后续如其他消费者要使用通知值，必须补跨过期恢复合同或按当前数据库 epoch 收敛，不能把它升级成唯一事实源。
