-- Initial schema. utf8mb4_0900_ai_ci makes name lookups case- and accent-insensitive.

CREATE TABLE country_info (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    iso_code          VARCHAR(2)   NOT NULL,
    name              VARCHAR(100) NOT NULL,
    capital_city      VARCHAR(100) NULL,
    phone_code        VARCHAR(10)  NULL,
    continent_code    VARCHAR(5)   NULL,
    currency_iso_code VARCHAR(5)   NULL,
    flag_url          VARCHAR(255) NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT pk_country_info PRIMARY KEY (id),
    -- The idempotency guarantee: concurrent creates for the same country cannot both succeed.
    CONSTRAINT uk_country_info_iso_code UNIQUE (iso_code),
    INDEX idx_country_info_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE language (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    iso_code        VARCHAR(10)  NULL,
    name            VARCHAR(100) NOT NULL,
    country_info_id BIGINT       NOT NULL,
    CONSTRAINT pk_language PRIMARY KEY (id),
    CONSTRAINT fk_language_country_info FOREIGN KEY (country_info_id)
        REFERENCES country_info (id) ON DELETE CASCADE,
    INDEX idx_language_country_info_id (country_info_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
