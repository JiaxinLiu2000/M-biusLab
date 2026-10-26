-- v0.0.0 Virtual lab: registered tools and the call log.

CREATE TABLE lab_tool (
    id             VARCHAR(80)   NOT NULL PRIMARY KEY,
    description    VARCHAR(2000) NOT NULL,
    policy_kind    VARCHAR(16)   NOT NULL,
    permits        INT           NOT NULL,
    origin         VARCHAR(16)   NOT NULL,
    owner_agent_id VARCHAR(80)   NULL,
    spec           JSON          NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by     VARCHAR(80)   NOT NULL,
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by     VARCHAR(80)   NOT NULL,
    row_version    INT           NOT NULL DEFAULT 0,
    deleted        TINYINT(1)    NOT NULL DEFAULT 0,
    deleted_at     DATETIME(3)   NULL,
    deleted_by     VARCHAR(80)   NULL,
    INDEX idx_lab_tool_origin (origin, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lab_call (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id  VARCHAR(120) NOT NULL,
    tool_id     VARCHAR(80)  NOT NULL,
    agent_id    VARCHAR(80)  NULL,
    operation   VARCHAR(80)  NULL,
    args        JSON         NULL,
    status      VARCHAR(16)  NOT NULL,
    result      JSON         NULL,
    log         TEXT         NULL,
    duration_ms BIGINT       NOT NULL DEFAULT 0,
    queued_ms   BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3)  NULL,
    UNIQUE KEY uk_lab_call_request (request_id),
    INDEX idx_lab_call_tool (tool_id, created_at),
    INDEX idx_lab_call_agent (agent_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
