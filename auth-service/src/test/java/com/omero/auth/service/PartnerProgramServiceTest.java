package com.omero.auth.service;

import com.omero.auth.dto.PartnerApplicationRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartnerProgramServiceTest {

    @Test
    void typeOfAcceptsContractKinds() {
        assertEquals("CUSTOM", PartnerProgramService.typeOf("custom"));
        assertEquals("ZONE", PartnerProgramService.typeOf("ZONE"));
        assertEquals("CODE", PartnerProgramService.typeOf(" code "));
        assertThrows(IllegalArgumentException.class, () -> PartnerProgramService.typeOf("BRIEF"));
    }

    @Test
    void rateBpsMatchesSeededPrograms() {
        assertEquals(1500, PartnerProgramService.rateBps("CUSTOM"));
        assertEquals(1000, PartnerProgramService.rateBps("ZONE"));
        assertEquals(700, PartnerProgramService.rateBps("CODE"));
    }

    @Test
    void pendingLegalHashIsNotSignable() {
        assertFalse(PartnerProgramService.signableHash("pending-legal-review"));
        assertFalse(PartnerProgramService.signableHash(null));
        assertFalse(PartnerProgramService.signableHash("abc"));
        assertTrue(PartnerProgramService.signableHash("a".repeat(64)));
    }

    @Test
    void unpublishedTemplateIsDraftOnly() {
        String sha = "a".repeat(64);
        assertTrue(PartnerProgramService.draftOnly("pending-legal-review", false));
        assertTrue(PartnerProgramService.draftOnly(sha, false));
        assertFalse(PartnerProgramService.draftOnly(sha, true));
        assertFalse(PartnerProgramService.canSign(sha, false, false));
        assertTrue(PartnerProgramService.canSign(sha, true, false));
        assertFalse(PartnerProgramService.canSign(sha, true, true));
    }

    @Test
    void paddedPendingHashStaysDraft() {
        String padded = "pending-legal-review" + " ".repeat(20);
        assertEquals("pending-legal-review", PartnerProgramService.templateSha(padded));
        assertTrue(PartnerProgramService.draftOnly(padded, true));
        assertFalse(PartnerProgramService.signableHash(padded));
    }

    @Test
    void clientIpPrefersCfConnectingIpBehindTraefik() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.10");
        when(req.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1");
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.1");
        assertEquals("203.0.113.10", PartnerProgramService.clientIp(req));
    }

    @Test
    void clientIpIgnoresCfIfNotFromTraefik() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.10");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getHeader("X-Forwarded-Host")).thenReturn(null);
        when(req.getHeader("X-Real-IP")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("10.0.0.5");
        assertEquals("10.0.0.5", PartnerProgramService.clientIp(req));
    }

    @Test
    void customClausesNeedClientAndDpa() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("acts_as_business", true);
        base.put("contract_accepted", true);
        base.put("burdensome_clauses", true);
        base.put("contract_version", "1.0");
        assertFalse(PartnerProgramService.clausesComplete("CUSTOM", base));
        base.put("end_client_informed", true);
        assertFalse(PartnerProgramService.clausesComplete("CUSTOM", base));
        base.put("dpa_annex_3", true);
        assertTrue(PartnerProgramService.clausesComplete("CUSTOM", base));
        assertTrue(PartnerProgramService.clausesComplete("ZONE", base));
        Map<String, Object> code = new LinkedHashMap<>(base);
        code.remove("end_client_informed");
        code.remove("dpa_annex_3");
        assertTrue(PartnerProgramService.clausesComplete("CODE", code));
        assertFalse(PartnerProgramService.clausesComplete("ZONE", code));
    }

    @Test
    void inetOrNullDropsAnon() {
        assertEquals("192.0.2.1", PartnerProgramService.inetOrNull("192.0.2.1"));
        assertNull(PartnerProgramService.inetOrNull("anon"));
        assertNull(PartnerProgramService.inetOrNull(""));
        assertEquals("2001:db8::1", PartnerProgramService.inetOrNull("2001:db8::1"));
    }

    @Test
    void applicationNotesKeepsActivityAndIntro() {
        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setActivity("Agenzia web");
        req.setIntro("Porto hotel in provincia di Bergamo.");
        assertEquals("Agenzia web\n\nPorto hotel in provincia di Bergamo.",
                PartnerProgramService.applicationNotes(req));
    }

    @Test
    void sha256Is64Hex() {
        String hex = PartnerProgramService.sha256Hex("testo");
        assertEquals(64, hex.length());
        assertTrue(hex.matches("[0-9a-f]{64}"));
    }
}
