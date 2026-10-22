-- Creator owns the current profile fields. Backfill each missing field independently.
-- Retain existing nonblank values and leave blank values unchanged when no candidate exists.
UPDATE creator c
SET profile_url = COALESCE((
    SELECT m.url_profile FROM public_creator_metric m
    WHERE m.creator_id = c.id AND m.url_profile ~ '[^[:space:]]'
    ORDER BY m.collected_at DESC, m.id DESC LIMIT 1
), c.profile_url)
WHERE NOT (COALESCE(c.profile_url, '') ~ '[^[:space:]]');

UPDATE creator c
SET location = COALESCE((
    SELECT m.location FROM public_creator_metric m
    WHERE m.creator_id = c.id AND m.location ~ '[^[:space:]]'
    ORDER BY m.collected_at DESC, m.id DESC LIMIT 1
), c.location)
WHERE NOT (COALESCE(c.location, '') ~ '[^[:space:]]');

-- Explicit destructive cleanup. Audit and all active auth/claim/ownership data remain.
DROP TABLE auth_exchange_code;
DROP TABLE creator_rate_card;
ALTER TABLE public_creator_metric DROP COLUMN url_profile;
ALTER TABLE public_creator_metric DROP COLUMN location;
