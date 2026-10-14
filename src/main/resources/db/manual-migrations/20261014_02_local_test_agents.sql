-- One-time MySQL migration for owner-scoped local agent pairing and credentials.
-- Take a backup first. Do not run against production until the target DB/schema is verified.
CREATE TABLE apix_local_test_agents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    credential_hash CHAR(64) NOT NULL,
    allowed_targets_json LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PAIRED',
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_apix_agent_credential_hash (credential_hash),
    KEY idx_apix_agent_user (user_id),
    CONSTRAINT fk_apix_local_agent_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE TABLE apix_local_agent_pairings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    pairing_hash CHAR(64) NOT NULL,
    agent_name VARCHAR(120) NOT NULL,
    allowed_targets_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_apix_pairing_hash (pairing_hash),
    KEY idx_apix_pairing_expiry (expires_at),
    CONSTRAINT fk_apix_local_pairing_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB;

-- Rollback (destructive to agent enrollment): stop APiX, then DROP TABLE
-- apix_local_agent_pairings; DROP TABLE apix_local_test_agents;
