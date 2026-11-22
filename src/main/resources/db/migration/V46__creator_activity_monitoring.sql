ALTER TABLE creator ADD COLUMN discovery_hidden boolean NOT NULL DEFAULT false;

CREATE TABLE creator_visibility_audit (
    id uuid PRIMARY KEY,
    creator_id uuid NOT NULL REFERENCES creator(id),
    actor_id uuid NOT NULL REFERENCES "user"(id),
    hidden boolean NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamp NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
);
CREATE INDEX idx_creator_visibility_audit ON creator_visibility_audit(creator_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_creator_activity ON post(creator_id);

-- Crawler dates can be ISO dates/timestamps or Unix seconds/milliseconds.
-- Never cast arbitrary crawler strings without handling invalid input.
CREATE FUNCTION im_post_timestamp(value text) RETURNS timestamp
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
BEGIN
    IF btrim(value) ~ '^\d{10}(\.\d+)?$' THEN
        RETURN to_timestamp(btrim(value)::double precision) AT TIME ZONE 'UTC';
    ELSIF btrim(value) ~ '^\d{13}$' THEN
        RETURN to_timestamp(btrim(value)::double precision / 1000) AT TIME ZONE 'UTC';
    ELSIF btrim(value) ~ '^\d{4}-\d{2}-\d{2}($|[T ])' THEN
        IF btrim(value) ~ '(Z|[+-]\d{2}:?\d{2})$' THEN
            RETURN (btrim(value)::timestamp with time zone AT TIME ZONE 'UTC');
        END IF;
        RETURN btrim(value)::timestamp;
    END IF;
    RETURN NULL;
EXCEPTION WHEN datetime_field_overflow OR invalid_datetime_format OR numeric_value_out_of_range THEN
    RETURN NULL;
END;
$$;
