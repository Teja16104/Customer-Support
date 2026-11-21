CREATE DATABASE IF NOT EXISTS smart_support
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_as_cs;

USE smart_support;
SET SESSION time_zone = '+00:00';

CREATE TABLE users (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role ENUM('CUSTOMER','AGENT','ADMIN') NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    routing_available BOOLEAN NOT NULL DEFAULT FALSE,
    max_active_tickets SMALLINT UNSIGNED NOT NULL DEFAULT 12,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_users_email (email),
    CONSTRAINT chk_users_capacity CHECK (max_active_tickets BETWEEN 1 AND 100),
    CONSTRAINT chk_users_routing_role CHECK (NOT routing_available OR role = 'AGENT')
) ENGINE=InnoDB;

CREATE TABLE categories (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    triage_keywords JSON NOT NULL,
    resolution_playbook TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_categories_code (code),
    CONSTRAINT chk_categories_keywords CHECK (JSON_TYPE(triage_keywords) = 'ARRAY')
) ENGINE=InnoDB;

CREATE TABLE agent_categories (
    agent_id BIGINT UNSIGNED NOT NULL,
    category_id BIGINT UNSIGNED NOT NULL,
    proficiency TINYINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (agent_id, category_id),
    KEY ix_agent_categories_category (category_id, proficiency, agent_id),
    CONSTRAINT fk_agent_categories_agent FOREIGN KEY (agent_id) REFERENCES users(id),
    CONSTRAINT fk_agent_categories_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT chk_agent_categories_proficiency CHECK (proficiency BETWEEN 1 AND 3)
) ENGINE=InnoDB;

CREATE TABLE tickets (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    ticket_key CHAR(30) NOT NULL,
    customer_id BIGINT UNSIGNED NOT NULL,
    category_id BIGINT UNSIGNED NOT NULL,
    submission_key BINARY(16) NOT NULL,
    submission_hash BINARY(32) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    status ENUM('OPEN','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED') NOT NULL DEFAULT 'OPEN',
    priority ENUM('LOW','MEDIUM','HIGH','URGENT') NOT NULL DEFAULT 'MEDIUM',
    sentiment ENUM('POSITIVE','NEUTRAL','NEGATIVE','MIXED','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    triage_status ENUM('PENDING','COMPLETE','FALLBACK','SKIPPED') NOT NULL DEFAULT 'PENDING',
    category_overridden BOOLEAN NOT NULL DEFAULT FALSE,
    priority_overridden BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_summary TEXT NULL,
    sla_cycle INT UNSIGNED NOT NULL DEFAULT 1,
    sla_started_at DATETIME(6) NOT NULL,
    first_response_due_at DATETIME(6) NOT NULL,
    resolution_due_at DATETIME(6) NOT NULL,
    first_response_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_tickets_key (ticket_key),
    UNIQUE KEY uq_tickets_submission (customer_id, submission_key),
    KEY ix_tickets_customer (customer_id, created_at, id),
    KEY ix_tickets_queue (status, priority, created_at, id),
    KEY ix_tickets_category (category_id, status, created_at, id),
    KEY ix_tickets_created (created_at, id),
    KEY ix_tickets_resolution_due (status, resolution_due_at, id),
    KEY ix_tickets_response_due (status, first_response_at, first_response_due_at, id),
    FULLTEXT KEY ft_tickets_complaint (subject, description),
    CONSTRAINT fk_tickets_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_tickets_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT chk_tickets_cycle CHECK (sla_cycle >= 1),
    CONSTRAINT chk_tickets_deadlines CHECK (
        first_response_due_at >= sla_started_at
        AND resolution_due_at >= first_response_due_at
    ),
    CONSTRAINT chk_tickets_resolution CHECK (
        (status IN ('RESOLVED','CLOSED') AND resolved_at IS NOT NULL AND resolution_summary IS NOT NULL)
        OR (status IN ('OPEN','IN_PROGRESS','WAITING_CUSTOMER') AND resolved_at IS NULL AND resolution_summary IS NULL)
    ),
    CONSTRAINT chk_tickets_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE ticket_assignments (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT UNSIGNED NOT NULL,
    agent_id BIGINT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NULL,
    source ENUM('AUTO','MANUAL') NOT NULL,
    reason VARCHAR(255) NOT NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at DATETIME(6) NULL,
    active_ticket_id BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN ended_at IS NULL THEN ticket_id ELSE NULL END
    ) STORED,
    UNIQUE KEY uq_assignments_active (active_ticket_id),
    KEY ix_assignments_ticket_history (ticket_id, assigned_at, id),
    KEY ix_assignments_agent (agent_id, ended_at, ticket_id),
    CONSTRAINT fk_assignments_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_assignments_agent FOREIGN KEY (agent_id) REFERENCES users(id),
    CONSTRAINT fk_assignments_actor FOREIGN KEY (assigned_by) REFERENCES users(id),
    CONSTRAINT chk_assignments_dates CHECK (ended_at IS NULL OR ended_at >= assigned_at),
    CONSTRAINT chk_assignments_source CHECK ((source = 'AUTO') = (assigned_by IS NULL))
) ENGINE=InnoDB;

CREATE TABLE ticket_comments (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT UNSIGNED NOT NULL,
    author_id BIGINT UNSIGNED NOT NULL,
    visibility ENUM('PUBLIC','INTERNAL') NOT NULL DEFAULT 'PUBLIC',
    body TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_comments_ticket (ticket_id, created_at, id),
    KEY ix_comments_visibility (ticket_id, visibility, created_at, id),
    CONSTRAINT fk_comments_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE ticket_events (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT UNSIGNED NOT NULL,
    actor_id BIGINT UNSIGNED NULL,
    sla_cycle INT UNSIGNED NOT NULL,
    event_type ENUM('CREATED','STATUS_CHANGED','CATEGORY_CHANGED','PRIORITY_CHANGED',
                    'ASSIGNMENT_CHANGED','FIRST_RESPONSE','SLA_ALERT','TRIAGE_COMPLETED') NOT NULL,
    from_status ENUM('OPEN','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED') NULL,
    to_status ENUM('OPEN','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED') NULL,
    details JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_events_timeline (ticket_id, created_at, id),
    KEY ix_events_metrics (event_type, to_status, created_at, ticket_id, sla_cycle),
    CONSTRAINT fk_events_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_events_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT chk_events_cycle CHECK (sla_cycle >= 1),
    CONSTRAINT chk_events_status CHECK (
        (event_type = 'STATUS_CHANGED' AND from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status)
        OR (event_type = 'CREATED' AND from_status IS NULL AND to_status IS NOT NULL AND to_status = 'OPEN')
        OR (event_type NOT IN ('STATUS_CHANGED','CREATED') AND from_status IS NULL AND to_status IS NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE ticket_feedback (
    ticket_id BIGINT UNSIGNED PRIMARY KEY,
    rating TINYINT UNSIGNED NOT NULL,
    comment VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_feedback_created (created_at, ticket_id),
    CONSTRAINT fk_feedback_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB;

CREATE TABLE ai_jobs (
    ticket_id BIGINT UNSIGNED PRIMARY KEY,
    status ENUM('QUEUED','RUNNING','DONE','OBSOLETE') NOT NULL DEFAULT 'QUEUED',
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY ix_ai_jobs_claim (status, available_at, ticket_id),
    KEY ix_ai_jobs_reclaim (status, lease_until, ticket_id),
    CONSTRAINT fk_ai_jobs_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT chk_ai_jobs_lease CHECK (
        (status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE ai_runs (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT UNSIGNED NOT NULL,
    requested_by BIGINT UNSIGNED NULL,
    feature ENUM('CLASSIFICATION','SENTIMENT','PRIORITY','RESOLUTION','RESPONSE') NOT NULL,
    source ENUM('MODEL','FALLBACK') NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    model_name VARCHAR(100) NULL,
    input_ticket_version BIGINT UNSIGNED NOT NULL,
    fallback_reason ENUM('TIMEOUT','RATE_LIMIT','UNAVAILABLE','INVALID_OUTPUT','DISABLED') NULL,
    latency_ms INT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_ai_runs_ticket (ticket_id, created_at, id),
    KEY ix_ai_runs_metrics (created_at, feature, source),
    CONSTRAINT fk_ai_runs_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_ai_runs_actor FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT chk_ai_runs_source CHECK (
        (source = 'MODEL' AND model_name IS NOT NULL AND fallback_reason IS NULL)
        OR (source = 'FALLBACK' AND fallback_reason IS NOT NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE sla_alerts (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT UNSIGNED NOT NULL,
    sla_cycle INT UNSIGNED NOT NULL,
    alert_type ENUM('URGENT','NEGATIVE_HIGH','FIRST_RESPONSE_DUE_SOON','FIRST_RESPONSE_BREACHED',
                    'RESOLUTION_DUE_SOON','RESOLUTION_BREACHED') NOT NULL,
    acknowledged_by BIGINT UNSIGNED NULL,
    acknowledged_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alerts_ticket_cycle_type (ticket_id, sla_cycle, alert_type),
    KEY ix_alerts_inbox (acknowledged_at, created_at, id),
    CONSTRAINT fk_alerts_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_alerts_actor FOREIGN KEY (acknowledged_by) REFERENCES users(id),
    CONSTRAINT chk_alerts_cycle CHECK (sla_cycle >= 1),
    CONSTRAINT chk_alerts_ack CHECK (
        (acknowledged_by IS NULL AND acknowledged_at IS NULL)
        OR (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL)
    )
) ENGINE=InnoDB;