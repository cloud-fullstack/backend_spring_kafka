package com.omero.auth.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accesso a {@code feaci}. Volutamente non è un {@code DataSource},
 * {@code JdbcOperations} o {@code TransactionManager}: l'auto-config di Spring Boot
 * continua a creare omero_db, Hibernate e il {@code JpaTransactionManager}.
 *
 * <p>Regole per {@code /api/reseller}:
 * <ul>
 *   <li>Transazioni su feaci: {@link #inTransaction}, non {@code @Transactional}
 *       (quel bound è il manager JPA di omero_db).</li>
 *   <li>{@code POST /applications} tocca due database e non è atomico: prima il partner
 *       {@code PENDING} su feaci, poi l'utente su omero_db; se il secondo passo fallisce,
 *       cancellare o marcare la riga su feaci.</li>
 *   <li>Senza {@code PARTNER_DATASOURCE_URL} questo bean non esiste: i controller
 *       usano la stessa {@code @ConditionalOnExpression} oppure
 *       {@code ObjectProvider<PartnerDb>} e rispondono 503. Stesso 503 se feaci
 *       è irraggiungibile (timeout Hikari 5 s).</li>
 * </ul>
 */
public final class PartnerDb implements AutoCloseable {

    private final HikariDataSource ds;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;

    PartnerDb(HikariDataSource ds) {
        this.ds = ds;
        this.jdbc = new JdbcTemplate(ds);
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public NamedParameterJdbcTemplate named() {
        return named;
    }

    public <T> T inTransaction(TransactionCallback<T> cb) {
        return tx.execute(cb);
    }

    @Override
    public void close() {
        ds.close();
    }
}
