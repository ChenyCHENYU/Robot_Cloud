-- 幂等缓存通知允许重复投递；单调围栏阻止旧投递者覆盖重放及新结果。
ALTER TABLE sys_cache_outbox
    ADD COLUMN delivery_revision BIGINT NOT NULL DEFAULT 0 COMMENT '投递/重放结果单调围栏',
    ADD COLUMN last_failure_id VARCHAR(36) NULL COMMENT '安全诊断关联ID，不含错误原文';

CREATE TABLE sys_cache_outbox_replay_log (
    id VARCHAR(36) NOT NULL COMMENT '重放审计关联ID',
    event_id VARCHAR(64) NOT NULL COMMENT '被重放的通知',
    actor_user_id VARCHAR(64) NOT NULL COMMENT '当前已认证操作者',
    actor_company_id VARCHAR(64) NOT NULL COMMENT '当前可信公司上下文',
    reason VARCHAR(32) NOT NULL COMMENT '受控原因枚举',
    previous_revision BIGINT NOT NULL,
    new_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_cache_outbox_replay_event (event_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='缓存通知重放只追加审计';

-- 全局治理动作登记在既有有效菜单管理页面；不创建虚假页面，也不自动授予普通角色。
INSERT INTO sys_menu_button (id, menu_id, button_name, perm_code, sort_no, status_flag,
    create_user_no, create_date_time, delete_flag, revision)
VALUES ('btn-cache-outbox-manage', 'menu-sys-menu', '缓存通知治理', 'sys:cache-outbox:manage',
    1, 1, 'SYSTEM', NOW(), 1, 0);
