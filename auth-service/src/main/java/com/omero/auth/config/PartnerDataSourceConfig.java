package com.omero.auth.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Secondo database {@code feaci} (programma Partner). Non registra bean di tipo
 * {@code DataSource}, {@code JdbcOperations} o {@code TransactionManager}, così
 * l'auto-config resta sul datasource principale (omero_db / telemaco_db).
 *
 * <p>Parte solo se {@code PARTNER_DATASOURCE_URL} è non vuoto
 * ({@code @ConditionalOnProperty} tratterebbe anche la stringa vuota come presente).
 * Il pool Hikari è pigro: se feaci è giù, login e billing su omero_db restano su.
 */
@Configuration
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${partner.datasource.url:}')")
@EnableConfigurationProperties(PartnerDbProperties.class)
public class PartnerDataSourceConfig {

    @Bean(destroyMethod = "close")
    public PartnerDb partnerDb(PartnerDbProperties p) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("feaci");
        ds.setJdbcUrl(p.url());
        ds.setUsername(p.username());
        ds.setPassword(p.password());
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(5_000);
        ds.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new PartnerDb(ds);
    }
}
