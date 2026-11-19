# Progressive creator discovery

Both `/campaigns/{id}/discover` and `/campaigns/{id}/discovery` share the same pool.

1. Retrieve exact campaign matches first, ordered by hybrid vector/text relevance.
2. If fewer than five distinct creators are available, remove the campaign city preference.
3. If still sparse, widen the campaign follower range by 20%.
4. If still sparse, allow related subject categories or cosine similarity of at least 0.5,
   and remove campaign follower bounds. Without embeddings, use related observed categories
   or product text evidence; never return an unfiltered catalogue.

Each stage adds to the existing pool. Exact matches remain first. Deduplicate by master ID
(or account ID when unlinked), cap the pool at 50, then paginate. Every page uses the same
pool; the requested page size does not change the expansion decision.

Explicit UI filters (follower range, niche, platform, tier, engagement, fee and claim status)
remain mandatory, as do campaign platforms, current snapshot eligibility and data quality.
The frontend must not send inherited campaign follower bounds as explicit UI filters.

Expanded rows disclose `isSimilarFallback`, `expansionReasons` (`LOCATION`, `FOLLOWERS`,
`TOPIC`) and a readable `reason`. `/discovery` reports policy `progressive-v3` and keeps AI
scores null: retrieval similarity is not shortlist Four-Memory/TOPSIS scoring.

`Toàn quốc`/`Vietnam` mean no city restriction; common Vietnamese city aliases are normalized.
When a campaign requests a city, unknown locations are permitted only during expansion
and are labelled accordingly.
