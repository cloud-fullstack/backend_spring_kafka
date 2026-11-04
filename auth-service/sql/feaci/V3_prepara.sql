-- V3_prepara.sql  ·  database feaci  ·  da lanciare PRIMA di V3__consensi_preventivi_firma.sql
--
-- Riconosce da solo lo stato di feaci e fa solo quello che serve:
--   A. V3 mai applicata            -> non fa nulla
--   B. applicata la prima V3        -> la toglie (legal_documents con colonna "kind")
--   C. applicata la bozza sviluppatori (tabelle non di partner_app)
--                                   -> passa tabelle e funzioni a partner_app,
--                                      toglie le colonne doppie su resellers
-- Si ferma senza toccare nulla se le tabelle V3 contengono già dati.
--
-- Va lanciato come superuser del cluster (serve per cambiare proprietario).
-- Uso: kubectl exec -i ... psql -X -v ON_ERROR_STOP=1 -U <superuser> -d feaci < V3_prepara.sql

BEGIN;

DO $$
DECLARE
    n         BIGINT := 0;
    has_v3    BOOLEAN := to_regclass('public.legal_documents') IS NOT NULL;
    first_v3  BOOLEAN;
    owner     TEXT;
    f         RECORD;
BEGIN
    IF NOT has_v3 THEN
        RAISE NOTICE 'Stato A: V3 mai applicata. Niente da preparare: lancia V3__consensi_preventivi_firma.sql';
        RETURN;
    END IF;

    SELECT (SELECT count(*) FROM legal_documents WHERE body_sha256 <> 'pending-legal-review')
         + COALESCE((SELECT count(*) FROM consent_records), 0)
         + COALESCE((SELECT count(*) FROM quote_events), 0)
      INTO n;
    IF n > 0 THEN
        RAISE EXCEPTION 'Le tabelle V3 contengono % righe reali: non tocco nulla. Scrivimi prima di procedere.', n;
    END IF;

    first_v3 := EXISTS (SELECT 1 FROM information_schema.columns
                         WHERE table_schema = 'public' AND table_name = 'legal_documents' AND column_name = 'kind');

    IF first_v3 THEN
        RAISE NOTICE 'Stato B: prima V3 trovata, la tolgo.';
        DROP VIEW  IF EXISTS current_consents;
        DROP TABLE IF EXISTS quote_events;
        DROP TABLE IF EXISTS consent_records;
        DROP TABLE IF EXISTS legal_documents;
        DROP FUNCTION IF EXISTS consent_records_append_only();
        DROP FUNCTION IF EXISTS consent_records_sync_reseller();
        ALTER TABLE custom_quotes DROP CONSTRAINT IF EXISTS custom_quotes_sent_check;
        ALTER TABLE custom_quotes DROP CONSTRAINT IF EXISTS custom_quotes_status_check;
        DROP INDEX IF EXISTS custom_quotes_number_uq;
        ALTER TABLE custom_quotes
            DROP COLUMN IF EXISTS number,
            DROP COLUMN IF EXISTS status,
            DROP COLUMN IF EXISTS valid_until,
            DROP COLUMN IF EXISTS pdf_path,
            DROP COLUMN IF EXISTS body_sha256;
        DROP TRIGGER IF EXISTS contract_acceptances_custom ON contract_acceptances;
        DROP FUNCTION IF EXISTS contract_acceptances_check_custom();
        ALTER TABLE contract_acceptances DROP CONSTRAINT IF EXISTS contract_acceptances_required_check;
        ALTER TABLE contract_acceptances
            DROP COLUMN IF EXISTS signatory_name,
            DROP COLUMN IF EXISTS signatory_role,
            DROP COLUMN IF EXISTS template_version,
            DROP COLUMN IF EXISTS acting_as_business,
            DROP COLUMN IF EXISTS signatory_authority,
            DROP COLUMN IF EXISTS contract_accepted,
            DROP COLUMN IF EXISTS specific_clauses_1341,
            DROP COLUMN IF EXISTS client_data_authorized,
            DROP COLUMN IF EXISTS dpa_accepted,
            DROP COLUMN IF EXISTS pdf_downloaded_at;
        RAISE NOTICE 'Fatto. Ora lancia V3__consensi_preventivi_firma.sql';
        RETURN;
    END IF;

    SELECT tableowner INTO owner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'legal_documents';
    IF owner <> 'partner_app' THEN
        RAISE NOTICE 'Stato C: bozza V3 trovata (tabelle di %), le passo a partner_app.', owner;
        ALTER TABLE legal_documents OWNER TO partner_app;
        IF to_regclass('public.consent_records') IS NOT NULL THEN ALTER TABLE consent_records OWNER TO partner_app; END IF;
        IF to_regclass('public.quote_events')    IS NOT NULL THEN ALTER TABLE quote_events    OWNER TO partner_app; END IF;
        FOR f IN SELECT p.oid::regprocedure AS sig
                   FROM pg_proc p JOIN pg_namespace s ON s.oid = p.pronamespace
                  WHERE s.nspname = 'public' AND p.proname LIKE 'feaci\_%'
        LOOP
            EXECUTE format('ALTER FUNCTION %s OWNER TO partner_app', f.sig);
        END LOOP;
        -- colonne doppie della bozza: si usano privacy_accepted_at e terms_summary_ack_at di 01_schema
        ALTER TABLE resellers DROP COLUMN IF EXISTS terms_accepted_at;
        ALTER TABLE resellers DROP COLUMN IF EXISTS rates_summary_accepted_at;
        RAISE NOTICE 'Fatto. Ora lancia V3__consensi_preventivi_firma.sql';
        RETURN;
    END IF;

    RAISE NOTICE 'V3 già presente e di partner_app: niente da preparare. Puoi rilanciare V3__consensi_preventivi_firma.sql senza danni.';
END
$$;

COMMIT;
