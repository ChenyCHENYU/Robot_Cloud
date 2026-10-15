-- Expand: 非法通知隔离保留，完成历史受控归档；不物理删除pending或审计事实。
ALTER TABLE sys_cache_outbox ADD COLUMN quarantined_at DATETIME(6) NULL COMMENT '永久非法事件隔离时间，人工治理后可重排';
ALTER TABLE sys_cache_outbox ADD COLUMN archived_at DATETIME(6) NULL COMMENT '到期完成历史逻辑归档时间，详情和审计仍可核对';
ALTER TABLE sys_cache_outbox ADD KEY idx_outbox_due_v2 (completed_at, quarantined_at, next_attempt_at, created_at, id);
ALTER TABLE sys_cache_outbox ADD KEY idx_outbox_recent_v2 (archived_at, created_at, id);
ALTER TABLE sys_cache_outbox ADD KEY idx_outbox_retention (archived_at, completed_at, id);
ALTER TABLE sys_temp_authorization ADD KEY idx_temp_governance (company_id, delete_flag, create_date_time, id);
ALTER TABLE sys_permission_audit_log ADD KEY idx_permission_audit_history (company_id, delete_flag, create_date_time, id);
