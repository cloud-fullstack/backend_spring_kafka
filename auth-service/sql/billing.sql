-- Incluso in omero_db_setup.sql.
-- Stesso schema Stripe/Lemon su DEV (omero_db) e PROD (telemaco_db) finché non si separano.
-- Idempotente: applicabile su entrambi (CREATE/ALTER IF NOT EXISTS).

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

-- Stripe Managed Payments (crediti). Stesso blocco in omero_db_setup.sql sezione 10.
CREATE TABLE IF NOT EXISTS billing_accounts (
    tenant_id                      UUID PRIMARY KEY REFERENCES tenants(id),
    balance_cents                  BIGINT NOT NULL DEFAULT 0,
    spend_month_cents              BIGINT NOT NULL DEFAULT 0,
    spend_month                    VARCHAR(7),
    stripe_customer_id             VARCHAR(255),
    card_last4                     VARCHAR(4),
    auto_recharge                  BOOLEAN NOT NULL DEFAULT false,
    auto_recharge_threshold_cents  BIGINT,
    auto_recharge_amount_cents     BIGINT,
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS stripe_webhook_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    event_name  VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS credit_purchases (
    checkout_session_id VARCHAR(255) PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    payment_intent_id   VARCHAR(255) UNIQUE,
    credit_cents        BIGINT NOT NULL,
    amount_total_cents  BIGINT NOT NULL DEFAULT 0,
    reversed_cents      BIGINT NOT NULL DEFAULT 0,
    status              VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE credit_purchases ADD COLUMN IF NOT EXISTS amount_total_cents BIGINT NOT NULL DEFAULT 0;

ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS stripe_price_id VARCHAR(255);

-- Prezzi IVA esclusa. Gold/Platinum coming_soon: non si vendono (telefonia).
UPDATE subscription_plans SET price = 29.00, features = COALESCE(features, '{}'::jsonb) || '{
  "status":"available","text_chat":true,"voice_replies":false,"analytics":false,"catalog":false,
  "chat_conversations":300,"voip":false,"voice_minutes":0,"concurrent_calls":0,"phone_numbers":0,"extra_minute_cents":null
}'::jsonb WHERE code = 'bronze';

UPDATE subscription_plans SET price = 45.00, features = COALESCE(features, '{}'::jsonb) || '{
  "status":"available","text_chat":true,"voice_replies":true,"analytics":true,"catalog":true,
  "chat_conversations":1000,"voip":false,"voice_minutes":0,"concurrent_calls":0,"phone_numbers":0,"extra_minute_cents":null
}'::jsonb WHERE code = 'silver';

UPDATE subscription_plans SET price = 99.00, features = COALESCE(features, '{}'::jsonb) || '{
  "status":"coming_soon","text_chat":true,"voice_replies":true,"analytics":true,"catalog":true,
  "chat_conversations":1000,"voip":true,"voice_minutes":1000,"concurrent_calls":1,"phone_numbers":1,"extra_minute_cents":12
}'::jsonb WHERE code = 'gold';

UPDATE subscription_plans SET price = 219.00, features = COALESCE(features, '{}'::jsonb) || '{
  "status":"coming_soon","text_chat":true,"voice_replies":true,"analytics":true,"catalog":true,
  "chat_conversations":1000,"voip":true,"voice_minutes":2500,"concurrent_calls":3,"phone_numbers":3,"extra_minute_cents":10
}'::jsonb WHERE code = 'platinum';

CREATE TABLE IF NOT EXISTS plan_waitlist (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL,
    plan_code  VARCHAR(50) NOT NULL,
    tenant_id  UUID REFERENCES tenants(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (email, plan_code)
);

INSERT INTO subscription_plans (code, name, price, billing_period, sort_order, features) VALUES
 ('whatsapp', 'WhatsApp Business', 10.00, 'monthly', 10,
  '{"kind":"addon","status":"coming_soon","channel":"whatsapp","shares_plan_quota":true,
    "template_messages":100,"included_in":["gold","platinum"]}'),
 ('whatsapp_extra', 'Conversazioni WhatsApp extra', 10.00, 'monthly', 11,
  '{"kind":"addon","status":"coming_soon","channel":"whatsapp","extra_conversations":150,
    "requires":["whatsapp","gold","platinum"]}')
ON CONFLICT (code) DO NOTHING;

UPDATE subscription_plans SET features = features || '{"whatsapp_included":true}'::jsonb
WHERE code IN ('gold','platinum');

INSERT INTO subscription_plans (code, name, price, billing_period, sort_order, features) VALUES
 ('whatsapp_new_number', 'Numero WhatsApp nuovo', 0.00, 'monthly', 12,
  '{"kind":"addon","status":"coming_soon","channel":"whatsapp"}')
ON CONFLICT (code) DO NOTHING;

-- ddl-auto=validate: tenant_addons.source; subscription_refunds;
-- billing_commissions.stripe_payment_intent_id, reversed_amount;
-- stripe_subscriptions cancel_at_period_end, cancel_at, period_end, canceled_at;
-- tenants.suspended_at. DEV, dopo il deploy della colonna source:
-- UPDATE tenant_addons SET source = 'stripe' WHERE source = 'manual';

CREATE TABLE IF NOT EXISTS tenant_addons (
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    addon_code  VARCHAR(50) NOT NULL,
    status      VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, addon_code)
);

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS stripe_subscription_updated_at TIMESTAMPTZ;
ALTER TABLE billing_commissions ADD COLUMN IF NOT EXISTS stripe_invoice_id VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_commissions_stripe_invoice_id
    ON billing_commissions (stripe_invoice_id) WHERE stripe_invoice_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_subscription_plans_stripe_price_id
    ON subscription_plans (stripe_price_id) WHERE stripe_price_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS stripe_subscriptions (
    subscription_id VARCHAR(255) PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    status VARCHAR(255) NOT NULL,
    plan_code VARCHAR(255),
    addon_codes VARCHAR(255),
    event_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stripe_subscriptions_tenant
    ON stripe_subscriptions (tenant_id);

ALTER TABLE stripe_subscriptions ADD COLUMN IF NOT EXISTS cancel_at_period_end BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE stripe_subscriptions ADD COLUMN IF NOT EXISTS cancel_at TIMESTAMPTZ;
ALTER TABLE stripe_subscriptions ADD COLUMN IF NOT EXISTS period_end TIMESTAMPTZ;
ALTER TABLE stripe_subscriptions ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ;

ALTER TABLE tenant_addons ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'manual';

ALTER TABLE billing_commissions ADD COLUMN IF NOT EXISTS stripe_payment_intent_id VARCHAR(255);
ALTER TABLE billing_commissions ADD COLUMN IF NOT EXISTS reversed_amount NUMERIC(10, 2) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS subscription_refunds (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id),
    subscription_id    VARCHAR(255) NOT NULL UNIQUE,
    invoice_id         VARCHAR(255) NOT NULL,
    payment_intent_id  VARCHAR(255) NOT NULL,
    amount_cents       BIGINT NOT NULL,
    stripe_refund_id   VARCHAR(255),
    status             VARCHAR(255) NOT NULL,
    reason             TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_subscription_refunds_tenant
    ON subscription_refunds (tenant_id, created_at);
ALTER TABLE subscription_refunds ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS stripe_invoice_payments (
    payment_intent_id VARCHAR(255) PRIMARY KEY,
    invoice_id        VARCHAR(255) NOT NULL,
    tenant_id         UUID NOT NULL REFERENCES tenants(id)
);

-- Se SPRING_DATASOURCE_USERNAME è owner, i GRANT non servono.
-- Altrimenti, sostituire owner con l'utente del Secret:
-- GRANT SELECT, INSERT, UPDATE ON billing_accounts, stripe_webhook_events, credit_purchases, plan_waitlist, stripe_subscriptions, subscription_refunds TO owner;
-- GRANT SELECT, UPDATE ON subscription_plans TO owner;
