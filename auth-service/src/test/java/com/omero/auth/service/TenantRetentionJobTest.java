package com.omero.auth.service;

import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantRetentionJobTest {

    @Mock TenantRepository tenants;
    @Mock UserRepository users;
    @Mock ChannelEntitlementClient operational;

    @Test
    void purgeAnonymizesCustomerUser() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Studio");
        tenant.setEmail("studio@example.com");
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setEmail("doc@example.com");
        user.setActive(true);
        when(users.findByTenantId(tenantId)).thenReturn(List.of(user));

        new TenantRetentionJob(tenants, users, operational).purge(tenant);
        verify(operational).purgeOperationalData(tenantId);

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(users).save(cap.capture());
        assertEquals("deleted-" + user.getId() + "@invalid.local", cap.getValue().getEmail());
        assertFalse(cap.getValue().isActive());
        assertEquals("deleted", tenant.getName());
        assertNull(tenant.getEmail());
    }
}
