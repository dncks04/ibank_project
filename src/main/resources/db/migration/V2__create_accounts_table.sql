CREATE TABLE accounts
(
    id             BIGSERIAL      NOT NULL,
    account_number VARCHAR(20)    NOT NULL,
    user_id        BIGINT         NOT NULL,
    balance        NUMERIC(19, 2) NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    created_at     TIMESTAMP      NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_accounts_user_id ON accounts (user_id);
