-- =====================================================================
-- Robot Cloud 系统库初始化（V1：结构）
-- 规范依据：wl-skills-bd standards/12
--   * MySQL 统一 lower_snake_case；utf8mb4
--   * 每表 ID + 七个治理字段（COMPANY_ID/CREATE_USER_NO/CREATE_DATE_TIME/
--     UPDATE_USER_NO/UPDATE_DATE_TIME/DELETE_FLAG/REVISION）
--   * 布尔/标志列 *_FLAG，禁止 is_ 前缀；DELETE_FLAG：1=有效，0=删除
--   * 全局表（无 COMPANY_ID）：sys_tenant/sys_company/sys_user/sys_user_company/
--     sys_menu/sys_role_menu/sys_menu_button/sys_dict_type/sys_dict_item
--   * 本文件已发布即不可变；变更必须新增 V2+ 迁移（Flyway 唯一写入者）
-- =====================================================================

-- ----------------------------
-- 租户
-- ----------------------------
CREATE TABLE sys_tenant (
    id               VARCHAR(64)  NOT NULL COMMENT '主键（不透明 String ID）',
    tenant_name      VARCHAR(100) NOT NULL COMMENT '租户名称',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    remark           VARCHAR(255) NULL COMMENT '备注',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_tenant_name (tenant_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '租户';

-- ----------------------------
-- 公司（COMPANY_ID 数据隔离键的实体）
-- ----------------------------
CREATE TABLE sys_company (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    tenant_id        VARCHAR(64)  NOT NULL COMMENT '所属租户 ID',
    company_name     VARCHAR(100) NOT NULL COMMENT '公司名称',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_company_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '公司';

-- ----------------------------
-- 平台用户
-- ----------------------------
CREATE TABLE sys_user (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    username         VARCHAR(30)  NOT NULL COMMENT '登录名',
    password_hash    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码摘要',
    nickname         VARCHAR(30)  NOT NULL COMMENT '显示名',
    email            VARCHAR(100) NULL COMMENT '邮箱',
    phone            VARCHAR(20)  NULL COMMENT '手机号',
    department_id    VARCHAR(64)  NULL COMMENT '主部门 ID',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用（停用禁止登录）',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username, delete_flag),
    KEY idx_sys_user_dept (department_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '平台用户';

-- ----------------------------
-- 用户-公司成员关系（contextId 实体）
-- ----------------------------
CREATE TABLE sys_user_company (
    id               VARCHAR(64) NOT NULL COMMENT '主键（登录上下文 contextId）',
    user_id          VARCHAR(64) NOT NULL COMMENT '用户 ID',
    company_id       VARCHAR(64) NOT NULL COMMENT '公司 ID',
    primary_flag     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否主公司：1=是（每用户最多一个）',
    status_flag      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=有效，0=移除',
    create_user_no   VARCHAR(64) NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME    NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64) NULL COMMENT '更新人工号',
    update_date_time DATETIME    NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_user_company_user (user_id),
    KEY idx_sys_user_company_company (company_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户公司成员关系';

-- ----------------------------
-- 部门（租户内树）
-- ----------------------------
CREATE TABLE sys_department (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    company_id       VARCHAR(64)  NOT NULL COMMENT '公司 ID（租户隔离键）',
    parent_id        VARCHAR(64)  NULL COMMENT '上级部门 ID',
    dept_name        VARCHAR(60)  NOT NULL COMMENT '部门名称',
    sort_no          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_department_company (company_id, parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '部门';

-- ----------------------------
-- 角色（租户内）
-- ----------------------------
CREATE TABLE sys_role (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    company_id       VARCHAR(64)  NOT NULL COMMENT '公司 ID（租户隔离键）',
    role_name        VARCHAR(30)  NOT NULL COMMENT '角色名称',
    role_code        VARCHAR(50)  NOT NULL COMMENT '角色编码（大写下划线）',
    data_scope       VARCHAR(20)  NOT NULL DEFAULT 'self' COMMENT '数据范围：all/custom/department_below/department/self',
    sort_no          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    remark           VARCHAR(255) NULL COMMENT '备注',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_role_company_code (company_id, role_code, delete_flag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色';

-- ----------------------------
-- 用户-角色绑定
-- ----------------------------
CREATE TABLE sys_user_role (
    id               VARCHAR(64) NOT NULL COMMENT '主键',
    company_id       VARCHAR(64) NOT NULL COMMENT '公司 ID（授权发生所在公司）',
    user_id          VARCHAR(64) NOT NULL COMMENT '用户 ID',
    role_id          VARCHAR(64) NOT NULL COMMENT '角色 ID',
    create_user_no   VARCHAR(64) NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME    NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64) NULL COMMENT '更新人工号',
    update_date_time DATETIME    NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_user_role_user (user_id, company_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户角色绑定';

-- ----------------------------
-- 菜单（全局表，前端动态路由唯一数据源）
-- ----------------------------
CREATE TABLE sys_menu (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    parent_id        VARCHAR(64)  NULL COMMENT '上级菜单 ID',
    menu_type        VARCHAR(20)  NOT NULL DEFAULT 'menu' COMMENT 'directory=目录，menu=菜单',
    menu_name        VARCHAR(60)  NOT NULL COMMENT '菜单名称',
    path             VARCHAR(200) NULL COMMENT '前端路由 path',
    component        VARCHAR(200) NULL COMMENT '前端组件标识',
    redirect         VARCHAR(200) NULL COMMENT '重定向地址',
    perm_code        VARCHAR(100) NULL COMMENT '页面级权限码',
    icon             VARCHAR(100) NULL COMMENT '图标',
    sort_no          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    visible_flag     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '菜单显示：1=显示，0=隐藏',
    keep_alive_flag  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '页面缓存：1=缓存，0=不缓存',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_menu_parent (parent_id, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '菜单';

-- ----------------------------
-- 角色-菜单授权
-- ----------------------------
CREATE TABLE sys_role_menu (
    id               VARCHAR(64) NOT NULL COMMENT '主键',
    role_id          VARCHAR(64) NOT NULL COMMENT '角色 ID',
    menu_id          VARCHAR(64) NOT NULL COMMENT '菜单 ID',
    create_user_no   VARCHAR(64) NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME    NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64) NULL COMMENT '更新人工号',
    update_date_time DATETIME    NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_role_menu_role (role_id),
    KEY idx_sys_role_menu_menu (menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色菜单授权';

-- ----------------------------
-- 菜单按钮权限
-- ----------------------------
CREATE TABLE sys_menu_button (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    menu_id          VARCHAR(64)  NOT NULL COMMENT '所属菜单 ID',
    button_name      VARCHAR(60)  NOT NULL COMMENT '按钮名称',
    perm_code        VARCHAR(100) NOT NULL COMMENT '按钮权限码',
    sort_no          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_menu_button_menu (menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '菜单按钮权限';

-- ----------------------------
-- 字典类型 / 字典项（全局表）
-- ----------------------------
CREATE TABLE sys_dict_type (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    type_code        VARCHAR(60)  NOT NULL COMMENT '类型编码（小写下划线，唯一）',
    type_name        VARCHAR(60)  NOT NULL COMMENT '类型名称',
    remark           VARCHAR(255) NULL COMMENT '备注',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_dict_type_code (type_code, delete_flag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '字典类型';

CREATE TABLE sys_dict_item (
    id               VARCHAR(64) NOT NULL COMMENT '主键',
    type_id          VARCHAR(64) NOT NULL COMMENT '所属类型 ID',
    item_label       VARCHAR(60) NOT NULL COMMENT '项标签',
    item_value       VARCHAR(60) NOT NULL COMMENT '项值',
    sort_no          INT         NOT NULL DEFAULT 0 COMMENT '排序',
    status_flag      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    create_user_no   VARCHAR(64) NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME    NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64) NULL COMMENT '更新人工号',
    update_date_time DATETIME    NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_dict_item_type (type_id, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '字典项';

-- ----------------------------
-- 临时授权（租户内）
-- ----------------------------
CREATE TABLE sys_temp_authorization (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    company_id       VARCHAR(64)  NOT NULL COMMENT '公司 ID（租户隔离键）',
    user_id          VARCHAR(64)  NOT NULL COMMENT '被授权用户 ID',
    role_id          VARCHAR(64)  NOT NULL COMMENT '临时授予角色 ID',
    effect_from      DATETIME     NOT NULL COMMENT '生效开始',
    effect_to        DATETIME     NOT NULL COMMENT '生效结束（最长 30 天）',
    reason           VARCHAR(255) NOT NULL COMMENT '授权理由（审计必填）',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=生效中，0=已撤销',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_temp_auth_user (user_id, effect_to)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '临时授权';

-- ----------------------------
-- 权限约束规则（租户内）
-- ----------------------------
CREATE TABLE sys_permission_constraint (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    company_id       VARCHAR(64)  NOT NULL COMMENT '公司 ID（租户隔离键）',
    rule_name        VARCHAR(100) NOT NULL COMMENT '规则名称',
    rule_type        VARCHAR(50)  NOT NULL COMMENT 'self_grant_ban/sensitive_confirm/role_mutual_exclusive',
    config_json      VARCHAR(1000) NULL COMMENT '规则配置 JSON',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_perm_constraint_company (company_id, rule_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '权限约束规则';

-- ----------------------------
-- 权限审计日志（只追加；禁改禁删）
-- ----------------------------
CREATE TABLE sys_permission_audit_log (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    company_id       VARCHAR(64)  NOT NULL COMMENT '公司 ID（租户隔离键）',
    actor_user_no    VARCHAR(64)  NOT NULL COMMENT '操作人',
    action           VARCHAR(50)  NOT NULL COMMENT '动作（save_role/delete_role/temp_authorize...）',
    target           VARCHAR(255) NULL COMMENT '目标对象',
    detail           VARCHAR(2000) NULL COMMENT '详情 JSON',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_perm_audit_company (company_id, create_date_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '权限审计日志';
