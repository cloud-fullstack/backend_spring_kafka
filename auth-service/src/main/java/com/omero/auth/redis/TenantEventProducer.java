package com.omero.auth.redis;

/**
 * Nomi stream Redis. L'XADD passa da OutboxRelay → RedisStreamEventPublisher.
 * Non è un bean: niente doppia pubblicazione.
 *
 * paid | trial.week | feature | deactivated
 * (trial.week, non telemacos:tenant:trial)
 */
public final class TenantEventProducer {

    static final String STREAM_PAID = "telemacos:tenant:paid";
    static final String STREAM_TRIAL7D = "telemacos:tenant:trial.week";
    static final String STREAM_FEATURE = "telemacos:tenant:feature";
    static final String STREAM_DEACT = "telemacos:tenant:deactivated";

    private TenantEventProducer() {}
}
