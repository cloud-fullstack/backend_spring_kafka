-- V3_verify.sql  ·  database feaci  ·  non lascia dati (ROLLBACK finale)
-- Richiede 01_schema + 02_dati_iniziali + V3 (revisione 2).
-- Dopo V5 (trigger contract_acceptances_published_template): 7 rifiuti.
-- Gira come partner_app, come Spring: se una tabella non è sua, fallisce subito.
-- Esito atteso: NOTICE "V3 verify: 7 rifiuti e 3 percorsi corretti OK".
--
-- Compatibile con V5 nostra, V5 con solo i tre template, entrambe, o nessuna:
-- i dati di prova usano versioni 998/999, lo stesso hash su template e contratto,
-- e retired_at: funziona anche quando il legale avrà pubblicato i contratti veri.
-- Il 7° rifiuto è la firma su bozza (published_at NULL). Senza il trigger V5
-- si usa il CHECK 01: SIGNED senza signed_at.
--
-- Uso: kubectl exec -i ... psql -X -v ON_ERROR_STOP=1 -U <superuser> -d feaci < V3_verify.sql
-- (oppure incollato in psql su feaci=#: il file apre e annulla da solo la transazione)

BEGIN;
SET ROLE partner_app;

DO $$
DECLARE
    rid    UUID := gen_random_uuid();
    rid2   UUID := gen_random_uuid();
    uid    UUID := gen_random_uuid();
    reqid  UUID := gen_random_uuid();
    qid    UUID := gen_random_uuid();
    tpl    UUID := gen_random_uuid();   -- CUSTOM pubblicato
    tpl2   UUID := gen_random_uuid();   -- CODE pubblicato
    tplz   UUID := gen_random_uuid();   -- ZONE pubblicato
    tpl_d  UUID := gen_random_uuid();   -- CUSTOM bozza
    cid    UUID := gen_random_uuid();
    cid2   UUID := gen_random_uuid();
    cid3   UUID := gen_random_uuid();   -- SENT, per SIGNED senza signed_at
    cid_d  UUID := gen_random_uuid();   -- contratto sulla bozza
    sha    TEXT := repeat('a', 64);
    v      TIMESTAMPTZ;
    v2     TIMESTAMPTZ;
    has_v5 BOOLEAN;
    ok_clauses JSONB := '{"acts_as_business":true,"contract_accepted":true,"burdensome_clauses":true,"end_client_informed":true,"dpa_annex_3":true,"contract_version":"1.0"}';
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'contract_acceptances_published_template'
    ) INTO has_v5;

    INSERT INTO resellers (id, user_id, contact_first_name, contact_last_name, contact_email, contact_phone,
                           legal_name, country, vat_number, interest, privacy_accepted_at, terms_summary_ack_at)
    VALUES (rid,  uid, 'Prova', 'Verifica', 'v3-verify-1@example.invalid', '+390000000', 'V3 Verify Srl',
            'IT', 'IT00000000001', 'CUSTOM', now(), now()),
           (rid2, gen_random_uuid(), 'Altro', 'Partner', 'v3-verify-2@example.invalid', '+390000001', 'Altro Srl',
            'IT', 'IT00000000002', 'CODE', now(), now());

    INSERT INTO custom_requests (id, reseller_id, client_name, client_country, client_sector, mode, created_by)
    VALUES (reqid, rid, 'Laboratorio di prova', 'IT', 'HEALTHCARE', 'COMPONENTS', uid);
    INSERT INTO custom_quotes (id, request_id, setup_cents, monthly_cents, created_by)
    VALUES (qid, reqid, 300000, 60000, uid);

    -- Stesso hash su template e contratto: il trigger V5 confronta btrim(body_sha256).
    -- retired_at = now(): pubblicati ma ritirati, così non entrano in conflitto con un
    -- template vero già in vigore (indice contract_templates_current_uq su type+locale).
    INSERT INTO contract_templates (id, type, version, title, body_markdown, body_sha256, published_at, retired_at)
    VALUES (tpl,   'CUSTOM', 999, 'Prova pubblicata', 'testo', sha, now(), now()),
           (tpl2,  'CODE',   999, 'Prova CODE',       'testo', sha, now(), now()),
           (tplz,  'ZONE',   999, 'Prova ZONE',       'testo', sha, now(), now()),
           (tpl_d, 'CUSTOM', 998, 'Prova bozza',      'testo', sha, NULL,  now());
    INSERT INTO reseller_contracts (id, reseller_id, type, template_id, rate_bps, status, rendered_body, body_sha256)
    VALUES (cid,   rid, 'CUSTOM', tpl,   1500, 'SENT', 'testo', sha),
           (cid2,  rid, 'CODE',   tpl2,   700, 'SENT', 'testo', sha),
           (cid3,  rid, 'ZONE',   tplz,  1000, 'SENT', 'testo', sha),
           (cid_d, rid, 'CUSTOM', tpl_d, 1500, 'SENT', 'testo', sha);

    -- ---------- 1. registro consensi in sola aggiunta ----------
    INSERT INTO consent_records (reseller_id, user_id, purpose, granted, document_version, clause_text)
    VALUES (rid, uid, 'marketing', true, '1.0', 'Acconsento a ricevere comunicazioni commerciali da Telemaco.');
    BEGIN
        UPDATE consent_records SET granted = false WHERE reseller_id = rid;
        RAISE EXCEPTION 'FALLITO 1: UPDATE su consent_records accettato';
    EXCEPTION WHEN integrity_constraint_violation THEN
        IF SQLERRM NOT LIKE '%append-only%' THEN RAISE; END IF;
    END;

    -- ---------- 2. presa visione obbligatoria non si registra come rifiutata ----------
    BEGIN
        INSERT INTO consent_records (reseller_id, user_id, purpose, granted, document_version, clause_text)
        VALUES (rid, uid, 'account_terms', false, '1.0', 'x');
        RAISE EXCEPTION 'FALLITO 2: account_terms con granted=false accettato';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    -- percorso corretto A: revoca marketing + profilazione -> colonne su resellers allineate
    INSERT INTO consent_records (reseller_id, user_id, purpose, granted, document_version, clause_text)
    VALUES (rid, uid, 'marketing', false, '1.0', 'Revoca dall''Area Partner.'),
           (rid, uid, 'profiling', true,  '1.0', 'Acconsento all''analisi del mio uso dell''Area Partner.');
    PERFORM 1 FROM resellers
      WHERE id = rid AND marketing_consent_at IS NOT NULL AND marketing_revoked_at IS NOT NULL
        AND profiling_consent_at IS NOT NULL AND profiling_revoked_at IS NULL;
    IF NOT FOUND THEN RAISE EXCEPTION 'FALLITO A: colonne consenso su resellers non allineate'; END IF;

    -- ---------- 3. preventivo inviato senza PDF ----------
    BEGIN
        INSERT INTO quote_events (quote_id, reseller_id, event_type) VALUES (qid, rid, 'sent');
        RAISE EXCEPTION 'FALLITO 3: sent senza PDF accettato';
    EXCEPTION WHEN integrity_constraint_violation THEN
        IF SQLERRM NOT LIKE '%without PDF%' THEN RAISE; END IF;
    END;

    -- ---------- 4. evento registrato da un altro partner ----------
    UPDATE custom_quotes SET pdf_sha256 = repeat('d', 64) WHERE id = qid;
    BEGIN
        INSERT INTO quote_events (quote_id, reseller_id, user_id, event_type) VALUES (qid, rid2, uid, 'viewed');
        RAISE EXCEPTION 'FALLITO 4: evento di un altro partner accettato';
    EXCEPTION WHEN integrity_constraint_violation THEN
        IF SQLERRM NOT LIKE '%another partner%' THEN RAISE; END IF;
    END;

    -- percorso corretto B: sent -> viewed -> accepted aggiorna le date
    INSERT INTO quote_events (quote_id, reseller_id, event_type) VALUES (qid, rid, 'sent');
    INSERT INTO quote_events (quote_id, reseller_id, user_id, event_type) VALUES (qid, rid, uid, 'viewed');
    INSERT INTO quote_events (quote_id, reseller_id, user_id, event_type) VALUES (qid, rid, uid, 'accepted');
    SELECT viewed_at, accepted_at INTO v, v2 FROM custom_quotes WHERE id = qid;
    IF v IS NULL OR v2 IS NULL OR (SELECT sent_at FROM custom_quotes WHERE id = qid) IS NULL THEN
        RAISE EXCEPTION 'FALLITO B: sent_at/viewed_at/accepted_at non aggiornati dagli eventi';
    END IF;

    -- ---------- 5. seconda accettazione ----------
    BEGIN
        INSERT INTO quote_events (quote_id, reseller_id, user_id, event_type) VALUES (qid, rid, uid, 'accepted');
        RAISE EXCEPTION 'FALLITO 5a: secondo accepted accettato';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        UPDATE custom_quotes SET accepted_at = now() + interval '1 minute' WHERE id = qid;
        RAISE EXCEPTION 'FALLITO 5b: accepted_at riscritto';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    -- ---------- 6. firma senza dichiarazioni obbligatorie ----------
    BEGIN
        INSERT INTO contract_acceptances (contract_id, user_id, body_sha256, ip, user_agent, clauses_approved)
        VALUES (cid, uid, sha, '192.0.2.1', 'verify', '{}');
        RAISE EXCEPTION 'FALLITO 6a: firma vuota accettata';
    EXCEPTION WHEN integrity_constraint_violation THEN
        IF SQLERRM NOT LIKE '%mandatory declarations%' THEN RAISE; END IF;
    END;
    BEGIN
        INSERT INTO contract_acceptances (contract_id, user_id, body_sha256, ip, user_agent, clauses_approved)
        VALUES (cid, uid, sha, '192.0.2.1', 'verify', ok_clauses - 'end_client_informed');
        RAISE EXCEPTION 'FALLITO 6b: contratto 1 senza end_client_informed accettato';
    EXCEPTION WHEN integrity_constraint_violation THEN
        IF SQLERRM NOT LIKE '%mandatory declarations%' THEN RAISE; END IF;
    END;

    -- ---------- 7. firma su bozza, oppure SIGNED senza signed_at ----------
    IF has_v5 THEN
        BEGIN
            INSERT INTO contract_acceptances (contract_id, user_id, body_sha256, ip, user_agent, clauses_approved)
            VALUES (cid_d, uid, sha, '192.0.2.1', 'verify', ok_clauses);
            RAISE EXCEPTION 'FALLITO 7: firma su template non pubblicato accettata';
        EXCEPTION WHEN integrity_constraint_violation THEN
            IF SQLERRM NOT LIKE '%not published%'
               AND SQLERRM NOT LIKE '%draft cannot be signed%' THEN RAISE; END IF;
        END;
    ELSE
        BEGIN
            UPDATE reseller_contracts SET status = 'SIGNED' WHERE id = cid3;
            RAISE EXCEPTION 'FALLITO 7: SIGNED senza signed_at accettato';
        EXCEPTION
            WHEN check_violation THEN NULL;
            WHEN integrity_constraint_violation THEN NULL;
        END;
    END IF;

    -- percorso corretto C: firma completa (CUSTOM) e firma CODE senza DPA né cliente finale
    INSERT INTO contract_acceptances (contract_id, user_id, body_sha256, ip, user_agent, clauses_approved)
    VALUES (cid,  uid, sha, '192.0.2.1', 'verify', ok_clauses),
           (cid2, uid, sha, '192.0.2.1', 'verify',
            '{"acts_as_business":true,"contract_accepted":true,"burdensome_clauses":true,"contract_version":"1.0"}');
    UPDATE reseller_contracts SET status = 'SIGNED', signed_at = now() WHERE id IN (cid, cid2);
    PERFORM 1 FROM reseller_contracts
      WHERE id = cid AND status = 'SIGNED' AND signed_at IS NOT NULL;
    IF NOT FOUND THEN RAISE EXCEPTION 'FALLITO C: SIGNED senza signed_at'; END IF;

    RAISE NOTICE 'V3 verify: 7 rifiuti e 3 percorsi corretti OK';
END
$$;

ROLLBACK;
