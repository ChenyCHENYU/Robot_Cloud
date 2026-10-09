CREATE TABLE sys_cache_epoch(scope VARCHAR(255) PRIMARY KEY, version BIGINT NOT NULL);
INSERT INTO sys_cache_epoch VALUES ('permissions',1);
CREATE TABLE sys_cache_outbox(id VARCHAR(64) PRIMARY KEY, event_kind VARCHAR(16) NOT NULL,
 cache_key VARCHAR(255) NOT NULL, cache_version BIGINT NOT NULL, created_at TIMESTAMP NOT NULL,
 next_attempt_at TIMESTAMP NOT NULL, completed_at TIMESTAMP, attempts INT DEFAULT 0 NOT NULL,
 last_error VARCHAR(255), delivery_revision BIGINT DEFAULT 0 NOT NULL, last_failure_id VARCHAR(36));
CREATE TABLE sys_cache_outbox_replay_log(id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(64) NOT NULL,
 actor_user_id VARCHAR(64) NOT NULL, actor_company_id VARCHAR(64) NOT NULL, reason VARCHAR(32) NOT NULL,
 previous_revision BIGINT NOT NULL, new_revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL);
