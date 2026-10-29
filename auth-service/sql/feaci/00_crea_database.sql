-- Eseguire con psql come superuser (non in una GUI senza \gexec).
-- Esempio:
--   kubectl exec -i -n telemaco-ns postgres-0 -- psql -U tikketetokkete -d postgres -f -
-- Non inserisce la password: dopo il CREATE ROLE
--   ALTER ROLE partner_app PASSWORD '<da-secret PARTNER_DATASOURCE_PASSWORD>';
-- Sul cluster attuale (feaci già creato, REVOKE/GRANT/COMMENT già applicati)
-- non serve rilanciare nulla.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'partner_app') THEN
    CREATE ROLE partner_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END
$$;

SELECT 'CREATE DATABASE feaci OWNER partner_app ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'feaci')\gexec

REVOKE ALL ON DATABASE feaci FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE feaci TO partner_app;
COMMENT ON DATABASE feaci IS 'Programma Partner Telemaco: partner, contratti, commissioni';
