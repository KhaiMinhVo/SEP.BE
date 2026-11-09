ALTER TABLE cooperation_proposal ADD COLUMN kpi_targets jsonb NOT NULL DEFAULT '[]';
ALTER TABLE collaboration ADD COLUMN learning_context jsonb;
ALTER TABLE historical_campaign ADD COLUMN learning_snapshot jsonb;
ALTER TABLE brand_pattern ADD COLUMN cohort_key varchar(300);
CREATE UNIQUE INDEX ux_brand_bayes_cohort ON brand_pattern(brand_profile_id,pattern_type,cohort_key)
 WHERE pattern_type='BRAND_FIT_BAYES_V2';
ALTER TABLE recommendation_run ADD COLUMN scoring_policy_parameters jsonb;
CREATE TABLE recommendation_scoring_evidence (
 run_id uuid NOT NULL REFERENCES recommendation_run(id),
 creator_id uuid NOT NULL REFERENCES creator(id),
 evidence_id uuid NOT NULL REFERENCES historical_campaign(id),
 component varchar(10) NOT NULL CHECK(component IN ('M1_R','M1_T','M1_K','M4')),
 PRIMARY KEY(run_id,creator_id,evidence_id,component)
);
CREATE INDEX idx_learning_history ON historical_campaign(master_id,recorded_at,id)
 WHERE learning_snapshot IS NOT NULL;
CREATE INDEX idx_learning_history_channel ON historical_campaign(creator_id,recorded_at,id)
 WHERE learning_snapshot IS NOT NULL;
CREATE FUNCTION immutable_learning_snapshots() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF TG_TABLE_NAME='collaboration' THEN
   IF OLD.learning_context IS NOT NULL THEN RAISE EXCEPTION 'Learning agreement snapshot is immutable'; END IF;
  ELSE
   IF OLD.learning_snapshot IS NOT NULL THEN RAISE EXCEPTION 'Learning evidence snapshot is immutable'; END IF;
  END IF;
  RETURN OLD;
 END IF;
 IF TG_TABLE_NAME='collaboration' THEN
  IF OLD.learning_context IS NOT NULL AND NEW.learning_context IS DISTINCT FROM OLD.learning_context THEN
   RAISE EXCEPTION 'Learning agreement snapshot is immutable';
  END IF;
 ELSE
  IF OLD.learning_snapshot IS NOT NULL AND NEW.learning_snapshot IS DISTINCT FROM OLD.learning_snapshot THEN
   RAISE EXCEPTION 'Learning evidence snapshot is immutable';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER freeze_learning_context BEFORE UPDATE OR DELETE ON collaboration
 FOR EACH ROW EXECUTE FUNCTION immutable_learning_snapshots();
CREATE TRIGGER freeze_learning_evidence BEFORE UPDATE OR DELETE ON historical_campaign
 FOR EACH ROW EXECUTE FUNCTION immutable_learning_snapshots();
