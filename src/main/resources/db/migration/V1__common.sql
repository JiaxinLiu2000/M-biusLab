-- v0.0.0 Common tables: entity revision history (traceability for every audited entity).
--
-- Audited-table convention (every mutable table MUST include these columns):
--   created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
--   created_by VARCHAR(80) NOT NULL,
--   updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
--   updated_by VARCHAR(80) NOT NULL,
--   row_version INT NOT NULL DEFAULT 0,          -- optimistic locking
--   deleted TINYINT(1) NOT NULL DEFAULT 0,       -- soft delete only; never DELETE rows
--   deleted_at DATETIME(3) NULL,
--   deleted_by VARCHAR(80) NULL
-- and every change MUST also be appended to entity_revision via RevisionLog.

CREATE TABLE entity_revision (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(64)  NOT NULL,
    entity_id   VARCHAR(80)  NOT NULL,
    operation   VARCHAR(32)  NOT NULL,
    actor       VARCHAR(80)  NOT NULL,
    reason      VARCHAR(1000) NULL,
    before_json MEDIUMTEXT   NULL,
    after_json  MEDIUMTEXT   NULL,
    changed_at  DATETIME(3)  NOT NULL,
    INDEX idx_rev_entity (entity_type, entity_id, id),
    INDEX idx_rev_time (changed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
