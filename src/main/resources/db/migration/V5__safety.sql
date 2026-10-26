-- v0.0.5 Safety officer: verdicts (idempotent by proposal id), policy revisions, settings, officer chat.

CREATE TABLE safety_verdict (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    proposal_id   VARCHAR(80)   NOT NULL,
    actor         VARCHAR(80)   NOT NULL,
    kind          VARCHAR(32)   NOT NULL,
    target        VARCHAR(1000) NULL,
    decision      VARCHAR(16)   NOT NULL,
    risk_score    DOUBLE        NOT NULL,
    rule_hits     JSON          NOT NULL,
    reasons       JSON          NOT NULL,
    reviewers     JSON          NOT NULL,
    proposal_json MEDIUMTEXT    NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by    VARCHAR(80)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by    VARCHAR(80)   NOT NULL,
    row_version   INT           NOT NULL DEFAULT 0,
    deleted       TINYINT(1)    NOT NULL DEFAULT 0,
    deleted_at    DATETIME(3)   NULL,
    deleted_by    VARCHAR(80)   NULL,
    UNIQUE KEY uk_safety_verdict_proposal (proposal_id),
    INDEX idx_safety_verdict_keyset (deleted, id),
    INDEX idx_safety_verdict_decision (decision, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE safety_policy_revision (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    version     INT           NOT NULL,
    content     MEDIUMTEXT    NOT NULL,
    content_sha CHAR(64)      NOT NULL,
    reason      VARCHAR(1000) NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by  VARCHAR(80)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by  VARCHAR(80)   NOT NULL,
    row_version INT           NOT NULL DEFAULT 0,
    deleted     TINYINT(1)    NOT NULL DEFAULT 0,
    deleted_at  DATETIME(3)   NULL,
    deleted_by  VARCHAR(80)   NULL,
    UNIQUE KEY uk_safety_policy_version (version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE safety_setting (
    setting_key VARCHAR(80)   NOT NULL PRIMARY KEY,
    value_json  VARCHAR(2000) NOT NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by  VARCHAR(80)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by  VARCHAR(80)   NOT NULL,
    row_version INT           NOT NULL DEFAULT 0,
    deleted     TINYINT(1)    NOT NULL DEFAULT 0,
    deleted_at  DATETIME(3)   NULL,
    deleted_by  VARCHAR(80)   NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE safety_chat_message (
    id              VARCHAR(80)  NOT NULL PRIMARY KEY,
    seq             BIGINT AUTO_INCREMENT NOT NULL UNIQUE,
    role            VARCHAR(16)  NOT NULL,
    content         MEDIUMTEXT   NOT NULL,
    proposed_policy MEDIUMTEXT   NULL,
    proposal_status VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by      VARCHAR(80)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by      VARCHAR(80)  NOT NULL,
    row_version     INT          NOT NULL DEFAULT 0,
    deleted         TINYINT(1)   NOT NULL DEFAULT 0,
    deleted_at      DATETIME(3)  NULL,
    deleted_by      VARCHAR(80)  NULL,
    INDEX idx_safety_chat_seq (deleted, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
