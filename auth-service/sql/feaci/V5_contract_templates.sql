-- V5 — bozze contract_templates per l'Area Partner (feaci).
-- Eseguire a mano come partner_app, dopo 01 + 02 + V3 + V4.
-- Non è Flyway. Il prefisso è V5_ (non V3_) per non confonderlo con le migrazioni V3.
--
-- published_at NULL = bozza scaricabile, non firmabile.
-- Quando il legale approva il testo (esempio CUSTOM v1), hash UTF-8 come sha256Hex() Java:
--
--   UPDATE contract_templates
--      SET body_markdown = '<testo approvato>',
--          body_sha256   = encode(sha256(convert_to('<testo approvato>', 'UTF8')), 'hex'),
--          published_at  = now()
--    WHERE type = 'CUSTOM' AND version = 1;
--
-- Stesso schema per ZONE e CODE. Firma solo se published_at IS NOT NULL
-- e body_sha256 è 64 hex (mai 'pending-legal-review'). Il trigger
-- contract_acceptances_published_template lo rifiuta anche se il codice sbaglia.

BEGIN;
SET ROLE partner_app;

ALTER TABLE contract_templates
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;

COMMENT ON COLUMN contract_templates.published_at IS
    'NULL = bozza. Il legale pubblica con body_markdown, body_sha256 (64 hex) e published_at = now().';

INSERT INTO contract_templates (type, version, title, body_markdown, body_sha256)
SELECT 'CUSTOM', 1,
       'Contratto di Partnership su misura',
       'Bozza Contratto di Partnership su misura, versione 1.' || E'\n' ||
       'Il PDF definitivo lo prepara il legale. Questa bozza non è firmabile.',
       'pending-legal-review'
 WHERE NOT EXISTS (SELECT 1 FROM contract_templates WHERE type = 'CUSTOM' AND version = 1);

INSERT INTO contract_templates (type, version, title, body_markdown, body_sha256)
SELECT 'ZONE', 1,
       'Contratto di Partnership di zona',
       'Bozza Contratto di Partnership di zona, versione 1.' || E'\n' ||
       'Il PDF definitivo lo prepara il legale. Questa bozza non è firmabile.',
       'pending-legal-review'
 WHERE NOT EXISTS (SELECT 1 FROM contract_templates WHERE type = 'ZONE' AND version = 1);

INSERT INTO contract_templates (type, version, title, body_markdown, body_sha256)
SELECT 'CODE', 1,
       'Contratto di Partnership codice partner',
       'Bozza Contratto di Partnership codice partner, versione 1.' || E'\n' ||
       'Il PDF definitivo lo prepara il legale. Questa bozza non è firmabile.',
       'pending-legal-review'
 WHERE NOT EXISTS (SELECT 1 FROM contract_templates WHERE type = 'CODE' AND version = 1);

CREATE OR REPLACE FUNCTION feaci_acceptance_published_template()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sha TEXT;
    published TIMESTAMPTZ;
BEGIN
    SELECT btrim(t.body_sha256), t.published_at INTO sha, published
      FROM reseller_contracts c
      JOIN contract_templates t ON t.id = c.template_id
     WHERE c.id = NEW.contract_id;
    IF published IS NULL
        OR sha IS NULL
        OR sha = 'pending-legal-review'
        OR length(sha) <> 64
        OR btrim(NEW.body_sha256) IS DISTINCT FROM sha
    THEN
        RAISE EXCEPTION 'contract template is not published'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS contract_acceptances_published_template ON contract_acceptances;
CREATE TRIGGER contract_acceptances_published_template
    BEFORE INSERT ON contract_acceptances
    FOR EACH ROW
    EXECUTE FUNCTION feaci_acceptance_published_template();

RESET ROLE;
COMMIT;
