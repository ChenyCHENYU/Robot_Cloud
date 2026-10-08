-- =====================================================================
-- V5：角色-按钮精确授权（RC11）
-- 菜单授权（sys_role_menu）不再隐含按钮权限；按钮权限独立绑定。
-- 种子：为 SYS_ADMIN 补齐既有按钮绑定，保持演示行为连续。
-- =====================================================================

CREATE TABLE sys_role_button (
    id               VARCHAR(64) NOT NULL COMMENT '主键',
    role_id          VARCHAR(64) NOT NULL COMMENT '角色 ID',
    button_id        VARCHAR(64) NOT NULL COMMENT '按钮权限 ID（sys_menu_button.ID）',
    create_user_no   VARCHAR(64) NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME    NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64) NULL COMMENT '更新人工号',
    update_date_time DATETIME    NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_button (role_id, button_id, delete_flag),
    KEY idx_sys_role_button_button (button_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色按钮授权';

-- SYS_ADMIN 延续既有按钮能力（与 V2/V4 种子按钮一一对应）
INSERT INTO sys_role_button (id, role_id, button_id, create_user_no, create_date_time, delete_flag, revision) VALUES
('rb-sa-1',  'role-sys-admin', 'btn-user-save',   'SYSTEM', NOW(), 1, 0),
('rb-sa-2',  'role-sys-admin', 'btn-user-del',    'SYSTEM', NOW(), 1, 0),
('rb-sa-3',  'role-sys-admin', 'btn-user-query',  'SYSTEM', NOW(), 1, 0),
('rb-sa-4',  'role-sys-admin', 'btn-user-reset',  'SYSTEM', NOW(), 1, 0),
('rb-sa-5',  'role-sys-admin', 'btn-role-save',   'SYSTEM', NOW(), 1, 0),
('rb-sa-6',  'role-sys-admin', 'btn-role-del',    'SYSTEM', NOW(), 1, 0),
('rb-sa-7',  'role-sys-admin', 'btn-role-query',  'SYSTEM', NOW(), 1, 0),
('rb-sa-8',  'role-sys-admin', 'btn-menu-save',   'SYSTEM', NOW(), 1, 0),
('rb-sa-9',  'role-sys-admin', 'btn-menu-del',    'SYSTEM', NOW(), 1, 0),
('rb-sa-10', 'role-sys-admin', 'btn-menu-query',  'SYSTEM', NOW(), 1, 0),
('rb-sa-11', 'role-sys-admin', 'btn-dict-save',   'SYSTEM', NOW(), 1, 0),
('rb-sa-12', 'role-sys-admin', 'btn-dict-del',    'SYSTEM', NOW(), 1, 0),
('rb-sa-13', 'role-sys-admin', 'btn-dict-query',  'SYSTEM', NOW(), 1, 0),
('rb-sa-14', 'role-sys-admin', 'btn-perm-list',   'SYSTEM', NOW(), 1, 0);
