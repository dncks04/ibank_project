CREATE TABLE users
(
    id         BIGSERIAL    NOT NULL,
    login_id   VARCHAR(20)  NOT NULL,
    password   VARCHAR(255) NOT NULL,
    name       VARCHAR(30)  NOT NULL,
    email      VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_login_id UNIQUE (login_id),
    CONSTRAINT uq_users_email UNIQUE (email)
);
