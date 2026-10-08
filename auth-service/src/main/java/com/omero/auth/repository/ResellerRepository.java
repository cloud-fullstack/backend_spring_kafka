package com.omero.auth.repository;

import com.omero.auth.entity.Reseller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ResellerRepository extends JpaRepository<Reseller, UUID> {
    Optional<Reseller> findByReferralCodeAndStatus(String referralCode, String status);
    boolean existsByReferralCode(String referralCode);
    Optional<Reseller> findByUserId(UUID userId);
}
