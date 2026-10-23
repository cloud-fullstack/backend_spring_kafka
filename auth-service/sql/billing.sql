-- Incluso in omero_db_setup.sql. DEV: omero_db. PROD Spring: telemaco_db.

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS lemon_subscription_updated_at TIMESTAMPTZ;

ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS lemon_variant_id VARCHAR(64);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS reseller_commission_percent NUMERIC(5, 2);

CREATE UNIQUE INDEX IF NOT EXISTS ux_subscription_plans_lemon_variant_id
    ON subscription_plans (lemon_variant_id)
    WHERE lemon_variant_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS lemon_webhook_events (
    event_id VARCHAR(255) PRIMARY KEY,
    event_name VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS billing_commissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reseller_id UUID NOT NULL,
    tenant_id UUID,
    user_id UUID,
    plan_code VARCHAR(50),
    amount NUMERIC(10, 2) NOT NULL,
    commission_percent NUMERIC(5, 2) NOT NULL,
    lemon_order_id VARCHAR(255),
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_commissions_lemon_order_id
    ON billing_commissions (lemon_order_id)
    WHERE lemon_order_id IS NOT NULL;
