-- Eseguire su omero_db (DEV) e telemaco_db (PROD). Niente FK verso tenants (auth-service).

CREATE TABLE IF NOT EXISTS whatsapp_onboarding (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    mode           VARCHAR(255) NOT NULL,
    phone_number   VARCHAR(255) NOT NULL,
    display_name   VARCHAR(255) NOT NULL,
    category       VARCHAR(255),
    contact_email  VARCHAR(255) NOT NULL,
    backup_ack     BOOLEAN NOT NULL DEFAULT false,
    not_on_wa_ack  BOOLEAN NOT NULL DEFAULT false,
    status         VARCHAR(255) NOT NULL DEFAULT 'draft',
    error_reason   VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_wa_onboarding_open
    ON whatsapp_onboarding (tenant_id) WHERE status NOT IN ('connected','failed');

CREATE TABLE IF NOT EXISTS whatsapp_accounts (
    tenant_id         UUID PRIMARY KEY,
    waba_id           VARCHAR(255) NOT NULL,
    phone_number_id   VARCHAR(255) NOT NULL,
    display_number    VARCHAR(255) NOT NULL,
    access_token_enc  TEXT NOT NULL,
    status            VARCHAR(255) NOT NULL,
    connected_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE whatsapp_accounts DROP CONSTRAINT IF EXISTS whatsapp_accounts_phone_number_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS ux_wa_phone_connected
    ON whatsapp_accounts (phone_number_id) WHERE status = 'connected';
ALTER TABLE whatsapp_accounts ADD COLUMN IF NOT EXISTS entitlement_off_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS whatsapp_webhook_events (
    message_id   VARCHAR(255) PRIMARY KEY,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS channel_entitlements (
    tenant_id     UUID PRIMARY KEY,
    whatsapp      BOOLEAN NOT NULL DEFAULT false,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
