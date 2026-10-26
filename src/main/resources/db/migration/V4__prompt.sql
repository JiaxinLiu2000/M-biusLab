-- v0.0.0 Prompt library: versioned, modular prompt templates (segments kept as exact text for prefix caching).
CREATE TABLE prompt_template (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_key     VARCHAR(160) NOT NULL,
    version        INT          NOT NULL,
    parent_version INT          NULL,
    segments_json  MEDIUMTEXT   NOT NULL,
    variables_json TEXT         NOT NULL,
    owner_agent_id VARCHAR(80)  NULL,
    invocations    BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by     VARCHAR(80)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by     VARCHAR(80)  NOT NULL,
    row_version    INT          NOT NULL DEFAULT 0,
    deleted        TINYINT(1)   NOT NULL DEFAULT 0,
    deleted_at     DATETIME(3)  NULL,
    deleted_by     VARCHAR(80)  NULL,
    UNIQUE KEY uk_prompt_key_version (prompt_key, version),
    INDEX idx_prompt_owner (owner_agent_id),
    INDEX idx_prompt_status (status, prompt_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
