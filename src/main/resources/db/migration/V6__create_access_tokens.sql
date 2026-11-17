CREATE TABLE access_tokens (
    id          UUID         NOT NULL PRIMARY KEY,
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_access_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX idx_access_tokens_user_id ON access_tokens (user_id);
CREATE INDEX idx_access_tokens_expires_at ON access_tokens (expires_at);
