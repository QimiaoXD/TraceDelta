CREATE TABLE eval_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    run_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    status_reason VARCHAR(255),
    dataset_id BIGINT NOT NULL,
    dataset_version_id BIGINT NOT NULL,
    dataset_name_snapshot VARCHAR(128) NOT NULL,
    dataset_version_no INT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    provider_id BIGINT NOT NULL,
    provider_name_snapshot VARCHAR(128) NOT NULL,
    model_snapshot VARCHAR(128) NOT NULL,
    target_config_snapshot_json LONGTEXT NOT NULL,
    concurrency INT NOT NULL,
    max_attempts INT NOT NULL,
    request_timeout_ms BIGINT NOT NULL,
    rate_limit_per_sec DOUBLE NOT NULL,
    rate_limit_burst INT NOT NULL,
    max_cost_usd DECIMAL(14, 6),
    max_total_tokens BIGINT,
    retry_base_delay_ms BIGINT NOT NULL,
    retry_max_delay_ms BIGINT NOT NULL,
    total_cases INT NOT NULL,
    completed_cases INT NOT NULL DEFAULT 0,
    succeeded_cases INT NOT NULL DEFAULT 0,
    failed_cases INT NOT NULL DEFAULT 0,
    skipped_cases INT NOT NULL DEFAULT 0,
    total_input_tokens BIGINT NOT NULL DEFAULT 0,
    total_output_tokens BIGINT NOT NULL DEFAULT 0,
    total_cost_usd DECIMAL(14, 6) NOT NULL DEFAULT 0,
    avg_latency_ms BIGINT,
    p95_latency_ms BIGINT,
    budget_exceeded BOOLEAN NOT NULL DEFAULT FALSE,
    budget_exceeded_reason VARCHAR(64),
    interrupted_attempt_count INT NOT NULL DEFAULT 0,
    rate_limit_degraded BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    started_at DATETIME,
    finished_at DATETIME,
    updated_at DATETIME NOT NULL
);

CREATE INDEX idx_eval_run_status ON eval_run (status);
CREATE INDEX idx_eval_run_created ON eval_run (created_at);

CREATE TABLE eval_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    dataset_case_id BIGINT,
    case_key VARCHAR(128) NOT NULL,
    case_hash CHAR(64) NOT NULL,
    case_snapshot_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    score_conclusion VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    has_interrupted_unknown BOOLEAN NOT NULL DEFAULT FALSE,
    output_text LONGTEXT,
    latency_ms BIGINT,
    ttft_ms BIGINT,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    estimated_cost_usd DECIMAL(14, 6) NOT NULL DEFAULT 0,
    error_type VARCHAR(64),
    error_message VARCHAR(1000),
    version INT NOT NULL DEFAULT 0,
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_eval_result_run_case UNIQUE (run_id, case_key)
);

CREATE INDEX idx_eval_result_run_status ON eval_result (run_id, status);

CREATE TABLE eval_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    eval_result_id BIGINT NOT NULL,
    case_key VARCHAR(128) NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    latency_ms BIGINT,
    ttft_ms BIGINT,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    estimated_cost_usd DECIMAL(14, 6) NOT NULL DEFAULT 0,
    error_type VARCHAR(64),
    error_message VARCHAR(1000),
    started_at DATETIME NOT NULL,
    finished_at DATETIME,
    CONSTRAINT uk_eval_attempt_result_no UNIQUE (eval_result_id, attempt_no)
);

CREATE INDEX idx_eval_attempt_status ON eval_attempt (status);
CREATE INDEX idx_eval_attempt_run ON eval_attempt (run_id);
