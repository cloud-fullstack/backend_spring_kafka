package com.omero.auth.service;

import com.omero.auth.entity.StripeSubscription;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.StripeSubscriptionRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class CancellationNoticeService {

    private static final Logger log = LoggerFactory.getLogger(CancellationNoticeService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALY);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final StripeSubscriptionRepository stripeSubscriptions;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final String mailFrom;

    public CancellationNoticeService(
            ObjectProvider<JavaMailSender> mailSender,
            StripeSubscriptionRepository stripeSubscriptions,
            TenantRepository tenants,
            UserRepository users,
            @Value("${app.mail-from:}") String mailFrom) {
        this.mailSender = mailSender;
        this.stripeSubscriptions = stripeSubscriptions;
        this.tenants = tenants;
        this.users = users;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
    }

    public void confirmScheduled(User user, OffsetDateTime endsAt) {
        String until = endsAt == null
                ? "alla fine del periodo già pagato"
                : "al " + format(endsAt);
        send(user, "Disdetta confermata",
                "Abbiamo registrato la disdetta. L'abbonamento resta attivo fino "
                        + until
                        + ". Dopo quella data l'assistente smette di rispondere. I dati del conto si cancellano dopo 90 giorni.");
    }

    @Scheduled(cron = "0 15 8 * * *", zone = "Europe/Rome")
    public void sendReminders() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (StripeSubscription row : stripeSubscriptions.findByCancelAtPeriodEndTrue()) {
            if (row.getPeriodEnd() == null || row.getTenantId() == null) {
                continue;
            }
            LocalDate end = row.getPeriodEnd().toLocalDate();
            User admin = users.findFirstByTenantIdAndTenantAdminTrue(row.getTenantId()).orElse(null);
            if (admin == null) {
                continue;
            }
            if (end.equals(today.plusDays(3))) {
                send(admin, "Abbonamento in scadenza tra 3 giorni",
                        "L'abbonamento termina il " + format(row.getPeriodEnd())
                                + ". Puoi riattivarlo dalla dashboard fino a quella data.");
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Tenant tenant : tenants.findByStatusAndSuspendedAtBefore("suspended", now)) {
            if (tenant.getSuspendedAt() == null || tenant.getSuspendedAt().isBefore(now.minusHours(24))) {
                continue;
            }
            User admin = users.findFirstByTenantIdAndTenantAdminTrue(tenant.getId()).orElse(null);
            if (admin == null) {
                continue;
            }
            send(admin, "Abbonamento terminato",
                    "L'abbonamento è terminato. I dati del conto si cancellano dopo 90 giorni.");
        }
    }

    private static String format(OffsetDateTime at) {
        if (at == null) {
            return "";
        }
        return at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().format(DAY);
    }

    private void send(User user, String subject, String body) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank()) {
            log.warn("SMTP not configured; cancellation mail not sent");
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(mailFrom);
            msg.setTo(user.getEmail());
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
        } catch (Exception e) {
            log.warn("cancellation mail failed user={}", user.getId());
        }
    }
}
