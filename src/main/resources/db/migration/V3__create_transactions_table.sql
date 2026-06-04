CREATE TABLE transactions
(
    id               BIGSERIAL      NOT NULL,
    idempotency_key  VARCHAR(36)    NOT NULL,
    from_account_id  BIGINT,
    to_account_id    BIGINT,
    amount           NUMERIC(19, 2) NOT NULL,
    type             VARCHAR(20)    NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    description      VARCHAR(200),
    created_at       TIMESTAMP      NOT NULL,

    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_transactions_from_account FOREIGN KEY (from_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transactions_to_account FOREIGN KEY (to_account_id) REFERENCES accounts (id)
);

CREATE INDEX idx_transaction_from_account ON transactions (from_account_id);
CREATE INDEX idx_transaction_to_account ON transactions (to_account_id);
