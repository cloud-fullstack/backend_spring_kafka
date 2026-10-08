-- omero: setup DB per auth-service (Spring). Rieseguibile.
-- Eseguire su telemaco_db PRIMA del deploy: con ddl-auto=validate ogni colonna
-- mappata dalle entity deve esistere, altrimenti il pod non parte.
BEGIN;

-- 1. Settori dei clienti (BusinessVertical, controllato in registrazione)
CREATE TABLE IF NOT EXISTS business_verticals (
    code            VARCHAR(30) PRIMARY KEY,
    name            VARCHAR(80) NOT NULL,
    sensitive_data  BOOLEAN NOT NULL DEFAULT false,
    active          BOOLEAN NOT NULL DEFAULT true
);
INSERT INTO business_verticals (code, name, sensitive_data) VALUES
    ('medical',        'Medici e dentisti',          true),
    ('legal',          'Avvocati e studi legali',    true),
    ('vehicle_rental', 'Noleggio auto e furgoni',    false),
    ('hospitality',    'B&B e strutture ricettive',  false),
    ('restaurant',     'Ristorazione',               false),
    ('ecommerce',      'E-commerce',                 false),
    ('other',          'Altro',                      false)
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
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS company_name                  VARCHAR(200);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS vat_number                    VARCHAR(20);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS country                       CHAR(2);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS reseller_id                   UUID REFERENCES resellers(id);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS reseller_attributed_at        TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS created_at                    TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS last_seen_at                  TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS lemon_subscription_updated_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_tenants_reseller ON tenants (reseller_id) WHERE reseller_id IS NOT NULL;

-- 5. Piani: variante Lemon Squeezy e % commissione reseller
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS lemon_variant_id            VARCHAR(64);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS reseller_commission_percent NUMERIC(5, 2);
CREATE UNIQUE INDEX IF NOT EXISTS ux_subscription_plans_lemon_variant_id
    ON subscription_plans (lemon_variant_id) WHERE lemon_variant_id IS NOT NULL;
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

COMMIT;
