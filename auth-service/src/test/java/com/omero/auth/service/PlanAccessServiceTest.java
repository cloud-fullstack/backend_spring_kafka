package com.omero.auth.service;

import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanAccessServiceTest {

    @Mock UserRepository userRepository;
    @Mock TenantRepository tenantRepository;

    PlanAccessService service;

    @BeforeEach
    void setUp() {
        service = new PlanAccessService(userRepository, tenantRepository);
    }

    @Test
    void missingUserHasNoFeatures() {
        UUID uid = UUID.randomUUID();
        when(userRepository.findById(uid)).thenReturn(Optional.empty());

        assertFalse(service.hasFeature(uid, "chatbot.text"));
        assertFalse(service.isSubscriptionValid(uid));
        assertFalse(service.isActiveUser(uid));
    }

    @Test
    void unknownTenantStatusIsNotValid() {
        UUID uid = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        User user = new User();
        user.setId(uid);
        user.setActive(true);
        user.setPlanId("demo");
        user.setTenantId(tenantId);
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setStatus("unknown");
        when(userRepository.findById(uid)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertFalse(service.isSubscriptionValid(uid));
        assertFalse(service.hasFeature(uid, "chatbot.text"));
    }

    @Test
    void inactiveUserIsNotActive() {
        UUID uid = UUID.randomUUID();
        User user = new User();
        user.setId(uid);
        user.setActive(false);
        user.setPlanId("demo");
        when(userRepository.findById(uid)).thenReturn(Optional.of(user));

        assertFalse(service.isActiveUser(uid));
        assertFalse(service.hasFeature(uid, "chatbot.text"));
    }

    @Test
    void demoTenantHasChatbotText() {
        UUID uid = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        User user = new User();
        user.setId(uid);
        user.setActive(true);
        user.setPlanId("demo");
        user.setTenantId(tenantId);
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setStatus("demo");
        when(userRepository.findById(uid)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertTrue(service.isActiveUser(uid));
        assertTrue(service.hasFeature(uid, "chatbot.text"));
        assertFalse(service.hasFeature(uid, "voip"));
    }
}
