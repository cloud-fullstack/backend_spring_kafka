package com.omero.auth.service;

import com.omero.auth.dto.RegisterRequest;
import com.omero.auth.dto.ResellerRegisterRequest;
import com.omero.auth.entity.Reseller;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.BusinessVerticalRepository;
import com.omero.auth.repository.ResellerRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock UserRepository userRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ResellerRepository resellerRepository;
    @Mock BusinessVerticalRepository verticalRepository;
    @Mock PasswordEncoder passwordEncoder;

    RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(
                userRepository, tenantRepository, resellerRepository, verticalRepository, passwordEncoder);
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        lenient().when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
    }

    private RegisterRequest base() {
        return RegisterRequest.builder()
                .email("doc@example.com")
                .password("secret1234")
                .confirmPassword("secret1234")
                .vertical("medical")
                .companyName("Studio")
                .country("IT")
                .build();
    }

    @Test
    void registerIgnoresClientPlanAndStaysDemo() {
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(verticalRepository.existsByCodeAndActiveTrue("medical")).thenReturn(true);

        RegistrationService.Result result = service.registerCustomer(base());

        assertEquals("demo", result.user().getPlanId());
        ArgumentCaptor<Tenant> cap = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(cap.capture());
        assertEquals("demo", cap.getValue().getStatus());
    }

    @Test
    void validReferralSetsResellerId() {
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(verticalRepository.existsByCodeAndActiveTrue("medical")).thenReturn(true);
        Reseller reseller = new Reseller();
        reseller.setId(UUID.randomUUID());
        when(resellerRepository.findByReferralCodeAndStatus("PARTNERAB12", "active"))
                .thenReturn(Optional.of(reseller));

        RegisterRequest req = base();
        req.setReferralCode("PARTNERAB12");
        service.registerCustomer(req);

        ArgumentCaptor<Tenant> cap = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(cap.capture());
        assertEquals(reseller.getId(), cap.getValue().getResellerId());
        assertNotNull(cap.getValue().getResellerAttributedAt());
    }

    @Test
    void unknownReferralStillRegisters() {
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(verticalRepository.existsByCodeAndActiveTrue("medical")).thenReturn(true);
        when(resellerRepository.findByReferralCodeAndStatus("PARTNERZZ99", "active"))
                .thenReturn(Optional.empty());

        RegisterRequest req = base();
        req.setReferralCode("PARTNERZZ99");
        RegistrationService.Result result = service.registerCustomer(req);

        assertNotNull(result.user().getId());
        ArgumentCaptor<Tenant> cap = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(cap.capture());
        assertNull(cap.getValue().getResellerId());
    }

    @Test
    void passwordMismatchDoesNotCreateUser() {
        RegisterRequest req = base();
        req.setConfirmPassword("other");
        assertThrows(IllegalArgumentException.class, () -> service.registerCustomer(req));
        verify(userRepository, never()).save(any());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void existingEmailDoesNotCreateUser() {
        when(userRepository.existsByEmailIgnoreCase("doc@example.com")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.registerCustomer(base()));
        verify(userRepository, never()).save(any());
    }

    @Test
    void emailIsNormalizedOnce() {
        when(userRepository.existsByEmailIgnoreCase("doc@example.com")).thenReturn(false);
        when(verticalRepository.existsByCodeAndActiveTrue("medical")).thenReturn(true);

        RegisterRequest req = base();
        req.setEmail("  Doc@Example.COM ");
        RegistrationService.Result result = service.registerCustomer(req);

        assertEquals("doc@example.com", result.user().getEmail());
        ArgumentCaptor<Tenant> cap = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(cap.capture());
        assertEquals("doc@example.com", cap.getValue().getEmail());
        verify(userRepository).existsByEmailIgnoreCase("doc@example.com");
    }

    @Test
    void registerResellerSavesVatCountryAndStaysInactive() {
        when(userRepository.existsByEmailIgnoreCase("partner@example.com")).thenReturn(false);
        when(resellerRepository.existsByReferralCode(any())).thenReturn(false);
        when(resellerRepository.save(any(Reseller.class))).thenAnswer(inv -> inv.getArgument(0));

        ResellerRegisterRequest req = ResellerRegisterRequest.builder()
                .email("Partner@Example.com")
                .password("secret1234")
                .confirmPassword("secret1234")
                .companyName("Agency")
                .vatNumber("IT123")
                .country("it")
                .agreedToTerms(true)
                .build();

        RegistrationService.Result result = service.registerReseller(req);

        assertFalse(result.user().isActive());
        assertEquals("partner@example.com", result.user().getEmail());
        assertTrue(result.referralCode().startsWith("PARTNER"));
        assertEquals(13, result.referralCode().length());
        ArgumentCaptor<Reseller> cap = ArgumentCaptor.forClass(Reseller.class);
        verify(resellerRepository).save(cap.capture());
        assertEquals("IT123", cap.getValue().getVatNumber());
        assertEquals("IT", cap.getValue().getCountry());
        assertEquals("pending", cap.getValue().getStatus());
    }

    @Test
    void completeProfileSetsVerticalOnTenant() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(verticalRepository.existsByCodeAndActiveTrue("vehicle_rental")).thenReturn(true);

        service.completeProfile(userId, "vehicle_rental", "Noleggio");

        assertEquals("vehicle_rental", tenant.getVertical());
        assertEquals("Noleggio", tenant.getCompanyName());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void newReferralCodeSkipsExisting() {
        when(resellerRepository.existsByReferralCode(any())).thenReturn(true, true, false);
        String code = service.newReferralCode();
        assertTrue(code.startsWith("PARTNER"));
        assertEquals(13, code.length());
        verify(resellerRepository, times(3)).existsByReferralCode(any());
    }
}
