ALTER TABLE users ADD COLUMN username_lower VARCHAR(120) GENERATED ALWAYS AS (LOWER(username)) ${generated_stored};
CREATE UNIQUE INDEX idx_users_username_lower ON users (username_lower);
