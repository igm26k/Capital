CREATE TABLE auth_challenges (
 id uuid PRIMARY KEY, email text NOT NULL, purpose text NOT NULL CHECK(purpose IN ('registration','recovery')),
 token_hash bytea NOT NULL CHECK(octet_length(token_hash)=32), password_hash text, timezone text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), expires_at timestamptz NOT NULL,
 UNIQUE(email,purpose), CHECK(expires_at>created_at),
 CHECK((purpose='registration' AND password_hash IS NOT NULL AND timezone IS NOT NULL) OR (purpose='recovery' AND password_hash IS NULL AND timezone IS NULL))
);
CREATE TABLE auth_mail (
 challenge_id uuid PRIMARY KEY REFERENCES auth_challenges(id) ON DELETE CASCADE,
 encrypted_body bytea, attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 available_at timestamptz NOT NULL DEFAULT clock_timestamp(), sent_at timestamptz,
 CHECK(sent_at IS NULL OR encrypted_body IS NULL)
);
CREATE INDEX auth_mail_queue ON auth_mail(available_at) WHERE sent_at IS NULL AND attempts<5;
