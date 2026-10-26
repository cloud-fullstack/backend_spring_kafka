package com.omero.channels.repository;

import com.omero.channels.entity.WhatsAppAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WhatsAppAccountRepository extends JpaRepository<WhatsAppAccount, UUID> {
    Optional<WhatsAppAccount> findByPhoneNumberIdAndStatus(String phoneNumberId, String status);
}
