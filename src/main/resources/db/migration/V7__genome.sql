-- v0.0.0 Agents, genomes (versioned agent definitions) and the reusable module catalog.

CREATE TABLE agent (
    id               VARCHAR(80)  NOT NULL PRIMARY KEY,
    role             VARCHAR(120) NOT NULL,
    kind             VARCHAR(16)  NOT NULL,
    parent_agent_id  VARCHAR(80)  NULL,
    active_genome_id VARCHAR(80)  NULL,
    lifecycle        VARCHAR(16)  NOT NULL,
    display_name     VARCHAR(200) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by  VARCHAR(80) NOT NULL,
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by  VARCHAR(80) NOT NULL,
    row_version INT NOT NULL DEFAULT 0,
    deleted     TINYINT(1) NOT NULL DEFAULT 0,
    deleted_at  DATETIME(3) NULL,
    deleted_by  VARCHAR(80) NULL,
    INDEX idx_agent_list (deleted, created_at, id),
    INDEX idx_agent_parent (parent_agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE genome (
    id            VARCHAR(80)  NOT NULL PRIMARY KEY,
    agent_id      VARCHAR(80)  NOT NULL,
    lineage_id    VARCHAR(80)  NOT NULL,
    parent_id     VARCHAR(80)  NULL,
    generation    INT          NOT NULL,
    role          VARCHAR(120) NOT NULL,
    state         VARCHAR(16)  NOT NULL,
    content_hash  CHAR(64)     NOT NULL,
    body          MEDIUMTEXT   NOT NULL,
    score_summary TEXT         NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by  VARCHAR(80) NOT NULL,
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by  VARCHAR(80) NOT NULL,
    row_version INT NOT NULL DEFAULT 0,
    deleted     TINYINT(1) NOT NULL DEFAULT 0,
    deleted_at  DATETIME(3) NULL,
    deleted_by  VARCHAR(80) NULL,
    UNIQUE KEY uk_genome_agent_hash (agent_id, content_hash),
    INDEX idx_genome_agent_gen (agent_id, generation),
    INDEX idx_genome_agent_state (agent_id, state),
    INDEX idx_genome_lineage (lineage_id, generation)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE module_def (
    module_id        VARCHAR(120) NOT NULL,
    version          INT          NOT NULL,
    kind             VARCHAR(32)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    description      TEXT         NULL,
    config           MEDIUMTEXT   NULL,
    origin_genome_id VARCHAR(80)  NULL,
    reuse_count      BIGINT       NOT NULL DEFAULT 0,
    avg_score        DOUBLE       NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by  VARCHAR(80) NOT NULL,
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by  VARCHAR(80) NOT NULL,
    row_version INT NOT NULL DEFAULT 0,
    deleted     TINYINT(1) NOT NULL DEFAULT 0,
    deleted_at  DATETIME(3) NULL,
    deleted_by  VARCHAR(80) NULL,
    PRIMARY KEY (module_id, version),
    INDEX idx_module_kind (kind, deleted, reuse_count)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
