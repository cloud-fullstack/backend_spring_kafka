-- V3 — consensi, preventivi, firma (feaci)
-- Eseguire a mano dopo 01_schema.sql e 02_dati_iniziali.sql. Niente Flyway.
-- Aggiunge legal_documents, consent_records, quote_events (20 → 23 tabelle)
-- e colonne su resellers, custom_quotes; regole su contract_acceptances.
--
-- Rifiuta:
--   1. UPDATE/DELETE su consent_records (registro append-only)
--   2. evento 'sent' su un preventivo senza PDF (pdf_sha256)
--   3. seconda accettazione dello stesso preventivo
--   4. firma senza dichiarazioni obbligatorie in clauses_approved
--   5. evento preventivo con un partner diverso dal proprietario del preventivo
--   6. presa visione obbligatoria (account_terms, partner_privacy, rates_summary,
--      dichiarazioni di firma) registrata con granted = false
--
-- Revisione 2 rispetto alla bozza degli sviluppatori:
--   * SET ROLE partner_app: le tabelle nuove appartengono al ruolo di Spring
--     (prima erano del superuser e Spring riceveva "permission denied").
--   * consent_records.user_id: chi ha spuntato la casella.
--   * Niente colonne doppie su resellers: per privacy e sintesi percentuali
--     restano privacy_accepted_at e terms_summary_ack_at di 01_schema.
--   * Il contratto 1 si riconosce da reseller_contracts.type, non da un campo
--     del JSON che il codice potrebbe dimenticare. DPA obbligatorio per CUSTOM e ZONE.
--   * quote_events aggiorna da solo sent_at / viewed_at / accepted_at.
--   * reseller_programs.base = 'RECURRING' per tutti: commissione solo sul canone.
--
-- Prima di questo file, se feaci non è vuoto: V3_prepara.sql
-- Uso: kubectl exec -i ... psql -X -v ON_ERROR_STOP=1 -U <superuser> -d feaci < questo_file

BEGIN;

SET ROLE partner_app;

-- ---------------------------------------------------------------------------
-- Catalogo documenti (condizioni, privacy partner, sintesi percentuali, DPA)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS legal_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type        TEXT NOT NULL CHECK (doc_type IN (
                        'partner_account_terms',
                        'partner_privacy',
                        'rates_summary',
                        'partnership_contract',
                        'dpa_annex_3'
                    )),
    version         TEXT NOT NULL,
    language        TEXT NOT NULL DEFAULT 'it',
    title           TEXT NOT NULL,
    body_sha256     TEXT NOT NULL,              -- 'pending-legal-review' finché il legale non approva
    pdf_sha256      TEXT,
    effective_from  DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (doc_type, version, language)
);

COMMENT ON TABLE legal_documents IS
    'Versioni dei testi mostrati al partner. Il testo definitivo lo firma il legale.';

-- ---------------------------------------------------------------------------
-- Registro consensi (fase A e C). Mai aggiornare: nuova riga se cambia idea.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consent_records (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reseller_id       UUID NOT NULL REFERENCES resellers (id),
    user_id           UUID NOT NULL,            -- utente che ha spuntato (database auth)
    purpose           TEXT NOT NULL CHECK (purpose IN (
                          'account_terms',
                          'partner_privacy',
                          'rates_summary',
                          'marketing',
                          'profiling',
                          'acts_as_business',
                          'contract_accepted',
                          'burdensome_clauses',
                          'end_client_informed',
                          'dpa_annex_3'
                      )),
    granted           BOOLEAN NOT NULL,
    document_id       UUID REFERENCES legal_documents (id),
    document_version  TEXT NOT NULL,
    clause_text       TEXT NOT NULL,            -- testo esatto mostrato accanto alla casella
    ip                INET,
    user_agent        TEXT,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- solo marketing e profilazione si possono rifiutare: il resto è presa visione o dichiarazione
    CONSTRAINT consent_records_mandatory_granted
        CHECK (purpose IN ('marketing', 'profiling') OR granted)
);

-- Se consent_records era già stata creata dalla bozza (senza user_id né vincolo), la completa.
ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE consent_records ALTER COLUMN user_id SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'consent_records_mandatory_granted') THEN
        ALTER TABLE consent_records ADD CONSTRAINT consent_records_mandatory_granted
            CHECK (purpose IN ('marketing', 'profiling') OR granted);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_consent_records_reseller
    ON consent_records (reseller_id, purpose, recorded_at DESC);

COMMENT ON TABLE consent_records IS
    'Append-only. Fase A: account_terms, partner_privacy, rates_summary, marketing, profiling. Fase C: le altre.';

CREATE OR REPLACE FUNCTION feaci_reject_consent_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'consent_records is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

DROP TRIGGER IF EXISTS consent_records_immutable ON consent_records;
CREATE TRIGGER consent_records_immutable
    BEFORE UPDATE OR DELETE ON consent_records
    FOR EACH ROW
    EXECUTE FUNCTION feaci_reject_consent_mutation();

-- Colonne di comodo su resellers, allineate dal trigger (il codice scrive solo nel registro).
-- marketing_consent_at e marketing_revoked_at esistono già in 01_schema.
ALTER TABLE resellers
    ADD COLUMN IF NOT EXISTS profiling_consent_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS profiling_revoked_at  TIMESTAMPTZ;

COMMENT ON COLUMN resellers.marketing_consent_at IS
    'Fase A, facoltativo. Aggiornata da consent_records. NULL = mai dato; non blocca la candidatura.';
COMMENT ON COLUMN resellers.profiling_consent_at IS
    'Fase A, facoltativo e separato dal marketing. Aggiornata da consent_records.';

CREATE OR REPLACE FUNCTION feaci_sync_reseller_consents()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.purpose = 'marketing' THEN
        IF NEW.granted THEN
            UPDATE resellers SET marketing_consent_at = NEW.recorded_at, marketing_revoked_at = NULL
             WHERE id = NEW.reseller_id;
        ELSE
            UPDATE resellers SET marketing_revoked_at = NEW.recorded_at WHERE id = NEW.reseller_id;
        END IF;
    ELSIF NEW.purpose = 'profiling' THEN
        IF NEW.granted THEN
            UPDATE resellers SET profiling_consent_at = NEW.recorded_at, profiling_revoked_at = NULL
             WHERE id = NEW.reseller_id;
        ELSE
            UPDATE resellers SET profiling_revoked_at = NEW.recorded_at WHERE id = NEW.reseller_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS consent_records_sync_reseller ON consent_records;
DROP FUNCTION IF EXISTS feaci_sync_reseller_consent();
CREATE TRIGGER consent_records_sync_reseller
    AFTER INSERT ON consent_records
    FOR EACH ROW
    EXECUTE FUNCTION feaci_sync_reseller_consents();

-- ---------------------------------------------------------------------------
-- Eventi preventivo (presa visione vs accettazione)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quote_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quote_id     UUID NOT NULL REFERENCES custom_quotes (id),
    reseller_id  UUID NOT NULL REFERENCES resellers (id),
    user_id      UUID,                          -- NULL solo per 'sent' ed 'expired' (sistema)
    event_type   TEXT NOT NULL CHECK (event_type IN (
                     'sent', 'viewed', 'accepted', 'rejected', 'expired'
                 )),
    ip           INET,
    user_agent   TEXT,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT quote_events_partner_action_has_user
        CHECK (event_type IN ('sent', 'expired') OR user_id IS NOT NULL)
);

-- Se quote_events era già stata creata dalla bozza, la completa.
ALTER TABLE quote_events ADD COLUMN IF NOT EXISTS user_id UUID;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'quote_events_partner_action_has_user') THEN
        ALTER TABLE quote_events ADD CONSTRAINT quote_events_partner_action_has_user
            CHECK (event_type IN ('sent', 'expired') OR user_id IS NOT NULL);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_quote_events_quote
    ON quote_events (quote_id, recorded_at);

CREATE UNIQUE INDEX IF NOT EXISTS quote_events_one_accept
    ON quote_events (quote_id)
    WHERE event_type = 'accepted';

COMMENT ON TABLE quote_events IS
    'sent/viewed/accepted. viewed = presa visione senza impegno. Un solo accepted per preventivo.';

ALTER TABLE custom_quotes
    ADD COLUMN IF NOT EXISTS pdf_sha256   TEXT,
    ADD COLUMN IF NOT EXISTS sent_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS viewed_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS accepted_at  TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'custom_quotes_sent_needs_pdf'
    ) THEN
        ALTER TABLE custom_quotes
            ADD CONSTRAINT custom_quotes_sent_needs_pdf
            CHECK (sent_at IS NULL OR (pdf_sha256 IS NOT NULL AND length(btrim(pdf_sha256)) > 0));
    END IF;
END
$$;

-- Prima di registrare un evento: PDF presente per 'sent', partner giusto per tutti.
CREATE OR REPLACE FUNCTION feaci_quote_event_check()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sha   TEXT;
    owner UUID;
BEGIN
    SELECT q.pdf_sha256, r.reseller_id INTO sha, owner
      FROM custom_quotes q
      JOIN custom_requests r ON r.id = q.request_id
     WHERE q.id = NEW.quote_id;

    IF owner IS DISTINCT FROM NEW.reseller_id THEN
        RAISE EXCEPTION 'quote belongs to another partner'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.event_type = 'sent' AND (sha IS NULL OR length(btrim(sha)) = 0) THEN
        RAISE EXCEPTION 'quote sent without PDF'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- Nomi della bozza sviluppatori: un solo BEFORE e un solo AFTER su quote_events.
DROP TRIGGER IF EXISTS quote_events_sent_needs_pdf ON quote_events;
DROP TRIGGER IF EXISTS quote_events_same_partner ON quote_events;
DROP TRIGGER IF EXISTS quote_events_touch_quote ON quote_events;
DROP FUNCTION IF EXISTS feaci_quote_sent_needs_pdf();
DROP FUNCTION IF EXISTS feaci_quote_event_same_partner();
DROP FUNCTION IF EXISTS feaci_quote_event_touch_quote();
DROP TRIGGER IF EXISTS quote_events_check ON quote_events;
CREATE TRIGGER quote_events_check
    BEFORE INSERT ON quote_events
    FOR EACH ROW
    EXECUTE FUNCTION feaci_quote_event_check();

-- Dopo l'evento: aggiorna le date su custom_quotes (una sola fonte da scrivere: quote_events).
CREATE OR REPLACE FUNCTION feaci_quote_event_apply()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_type = 'sent' THEN
        UPDATE custom_quotes SET sent_at = COALESCE(sent_at, NEW.recorded_at) WHERE id = NEW.quote_id;
    ELSIF NEW.event_type = 'viewed' THEN
        UPDATE custom_quotes SET viewed_at = COALESCE(viewed_at, NEW.recorded_at) WHERE id = NEW.quote_id;
    ELSIF NEW.event_type = 'accepted' THEN
        UPDATE custom_quotes
           SET viewed_at = COALESCE(viewed_at, NEW.recorded_at),
               accepted_at = NEW.recorded_at
         WHERE id = NEW.quote_id;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS quote_events_apply ON quote_events;
CREATE TRIGGER quote_events_apply
    AFTER INSERT ON quote_events
    FOR EACH ROW
    EXECUTE FUNCTION feaci_quote_event_apply();

-- Seconda accettazione anche se qualcuno scrive direttamente custom_quotes.accepted_at
CREATE OR REPLACE FUNCTION feaci_quote_one_accept()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.accepted_at IS NOT NULL AND NEW.accepted_at IS DISTINCT FROM OLD.accepted_at THEN
        RAISE EXCEPTION 'quote already accepted'
            USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS custom_quotes_one_accept ON custom_quotes;
CREATE TRIGGER custom_quotes_one_accept
    BEFORE UPDATE OF accepted_at ON custom_quotes
    FOR EACH ROW
    EXECUTE FUNCTION feaci_quote_one_accept();

-- ---------------------------------------------------------------------------
-- Firma: dichiarazioni obbligatorie in contract_acceptances.clauses_approved
-- (la colonna esiste già in 01_schema, JSONB NOT NULL)
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN contract_acceptances.clauses_approved IS
    'Caselle fase C: acts_as_business, contract_accepted, burdensome_clauses, end_client_informed (CUSTOM), dpa_annex_3 (CUSTOM e ZONE), contract_version.';

CREATE OR REPLACE FUNCTION feaci_acceptance_requires_clauses()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    c     JSONB := NEW.clauses_approved;
    ctype TEXT;
BEGIN
    SELECT type INTO ctype FROM reseller_contracts WHERE id = NEW.contract_id;

    IF c IS NULL
        OR jsonb_typeof(c) <> 'object'
        OR COALESCE(c->>'acts_as_business', '')   <> 'true'
        OR COALESCE(c->>'contract_accepted', '')  <> 'true'
        OR COALESCE(c->>'burdensome_clauses', '') <> 'true'
        OR COALESCE(c->>'contract_version', '')   = ''
        OR (ctype = 'CUSTOM' AND COALESCE(c->>'end_client_informed', '') <> 'true')
        OR (ctype IN ('CUSTOM', 'ZONE') AND COALESCE(c->>'dpa_annex_3', '') <> 'true')
    THEN
        RAISE EXCEPTION 'signature missing mandatory declarations'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS contract_acceptances_require_clauses ON contract_acceptances;
CREATE TRIGGER contract_acceptances_require_clauses
    BEFORE INSERT OR UPDATE OF clauses_approved ON contract_acceptances
    FOR EACH ROW
    EXECUTE FUNCTION feaci_acceptance_requires_clauses();

-- ---------------------------------------------------------------------------
-- Decisione prodotto: commissione solo sul canone, per tutti e tre i modelli
-- ---------------------------------------------------------------------------
UPDATE reseller_programs SET base = 'RECURRING' WHERE base <> 'RECURRING';

-- ---------------------------------------------------------------------------
-- Versioni segnaposto (il legale sostituisce hash, titolo e PDF)
-- Il backend non deve mostrare in produzione un documento con body_sha256 'pending-legal-review'.
-- ---------------------------------------------------------------------------
INSERT INTO legal_documents (doc_type, version, language, title, body_sha256, effective_from)
VALUES
    ('partner_account_terms', '1.0', 'it',
     'Condizioni d’uso dell’account partner',
     'pending-legal-review', DATE '2026-11-01'),
    ('partner_privacy', '1.0', 'it',
     'Informativa privacy per i partner',
     'pending-legal-review', DATE '2026-11-01'),
    ('rates_summary', '1.0', 'it',
     'Sintesi delle condizioni e delle percentuali dei modelli partner',
     'pending-legal-review', DATE '2026-11-01'),
    ('partnership_contract', '1.0', 'it',
     'Contratto di Partnership',
     'pending-legal-review', DATE '2026-11-01'),
    ('dpa_annex_3', '1.0', 'it',
     'Accordo sul trattamento dei dati (Allegato 3)',
     'pending-legal-review', DATE '2026-11-01')
ON CONFLICT (doc_type, version, language) DO NOTHING;

RESET ROLE;
COMMIT;
