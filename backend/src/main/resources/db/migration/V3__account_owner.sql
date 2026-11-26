-- Accounts belong to a Keycloak user: owner_id = the token's `sub` claim.
-- Accounts opened before authentication existed keep NULL: only OPS can read
-- them, and no one can send money from them through the API.
ALTER TABLE accounts ADD COLUMN owner_id VARCHAR(64);
CREATE INDEX idx_accounts_owner ON accounts (owner_id, created_at);
