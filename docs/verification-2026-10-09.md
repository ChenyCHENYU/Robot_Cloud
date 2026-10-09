# 三批改进验收记录 · 2026-10-09

本记录对应本轮工作区代码，说明实际检查及其边界。测试和运行结果绑定各次源码/制品摘要；历史报告不能替代后续代码验收。

## 变更范围

| 批次 | 当前落点 |
| --- | --- |
| 写命令 | 用户启停/移出、角色删除强制客户端 expectedRevision；状态与改密专用 DTO，改密保护账号/成员双版本 |
| 职责与事务 | RoleQueryService、RoleCommandService、TemporaryAuthorizationCommandService；角色、绑定、成功审计、epoch 和 Outbox 同事务 |
| 持续门禁 | 本地/CI 同入口；行为、ArchUnit、HTTP/数据库机器契约、已有库升级和最终 JAR Mapper 装配 |
| 缓存恢复 | V10 投递围栏、追加重放审计、永久超管预览/确认治理、积压/时延/分类指标 |
| 错误与链路 | 应用异常日志受控分类，技术响应固定；网关/Servlet 统一有界 TraceId，非法头重新生成 |
| 文档 | README、架构与拆分路线、API/数据库/部署/授权/规范适配同步 |

V1–V5 发布迁移保持不变。前端新增请求字段需同步，未修改 Robot Admin 页面。

## 环境与门禁

| 项 | 本次实际环境 |
| --- | --- |
| Java / Maven | JDK 21；Java17编译目标及 release API 约束；Maven3.9.16 |
| MySQL / Redis | 独立临时 MySQL8.4.11、Redis7.4.2，绑定loopback高位端口 |
| 应用 | Gateway28080、Auth29201、System29202/29203，local profile |
| 身份与日志 | 开发种子及随机测试账号；凭据/缓存载荷不进入探针报告；最后启动器显式 DEBUG=false |

完整入口：

```bash
bash scripts/verify.sh --mysql-upgrade
```

实际结果为 **14模块、56套Java XML报告、308测试，0失败/0错误/0跳过**。另有 **23项Python工具/协议测试**。打包探针只从最终 Boot JAR 取生产类和依赖，17个Mapper、68个基础statement完成真实Spring/MyBatis装配；零自定义XML符合当前实现，全部10条SQL资源字节与manifest匹配。

3项真实MySQL升级验收包括：

1. V5用户/成员/角色/绑定数据和非零版本→V6旧pending通知→V10，核对数据保全、历史、校验和、字段/唯一索引及重复历史软删；旧事件新增围栏为0，历史attempts和未完成状态保留。
2. 只读重复预检发现冲突时，在执行后续DDL前停止，不自动合并或删除授权。
3. 非空无Flyway历史库拒绝自动baseline，原数据不变。

升级测试随机创建 `robot_verify_<随机ID>` 库，最终只清理自己创建的库。数据库机器合同是用户/角色聚合的6表76列子集，不能推导全库治理完成。

## 真实运行检查

| 场景 | 实际检查 |
| --- | --- |
| 写命令HTTP | 19组检查、49次Gateway请求；缺/负/旧/当前版本、双版本改密、角色引用保护、移出后隐藏及解绑 |
| 两System缓存故障 | 15项检查；TCP断网保留持久pending，恢复投递、迟到版本不回退、已应用ACK丢失、同planHash并发单胜及追加审计 |
| 旧worker围栏 | 按唯一inflight识别持有旧ACK节点；释放后该节点superseded计数增加，证明条件回写0行 |
| 两实例重启 | 4项检查；失败通知在两个JVM停止后仍持久，换新PID后恢复处理，保留尝试历史及已提交版本 |
| 旧权限缓存回填 | 18项检查；普通成员真实权限载荷DUMP仅存内存，撤权后RESTORE旧key，首次请求和新命名空间缓存命中都为HTTP403 |
| 本地启动 | 两System、Auth、Gateway健康为UP，经真实内部Feign与网关完成上述请求 |

Outbox故障期间的SQLfixture仅同事务推进epoch并入队，证明真实调度恢复；不等同于“Redis断网时MVC业务写命令仍可完成身份校验”。完整Service聚合回滚、审计/Outbox失败、双版本与竞争由Spring/H2事务回归另行验证。

旧缓存回填使用真实序列化快照模拟在途旧读，不是HA旧快照恢复。通知key现存生命周期内单调，跨7天TTL后的历史提示值重建不能作为权威；读侧始终以DBepoch决定命名空间。

## 问题与修复记录

首轮新增打包探针配置被System组件扫描，导致MyBatis提前装配、SqlSessionFactory缺失；修成显式注册的隔离配置后重跑通过。随后发现macOS临时目录符号链接路径误报，统一resolve并保留越界检查和负例。

故障脚本先后修正了当前登录响应字段、ACK丢失场景的worker空闲等待，以及真实list权限是页面资源的选择。失败回执保留，未通过删检查、改目录或mock运行结果消除失败。

早期隔离进程继承终端 `DEBUG=release`，Spring因此开启详细日志。最后启动器明确关闭DEBUG；早期raw日志已丢弃，只保留白名单启动和受控通知摘要。应用处理器的脱敏输出不控制第三方组件在DEBUG/TRACE下记录载荷，部署仍需控制实际日志等级。

## 规范回执和CI边界

同一run-id：`2d6d4ce8-6fdf-4e1a-a76a-6d48e00d86e4`。

- BD选取除B1/B17以外30条规则，实际0错误/0警告；全量仍记录74条框架/适用位置差异，详见 [规范映射](conventions-bd-mapping.md)。
- BD task因未安装目标项目Skill/standards保留gap；Kit task未选适用前端Skill，其默认src/views校验不能用于Java工程，真实失败回执同样留存。
- GitHub Actions定义push/PR/manual门禁、固定Actions SHA和报告归档。上述本地验收时尚未配置remote；后续发布接入GitHub/Gitee，托管job的实际结果以对应提交的Actions记录为准。

原始XML、构建日志、机器回执、运行JSON及源码/制品SHA在工作区 `output/robot-cloud-iteration-2026-10-09/`。该目录位于Robot_Cloud仓库之外，独立clone不自带；托管CI产物通过job artifact另行归档，本文件保留仓库内可阅读摘要。

## 仍需对应环境验收

目标MySQL8.0/JDK17运行、真实Robot Admin页面字段/冲突交互、Nacos/Sentinel、RedisHA/cluster和旧会话快照恢复、生产容量及备份/DDL失败恢复仍未由本次演练证明。Flyway9对本机MySQL8.4提示支持范围警告，执行成功不代表官方兼容保证。

完成Outbox记录的保留期、归档作业和告警阈值，以及全局目录写权限、临时授权产品规则、通用字段/复杂数据策略仍需定稿。架构迭代条件见 [架构与演进](architecture.md)，运行步骤见 [部署](deployment.md)。
