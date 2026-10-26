package com.omero.auth.repository;

import com.omero.auth.entity.PlanWaitlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlanWaitlistRepository extends JpaRepository<PlanWaitlist, UUID> {
    boolean existsByEmailIgnoreCaseAndPlanCodeIgnoreCase(String email, String planCode);
}
