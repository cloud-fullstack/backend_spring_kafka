package com.omero.auth.repository;

import com.omero.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    Optional<User> findByResetToken(String resetToken);
    Optional<User> findFirstByTenantIdAndTenantAdminTrue(UUID tenantId);

    List<User> findByTenantId(UUID tenantId);
}
