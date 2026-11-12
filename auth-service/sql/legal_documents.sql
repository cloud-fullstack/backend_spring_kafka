-- Catalogo documenti legali tenant (DPA art. 28, privacy, termini).
-- Stesso blocco in omero_db_setup.sql. Eseguire su telemaco_db e omero_db
-- finché i due database non sono separati. Niente Flyway, niente secret.
--
--   kubectl exec -i -n telemaco-ns postgres-0 -- \
--     psql -U postgres -d telemaco_db < backend_spring_kafka/auth-service/sql/legal_documents.sql
--   kubectl exec -i -n telemaco-ns postgres-0 -- \
--     psql -U postgres -d omero_db < backend_spring_kafka/auth-service/sql/legal_documents.sql

BEGIN;

CREATE TABLE IF NOT EXISTS legal_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type        TEXT NOT NULL CHECK (doc_type IN (
                        'tenant_terms',
                        'tenant_privacy',
                        'dpa_art28',
                        'data_retention_policy'
                    )),
    version         TEXT NOT NULL,
    language        TEXT NOT NULL DEFAULT 'it',
    title           TEXT NOT NULL,
    body_sha256     TEXT NOT NULL DEFAULT 'pending-legal-review',
    pdf_url         TEXT,
    effective_from  DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (doc_type, version, language)
);

COMMENT ON TABLE legal_documents IS
    'DPA e informative verso i clienti che comprano Telemaco (tenant). Il DPA partner sta su feaci.';

INSERT INTO legal_documents (doc_type, version, language, title, body_sha256, effective_from)
VALUES
    ('dpa_art28', '1.0', 'it',
     'Accordo sul Trattamento dei Dati Personali (Art. 28 GDPR) — Clienti',
     'pending-legal-review', CURRENT_DATE),
    ('dpa_art28', '1.0', 'en',
     'Data Processing Agreement (Art. 28 GDPR) — Customers',
     'pending-legal-review', CURRENT_DATE),
    ('dpa_art28', '1.0', 'es',
     'Acuerdo de Encargo del Tratamiento (Art. 28 RGPD) — Clientes',
     'pending-legal-review', CURRENT_DATE),
    ('dpa_art28', '1.0', 'fr',
     'Accord de traitement des données (Art. 28 RGPD) — Clients',
     'pending-legal-review', CURRENT_DATE),
    ('dpa_art28', '1.0', 'de',
     'Auftragsverarbeitungsvertrag (Art. 28 DSGVO) — Kunden',
     'pending-legal-review', CURRENT_DATE),
    ('tenant_privacy', '1.0', 'it',
     'Informativa sul trattamento dei dati degli utenti finali',
     'pending-legal-review', CURRENT_DATE),
    ('data_retention_policy', '1.0', 'it',
     'Politica di conservazione e cancellazione per settore',
     'pending-legal-review', CURRENT_DATE)
ON CONFLICT (doc_type, version, language) DO NOTHING;

COMMIT;
