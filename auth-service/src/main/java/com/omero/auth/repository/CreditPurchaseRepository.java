package com.omero.auth.repository;

import com.omero.auth.entity.CreditPurchase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CreditPurchaseRepository extends JpaRepository<CreditPurchase, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CreditPurchase> findByPaymentIntentId(String paymentIntentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO credit_purchases (
                checkout_session_id, tenant_id, payment_intent_id,
                credit_cents, amount_total_cents, reversed_cents, status)
            VALUES (:sid, :tenantId, NULLIF(:pi, ''), :credits, :total, 0, 'pending')
            ON CONFLICT (checkout_session_id) DO UPDATE SET
                payment_intent_id = COALESCE(credit_purchases.payment_intent_id, EXCLUDED.payment_intent_id),
                amount_total_cents = CASE
                    WHEN credit_purchases.amount_total_cents = 0 THEN EXCLUDED.amount_total_cents
                    ELSE credit_purchases.amount_total_cents END
            """, nativeQuery = true)
    int upsertPending(
            @Param("sid") String checkoutSessionId,
            @Param("tenantId") UUID tenantId,
            @Param("pi") String paymentIntentId,
            @Param("credits") long creditCents,
            @Param("total") long amountTotalCents);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE credit_purchases SET status = 'paid'
            WHERE checkout_session_id = :sid AND status = 'pending'
            """, nativeQuery = true)
    int markPaidIfPending(@Param("sid") String checkoutSessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE credit_purchases SET status = 'failed'
            WHERE checkout_session_id = :sid AND status = 'pending'
            """, nativeQuery = true)
    int markFailedIfPending(@Param("sid") String checkoutSessionId);
}
