CREATE
DATABASE IF NOT EXISTS safepay
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE
safepay;

-- 회원 (member)
CREATE TABLE member
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    email      VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL COMMENT 'BCrypt hashed',
    name       VARCHAR(50)  NOT NULL,
    phone      VARCHAR(255) NOT NULL COMMENT 'AES-256 encrypted',
    role       ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER',
    created_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    INDEX      idx_member_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 계좌 (account)
CREATE TABLE account
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id           BIGINT         NOT NULL,
    account_number      VARCHAR(255)   NOT NULL COMMENT 'AES-256-GCM encrypted (random IV)',
    account_number_hash VARCHAR(64)    NOT NULL COMMENT 'HMAC-SHA-256 blind index for uniqueness',
    balance             DECIMAL(15, 2) NOT NULL DEFAULT 0.00,
    account_type        ENUM('CHECKING', 'SAVINGS') NOT NULL,
    status              ENUM('ACTIVE', 'FROZEN', 'CLOSED') NOT NULL DEFAULT 'ACTIVE',
    version             BIGINT         NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_account_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
    UNIQUE INDEX uk_account_number_hash (account_number_hash),
    INDEX               idx_account_member_id (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 거래 내역 (transaction)
CREATE TABLE transaction
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id      BIGINT         NOT NULL,
    type            ENUM('DEPOSIT', 'WITHDRAW', 'TRANSFER_IN', 'TRANSFER_OUT') NOT NULL,
    amount          DECIMAL(15, 2) NOT NULL,
    balance_after   DECIMAL(15, 2) NOT NULL,
    description     VARCHAR(255) NULL,
    idempotency_key VARCHAR(36)    NOT NULL COMMENT 'UUID v4 for idempotency',
    status          ENUM('SUCCESS', 'FAILED', 'PENDING') NOT NULL DEFAULT 'SUCCESS',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    INDEX           idx_transaction_account_id (account_id),
    INDEX           idx_transaction_created_at (created_at),
    UNIQUE INDEX uk_account_idempotency (account_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
