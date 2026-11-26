CREATE TABLE provider_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(255),
    api_key_ciphertext VARCHAR(1024),
    api_key_mask VARCHAR(64),
    default_model VARCHAR(128) NOT NULL,
    pricing_json LONGTEXT,
    mock_config_json LONGTEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
