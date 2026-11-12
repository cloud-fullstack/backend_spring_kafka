-- telemaco_db / omero_db: registro servizi satellite per tenant.
-- CREATE DATABASE circe / calipso / nausicaa / ermete a parte; niente password in git.
CREATE TABLE IF NOT EXISTS tenant_service_refs (
    tenant_id      UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    service        TEXT        NOT NULL,
    external_ref   TEXT        NOT NULL,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, service)
);
CREATE INDEX IF NOT EXISTS idx_tsrefs_service ON tenant_service_refs (service, external_ref);
