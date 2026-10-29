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
```

`-i` senza `-t` per la redirezione `<`. `-X` ignora un eventuale `.psqlrc`.
La password del ruolo applicativo è quella della variabile
`PARTNER_DATASOURCE_PASSWORD` nel Secret; non va scritta nel repository.

Controllo atteso: 20 tabelle di proprietà del ruolo applicativo, e
CUSTOM 15%, ZONE 10%, CODE 7% in `reseller_programs`.

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
- `POST /applications` (due DB) non è atomico: prima partner `PENDING` sul
  database partner, poi utente sul database principale; se il secondo passo
  fallisce, rollback sulla riga partner.
- Controller: stessa `@ConditionalOnExpression` o `ObjectProvider<PartnerDb>` + 503,
  anche su timeout di connessione (5 s).
- Il controllo della data di apertura (1° novembre 2026) lo fa il backend su
  `reseller_programs.valid_from`. Il frontend abilita l’invio solo se
  `VITE_PARTNER_APPLICATIONS_API=true` in build **e** la data è passata.
  Il preventivo in Area Partner usa `VITE_PARTNER_QUOTES_API` a parte
  (e, quando ci sarà, GET /me approvato). Senza flag il pulsante resta spento
  anche dopo il 1° novembre.
- Mittente email partner: solo gli indirizzi ammessi dal CHECK su
  `email_outbox.from_address`, non `SMTP_FROM`.

Il Service Postgres e la NetworkPolicy subscriptions → Postgres :5432 sono
già aperti; i nomi si vedono con `kubectl get svc` nel namespace Postgres.

## Rilascio

1. Immagine `omero-auth` con `PartnerDb`, import in k3s, `helm upgrade`
   **senza** `PARTNER_DATASOURCE_URL` nel Secret.
   Il servizio deve partire identico a oggi.
2. Aggiungere nel Secret delle subscriptions (valori solo lì, non in git):
   `PARTNER_DATASOURCE_URL`, `PARTNER_DATASOURCE_USERNAME`,
   `PARTNER_DATASOURCE_PASSWORD`.
3. Rollout del deploy subscriptions e controllo nei log del pool Hikari
   partner alla prima chiamata a `/api/reseller`.

In PROD le stesse tre variabili stanno nel Secret dello Spring di produzione,
quando sarà un pod proprio.

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

`email_outbox.from_address` accetta solo i due mittenti registrati su Brevo
(CHECK in `01_schema.sql`). **Non** usare `SMTP_FROM` per le email partner:
l'INSERT fallirebbe. Il mittente va scelto nel codice del servizio partner.

## 20 tabelle

Partner: `resellers`, `reseller_email_tokens`  
Percentuali: `reseller_programs`  
Contratto 1: `custom_components`, `custom_requests`, `custom_request_components`, `custom_quotes`  
Firma: `contract_templates`, `reseller_contracts`, `contract_tokens`, `contract_acceptances`  
Contratto 2: `reseller_zones`, `leads`, `lead_assignments`  
Contratto 3: `reseller_codes`  
Soldi: `tenant_attributions`, `commissions`, `commission_statements`  
Servizio: `email_outbox`, `audit_log`

## Fatturazione (decisione prodotto)

Nei tre modelli **Telemaco fattura il canone al cliente finale a listino**;
il partner riceve una commissione (15 / 10 / 7), non uno sconto con rifatturazione.
Il prezzo al cliente non lo decide il partner. Base di calcolo: importi incassati,
al netto di IVA e sconti (stessa frase in pagina, nelle condizioni e nel calcolo
di `commissions`). Rischio Enasarco da coprire nel contratto scritto (non in pagina).
