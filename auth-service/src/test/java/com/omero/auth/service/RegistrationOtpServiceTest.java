package com.omero.auth.service;

import com.omero.auth.dto.RegisterRequest;
import com.omero.auth.entity.User;
import com.omero.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationOtpServiceTest {

    @Mock UserRepository userRepository;
    @Mock RegistrationService registrationService;
    @Mock ObjectProvider<JavaMailSender> mailSender;
    @Mock JavaMailSender sender;

    RegistrationOtpService service;

    @BeforeEach
    void setUp() {
        lenient().when(mailSender.getIfAvailable()).thenReturn(sender);
        service = new RegistrationOtpService(
                userRepository, registrationService, mailSender, "noreply@example.com", 10);
    }

    private RegisterRequest base() {
        return RegisterRequest.builder()
                .email("  New@Example.com ")
                .password("secret1")
                .confirmPassword("secret1")
                .vertical("hospitality")
                .build();
    }

    @Test
    void startSendsCodeAndVerifyCreatesUser() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        User user = new User();
        user.setId(UUID.randomUUID());
        when(registrationService.registerCustomer(any())).thenReturn(new RegistrationService.Result(user, null));

        service.start(base());
        String code = capturedCode();
        RegistrationService.Result result = service.verify("new@example.com", code);

        assertEquals(user.getId(), result.user().getId());
        verify(registrationService).registerCustomer(any());
    }

    @Test
    void wrongCodeDoesNotCreateUser() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        service.start(base());
        assertThrows(IllegalArgumentException.class, () -> service.verify("new@example.com", "000000"));
        verify(registrationService, never()).registerCustomer(any());
    }

    @Test
    void existingEmailDoesNotStart() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.start(base()));
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void passwordMismatchDoesNotStart() {
        RegisterRequest req = base();
        req.setConfirmPassword("other");
        assertThrows(IllegalArgumentException.class, () -> service.start(req));
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    private String capturedCode() {
        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        Matcher m = Pattern.compile("(\\d{6})").matcher(cap.getValue().getText());
        assertTrue(m.find());
        return m.group(1);
    }
}
