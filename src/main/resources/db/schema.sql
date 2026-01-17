-- Schema for TransactionServicePilot (MySQL)

CREATE TABLE IF NOT EXISTS accounts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_number VARCHAR(64) NOT NULL UNIQUE,
  currency VARCHAR(3) NOT NULL,
  balance DECIMAL(19,4) NOT NULL DEFAULT 0,
  available_balance DECIMAL(19,4) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(16) DEFAULT 'ACTIVE',
  created_by VARCHAR(64),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64),
  updated_at TIMESTAMP NULL,
  INDEX idx_accounts_account_number (account_number)
);

CREATE TABLE IF NOT EXISTS transactions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tx_id VARCHAR(128) NOT NULL UNIQUE,
  account_id BIGINT NOT NULL,
  type VARCHAR(16) NOT NULL,
  amount DECIMAL(19,4) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  processed_at TIMESTAMP NULL,
  error TEXT,
  retry_count INT DEFAULT 0,
  INDEX idx_transactions_account_id (account_id),
  INDEX idx_transactions_tx_id (tx_id),
  FOREIGN KEY (account_id) REFERENCES accounts(id)
);
