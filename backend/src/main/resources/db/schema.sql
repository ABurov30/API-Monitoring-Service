CREATE TABLE api_data (id uuid PRIMARY KEY, created_at timestamptz NOT NULL, success boolean NOT NULL, payload text NOT NULL);
CREATE INDEX api_data_latest ON api_data (created_at DESC, id DESC);
CREATE TABLE api_outbox (id uuid PRIMARY KEY, data_id uuid NOT NULL, topic varchar(100) NOT NULL, event_payload text NOT NULL, status varchar(20) NOT NULL CHECK(status IN ('PENDING','IN_PROGRESS','SENT','FAILED')), attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL, locked_until timestamptz, lock_token uuid, created_at timestamptz NOT NULL, sent_at timestamptz, last_error text);
CREATE INDEX api_outbox_ready ON api_outbox (status, next_attempt_at, locked_until);
CREATE TABLE users (id uuid PRIMARY KEY, username varchar(100) UNIQUE NOT NULL, password varchar(100) NOT NULL);
CREATE TABLE user_roles (user_id uuid NOT NULL REFERENCES users(id), role varchar(30) NOT NULL, PRIMARY KEY(user_id, role));
CREATE TABLE refresh_tokens (hash varchar(64) PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), expires_at timestamptz NOT NULL, used boolean NOT NULL DEFAULT false, revoked boolean NOT NULL DEFAULT false);
CREATE INDEX refresh_owner ON refresh_tokens(user_id);
CREATE INDEX refresh_expiry ON refresh_tokens(expires_at);
