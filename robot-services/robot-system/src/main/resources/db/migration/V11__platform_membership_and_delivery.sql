-- Expand: 独立平台账号事实默认关闭，不把公司 SUPER_ADMIN 自动提升为平台属主。
ALTER TABLE sys_user ADD COLUMN platform_admin_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '独立全局目录平台管理员，普通账号接口不可写';

-- 每个公司成员一个主部门；旧全局字段暂保留供升级对账，不再作为新写入事实。
ALTER TABLE sys_user_company ADD COLUMN department_id VARCHAR(64) NULL COMMENT '当前公司成员主部门';
UPDATE sys_user_company m
JOIN sys_user u ON u.id = m.user_id AND u.delete_flag = 1
JOIN sys_department d ON d.id = u.department_id AND d.company_id = m.company_id
    AND d.delete_flag = 1 AND d.status_flag = 1
SET m.department_id = d.id
WHERE m.delete_flag = 1 AND m.department_id IS NULL;
ALTER TABLE sys_user_company ADD KEY idx_member_department (company_id, delete_flag, department_id, user_id);

-- Auth 可恢复投递的去重身份；历史无身份记录允许并存。
ALTER TABLE sys_login_log ADD COLUMN event_id VARCHAR(36) NULL COMMENT '可信认证投递UUID，重复事件只追加一次';
ALTER TABLE sys_login_log ADD UNIQUE KEY uk_login_event (event_id);
