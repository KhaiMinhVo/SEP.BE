-- Crawler history can contain percentages and already-normalized ratios.
-- Derive the ratio from observed counts so a repaired/new snapshot is never divided twice.
-- Incomplete counts cannot establish a unit: leave those rows for review/recrawl.
UPDATE public_creator_metric
SET engagement_rate = ROUND((avg_likes + avg_comments + avg_shares) / NULLIF(followers, 0), 4)
WHERE crawl_run_id IS NOT NULL
  AND followers > 0
  AND avg_likes >= 0
  AND avg_comments >= 0
  AND avg_shares >= 0
  AND (avg_likes + avg_comments + avg_shares) / NULLIF(followers, 0) <= 999.9999;
