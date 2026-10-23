-- omero: setup DB per auth-service (Spring). Rieseguibile.
-- Hibernate ddl-auto=validate: se manca una colonna (reset_token, google_id, …)
-- o la tabella resellers, lo Spring non parte.
--
--   kubectl exec -i -n telemaco-ns postgres-0 -- \
--     psql -U postgres -d telemaco_db < backend_spring_kafka/auth-service/sql/omero_db_setup.sql
--   kubectl exec -n telemaco-ns postgres-0 -- \
--     psql -U postgres -d telemaco_db -c '\d users'
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
INSERT INTO subscription_plans (code, name, price, billing_period, trial_days, sort_order, features)
VALUES
  ('demo',     'Demo',     0.00,  'monthly', 0, 0, '{"text_chat":true,"voice_replies":true,"voip":false,"analytics":false}'),
  ('bronze',   'Bronze',   0.00,  'monthly', 0, 1, '{"text_chat":true,"voice_replies":true,"voip":false,"analytics":false}'),
  ('silver',   'Silver',   45.00, 'monthly', 0, 2, '{"text_chat":true,"voice_replies":true,"voip":false,"analytics":true}'),
  ('gold',     'Gold',     0.00,  'monthly', 0, 3, '{"text_chat":true,"voice_replies":true,"voip":true,"analytics":true}'),
  ('platinum', 'Platinum', 0.00,  'monthly', 0, 4, '{"text_chat":true,"voice_replies":true,"voip":true,"analytics":true}')
ON CONFLICT (code) DO NOTHING;
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

COMMIT;
