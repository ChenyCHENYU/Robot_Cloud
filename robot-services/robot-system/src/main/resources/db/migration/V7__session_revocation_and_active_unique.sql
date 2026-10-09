-- 仅新增迁移；账号/成员撤销版本与乐观锁 revision 分离。
ALTER TABLE sys_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0 COMMENT '账号会话撤销版本';
ALTER TABLE sys_user_company ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0 COMMENT '成员上下文撤销版本';
ALTER TABLE sys_menu ADD COLUMN remark VARCHAR(255) NULL COMMENT '备注';
ALTER TABLE sys_menu_button ADD COLUMN remark VARCHAR(255) NULL COMMENT '备注';

-- 活跃记录唯一，历史软删行可重复；原 (业务键, delete_flag) 无法支持第二次删除。
ALTER TABLE sys_user ADD COLUMN active_username VARCHAR(30)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN username ELSE NULL END) STORED;
ALTER TABLE sys_user ADD UNIQUE KEY uk_sys_user_active_username (active_username);
ALTER TABLE sys_user DROP INDEX uk_sys_user_username;

ALTER TABLE sys_role_button ADD COLUMN active_button_id VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN button_id ELSE NULL END) STORED;
ALTER TABLE sys_role_button ADD UNIQUE KEY uk_sys_role_button_active (role_id, active_button_id);
ALTER TABLE sys_role_button DROP INDEX uk_sys_role_button;
