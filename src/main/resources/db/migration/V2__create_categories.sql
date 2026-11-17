CREATE TABLE categories (
    id           UUID         NOT NULL PRIMARY KEY,
    name         VARCHAR(120) NOT NULL UNIQUE,
    slug         VARCHAR(140) NOT NULL UNIQUE,
    description  TEXT,
    is_archived  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
