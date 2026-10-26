package com.omero.channels.repository;

import com.omero.channels.entity.ChannelEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChannelEntitlementRepository extends JpaRepository<ChannelEntitlement, UUID> {
}
