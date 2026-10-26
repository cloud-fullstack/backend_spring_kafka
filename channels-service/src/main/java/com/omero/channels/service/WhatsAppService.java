package com.omero.channels.service;

import com.omero.channels.crypto.TokenCipher;
import com.omero.channels.entity.ChannelEntitlement;
import com.omero.channels.entity.WhatsAppAccount;
import com.omero.channels.entity.WhatsAppOnboarding;
import com.omero.channels.repository.ChannelEntitlementRepository;
import com.omero.channels.repository.WhatsAppAccountRepository;
import com.omero.channels.repository.WhatsAppOnboardingRepository;
import com.omero.channels.repository.WhatsAppWebhookEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class WhatsAppService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final List<String> OPEN = List.of("connected", "failed");

    private final WhatsAppOnboardingRepository onboarding;
    private final WhatsAppAccountRepository accounts;
    private final WhatsAppWebhookEventRepository events;
    private final ChannelEntitlementRepository entitlements;
    private final TokenCipher cipher;
    private final MetaGraphClient meta;
    private final String esConfigId;

    public WhatsAppService(
            WhatsAppOnboardingRepository onboarding,
            WhatsAppAccountRepository accounts,
            WhatsAppWebhookEventRepository events,
            ChannelEntitlementRepository entitlements,
            TokenCipher cipher,
            MetaGraphClient meta,
            @Value("${meta.es-config-id}") String esConfigId) {
        this.onboarding = onboarding;
        this.accounts = accounts;
        this.events = events;
        this.entitlements = entitlements;
        this.cipher = cipher;
        this.meta = meta;
        this.esConfigId = esConfigId;
    }

    public boolean entitled(UUID tenantId) {
        return entitlements.findById(tenantId).map(ChannelEntitlement::isWhatsapp).orElse(false);
    }

    @Transactional
    public Map<String, Object> startOnboarding(UUID tenantId, Map<String, Object> body) {
        if (!entitled(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String mode = str(body.get("mode"));
        if ("new_number".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not available yet");
        }
        if (!"existing_app".equals(mode) && !"existing_number".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String phone = str(body.get("phoneNumber"));
        if (!E164.matcher(phone).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String displayName = str(body.get("displayName"));
        if (displayName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        boolean backup = Boolean.TRUE.equals(body.get("backupAck"));
        boolean appAck = Boolean.TRUE.equals(body.get("appAck"));
        boolean notOnWa = Boolean.TRUE.equals(body.get("notOnWaAck"));
        if ("existing_app".equals(mode) && (!backup || !appAck)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if ("existing_number".equals(mode) && !notOnWa) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        WhatsAppOnboarding row = onboarding
                .findFirstByTenantIdAndStatusNotIn(tenantId, OPEN)
                .orElseGet(WhatsAppOnboarding::new);
        row.setTenantId(tenantId);
        row.setMode(mode);
        row.setPhoneNumber(phone);
        row.setDisplayName(displayName);
        row.setCategory(str(body.get("category")));
        row.setContactEmail(str(body.get("contactEmail")).toLowerCase());
        row.setBackupAck(backup);
        row.setNotOnWaAck(notOnWa);
        row.setStatus("draft");
        onboarding.save(row);
        Map<String, Object> out = toOnboarding(row);
        out.put("esConfigId", esConfigId);
        out.put("appId", meta.appId());
        return out;
    }

    public Map<String, Object> connect(UUID tenantId, Map<String, Object> body) {
        if (!entitled(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        UUID onboardingId = parseUuid(str(body.get("onboardingId")));
        WhatsAppOnboarding row = onboarding.findByIdAndTenantId(onboardingId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String pin = str(body.get("pin"));
        if ("existing_number".equals(row.getMode()) && !pin.matches("\\d{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PIN non valido");
        }
        String wabaId = str(body.get("wabaId"));
        String phoneNumberId = str(body.get("phoneNumberId"));
        if (!wabaId.matches("\\d+") || !phoneNumberId.matches("\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            String token = meta.exchangeCode(str(body.get("code")));
            if (!meta.phoneBelongsToWaba(wabaId, phoneNumberId, token)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            Map<?, ?> phone = meta.get("/" + phoneNumberId + "?fields=id,display_phone_number", token);
            if (phone == null || phone.get("id") == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            meta.subscribeApp(wabaId, token);
            if ("existing_number".equals(row.getMode())) {
                meta.registerPhone(phoneNumberId, token, pin);
            }
            String display = phone.get("display_phone_number") == null
                    ? row.getPhoneNumber()
                    : phone.get("display_phone_number").toString();
            WhatsAppAccount acc = accounts.findById(tenantId).orElseGet(WhatsAppAccount::new);
            acc.setTenantId(tenantId);
            acc.setWabaId(wabaId);
            acc.setPhoneNumberId(phoneNumberId);
            acc.setDisplayNumber(display);
            acc.setAccessTokenEnc(cipher.encrypt(token));
            acc.setStatus("connected");
            acc.setConnectedAt(OffsetDateTime.now(ZoneOffset.UTC));
            accounts.save(acc);
            row.setStatus("connected");
            row.setErrorReason(null);
            onboarding.save(row);
            return status(tenantId);
        } catch (RuntimeException e) {
            ResponseStatusException mapped = MetaErrors.wrap(e);
            row.setStatus("failed");
            row.setErrorReason(mapped.getReason());
            onboarding.save(row);
            throw mapped;
        }
    }

    public Map<String, Object> status(UUID tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        onboarding.findFirstByTenantIdOrderByUpdatedAtDesc(tenantId)
                .ifPresent(o -> m.put("onboarding", toOnboarding(o)));
        accounts.findById(tenantId).ifPresent(a -> {
            m.put("accountStatus", a.getStatus());
            m.put("displayNumber", a.getDisplayNumber());
            m.put("status", a.getStatus());
        });
        m.put("entitled", entitled(tenantId));
        m.put("appId", meta.appId());
        m.put("esConfigId", esConfigId);
        return m;
    }

    @Transactional
    public void disconnect(UUID tenantId) {
        accounts.findById(tenantId).ifPresent(a -> {
            a.setStatus("disconnected");
            a.setAccessTokenEnc("");
            accounts.save(a);
        });
    }

    @Transactional
    public boolean rememberMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        return events.insertIfAbsent(messageId) == 1;
    }

    public WhatsAppAccount accountByPhoneNumberId(String phoneNumberId) {
        return accounts.findByPhoneNumberIdAndStatus(phoneNumberId, "connected").orElse(null);
    }

    public void send(UUID tenantId, String to, String text) {
        WhatsAppAccount acc = accounts.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"connected".equalsIgnoreCase(acc.getStatus()) || !entitled(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        meta.sendText(acc.getPhoneNumberId(), cipher.decrypt(acc.getAccessTokenEnc()), to, text);
    }

    @Transactional
    public void applyEntitlement(UUID tenantId, boolean whatsapp) {
        ChannelEntitlement e = entitlements.findById(tenantId).orElseGet(ChannelEntitlement::new);
        e.setTenantId(tenantId);
        e.setWhatsapp(whatsapp);
        e.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entitlements.save(e);
        accounts.findById(tenantId).ifPresent(a -> {
            if (!whatsapp && "connected".equalsIgnoreCase(a.getStatus())) {
                a.setStatus("suspended");
                a.setEntitlementOffAt(OffsetDateTime.now(ZoneOffset.UTC));
                accounts.save(a);
            } else if (whatsapp && "suspended".equalsIgnoreCase(a.getStatus())) {
                a.setStatus("connected");
                a.setEntitlementOffAt(null);
                accounts.save(a);
            }
        });
    }

    // Con replicaCount > 1 serve ShedLock.
    @Scheduled(cron = "0 40 * * * *")
    public void disconnectAfterGrace() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        for (WhatsAppAccount a : accounts.findAll()) {
            if (!"suspended".equalsIgnoreCase(a.getStatus()) || a.getEntitlementOffAt() == null) {
                continue;
            }
            if (a.getEntitlementOffAt().isAfter(cutoff)) {
                continue;
            }
            a.setStatus("disconnected");
            a.setAccessTokenEnc("");
            accounts.save(a);
        }
    }

    @Transactional
    public void purgeOldWebhookEvents() {
        events.deleteOlderThan(OffsetDateTime.now(ZoneOffset.UTC).minusDays(14));
    }

    private static Map<String, Object> toOnboarding(WhatsAppOnboarding row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("mode", row.getMode());
        m.put("phoneNumber", row.getPhoneNumber());
        m.put("displayName", row.getDisplayName());
        m.put("status", row.getStatus());
        m.put("errorReason", row.getErrorReason());
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    public static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
