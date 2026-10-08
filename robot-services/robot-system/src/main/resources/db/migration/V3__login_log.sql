-- =====================================================================
-- V3：登录日志表（认证服务经 /inner/log/login-record 写入，只追加）
-- =====================================================================

CREATE TABLE sys_login_log (
    id               VARCHAR(64)  NOT NULL COMMENT '主键',
    company_id       VARCHAR(64)  NULL COMMENT '公司 ID（登录失败可能未定上下文，允许空）',
    user_id          VARCHAR(64)  NULL COMMENT '用户 ID',
    username         VARCHAR(30)  NULL COMMENT '登录名',
    login_type       VARCHAR(20)  NOT NULL COMMENT 'login/activate/switch/refresh/logout',
    status_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=成功，0=失败',
    ip               VARCHAR(45)  NULL COMMENT '客户端 IP（兼容 IPv6）',
    user_agent       VARCHAR(500) NULL COMMENT '客户端 UA',
    login_time       DATETIME     NOT NULL COMMENT '登录时间',
    create_user_no   VARCHAR(64)  NOT NULL COMMENT '创建人工号',
    create_date_time DATETIME     NOT NULL COMMENT '创建时间',
    update_user_no   VARCHAR(64)  NULL COMMENT '更新人工号',
    update_date_time DATETIME     NULL COMMENT '更新时间',
    delete_flag      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有效，0=删除',
    revision         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_sys_login_log_user (user_id, login_time),
    KEY idx_sys_login_log_company (company_id, login_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '登录日志';
