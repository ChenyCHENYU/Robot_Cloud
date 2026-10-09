-- 权威缓存 epoch 与事务内待发记录；控制表由 system 独占，不存用户权限载荷。
CREATE TABLE sys_cache_epoch (
    scope VARCHAR(255) NOT NULL COMMENT '权限域或字典编码命名空间',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '数据库事务内递增版本',
    PRIMARY KEY (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='缓存权威版本';

INSERT INTO sys_cache_epoch (scope, version) VALUES ('permissions', 1);
INSERT INTO sys_cache_epoch (scope, version) VALUES ('menu-tree', 1);
INSERT INTO sys_cache_epoch (scope, version)
SELECT CONCAT('dictionary:', type_code), 1 FROM sys_dict_type WHERE delete_flag = 1;

CREATE TABLE sys_cache_outbox (
    id VARCHAR(64) NOT NULL COMMENT '幂等事件标识',
    event_kind VARCHAR(16) NOT NULL COMMENT 'permissions/dictionary',
    cache_key VARCHAR(255) NOT NULL COMMENT '目标通知或失效缓存键',
    cache_version BIGINT NOT NULL COMMENT '对应已提交版本',
    created_at DATETIME(6) NOT NULL COMMENT '入队时间',
    next_attempt_at DATETIME(6) NOT NULL COMMENT '下次重试时间',
    completed_at DATETIME(6) NULL COMMENT '完成时间',
    attempts INT NOT NULL DEFAULT 0 COMMENT '失败次数',
    last_error VARCHAR(255) NULL COMMENT '仅记录异常类型，不包含凭据或原始消息',
    PRIMARY KEY (id),
    KEY idx_cache_outbox_pending (completed_at, next_attempt_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事务缓存通知待发记录';
