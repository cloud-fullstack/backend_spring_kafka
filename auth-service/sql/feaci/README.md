# Database partner — Programma Partner Telemaco

Database **separato** da quello di auth/billing, sullo stesso Postgres del cluster.
Non ha foreign key verso `users` / `tenants`: `user_id` e `tenant_id` sono UUID;
la coerenza è nel codice (es. `tenant_attributions` nello stesso flusso che crea
il tenant).

Utente, nome database, host e password **non** stanno in questo file: sono nel
Secret delle subscriptions e negli script già eseguiti sul server.

## Script (eseguiti a mano, niente Flyway)

| File | Dove | Cosa |
|---|---|---|
| `00_crea_database.sql` | superuser sul cluster | ruolo applicativo + database partner |
| `01_schema.sql` | database partner | 20 tabelle, indici, CHECK, trigger (una sola volta) |
| `02_dati_iniziali.sql` | database partner | percentuali **15 / 10 / 7** dal 2026-11-01 e catalogo componenti |
| `V3_prepara.sql` | database partner, superuser | Prima di V3: toglie la V3 vecchia, o passa le tabelle della bozza a `partner_app`. Si ferma se ci sono dati reali |
| `V3__consensi_preventivi_firma.sql` | database partner, `SET ROLE partner_app` | Revisione 2. 23 tabelle. Proprietà `partner_app`. DPA su CUSTOM e ZONE. Commissione solo `RECURRING` |
| `V3_verify.sql` | feaci, `SET ROLE partner_app`, ROLLBACK | dopo V5: 7 rifiuti + 3 percorsi corretti. Versioni 998/999 con `retired_at`, anche a contratti veri già pubblicati |
| V4 | già sul cluster (non in git) | Paesi: CH e PT ammessi, GB e IE no |
| `V5_contract_templates.sql` | database partner, `SET ROLE partner_app` | tre template se mancano + trigger `contract_acceptances_published_template`. Firma solo dopo UPDATE del legale |
| `V6_dpa.sql` | database partner, `SET ROLE partner_app` | `reseller_contracts.type` ammette `DPA`; template e `legal_documents.dpa_annex_3` |

`01_schema.sql` e `02_dati_iniziali.sql` restano sul server dove sono stati
provati (PostgreSQL 16, ok da 11 in su). Copiarli qui quando si versionano.
`00` e `02` sono rilanciabili; `01` è in transazione: se fallisce a metà non lascia nulla.

`00` è per **psql** (`\gexec`). Su un cluster nuovo crea il database solo se manca;
`REVOKE` / `GRANT CONNECT, TEMPORARY` / `COMMENT` sono idempotenti. Non lanciare
`00` da un client senza `\gexec`. **Sul cluster attuale è già tutto applicato.**

### Comandi (cluster nuovo)

Dalla cartella con gli script, sul server dove si usa `kubectl`, con utente
superuser e nomi presi dal Secret / dallo stato del cluster (non da qui):

```bash
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -U <superuser> -d postgres -f - < 00_crea_database.sql
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -U <superuser> -d <db-partner> < 01_schema.sql
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -U <superuser> -d <db-partner> < 02_dati_iniziali.sql
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -v ON_ERROR_STOP=1 -U <superuser> -d <db-partner> < V3_prepara.sql
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -v ON_ERROR_STOP=1 -U <superuser> -d <db-partner> < V3__consensi_preventivi_firma.sql
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -v ON_ERROR_STOP=1 -U <superuser> -d <db-partner> < V5_contract_templates.sql
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -v ON_ERROR_STOP=1 -U <superuser> -d <db-partner> < V6_dpa.sql
kubectl exec -i -n <ns-postgres> <pod-postgres> -- psql -X -v ON_ERROR_STOP=1 -U <superuser> -d <db-partner> < V3_verify.sql
```

`-i` senza `-t` per la redirezione `<`. `-X` ignora un eventuale `.psqlrc`.
La password del ruolo applicativo è quella della variabile
`PARTNER_DATASOURCE_PASSWORD` nel Secret; non va scritta nel repository.

Controllo atteso dopo 01+02: 20 tabelle di proprietà del ruolo applicativo, e
CUSTOM 15%, ZONE 10%, CODE 7% in `reseller_programs`. Dopo V3: 23 tabelle.
`V3_verify.sql` è in transazione con ROLLBACK; non lascia dati.
Dopo V5 l’esito è *7 rifiuti e 3 percorsi corretti OK*. I template di prova
sono versioni 998/999 con `retired_at = now()`, così non toccano l’indice
`contract_templates_current_uq` se il legale ha già pubblicato i testi veri.

### Promemoria cluster

- **telemaco_db / omero_db** (noleggio demo): tenant vehicle `Rucchi*` → `Demo Vermietung` (script in `backend_llm/sql/rename_demo_vehicle_tenant.sql`).
- **feaci** (partner): revisione 2 (`V3_prepara.sql` poi `V3__consensi_preventivi_firma.sql`). V4 già applicata (CH, PT sì; GB, IE no). Template Area Partner: `V5_contract_templates.sql`. Verifica: `V3_verify.sql` dopo V5, *7 rifiuti e 3 percorsi corretti OK*, `ROLLBACK` (versioni 998/999 + `retired_at`, anche con contratti veri già pubblicati).

Fase A: `privacy_accepted_at` e `terms_summary_ack_at` restano di 01_schema. Marketing è già in 01; V3 aggiunge `profiling_consent_at` / `profiling_revoked_at`. Un nuovo consenso marketing azzera `marketing_revoked_at`. `reseller_programs.base` è `RECURRING` (niente commissione sull’attivazione).

### Flyway

Flyway **non** è nel `pom.xml`. Non aggiungere `spring.flyway.baseline-*`.
`PartnerDb` oggi **non espone** il `DataSource` (così l'auto-config di Spring Boot
non lo vede). Se un giorno servisse Flyway sul database partner: accessor aggiunto
solo in quel momento, bean Flyway dedicato a quel pool, cartella
`db/migration/feaci` (V3 in poi).
Non usare `ddl-auto: update` sul database partner.

## Spring

Classi in `com.omero.auth.config`:

| Classe | Ruolo |
|---|---|
| `PartnerDbProperties` | record `partner.datasource.*`; `toString()` non stampa la password |
| `PartnerDataSourceConfig` | crea il bean `PartnerDb` solo se l'URL è non vuoto (`@ConditionalOnExpression` + `hasText`) |
| `PartnerDb` | pool Hikari (pigro, max 5, timeout 5 s, `SET TIME ZONE 'UTC'`), `jdbc()`, `named()`, `inTransaction()` |

Default **vuoto**, come in `application.yml`. Un default JDBC farebbe partire
`PartnerDb` anche senza Secret.

```yaml
partner:
  datasource:
    url: ${PARTNER_DATASOURCE_URL:}
    username: ${PARTNER_DATASOURCE_USERNAME:}
    password: ${PARTNER_DATASOURCE_PASSWORD:}
```

Il bean esposto è solo `PartnerDb`. Nessun `DataSource` / `JdbcTemplate` /
`TransactionManager` del database partner, altrimenti Hibernate lo valida al
posto del database principale e l'avvio fallisce.

Regole per `/api/reseller`:

- Transazioni sul database partner: `partnerDb.inTransaction(...)`, non `@Transactional`.
- Utenti e referral: JPA sul **primario** (`SPRING_DATASOURCE_URL`). In cluster
  oggi è `telemaco_db` (stesso DB di `/api/auth/login`, `users`, `billing_accounts`).
  Non un `omero_db` distinto: se l’INSERT andasse lì, il login non troverebbe l’utente.
- `POST /applications` (due DB) non è atomico: `saveAndFlush` di utente+referral sul
  primario, poi reseller e `consent_records` su feaci. Se feaci fallisce, si
  cancellano le righe sul primario. Non il contrario: `consent_records` è append-only.
- Controller: `ObjectProvider<PartnerDb>` + 503 se manca l’URL o feaci è giù
  (timeout Hikari 5 s). `DataAccessResourceFailureException` → 503.
- Area privata, dopo login (`users.is_reseller`, `is_active`):
  - `GET /api/reseller/me` — anagrafica feaci + elenco `reseller_contracts`.
    Un candidato `PENDING_EMAIL` vede lo stato, non firma.
  - `POST /api/reseller/contracts` e `POST .../accept` richiedono
    `resellers.status` `APPROVED` o `ACTIVE` (altrimenti 403 Partner not approved).
  - Bozza da `contract_templates` (status `SENT`). Firma, stessa transazione:
    `contract_acceptances`, `reseller_contracts.status = 'SIGNED'` con
    `signed_at = now()`, e `resellers.status = 'ACTIVE'` se era `APPROVED`.
  - `intro` e `activity` del form vanno in `resellers.notes`.
  - Hash: `btrim` / `trim()`; pubblicazione con
    `encode(sha256(convert_to(...UTF8)), 'hex')` (vedi `V5_contract_templates.sql`).
    Trigger `contract_acceptances_published_template` rifiuta le bozze.
  - Consensi fase A: `clause_text` obbligatorio (testo mostrato); `document_id` e
    versione da `legal_documents` per ciascun documento.
  - IP firma: `CF-Connecting-IP` solo se la richiesta arriva da Traefik
    (`X-Forwarded-For` o `X-Forwarded-Host`). Affidabile solo se 80/443
    accettano traffico esclusivamente da Cloudflare; altrimenti l’intestazione
    è falsificabile. Finché non è verificato, l’IP è indizio, non prova unica.
  - Nessun endpoint porta un partner a `APPROVED`. A mano, finché manca il back-office:

        UPDATE resellers SET status = 'APPROVED', reviewed_at = now()
         WHERE lower(contact_email) = lower('partner@esempio.it');
- `/api/auth/reseller/register` resta il vecchio flusso solo sul primario (utente
  inattivo, niente feaci, niente bozza in Area Partner).
- Il controllo della data di apertura (1° novembre 2026) lo fa il backend su
  `reseller_programs.valid_from`. Il frontend abilita l’invio solo se
  `VITE_PARTNER_APPLICATIONS_API=true` in build **e** la data è passata.
  Il preventivo in Area Partner usa `VITE_PARTNER_QUOTES_API` a parte
  (e GET /me con `partnerRecord`). Senza flag il pulsante resta spento
  anche dopo il 1° novembre. La firma del quadro in Area Partner usa le
  API `/contracts` quando il partner è loggato; non dipende dal flag preventivi.
- Mittente email partner: solo gli indirizzi ammessi dal CHECK su
  `email_outbox.from_address`, non `SMTP_FROM`.

Il Service Postgres e la NetworkPolicy subscriptions → Postgres :5432 sono
già aperti; i nomi si vedono con `kubectl get svc` nel namespace Postgres.

## Rilascio

1. Immagine `omero-auth` con `PartnerDb`, import in k3s.
2. Le tre variabili feaci stanno in **omero-subscriptions-secrets** (pod
   `omero-omero-subscriptions`, anche telemaco.app). Senza di esse `/api/reseller` è 503.
   Dopo lo split: `telemaco-subscriptions-secrets` in PROD.
3. Rollout del deploy subscriptions e controllo nei log del pool Hikari
   partner alla prima chiamata a `/api/reseller`.

## Accesso alle tabelle: JdbcTemplate, niente `@Entity`

Hibernate ha un solo `EntityManager`, sul database principale. Un `@Entity` su
una tabella del database partner verrebbe validato contro il principale e
l'avvio fallirebbe. C'è anche conflitto di nome con la vecchia `resellers` del
database principale, che resta per `/api/reseller/me` finché il flusso non
legge solo il database partner.

Sul database partner: solo `PartnerDb` e `RowMapper`. Se un giorno servisse JPA
lì, servirebbe un secondo `EntityManagerFactory` dedicato. Oggi non conviene.

| Tipo | Con JdbcTemplate |
|---|---|
| enum (`status`, `type`) | String scritta e letta con `name()` e `valueOf()`, allineata ai CHECK |
| `TEXT[]` | in scrittura `connection.createArrayOf("text", arr)`, in lettura `(String[]) rs.getArray(col).getArray()` |
| `JSONB` | in scrittura il parametro con il cast `?::jsonb` (o `:p::jsonb`) e una stringa JSON, in lettura `rs.getString` con Jackson |
| `INET` | in scrittura `?::inet`, in lettura `rs.getString` |
| `updated_at` | lo gestisce il trigger, quindi non va messo negli UPDATE |

## Email

Due canali distinti:

- Account (OTP, reset password): Migadu, `noreply@telemaco.app` (`SMTP_*`).
- Programma partner (`email_outbox`): solo `info@telemaco.app` o `support@telemaco.app`
  (Brevo, CHECK in `01_schema.sql`). **Non** usare `SMTP_FROM` / `noreply@`: l'INSERT fallirebbe.

## 23 tabelle

Partner: `resellers`, `reseller_email_tokens`  
Percentuali: `reseller_programs`  
Contratto 1: `custom_components`, `custom_requests`, `custom_request_components`, `custom_quotes`  
Firma: `contract_templates`, `reseller_contracts`, `contract_tokens`, `contract_acceptances`  
Contratto 2: `reseller_zones`, `leads`, `lead_assignments`  
Contratto 3: `reseller_codes`  
Soldi: `tenant_attributions`, `commissions`, `commission_statements`  
Servizio: `email_outbox`, `audit_log`  
V3: `legal_documents`, `consent_records`, `quote_events`

Fase A (form pubblico): `privacy_accepted_at` e `terms_summary_ack_at` (01_schema); marketing/profilazione in `consent_records` e sulle quattro colonne V3. Il rifiuto dei facoltativi non blocca la candidatura.

Fase B (preventivo in Area Partner): `quote_events` `viewed` = presa visione senza impegno; `accepted` una sola volta; `sent_at` / `viewed_at` / `accepted_at` li scrive il trigger. Un `sent` senza PDF, un evento di un altro partner e una seconda accettazione sono rifiutati.

Fase C (firma): `clauses_approved` (colonna già in 01_schema). Obbligatorie: impresa, contratto, clausole onerose, `contract_version`. CUSTOM: cliente finale + DPA. ZONE: DPA. CODE: né DPA né cliente finale.

Su `quote_events` restano due trigger: `quote_events_check` (BEFORE) e `quote_events_apply` (AFTER).

## Fatturazione (decisione prodotto)

Nei tre modelli **Telemaco fattura il canone al cliente finale a listino**;
il partner riceve una commissione (15 / 10 / 7), non uno sconto con rifatturazione.
Il prezzo al cliente non lo decide il partner. Base di calcolo: importi incassati,
al netto di IVA e sconti (stessa frase in pagina, nelle condizioni e nel calcolo
di `commissions`). Rischio Enasarco da coprire nel contratto scritto (non in pagina).
