-- V6 — tipo contratto DPA per i rivenditori (feaci).
-- Eseguire a mano come partner_app, dopo V5. Non è Flyway.
-- Non inventa secret, hash o Price ID.
--
-- type='DPA': inviato in registrazione (SENT), firmato in Area Partner (SIGNED).
-- ZONE/CODE/CUSTOM richiedono un DPA SIGNED (controllo nel backend).
-- contract_templates non ha colonna "name": usare title / type / locale.
-- SELECT type, version, locale, title FROM contract_templates WHERE type = 'DPA';
-- IT/EN pubblicati sul cluster (2026-11-12) non si sovrascrivono: ON CONFLICT / NOT EXISTS.

BEGIN;
SET ROLE partner_app;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'public.reseller_contracts'::regclass
           AND c.contype = 'c'
           AND pg_get_constraintdef(c.oid) ~* 'CUSTOM'
           AND pg_get_constraintdef(c.oid) ~* 'ZONE'
           AND pg_get_constraintdef(c.oid) !~* '''DPA'''
    LOOP
        EXECUTE format('ALTER TABLE reseller_contracts DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE reseller_contracts DROP CONSTRAINT IF EXISTS reseller_contracts_type_check;
ALTER TABLE reseller_contracts
    ADD CONSTRAINT reseller_contracts_type_check
    CHECK (type = ANY (ARRAY[
        'CUSTOM'::text,
        'ZONE'::text,
        'CODE'::text,
        'DPA'::text
    ]));

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'public.contract_templates'::regclass
           AND c.contype = 'c'
           AND pg_get_constraintdef(c.oid) ~* 'CUSTOM'
           AND pg_get_constraintdef(c.oid) ~* 'ZONE'
           AND pg_get_constraintdef(c.oid) !~* '''DPA'''
    LOOP
        EXECUTE format('ALTER TABLE contract_templates DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE contract_templates DROP CONSTRAINT IF EXISTS contract_templates_type_check;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.contract_templates'::regclass
           AND conname = 'contract_templates_type_check'
    ) THEN
        ALTER TABLE contract_templates
            ADD CONSTRAINT contract_templates_type_check
            CHECK (type = ANY (ARRAY[
                'CUSTOM'::text,
                'ZONE'::text,
                'CODE'::text,
                'CUSTOM_OFFER'::text,
                'DPA'::text
            ]));
    END IF;
END $$;

INSERT INTO legal_documents (doc_type, version, language, title, body_sha256, effective_from)
VALUES (
    'dpa_annex_3',
    '1.0',
    'it',
    'Accordo sul Trattamento dei Dati Personali (Art. 28 GDPR)',
    'pending-legal-review',
    CURRENT_DATE
)
ON CONFLICT (doc_type, version, language) DO UPDATE
    SET title = EXCLUDED.title
  WHERE legal_documents.body_sha256 = 'pending-legal-review';

INSERT INTO contract_templates (type, version, title, body_markdown, body_sha256)
SELECT 'DPA', 1,
       'Accordo sul Trattamento dei Dati Personali (Art. 28 GDPR)',
       'Bozza DPA partner (Allegato 3), versione 1.' || E'\n' ||
       'Il PDF definitivo lo prepara il legale. Questa bozza non è firmabile.',
       'pending-legal-review'
 WHERE NOT EXISTS (SELECT 1 FROM contract_templates WHERE type = 'DPA' AND version = 1);

RESET ROLE;
COMMIT;
