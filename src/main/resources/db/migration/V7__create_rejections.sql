CREATE TABLE rejections (
    id          UUID                     NOT NULL PRIMARY KEY,
    ticket_id   UUID                     NOT NULL,
    agent_id    UUID,
    manager_id  UUID                     NOT NULL,
    reason      TEXT                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_rejections_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rejections_agent FOREIGN KEY (agent_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rejections_manager FOREIGN KEY (manager_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_rejections_reason CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX idx_rejections_ticket_id ON rejections (ticket_id);
CREATE INDEX idx_rejections_agent_id ON rejections (agent_id);
CREATE INDEX idx_rejections_manager_id ON rejections (manager_id);
