-- omero: setup DB per auth-service (Spring). Rieseguibile.
-- Hibernate ddl-auto=validate: se manca una colonna (reset_token, google_id, …)
-- o la tabella resellers, lo Spring non parte.
--
-- DEV = omero_db. PROD Spring = telemaco_db (stesso schema billing/Stripe, per ora).
-- telemaco_db resta anche il DB del demo LLM. Le tabelle Stripe si divideranno in futuro.
-- Una tantum, come postgres:
--   kubectl exec -n telemaco-ns postgres-0 -- \
--     psql -U postgres -c "CREATE DATABASE omero_db OWNER postgres;"
-- Se SPRING_DATASOURCE_USERNAME non è postgres:
--     GRANT ALL ON DATABASE omero_db TO <utente>;
-- Poi lo schema:
--   kubectl exec -i -n telemaco-ns postgres-0 -- \
--     psql -U postgres -d omero_db < backend_spring_kafka/auth-service/sql/omero_db_setup.sql
--   kubectl exec -n telemaco-ns postgres-0 -- \
--     psql -U postgres -d omero_db -c '\d users'
BEGIN;

CREATE TABLE IF NOT EXISTS users (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid()
);
CREATE TABLE IF NOT EXISTS subscription_plans (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code  VARCHAR(255),
    name  VARCHAR(255),
    price NUMERIC
);

-- 1. Settori dei clienti (BusinessVertical, controllato in registrazione)
CREATE TABLE IF NOT EXISTS business_verticals (
    code            VARCHAR(30) PRIMARY KEY,
    name            VARCHAR(80) NOT NULL,
    sensitive_data  BOOLEAN NOT NULL DEFAULT false,
    active          BOOLEAN NOT NULL DEFAULT true
);
INSERT INTO business_verticals (code, name, sensitive_data) VALUES
    ('medical',        'Medici e dentisti',                   true),
    ('legal',          'Avvocati e studi legali',             true),
    ('accounting',     'Commercialisti e consulenti fiscali', true),
    ('vehicle_rental', 'Noleggio auto e furgoni',             false),
    ('hospitality',    'B&B e strutture ricettive',           false),
    ('restaurant',     'Ristorazione',                        false),
    ('ecommerce',      'E-commerce',                          false),
    ('other',          'Altro',                               false)
ON CONFLICT (code) DO NOTHING;

-- 2. Utenti (entity User)
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash       VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name          VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name           VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number        VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_id             VARCHAR(50) NOT NULL DEFAULT 'demo';
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id           UUID;
ALTER TABLE users ADD COLUMN IF NOT EXISTS business_name       VARCHAR(200);
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active           BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_reseller         BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_tenant_admin     BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS google_id           VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS facebook_id         VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_token         VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_token_expires TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at          TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at          TIMESTAMPTZ;
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email_lower ON users (lower(email));
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_reset_token ON users (reset_token) WHERE reset_token IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_google_id   ON users (google_id)   WHERE google_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_facebook_id ON users (facebook_id) WHERE facebook_id IS NOT NULL;

-- 3. Reseller (entity Reseller)
CREATE TABLE IF NOT EXISTS resellers (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL UNIQUE REFERENCES users(id),
    referral_code  VARCHAR(20) NOT NULL UNIQUE,
    company_name   VARCHAR(200),
    vat_number     VARCHAR(20),
    country        CHAR(2),
    status         VARCHAR(20) NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('pending', 'active', 'suspended', 'closed')),
    approved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4. Tenant (entity Tenant)
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS name                          VARCHAR(200);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS email                         VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS status                        VARCHAR(20) DEFAULT 'demo';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS vertical                      VARCHAR(30) REFERENCES business_verticals(code);
-- createFromSocial salva vertical = NULL finché non arriva complete-profile
ALTER TABLE tenants ALTER COLUMN vertical DROP NOT NULL;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS company_name                  VARCHAR(200);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS vat_number                    VARCHAR(20);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS country                       CHAR(2);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS reseller_id                   UUID REFERENCES resellers(id);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS reseller_attributed_at        TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS created_at                    TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS last_seen_at                  TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS lemon_subscription_updated_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_tenants_reseller ON tenants (reseller_id) WHERE reseller_id IS NOT NULL;

-- 5. Piani: users.plan_id ha FK su subscription_plans(code); lo Spring scrive 'demo'
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS code                        VARCHAR(255);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS billing_period              VARCHAR(20);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS trial_days                  INTEGER DEFAULT 0;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS sort_order                  INT DEFAULT 0;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS features                    JSONB;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS lemon_variant_id            VARCHAR(64);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS reseller_commission_percent NUMERIC(5, 2);
CREATE UNIQUE INDEX IF NOT EXISTS subscription_plans_code_key ON subscription_plans (code);
CREATE UNIQUE INDEX IF NOT EXISTS ux_subscription_plans_lemon_variant_id
    ON subscription_plans (lemon_variant_id) WHERE lemon_variant_id IS NOT NULL;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS stripe_price_id VARCHAR(255);
INSERT INTO subscription_plans (code, name, price, billing_period, trial_days, sort_order, features)
VALUES
  ('demo',     'Demo',     0.00,  'monthly', 0, 0, '{"status":"available","text_chat":true,"voice_replies":true,"voip":false,"analytics":false}'),
  ('bronze',   'Bronze',   29.00, 'monthly', 0, 1, '{"status":"available","text_chat":true,"voice_replies":false,"analytics":false,"catalog":false,"chat_conversations":300,"voip":false,"voice_minutes":0,"concurrent_calls":0,"phone_numbers":0}'),
  ('silver',   'Silver',   45.00, 'monthly', 0, 2, '{"status":"available","text_chat":true,"voice_replies":true,"analytics":true,"catalog":true,"chat_conversations":1000,"voip":false,"voice_minutes":0,"concurrent_calls":0,"phone_numbers":0}'),
  ('gold',     'Gold',     99.00, 'monthly', 0, 3, '{"status":"coming_soon","text_chat":true,"voice_replies":true,"analytics":true,"catalog":true,"chat_conversations":1000,"voip":true,"voice_minutes":1000,"concurrent_calls":1,"phone_numbers":1,"extra_minute_cents":12}'),
  ('platinum', 'Platinum', 219.00,'monthly', 0, 4, '{"status":"coming_soon","text_chat":true,"voice_replies":true,"analytics":true,"catalog":true,"chat_conversations":1000,"voip":true,"voice_minutes":2500,"concurrent_calls":3,"phone_numbers":3,"extra_minute_cents":10}')
ON CONFLICT (code) DO NOTHING;
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
UPDATE subscription_plans SET reseller_commission_percent = 10.00
 WHERE code IN ('bronze', 'silver', 'gold', 'platinum') AND reseller_commission_percent IS NULL;

-- 6. Webhook: idempotenza
CREATE TABLE IF NOT EXISTS lemon_webhook_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    event_name  VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 7. Commissioni reseller
CREATE TABLE IF NOT EXISTS billing_commissions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reseller_id        UUID NOT NULL REFERENCES resellers(id),
    tenant_id          UUID REFERENCES tenants(id),
    user_id            UUID REFERENCES users(id),
    plan_code          VARCHAR(50),
    amount             NUMERIC(10, 2) NOT NULL,
    commission_percent NUMERIC(5, 2) NOT NULL,
    lemon_order_id     VARCHAR(255),
    status             VARCHAR(20) DEFAULT 'pending',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_commissions_lemon_order_id
    ON billing_commissions (lemon_order_id) WHERE lemon_order_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_billing_commissions_reseller
    ON billing_commissions (reseller_id, status, created_at);

-- 8. Allinea i tipi a Hibernate (entity senza length = VARCHAR(255))
ALTER TABLE users ALTER COLUMN email TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN password_hash TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN first_name TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN last_name TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN phone_number TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN plan_id TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN business_name TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN google_id TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN facebook_id TYPE VARCHAR(255);
ALTER TABLE users ALTER COLUMN reset_token TYPE VARCHAR(255);

ALTER TABLE tenants ALTER COLUMN name TYPE VARCHAR(255);
ALTER TABLE tenants ALTER COLUMN email TYPE VARCHAR(255);
ALTER TABLE tenants ALTER COLUMN status TYPE VARCHAR(255);
ALTER TABLE tenants ALTER COLUMN vertical TYPE VARCHAR(255);
ALTER TABLE business_verticals ALTER COLUMN name TYPE VARCHAR(255);
ALTER TABLE business_verticals ALTER COLUMN code TYPE VARCHAR(255);
ALTER TABLE tenants ALTER COLUMN company_name TYPE VARCHAR(255);
ALTER TABLE tenants ALTER COLUMN vat_number TYPE VARCHAR(255);
ALTER TABLE tenants ALTER COLUMN country TYPE VARCHAR(255);

ALTER TABLE resellers ALTER COLUMN referral_code TYPE VARCHAR(255);
ALTER TABLE resellers ALTER COLUMN company_name TYPE VARCHAR(255);
ALTER TABLE resellers ALTER COLUMN vat_number TYPE VARCHAR(255);
ALTER TABLE resellers ALTER COLUMN country TYPE VARCHAR(255);
ALTER TABLE resellers ALTER COLUMN status TYPE VARCHAR(255);
UPDATE resellers SET company_name = '' WHERE company_name IS NULL;
ALTER TABLE resellers ALTER COLUMN company_name SET NOT NULL;

ALTER TABLE subscription_plans ALTER COLUMN code TYPE VARCHAR(255);
ALTER TABLE subscription_plans ALTER COLUMN name TYPE VARCHAR(255);
ALTER TABLE subscription_plans ALTER COLUMN lemon_variant_id TYPE VARCHAR(255);

ALTER TABLE lemon_webhook_events ALTER COLUMN event_name TYPE VARCHAR(255);

ALTER TABLE billing_commissions ALTER COLUMN plan_code TYPE VARCHAR(255);
ALTER TABLE billing_commissions ALTER COLUMN status TYPE VARCHAR(255);
ALTER TABLE billing_commissions ALTER COLUMN lemon_order_id TYPE VARCHAR(255);

-- 9. Date senza fuso -> timestamptz (solo se serve, così si può rieseguire)
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT table_name, column_name
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name IN ('users', 'resellers', 'tenants')
      AND column_name IN ('created_at', 'updated_at', 'approved_at', 'last_seen_at', 'reset_token_expires')
      AND data_type = 'timestamp without time zone'
  LOOP
    EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE timestamptz USING %I AT TIME ZONE ''UTC''',
                   r.table_name, r.column_name, r.column_name);
  END LOOP;
END $$;

-- 10. Crediti Stripe (Managed Payments). Lemon resta per i piani finché non si migra.
-- Stesso blocco in billing.sql (omero_db DEV e telemaco_db PROD, per ora).
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
-- Acquisti crediti: accredito solo se paid; storno rimborsi/dispute in proporzione (IVA inclusa nell'addebito).
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

CREATE TABLE IF NOT EXISTS tenant_addons (
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    addon_code  VARCHAR(50) NOT NULL,
    status      VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, addon_code)
);
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

COMMIT;
