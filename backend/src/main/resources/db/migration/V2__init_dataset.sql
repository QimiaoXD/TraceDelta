CREATE TABLE dataset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    created_at DATETIME NOT NULL
);

CREATE TABLE dataset_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    note VARCHAR(255),
    case_count INT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_dataset_version UNIQUE (dataset_id, version_no)
);

CREATE TABLE dataset_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_version_id BIGINT NOT NULL,
    case_key VARCHAR(128) NOT NULL,
    input_json LONGTEXT NOT NULL,
    messages_json LONGTEXT NULL,
    reference_answer LONGTEXT NULL,
    expected_doc_ids_json LONGTEXT NULL,
    assertions_json LONGTEXT NOT NULL,
    tags_json LONGTEXT NULL,
    case_hash CHAR(64) NOT NULL,
    CONSTRAINT uk_version_case_key UNIQUE (dataset_version_id, case_key)
);
