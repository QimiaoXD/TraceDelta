ALTER TABLE eval_run MODIFY COLUMN provider_id BIGINT NULL;
ALTER TABLE eval_run ADD COLUMN http_target_id BIGINT NULL;
ALTER TABLE eval_run ADD COLUMN trace_incomplete BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE eval_run ADD COLUMN trace_dropped_count INT NOT NULL DEFAULT 0;

ALTER TABLE eval_result ADD COLUMN trace_id CHAR(32) NULL;
CREATE INDEX idx_eval_result_trace_id ON eval_result (trace_id);

CREATE TABLE http_target (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    url VARCHAR(1024) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    headers_ciphertext LONGTEXT,
    headers_masked_json TEXT,
    body_template LONGTEXT,
    response_json_path VARCHAR(255),
    default_timeout_ms BIGINT NOT NULL DEFAULT 30000,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_http_target_name UNIQUE (name)
);

CREATE TABLE trace_span (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id CHAR(32) NOT NULL,
    span_id CHAR(16) NOT NULL,
    parent_span_id CHAR(16),
    run_id BIGINT NOT NULL,
    eval_result_id BIGINT NOT NULL,
    case_key VARCHAR(128) NOT NULL,
    attempt_no INT NOT NULL DEFAULT 0,
    span_name VARCHAR(128) NOT NULL,
    span_kind VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    start_time_ms BIGINT NOT NULL,
    end_time_ms BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL,
    ttft_ms BIGINT,
    input_tokens INT,
    output_tokens INT,
    estimated_cost_usd DECIMAL(14, 6),
    input_payload LONGTEXT,
    output_payload LONGTEXT,
    attributes_json LONGTEXT,
    error_type VARCHAR(64),
    error_message VARCHAR(1000),
    created_at DATETIME NOT NULL
);

CREATE INDEX idx_trace_span_trace_time ON trace_span (trace_id, start_time_ms);
CREATE INDEX idx_trace_span_run ON trace_span (run_id);
CREATE INDEX idx_trace_span_result ON trace_span (eval_result_id, attempt_no, start_time_ms);
