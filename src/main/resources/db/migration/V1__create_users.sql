CREATE TABLE users (
    id          UUID         NOT NULL PRIMARY KEY,
    username    VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX idx_users_deleted_at ON users (deleted_at);

CREATE TABLE user_roles (
    user_id UUID        NOT NULL PRIMARY KEY,
    role    VARCHAR(32) NOT NULL,
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('AGENT', 'MANAGER', 'ADMIN'))
);
