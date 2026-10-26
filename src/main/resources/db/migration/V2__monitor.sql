-- v0.0.0 Monitor: append-only event log of everything agents, nodes and modules do.

CREATE TABLE monitor_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id       VARCHAR(40)   NOT NULL,
    ts             DATETIME(3)   NOT NULL,
    trace_id       VARCHAR(40)   NULL,
    span_id        VARCHAR(40)   NULL,
    parent_span_id VARCHAR(40)   NULL,
    source_kind    VARCHAR(24)   NOT NULL,
    source_id      VARCHAR(160)  NOT NULL,
    agent_id       VARCHAR(80)   NULL,
    event_type     VARCHAR(32)   NOT NULL,
    target_id      VARCHAR(160)  NULL,
    status         VARCHAR(16)   NOT NULL,
    latency_ms     BIGINT        NOT NULL DEFAULT 0,
    bytes          BIGINT        NOT NULL DEFAULT 0,
    summary        VARCHAR(1000) NULL,
    payload        MEDIUMTEXT    NULL,
    UNIQUE KEY uk_monitor_event (event_id),
    INDEX idx_monitor_ts (ts),
    INDEX idx_monitor_agent_ts (agent_id, ts),
    INDEX idx_monitor_trace (trace_id, id),
    INDEX idx_monitor_source_ts (source_id, ts)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
