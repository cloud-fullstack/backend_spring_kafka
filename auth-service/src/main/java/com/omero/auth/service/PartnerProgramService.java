package com.omero.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.config.PartnerDb;
import com.omero.auth.dto.PartnerApplicationRequest;
import com.omero.auth.dto.PartnerConsentDto;
import com.omero.auth.dto.PartnerSignRequest;
import com.omero.auth.entity.Reseller;
import com.omero.auth.entity.User;
import com.omero.auth.repository.ResellerRepository;
import com.omero.auth.repository.UserRepository;
import com.omero.auth.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Programma partner su feaci via {@link PartnerDb}, non JPA.
 * Utenti e referral: stesso datasource di {@code /api/auth/login}
 * ({@code SPRING_DATASOURCE_URL}). In cluster oggi è {@code telemaco_db}
 * (users, billing_accounts). Non scrivere su un database omero_db distinto.
 */
@Service
public class PartnerProgramService {

    private static final Logger log = LoggerFactory.getLogger(PartnerProgramService.class);
    private static final String PENDING_LEGAL = "pending-legal-review";
    private static final Set<String> TYPES = Set.of("CUSTOM", "ZONE", "CODE", "DPA");
    private static final Set<String> PURPOSES = Set.of(
            "account_terms", "partner_privacy", "rates_summary", "marketing", "profiling",
            "acts_as_business", "contract_accepted", "burdensome_clauses",
            "end_client_informed", "dpa_annex_3");
    private static final Set<String> PHASE_A_MANDATORY =
            Set.of("account_terms", "partner_privacy", "rates_summary", "dpa_annex_3");
    private static final Set<String> PHASE_A_OPTIONAL = Set.of("marketing", "profiling");
    private static final Set<String> APPROVED_STATUSES = Set.of("APPROVED", "ACTIVE");

    private final ObjectProvider<PartnerDb> partnerDb;
    private final UserRepository userRepository;
    private final ResellerRepository resellerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwt;
    private final ObjectMapper objectMapper;

    public PartnerProgramService(
            ObjectProvider<PartnerDb> partnerDb,
            UserRepository userRepository,
            ResellerRepository resellerRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwt,
            ObjectMapper objectMapper) {
        this.partnerDb = partnerDb;
        this.userRepository = userRepository;
        this.resellerRepository = resellerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.objectMapper = objectMapper;
    }

    public boolean available() {
        return partnerDb.getIfAvailable() != null;
    }

    public record ApplicationResult(String token, String referralCode) {}

    @Transactional
    public ApplicationResult apply(PartnerApplicationRequest req, HttpServletRequest http) {
        PartnerDb db = requireDb();
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match");
        }
        if (!req.isAccountTerms() || !req.isRatesSummary() || !req.isDpaAnnex3()) {
            throw new IllegalArgumentException("Terms and conditions must be accepted");
        }
        String email = RegistrationService.normalizeEmail(req.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        String interest = typeOf(req.getInterest());
        String country = req.getCountry().trim().toUpperCase(Locale.ROOT);
        requireProgramOpen(db);
        requirePhaseAConsentTexts(req);
        String ip = inetOrNull(clientIp(http));
        String ua = userAgent(http);

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setFirstName(req.getFirstName().trim());
        user.setLastName(req.getLastName().trim());
        user.setPhoneNumber(req.getPhone().trim());
        // planId NOT NULL. Senza tenant_id non si apre un tenant demo: checkout,
        // billing e hasFeature richiedono un tenant. tenantAdmin resta false.
        user.setPlanId("demo");
        user.setBusinessName(req.getLegalName().trim());
        user.setActive(true);
        user.setReseller(true);
        user.setTenantAdmin(false);
        user = userRepository.saveAndFlush(user);

        Reseller referral = new Reseller();
        referral.setUserId(user.getId());
        referral.setCompanyName(req.getLegalName().trim());
        referral.setVatNumber(req.getVatNumber().trim());
        referral.setCountry(country);
        referral.setStatus("pending");
        referral.setReferralCode(newReferralCode());
        try {
            resellerRepository.saveAndFlush(referral);
        } catch (RuntimeException e) {
            userRepository.delete(user);
            throw e;
        }

        UUID userId = user.getId();
        try {
            db.inTransaction(status -> {
                UUID rid = insertReseller(db, userId, req, interest, country);
                insertPhaseAConsents(db, rid, userId, req, ip, ua);
                try {
                    insertContract(db, rid, "DPA");
                } catch (RuntimeException e) {
                    log.warn("feaci DPA contract not created at apply");
                }
                return rid;
            });
        } catch (RuntimeException e) {
            log.warn("feaci application insert failed");
            resellerRepository.delete(referral);
            userRepository.delete(user);
            if (e instanceof DataAccessException) {
                throw new IllegalArgumentException("Invalid request");
            }
            throw e;
        }
        return new ApplicationResult(jwt.generateToken(user), referral.getReferralCode());
    }

    public Map<String, Object> me(UUID userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        User user = userRepository.findById(userId).orElseThrow(() -> new SecurityException("Invalid credentials"));
        if (!user.isReseller()) {
            throw new SecurityException("Invalid credentials");
        }
        Reseller referral = resellerRepository.findByUserId(userId).orElse(null);
        body.put("status", referral == null ? "pending" : referral.getStatus());
        body.put("referralCode", referral == null ? null : referral.getReferralCode());
        body.put("companyName", referral == null ? user.getBusinessName() : referral.getCompanyName());
        body.put("partnerRecord", false);
        body.put("legalName", body.get("companyName"));
        body.put("country", referral == null ? null : referral.getCountry());
        body.put("interest", null);
        body.put("contractVersion", "1.0");
        body.put("contracts", List.of());
        PartnerDb db = partnerDb.getIfAvailable();
        if (db == null) {
            return body;
        }
        try {
            FeaciReseller row = findReseller(db, userId);
            if (row == null) {
                return body;
            }
            body.put("partnerRecord", true);
            body.put("legalName", row.legalName());
            body.put("country", row.country());
            body.put("interest", row.interest());
            body.put("status", row.status());
            body.put("approved", APPROVED_STATUSES.contains(row.status()));
            body.put("id", row.id().toString());
            body.put("contractVersion", documentVersion(db, "partnership_contract"));
            body.put("contracts", listContracts(db, row.id()));
            return body;
        } catch (DataAccessException e) {
            log.warn("feaci /me unavailable");
            throw new IllegalStateException("Service unavailable");
        }
    }

    public Map<String, Object> ensureContract(UUID userId, String rawType) {
        PartnerDb db = requireDb();
        String type = typeOf(rawType);
        FeaciReseller row = requireReseller(db, userId);
        if (!"DPA".equals(type) && signedOfType(db, row.id(), "DPA") == null) {
            throw new IllegalArgumentException("DPA must be signed");
        }
        try {
            Map<String, Object> signed = signedOfType(db, row.id(), type);
            if (signed != null) {
                return signed;
            }
            Map<String, Object> existing = unsignedOfType(db, row.id(), type);
            if (existing != null) {
                Template tpl = latestTemplate(db, type);
                if (tpl != null && tpl.published() && Boolean.TRUE.equals(existing.get("draftOnly"))) {
                    refreshFromPublishedTemplate(db, UUID.fromString(String.valueOf(existing.get("id"))), tpl);
                    return getContract(db, row.id(), UUID.fromString(String.valueOf(existing.get("id"))));
                }
                return existing;
            }
            return db.inTransaction(status -> insertContract(db, row.id(), type));
        } catch (DataAccessException e) {
            log.warn("feaci ensureContract failed");
            throw new IllegalArgumentException("Contract template not available");
        }
    }

    public Map<String, Object> contract(UUID userId, UUID contractId) {
        PartnerDb db = requireDb();
        FeaciReseller row = requireReseller(db, userId);
        Map<String, Object> found = getContract(db, row.id(), contractId);
        if (found == null) {
            throw new IllegalArgumentException("Invalid request");
        }
        return found;
    }

    public Map<String, Object> accept(UUID userId, UUID contractId, PartnerSignRequest req, HttpServletRequest http) {
        PartnerDb db = requireDb();
        FeaciReseller row = requireReseller(db, userId);
        Map<String, Object> current = getContract(db, row.id(), contractId);
        if (current == null) {
            throw new IllegalArgumentException("Invalid request");
        }
        if (Boolean.TRUE.equals(current.get("signed"))) {
            throw new IllegalArgumentException("Invalid request");
        }
        if (!Boolean.TRUE.equals(current.get("signable"))) {
            throw new IllegalArgumentException("Contract template not available");
        }
        String type = String.valueOf(current.get("type"));
        Map<String, Object> clauses = req.getClausesApproved() == null
                ? Map.of() : req.getClausesApproved();
        if (!clausesComplete(type, clauses)) {
            throw new IllegalArgumentException("Signature missing mandatory declarations");
        }
        String sha = normalizedSha(String.valueOf(current.get("bodySha256")));
        String ip = inetOrNull(clientIp(http));
        String ua = userAgent(http);
        try {
            String clausesJson = objectMapper.writeValueAsString(clauses);
            db.inTransaction(status -> {
                insertSignConsents(db, row.id(), userId, type, req, ip, ua);
                MapSqlParameterSource p = new MapSqlParameterSource()
                        .addValue("cid", contractId)
                        .addValue("uid", userId)
                        .addValue("sha", sha)
                        .addValue("ip", ip)
                        .addValue("ua", ua)
                        .addValue("clauses", clausesJson);
                db.named().update("""
                        INSERT INTO contract_acceptances
                            (contract_id, user_id, body_sha256, ip, user_agent, clauses_approved)
                        VALUES
                            (:cid, :uid, :sha, CAST(:ip AS inet), :ua, CAST(:clauses AS jsonb))
                        """, p);
                db.named().update("""
                        UPDATE reseller_contracts SET status = 'SIGNED', signed_at = now() WHERE id = :cid
                        """, new MapSqlParameterSource("cid", contractId));
                db.named().update("""
                        UPDATE resellers SET status = 'ACTIVE' WHERE id = :rid AND status = 'APPROVED'
                        """, new MapSqlParameterSource("rid", row.id()));
                return true;
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request");
        } catch (DataAccessException e) {
            log.warn("feaci accept failed");
            throw new IllegalArgumentException("Signature missing mandatory declarations");
        }
        return getContract(db, row.id(), contractId);
    }

    PartnerDb requireDb() {
        PartnerDb db = partnerDb.getIfAvailable();
        if (db == null) {
            throw new IllegalStateException("Service unavailable");
        }
        return db;
    }

    static String typeOf(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Invalid request");
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) {
            throw new IllegalArgumentException("Invalid request");
        }
        return t;
    }

    static int rateBps(String type) {
        return switch (type) {
            case "CUSTOM" -> 1500;
            case "ZONE" -> 1000;
            case "CODE" -> 700;
            case "DPA" -> 0;
            default -> throw new IllegalArgumentException("Invalid request");
        };
    }

    static String normalizedSha(String sha) {
        return sha == null ? "" : sha.trim();
    }

    static String templateSha(String raw) {
        String s = normalizedSha(raw);
        if (s.isEmpty() || PENDING_LEGAL.equalsIgnoreCase(s)) {
            return PENDING_LEGAL;
        }
        return s;
    }

    static boolean signableHash(String sha) {
        String s = normalizedSha(sha);
        return s.length() == 64 && !PENDING_LEGAL.equalsIgnoreCase(s);
    }

    static boolean draftOnly(String sha, boolean published) {
        return !published || PENDING_LEGAL.equalsIgnoreCase(normalizedSha(sha));
    }

    static boolean canSign(String sha, boolean published, boolean signed) {
        return !signed && published && signableHash(sha);
    }

    static boolean clausesComplete(String type, Map<String, Object> c) {
        if (c == null) {
            return false;
        }
        if (!flag(c, "acts_as_business") || !flag(c, "contract_accepted") || !flag(c, "burdensome_clauses")) {
            return false;
        }
        if (stringVal(c.get("contract_version")).isBlank()) {
            return false;
        }
        if ("CUSTOM".equals(type) && !flag(c, "end_client_informed")) {
            return false;
        }
        if (("CUSTOM".equals(type) || "ZONE".equals(type)) && !flag(c, "dpa_annex_3")) {
            return false;
        }
        return true;
    }

    static boolean flag(Map<String, Object> c, String key) {
        Object v = c.get(key);
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    static String inetOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return s;
        }
        if (s.contains(":") && s.length() <= 45) {
            return s;
        }
        return null;
    }

    static String sha256Hex(String body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Service unavailable");
        }
    }

    private void requireProgramOpen(PartnerDb db) {
        Integer n = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM reseller_programs WHERE valid_from <= CURRENT_DATE",
                Integer.class);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("Partner program is not open");
        }
    }

    private UUID insertReseller(
            PartnerDb db, UUID userId, PartnerApplicationRequest req, String interest, String country) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("first", req.getFirstName().trim())
                .addValue("last", req.getLastName().trim())
                .addValue("email", RegistrationService.normalizeEmail(req.getEmail()))
                .addValue("phone", req.getPhone().trim())
                .addValue("legal", req.getLegalName().trim())
                .addValue("country", country)
                .addValue("vat", req.getVatNumber().trim())
                .addValue("interest", interest)
                .addValue("notes", applicationNotes(req));
        return db.named().queryForObject("""
                INSERT INTO resellers (
                    user_id, contact_first_name, contact_last_name, contact_email, contact_phone,
                    legal_name, country, vat_number, interest, notes,
                    privacy_accepted_at, terms_summary_ack_at)
                VALUES (
                    :userId, :first, :last, :email, :phone,
                    :legal, :country, :vat, :interest, :notes, now(), now())
                RETURNING id
                """, p, (rs, i) -> rs.getObject("id", UUID.class));
    }

    private void requirePhaseAConsentTexts(PartnerApplicationRequest req) {
        Map<String, PartnerConsentDto> byPurpose = consentsByPurpose(req.getConsents());
        for (String purpose : PHASE_A_MANDATORY) {
            if (clauseText(byPurpose.get(purpose)).isBlank()) {
                throw new IllegalArgumentException("Terms and conditions must be accepted");
            }
        }
        for (String purpose : PHASE_A_OPTIONAL) {
            if (clauseText(byPurpose.get(purpose)).isBlank()) {
                throw new IllegalArgumentException("Terms and conditions must be accepted");
            }
        }
    }

    private void insertPhaseAConsents(
            PartnerDb db, UUID rid, UUID userId, PartnerApplicationRequest req, String ip, String ua) {
        Map<String, PartnerConsentDto> byPurpose = consentsByPurpose(req.getConsents());
        insertConsentFrom(db, rid, userId, "account_terms", true, byPurpose, ip, ua);
        insertConsentFrom(db, rid, userId, "partner_privacy", true, byPurpose, ip, ua);
        insertConsentFrom(db, rid, userId, "rates_summary", true, byPurpose, ip, ua);
        insertConsentFrom(db, rid, userId, "dpa_annex_3", true, byPurpose, ip, ua);
        insertConsentFrom(db, rid, userId, "marketing", req.isMarketing(), byPurpose, ip, ua);
        insertConsentFrom(db, rid, userId, "profiling", req.isProfiling(), byPurpose, ip, ua);
    }

    private void insertSignConsents(
            PartnerDb db, UUID rid, UUID userId, String type, PartnerSignRequest req,
            String ip, String ua) {
        List<PartnerConsentDto> rows = req.getConsents() == null ? List.of() : req.getConsents();
        Map<String, PartnerConsentDto> byPurpose = consentsByPurpose(rows);
        for (PartnerConsentDto c : rows) {
            if (c == null || c.getPurpose() == null) {
                continue;
            }
            String purpose = c.getPurpose().trim();
            if (!PURPOSES.contains(purpose) || PHASE_A_MANDATORY.contains(purpose)
                    || PHASE_A_OPTIONAL.contains(purpose)) {
                continue;
            }
            if ("end_client_informed".equals(purpose) && !"CUSTOM".equals(type)) {
                continue;
            }
            if ("dpa_annex_3".equals(purpose) && !("CUSTOM".equals(type) || "ZONE".equals(type))) {
                continue;
            }
            if (!c.isGranted() || clauseText(c).isBlank()) {
                throw new IllegalArgumentException("Signature missing mandatory declarations");
            }
        }
        insertConsentFrom(db, rid, userId, "acts_as_business", true, byPurpose, ip, ua);
        insertConsentFrom(db, rid, userId, "contract_accepted", true, byPurpose, ip, ua);
        insertConsentFrom(db, rid, userId, "burdensome_clauses", true, byPurpose, ip, ua);
        if ("CUSTOM".equals(type)) {
            insertConsentFrom(db, rid, userId, "end_client_informed", true, byPurpose, ip, ua);
        }
        if ("CUSTOM".equals(type) || "ZONE".equals(type)) {
            insertConsentFrom(db, rid, userId, "dpa_annex_3", true, byPurpose, ip, ua);
        }
    }

    private void insertConsentFrom(
            PartnerDb db, UUID rid, UUID userId, String purpose, boolean granted,
            Map<String, PartnerConsentDto> byPurpose, String ip, String ua) {
        PartnerConsentDto c = byPurpose.get(purpose);
        String text = clauseText(c);
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    PHASE_A_MANDATORY.contains(purpose) || PHASE_A_OPTIONAL.contains(purpose)
                            ? "Terms and conditions must be accepted"
                            : "Signature missing mandatory declarations");
        }
        LegalDoc doc = legalDoc(db, docTypeFor(purpose));
        String version = doc != null ? doc.version() : "1.0";
        insertConsent(db, rid, userId, purpose, granted, doc == null ? null : doc.id(), version, text, ip, ua);
    }

    private void insertConsent(
            PartnerDb db, UUID rid, UUID userId, String purpose, boolean granted,
            UUID documentId, String version, String text, String ip, String ua) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("rid", rid)
                .addValue("uid", userId)
                .addValue("purpose", purpose)
                .addValue("granted", granted)
                .addValue("doc", documentId)
                .addValue("ver", version)
                .addValue("text", text)
                .addValue("ip", ip)
                .addValue("ua", ua);
        db.named().update("""
                INSERT INTO consent_records
                    (reseller_id, user_id, purpose, granted, document_id, document_version, clause_text, ip, user_agent)
                VALUES
                    (:rid, :uid, :purpose, :granted, :doc, :ver, :text, CAST(:ip AS inet), :ua)
                """, p);
    }

    private FeaciReseller requireReseller(PartnerDb db, UUID userId) {
        FeaciReseller row = findReseller(db, userId);
        if (row == null) {
            throw new IllegalArgumentException("Partner not found");
        }
        if (!APPROVED_STATUSES.contains(row.status())) {
            throw new SecurityException("Partner not approved");
        }
        return row;
    }

    private FeaciReseller findReseller(PartnerDb db, UUID userId) {
        try {
            return db.named().queryForObject("""
                    SELECT id, legal_name, country, vat_number, interest, status
                      FROM resellers
                     WHERE user_id = :uid
                    """, new MapSqlParameterSource("uid", userId),
                    (rs, i) -> new FeaciReseller(
                            rs.getObject("id", UUID.class),
                            rs.getString("legal_name"),
                            rs.getString("country"),
                            rs.getString("vat_number"),
                            rs.getString("interest"),
                            normalizedStatus(rs.getString("status"))));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private List<Map<String, Object>> listContracts(PartnerDb db, UUID resellerId) {
        return db.named().query("""
                SELECT c.id, c.type, c.status, c.rate_bps, c.body_sha256, c.rendered_body,
                       t.version AS template_version, t.title, t.published_at,
                       EXISTS (
                           SELECT 1 FROM contract_acceptances a WHERE a.contract_id = c.id
                       ) AS signed
                  FROM reseller_contracts c
                  JOIN contract_templates t ON t.id = c.template_id
                 WHERE c.reseller_id = :rid
                 ORDER BY t.version DESC
                """, new MapSqlParameterSource("rid", resellerId),
                (rs, i) -> toContract(rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getInt("rate_bps"),
                        rs.getString("body_sha256"),
                        rs.getString("rendered_body"),
                        rs.getString("title"),
                        String.valueOf(rs.getObject("template_version")),
                        rs.getBoolean("signed"),
                        rs.getTimestamp("published_at") != null));
    }

    private Map<String, Object> unsignedOfType(PartnerDb db, UUID resellerId, String type) {
        List<Map<String, Object>> rows = db.named().query("""
                SELECT c.id, c.type, c.status, c.rate_bps, c.body_sha256, c.rendered_body,
                       t.version AS template_version, t.title, t.published_at
                  FROM reseller_contracts c
                  JOIN contract_templates t ON t.id = c.template_id
                 WHERE c.reseller_id = :rid AND c.type = :type
                   AND NOT EXISTS (SELECT 1 FROM contract_acceptances a WHERE a.contract_id = c.id)
                 ORDER BY t.version DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("rid", resellerId)
                        .addValue("type", type),
                (rs, i) -> toContract(rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getInt("rate_bps"),
                        rs.getString("body_sha256"),
                        rs.getString("rendered_body"),
                        rs.getString("title"),
                        String.valueOf(rs.getObject("template_version")),
                        false,
                        rs.getTimestamp("published_at") != null));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> signedOfType(PartnerDb db, UUID resellerId, String type) {
        List<Map<String, Object>> rows = db.named().query("""
                SELECT c.id, c.type, c.status, c.rate_bps, c.body_sha256, c.rendered_body,
                       t.version AS template_version, t.title, t.published_at
                  FROM reseller_contracts c
                  JOIN contract_templates t ON t.id = c.template_id
                 WHERE c.reseller_id = :rid AND c.type = :type AND c.status = 'SIGNED'
                 ORDER BY t.version DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("rid", resellerId)
                        .addValue("type", type),
                (rs, i) -> toContract(rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getInt("rate_bps"),
                        rs.getString("body_sha256"),
                        rs.getString("rendered_body"),
                        rs.getString("title"),
                        String.valueOf(rs.getObject("template_version")),
                        true,
                        rs.getTimestamp("published_at") != null));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> getContract(PartnerDb db, UUID resellerId, UUID contractId) {
        try {
            return db.named().queryForObject("""
                    SELECT c.id, c.type, c.status, c.rate_bps, c.body_sha256, c.rendered_body,
                           t.version AS template_version, t.title, t.published_at,
                           EXISTS (
                               SELECT 1 FROM contract_acceptances a WHERE a.contract_id = c.id
                           ) AS signed
                      FROM reseller_contracts c
                      JOIN contract_templates t ON t.id = c.template_id
                     WHERE c.id = :cid AND c.reseller_id = :rid
                    """, new MapSqlParameterSource()
                            .addValue("cid", contractId)
                            .addValue("rid", resellerId),
                    (rs, i) -> toContract(rs.getObject("id", UUID.class),
                            rs.getString("type"),
                            rs.getString("status"),
                            rs.getInt("rate_bps"),
                            rs.getString("body_sha256"),
                            rs.getString("rendered_body"),
                            rs.getString("title"),
                            String.valueOf(rs.getObject("template_version")),
                            rs.getBoolean("signed"),
                            rs.getTimestamp("published_at") != null));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Map<String, Object> insertContract(PartnerDb db, UUID resellerId, String type) {
        Template tpl = latestTemplate(db, type);
        if (tpl == null) {
            throw new IllegalArgumentException("Contract template not available");
        }
        String body = tpl.body() == null ? "" : tpl.body();
        String sha = templateSha(tpl.sha256());
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("rid", resellerId)
                .addValue("type", type)
                .addValue("tid", tpl.id())
                .addValue("bps", rateBps(type))
                .addValue("body", body)
                .addValue("sha", sha);
        UUID id = db.named().queryForObject("""
                INSERT INTO reseller_contracts
                    (reseller_id, type, template_id, rate_bps, status, rendered_body, body_sha256)
                VALUES
                    (:rid, :type, :tid, :bps, 'SENT', :body, :sha)
                RETURNING id
                """, p, (rs, i) -> rs.getObject("id", UUID.class));
        return toContract(id, type, "SENT", rateBps(type), sha, body, tpl.title(),
                String.valueOf(tpl.version()), false, tpl.published());
    }

    private void refreshFromPublishedTemplate(PartnerDb db, UUID contractId, Template tpl) {
        String body = tpl.body() == null ? "" : tpl.body();
        String sha = templateSha(tpl.sha256());
        db.named().update("""
                UPDATE reseller_contracts
                   SET rendered_body = :body, body_sha256 = :sha, template_id = :tid
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("body", body)
                .addValue("sha", sha)
                .addValue("tid", tpl.id())
                .addValue("id", contractId));
    }

    private Template latestTemplate(PartnerDb db, String type) {
        try {
            return db.named().queryForObject("""
                    SELECT id, type, version, title, body_markdown, body_sha256, published_at
                      FROM contract_templates
                     WHERE type = :type
                     ORDER BY (published_at IS NOT NULL) DESC, version DESC
                     LIMIT 1
                    """, new MapSqlParameterSource("type", type),
                    (rs, i) -> new Template(
                            rs.getObject("id", UUID.class),
                            rs.getString("type"),
                            rs.getObject("version"),
                            rs.getString("title"),
                            rs.getString("body_markdown"),
                            rs.getString("body_sha256"),
                            rs.getTimestamp("published_at") != null));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String documentVersion(PartnerDb db, String docType) {
        LegalDoc doc = legalDoc(db, docType);
        return doc == null ? "1.0" : doc.version();
    }

    private LegalDoc legalDoc(PartnerDb db, String docType) {
        try {
            return db.named().queryForObject("""
                    SELECT id, version FROM legal_documents
                     WHERE doc_type = :t
                     ORDER BY effective_from DESC
                     LIMIT 1
                    """, new MapSqlParameterSource("t", docType),
                    (rs, i) -> new LegalDoc(
                            rs.getObject("id", UUID.class),
                            rs.getString("version") == null || rs.getString("version").isBlank()
                                    ? "1.0" : rs.getString("version")));
        } catch (DataAccessException e) {
            return null;
        }
    }

    private Map<String, Object> toContract(
            UUID id, String type, String status, int rateBps, String sha, String body,
            String title, String version, boolean signed, boolean published) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("type", type);
        m.put("status", status);
        m.put("rateBps", rateBps);
        m.put("title", title);
        m.put("version", version);
        m.put("bodyMarkdown", body);
        m.put("bodySha256", sha);
        m.put("published", published);
        m.put("signable", canSign(sha, published, signed));
        m.put("signed", signed);
        m.put("draftOnly", draftOnly(sha, published));
        return m;
    }

    private String newReferralCode() {
        for (int i = 0; i < 20; i++) {
            String code = "PARTNER" + randomCode(6);
            if (!resellerRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Service unavailable");
    }

    private static String randomCode(int len) {
        String alphanum = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rng = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphanum.charAt(rng.nextInt(alphanum.length())));
        }
        return sb.toString();
    }

    static String applicationNotes(PartnerApplicationRequest req) {
        String activity = req.getActivity() == null ? "" : req.getActivity().trim();
        String intro = req.getIntro() == null ? "" : req.getIntro().trim();
        if (activity.isBlank()) {
            return intro.isBlank() ? null : intro;
        }
        if (intro.isBlank()) {
            return activity;
        }
        return activity + "\n\n" + intro;
    }

    static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        // CF-Connecting-IP è il partner solo se l’origine accetta traffico
        // esclusivamente da Cloudflare. Se omero.dev è raggiungibile in diretto,
        // l’intestazione è falsificabile: Traefik la inoltra.
        boolean viaTraefik = hasHeader(request, "X-Forwarded-For") || hasHeader(request, "X-Forwarded-Host");
        if (viaTraefik) {
            String cf = header(request, "CF-Connecting-IP");
            if (inetOrNull(cf) != null) {
                return cf.trim();
            }
        }
        String real = header(request, "X-Real-IP");
        if (inetOrNull(real) != null) {
            return real.trim();
        }
        String forwarded = header(request, "X-Forwarded-For");
        if (forwarded != null) {
            String[] parts = forwarded.split(",");
            return parts[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > 512 ? ua.substring(0, 512) : ua;
    }

    private static String stringVal(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String normalizedStatus(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, PartnerConsentDto> consentsByPurpose(List<PartnerConsentDto> rows) {
        Map<String, PartnerConsentDto> byPurpose = new LinkedHashMap<>();
        if (rows == null) {
            return byPurpose;
        }
        for (PartnerConsentDto c : rows) {
            if (c == null || c.getPurpose() == null) {
                continue;
            }
            String purpose = c.getPurpose().trim();
            if (PURPOSES.contains(purpose)) {
                byPurpose.put(purpose, c);
            }
        }
        return byPurpose;
    }

    private static String clauseText(PartnerConsentDto c) {
        return c == null || c.getClauseText() == null ? "" : c.getClauseText().trim();
    }

    private static String docTypeFor(String purpose) {
        return switch (purpose) {
            case "account_terms" -> "partner_account_terms";
            case "partner_privacy", "marketing", "profiling" -> "partner_privacy";
            case "rates_summary" -> "rates_summary";
            case "dpa_annex_3" -> "dpa_annex_3";
            default -> "partnership_contract";
        };
    }

    private static boolean hasHeader(HttpServletRequest request, String name) {
        return header(request, name) != null;
    }

    private static String header(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private record FeaciReseller(
            UUID id, String legalName, String country, String vat, String interest, String status) {}

    private record LegalDoc(UUID id, String version) {}

    private record Template(
            UUID id, String type, Object version, String title, String body, String sha256, boolean published) {}
}
