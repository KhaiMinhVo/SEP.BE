-- Existing subscriptions retain catalog terms as of migration time.
ALTER TABLE subscription ADD COLUMN purchased_plan_name varchar(100);
ALTER TABLE subscription ADD COLUMN purchased_price numeric(19,2);
ALTER TABLE subscription ADD COLUMN purchased_currency varchar(3);
ALTER TABLE subscription ADD COLUMN purchased_duration_days integer;
ALTER TABLE subscription ADD COLUMN purchased_campaign_quota integer;
ALTER TABLE subscription ADD COLUMN purchased_recommendation_quota integer;
ALTER TABLE subscription ADD COLUMN purchased_refresh_quota integer;
ALTER TABLE subscription ADD COLUMN purchased_description text;
UPDATE subscription s SET purchased_plan_name = p.plan_name, purchased_price = p.price, purchased_currency = p.currency, purchased_duration_days = p.duration_days, purchased_campaign_quota = p.campaign_quota, purchased_recommendation_quota = p.ai_recommendation_quota, purchased_refresh_quota = p.refresh_quota, purchased_description = p.description FROM plan p WHERE s.plan_id = p.id;
ALTER TABLE subscription ALTER COLUMN purchased_plan_name SET NOT NULL;
ALTER TABLE subscription ALTER COLUMN purchased_price SET NOT NULL;
ALTER TABLE subscription ALTER COLUMN purchased_currency SET NOT NULL;
ALTER TABLE subscription ALTER COLUMN purchased_duration_days SET NOT NULL;
ALTER TABLE subscription ALTER COLUMN purchased_campaign_quota SET NOT NULL;
ALTER TABLE subscription ALTER COLUMN purchased_recommendation_quota SET NOT NULL;
ALTER TABLE subscription ALTER COLUMN purchased_refresh_quota SET NOT NULL;
ALTER TABLE payment ADD COLUMN gateway_create_date varchar(14);
ALTER TABLE payment ADD COLUMN last_reconciled_at timestamp;
CREATE INDEX ix_payment_reconcile ON payment (last_reconciled_at, created_at) WHERE status = 'PENDING' AND payment_gateway = 'VNPAY';
-- Payment retains the original charged price even if the catalog was edited earlier.
UPDATE subscription s SET purchased_price = p.amount, purchased_currency = p.currency
FROM payment p WHERE p.subscription_id = s.id;

-- Manual refund workflow and cancellation at period end.
ALTER TABLE subscription ADD COLUMN cancel_at_period_end boolean NOT NULL DEFAULT false;
ALTER TABLE subscription ADD COLUMN refund_hold boolean NOT NULL DEFAULT false;
ALTER TABLE payment ADD COLUMN refund_status varchar(32) NOT NULL DEFAULT 'NONE';
ALTER TABLE payment ADD COLUMN refund_requested_at timestamp;
ALTER TABLE payment ADD COLUMN refund_approved_at timestamp;
ALTER TABLE payment ADD COLUMN refunded_at timestamp;
ALTER TABLE payment ADD COLUMN refund_reason varchar(1000);
ALTER TABLE payment ADD COLUMN refund_decision_reason varchar(1000);
ALTER TABLE payment ADD COLUMN refund_transfer_reference varchar(255);
ALTER TABLE payment ADD COLUMN refund_exception_type varchar(32);
ALTER TABLE payment ADD COLUMN refund_approved_by uuid;
ALTER TABLE payment ADD COLUMN refund_completed_by uuid;
-- Preserve pre-workflow refunds without inventing a transfer time or reference.
UPDATE payment SET refund_status = 'COMPLETED' WHERE status = 'REFUNDED';
ALTER TABLE payment ADD CONSTRAINT ck_payment_refund_status CHECK (refund_status IN ('NONE','REQUESTED','APPROVED','REJECTED','PROCESSING','GATEWAY_FAILED','COMPLETED'));
ALTER TABLE payment ADD CONSTRAINT ck_payment_refund_consistency CHECK ((status = 'REFUNDED') = (refund_status = 'COMPLETED'));
CREATE UNIQUE INDEX ux_payment_manual_refund_transfer ON payment(payment_gateway, refund_transfer_reference) WHERE refund_transfer_reference IS NOT NULL;

-- Match case-insensitive plan-name validation, including concurrent writes.
CREATE UNIQUE INDEX ux_plan_name_normalized ON plan (lower(btrim(plan_name)));

-- Persist the outbound refund claim before contacting VNPAY. Never resend unknown outcomes.
ALTER TABLE payment ADD COLUMN gateway_refund_request_id varchar(32);
ALTER TABLE payment ADD COLUMN gateway_refund_create_date varchar(14);
ALTER TABLE payment ADD COLUMN gateway_refund_last_checked_at timestamp;
ALTER TABLE payment ADD COLUMN gateway_refund_code varchar(32);
ALTER TABLE payment ADD COLUMN gateway_refund_transaction_no varchar(32);
CREATE UNIQUE INDEX ux_payment_gateway_refund_request ON payment(gateway_refund_request_id) WHERE gateway_refund_request_id IS NOT NULL;
CREATE INDEX ix_payment_refund_reconcile ON payment(gateway_refund_last_checked_at) WHERE refund_status='PROCESSING';
