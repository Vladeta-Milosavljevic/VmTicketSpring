CREATE TABLE comments (
    id           UUID    NOT NULL PRIMARY KEY,
    ticket_id    UUID    NOT NULL,
    user_id      UUID    NOT NULL,
    body         TEXT    NOT NULL,
    is_internal  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_comments_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX idx_comments_ticket_id_created_at ON comments (ticket_id, created_at);
