-- v0.0.0 Agent run log; invocation_id is the idempotency key of AgentRunner.

CREATE TABLE agent_run (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    invocation_id VARCHAR(255) NOT NULL,
    agent_id      VARCHAR(80)  NOT NULL,
    genome_id     VARCHAR(80)  NULL,
    trace_id      VARCHAR(80)  NULL,
    status        VARCHAR(16)  NOT NULL,
    input         MEDIUMTEXT   NULL,
    output        MEDIUMTEXT   NULL,
    cost_usd      DOUBLE       NOT NULL DEFAULT 0,
    duration_ms   BIGINT       NOT NULL DEFAULT 0,
    error         VARCHAR(2000) NULL,
    started_at    DATETIME(3)  NOT NULL,
    finished_at   DATETIME(3)  NULL,
    UNIQUE KEY uk_agent_run_invocation (invocation_id),
    INDEX idx_agent_run_agent (agent_id, started_at),
    INDEX idx_agent_run_genome (genome_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
