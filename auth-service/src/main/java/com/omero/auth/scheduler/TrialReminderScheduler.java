package com.omero.auth.scheduler;

import com.omero.auth.entity.Tenant;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.service.TrialReminderI18n;
import com.omero.auth.service.WelcomeEmailI18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import jakarta.mail.internet.MimeMessage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Demo emails at 3 days left, 1 day left, and expiry.
 * Timestamp columns are the scheduled send time; they are cleared after a successful send.
 * If SMTP is missing, {@link #send} returns false and the timestamps stay set so the
 * mail still goes out once SMTP is configured — they are not discarded.
 */
@Component
public class TrialReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialReminderScheduler.class);
    private static final List<String> DEMO = List.of("demo", "registered");

    private final TenantRepository tenants;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailFrom;
    private final String smtpPassword;
    private final String frontendBaseUrl;

    public TrialReminderScheduler(
            TenantRepository tenants,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail-from:}") String mailFrom,
            @Value("${spring.mail.password:}") String smtpPassword,
            @Value("${app.frontend-base-url:https://telemaco.app}") String frontendBaseUrl) {
        this.tenants = tenants;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.smtpPassword = smtpPassword == null ? "" : smtpPassword.trim();
        String base = frontendBaseUrl == null ? "https://telemaco.app" : frontendBaseUrl.trim();
        this.frontendBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Scheduled(cron = "0 20 * * * *")
    @Transactional
    public void sendDueReminders() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Tenant t : tenants.dueTrialReminder3d(DEMO, now)) {
            if (send(t, TrialReminderI18n.D3)) {
                t.setTrialReminder3dAt(null);
            }
        }
        for (Tenant t : tenants.dueTrialReminder1d(DEMO, now)) {
            if (send(t, TrialReminderI18n.D1)) {
                t.setTrialReminder1dAt(null);
            }
        }
        for (Tenant t : tenants.dueTrialReminder0d(DEMO, now)) {
            if (send(t, TrialReminderI18n.D0)) {
                t.setTrialReminder0dAt(null);
            }
        }
    }

    boolean send(Tenant tenant, TrialReminderI18n kind) {
        String to = tenant.getEmail();
        if (to == null || to.isBlank()) {
            return true;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank() || smtpPassword.isBlank()) {
            log.warn("Trial reminder skipped: SMTP not ready");
            return false;
        }
        try {
            WelcomeEmailI18n lang = WelcomeEmailI18n.fromCountry(tenant.getCountry());
            String dash = frontendBaseUrl + "/dashboard?tab=plans";
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(kind.subject(lang));
            helper.setText(kind.bodyHtml(lang, dash), true);
            sender.send(mime);
            log.info("trial reminder {} tenant={}", kind, tenant.getId());
            return true;
        } catch (Exception e) {
            log.warn("trial reminder failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
