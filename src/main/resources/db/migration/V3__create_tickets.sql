CREATE TABLE tickets (
    id                       UUID         NOT NULL PRIMARY KEY,
    title                    VARCHAR(200) NOT NULL,
    description              TEXT,
    urgency                  VARCHAR(32)  NOT NULL,
    deadline                 TIMESTAMP WITH TIME ZONE,
    status                   VARCHAR(32)  NOT NULL,
    category_id              UUID         NOT NULL,
    requester_id             UUID         NOT NULL,
    manager_id               UUID,
    agent_id                 UUID,
    completed_by_agent_at    TIMESTAMP WITH TIME ZONE,
    completed_by_manager_at  TIMESTAMP WITH TIME ZONE,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at               TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_tickets_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_requester FOREIGN KEY (requester_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_manager FOREIGN KEY (manager_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_agent FOREIGN KEY (agent_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tickets_urgency CHECK (urgency IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_tickets_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX idx_tickets_status ON tickets (status);
CREATE INDEX idx_tickets_urgency ON tickets (urgency);
CREATE INDEX idx_tickets_deleted_at ON tickets (deleted_at);
CREATE INDEX idx_tickets_requester_id ON tickets (requester_id);
CREATE INDEX idx_tickets_agent_id ON tickets (agent_id);
CREATE INDEX idx_tickets_manager_id ON tickets (manager_id);
CREATE INDEX idx_tickets_category_id ON tickets (category_id);
