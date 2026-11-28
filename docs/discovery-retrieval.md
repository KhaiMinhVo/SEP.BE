# Progressive creator discovery

Both `/campaigns/{id}/discover` and `/campaigns/{id}/discovery` share the same pool.

1. Retrieve exact campaign matches first, ordered by hybrid vector/text relevance.
2. If fewer than five distinct creators are available, remove the campaign city preference.
3. If still sparse, widen the campaign follower range by 20%.
4. If still sparse, allow explicitly related subject categories and remove campaign
   follower bounds. Vector similarity can rank eligible creators but cannot bypass
   campaign category/niche filters; never return an unfiltered catalogue.

Each stage adds to the existing pool. Exact matches remain first. Deduplicate by master ID
(or account ID when unlinked), cap the pool at 50, then paginate. Every page uses the same
pool; the requested page size does not change the expansion decision.

Explicit UI filters (follower range, niche, platform, tier, engagement, fee and claim status)
remain mandatory, as do campaign platforms, current snapshot eligibility and data quality.
Discovery accepts the latest `FRESH` or `STALE` snapshot. Age alone does not hide a creator;
rejected, hidden and failed snapshots remain excluded. Responses expose `freshnessStatus`,
`collectedAt` and `observedAt` so clients disclose the age of stored metrics.
For a selected broad category, product text does not become an extra eligibility gate unless
it identifies a supported product subtopic (for example, sunscreen narrows Beauty to skincare).
The frontend must not send inherited campaign follower bounds as explicit UI filters.

Expanded rows disclose `isSimilarFallback`, `expansionReasons` (`LOCATION`, `FOLLOWERS`,
`TOPIC`) and a readable `reason`. `/discovery` reports policy `progressive-v4` and keeps AI
scores null: retrieval similarity is not shortlist Four-Memory/TOPSIS scoring.

`Toàn quốc`/`Vietnam` mean no city restriction; common Vietnamese city aliases are normalized.
When a campaign requests a city, unknown locations are permitted only during expansion
and are labelled accordingly.

## Explicit refresh and subscription credits

Discovery and saving/restoring a shortlist item use stored metrics and never trigger a crawl.
An owner Brand explicitly requests `POST /campaigns/{campaignId}/creators/{creatorId}/refresh`
with a UUID `Idempotency-Key` and `REQUEST_CREATOR_REFRESH`. The backend atomically reserves
one purchased refresh credit from an active subscription. Missing/expired/held subscriptions
and exhausted credits prevent refresh while stored-data discovery and shortlist remain usable.
Retries reuse the request; another request for the same Brand/channel within five minutes
reuses the pending job. Unsupported platforms are rejected before quota is reserved.

The backend commits quota reservation before calling the crawler, without holding a transaction
across the network. Dispatch failures return the credit once; abandoned reservations are released
after ten minutes. Accepted requests consume one credit and return HTTP 202 `QUEUED`, which does
not claim that metrics are already updated. Later crawl failure does not automatically refund an
accepted request. The crawler trigger requires the server service key; never send it from a browser.
Migration V59 persists request identity and dispatch outcome. Deploy the crawler service-key guard
and the matching backend key together before exposing the explicit refresh action.
