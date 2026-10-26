CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS site_login_credential (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    site_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    login_username VARCHAR(128) NOT NULL,
    password_nonce BINARY(12) NOT NULL,
    password_ciphertext VARBINARY(8192) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_site_credential_owner_site_username (owner_user_id, site_code, login_username),
    KEY idx_site_credential_owner (owner_user_id),
    CONSTRAINT fk_site_credential_owner FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS extractor_credential_key (
    owner_user_id BIGINT NOT NULL,
    encryption_key BINARY(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_user_id),
    CONSTRAINT fk_extractor_credential_key_owner FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS site_collection_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    site_credential_id BIGINT NULL,
    site_code VARCHAR(64) NOT NULL,
    run_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP NULL,
    progress_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_collection_owner_site_account_time
        (owner_user_id, site_code, site_credential_id, finished_at),
    CONSTRAINT fk_collection_run_owner FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_collection_run_site_credential FOREIGN KEY (site_credential_id)
        REFERENCES site_login_credential (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
