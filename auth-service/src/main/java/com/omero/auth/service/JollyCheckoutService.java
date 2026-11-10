package com.omero.auth.service;

import com.omero.auth.entity.User;
import com.omero.auth.entity.WhatsAppNumberRequest;
import com.omero.auth.repository.UserRepository;
import com.omero.auth.repository.WhatsAppNumberRequestRepository;
import com.omero.auth.web.PlanNotAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class JollyCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(JollyCheckoutService.class);
    private static final List<String> OPEN_STATUSES = List.of("pending_payment", "requested", "number_assigned");

    private final BillingAccountService accounts;
    private final WhatsAppNumberRequestRepository numberRequests;
    private final StripeCheckoutService stripeCheckout;
    private final UserRepository users;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailFrom;
    private final String supportEmail;

    public JollyCheckoutService(
            BillingAccountService accounts,
            WhatsAppNumberRequestRepository numberRequests,
            StripeCheckoutService stripeCheckout,
            UserRepository users,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail-from:}") String mailFrom,
            @Value("${app.support-email:support@telemaco.app}") String supportEmail) {
        this.accounts = accounts;
        this.numberRequests = numberRequests;
        this.stripeCheckout = stripeCheckout;
        this.users = users;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.supportEmail = supportEmail == null ? "" : supportEmail.trim();
    }

    /** Checkout Jolly puro: il cliente ha già un numero Business. */
    public String checkoutDirect(UUID userId) {
        return stripeCheckout.jollyCheckoutUrl(userId, null);
    }

    /**
     * Draft in whatsapp_number_requests (pending_payment) + sessione Stripe
     * Jolly recurring + setup one-off (add_invoice_item, default 49 €).
     */
    @Transactional
    public String checkoutWithSetup(UUID userId, JollySetupRequest body) {
        User user = accounts.requireUser(userId);
        if (user.getTenantId() == null) {
            throw new PlanNotAvailableException();
        }
        if (body == null || !body.termsAccepted()) {
            throw new IllegalArgumentException("Invalid request");
        }
        String company = trim(body.companyName());
        String address = trim(body.address());
        String display = trim(body.displayName());
        if (company.isBlank() || address.isBlank() || display.isBlank()) {
            throw new IllegalArgumentException("Invalid request");
        }
        if (numberRequests.existsByTenantIdAndStatusIn(user.getTenantId(), OPEN_STATUSES)) {
            throw new IllegalStateException("Esiste già una richiesta numero aperta per questo account");
        }

        WhatsAppNumberRequest req = new WhatsAppNumberRequest();
        req.setTenantId(user.getTenantId());
        req.setCompanyName(company);
        req.setVatNumber(blankToNull(trim(body.vatNumber())));
        req.setAddress(address);
        req.setAreaCode(blankToNull(trim(body.areaCode())));
        req.setDisplayName(display);
        req.setTermsAccepted(true);
        req.setStatus("pending_payment");
        req = numberRequests.save(req);

        try {
            StripeCheckoutService.CreatedCheckout session = stripeCheckout.createJollyCheckout(userId, req.getId());
            if (session.sessionId() != null && !session.sessionId().isBlank()) {
                req.setStripeSessionId(session.sessionId());
                numberRequests.save(req);
            }
            return session.url();
        } catch (RuntimeException e) {
            numberRequests.delete(req);
            throw e;
        }
    }

    /** Webhook: pending_payment → requested, poi email di presa in carico. */
    @Transactional
    public void markRequested(UUID requestId, String sessionId) {
        WhatsAppNumberRequest found = null;
        if (requestId != null) {
            found = numberRequests.findById(requestId).orElse(null);
        }
        if (found == null && sessionId != null && !sessionId.isBlank()) {
            found = numberRequests.findByStripeSessionId(sessionId).orElse(null);
        }
        if (found == null) {
            return;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            found.setStripeSessionId(sessionId);
        }
        boolean becameRequested = "pending_payment".equalsIgnoreCase(found.getStatus());
        if (becameRequested) {
            found.setStatus("requested");
        }
        numberRequests.save(found);
        if (becameRequested) {
            log.info("Richiesta numero WA {} → requested (session {})", found.getId(), sessionId);
            notify(found);
        }
    }

    private void notify(WhatsAppNumberRequest row) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank()) {
            log.info("jolly number request tenant={} id={}", row.getTenantId(), row.getId());
            return;
        }
        String customerEmail = users.findByTenantId(row.getTenantId()).stream()
                .map(User::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse("");
        String body = "Richiesta numero WhatsApp Business (Jolly + setup)\n"
                + "tenant=" + row.getTenantId()
                + "\nrequest=" + row.getId()
                + "\nemail=" + customerEmail
                + "\ncompany=" + row.getCompanyName()
                + "\nvat=" + nullToEmpty(row.getVatNumber())
                + "\naddress=" + row.getAddress()
                + "\narea=" + nullToEmpty(row.getAreaCode())
                + "\ndisplay=" + row.getDisplayName()
                + "\n\nAssegnare un numero Telnyx, poi Embedded Signup Meta.";
        try {
            SimpleMailMessage ops = new SimpleMailMessage();
            ops.setFrom(mailFrom);
            ops.setTo(supportEmail.isBlank() ? (customerEmail.isBlank() ? mailFrom : customerEmail) : supportEmail);
            if (!customerEmail.isBlank()) {
                ops.setReplyTo(customerEmail);
            }
            ops.setSubject("Jolly · richiesta numero WhatsApp · " + row.getTenantId());
            ops.setText(body);
            sender.send(ops);
            if (!customerEmail.isBlank()) {
                SimpleMailMessage customer = new SimpleMailMessage();
                customer.setFrom(mailFrom);
                customer.setTo(customerEmail);
                customer.setSubject("Telemaco · richiesta numero WhatsApp ricevuta");
                customer.setText(
                        "Abbiamo preso in carico la richiesta di un numero WhatsApp Business.\n"
                                + "Ti scriviamo quando il numero è pronto per l'Embedded Signup.");
                sender.send(customer);
            }
        } catch (Exception e) {
            log.warn("jolly number-request mail failed tenant={}", row.getTenantId());
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
