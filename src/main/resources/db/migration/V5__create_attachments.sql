CREATE TABLE attachments (
    id                  UUID          NOT NULL PRIMARY KEY,
    ticket_id           UUID          NOT NULL,
    comment_id          UUID,
    uploaded_by_id      UUID          NOT NULL,
    original_file_name  VARCHAR(255)  NOT NULL,
    content_type        VARCHAR(127)  NOT NULL,
    size_bytes          BIGINT        NOT NULL,
    storage_key         VARCHAR(512)  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_attachments_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_attachments_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_attachments_uploaded_by FOREIGN KEY (uploaded_by_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_attachments_size_bytes CHECK (size_bytes >= 0)
);

CREATE INDEX idx_attachments_ticket_id ON attachments (ticket_id);
CREATE INDEX idx_attachments_comment_id ON attachments (comment_id);
CREATE INDEX idx_attachments_deleted_at ON attachments (deleted_at);
