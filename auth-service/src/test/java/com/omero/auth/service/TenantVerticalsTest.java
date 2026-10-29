package com.omero.auth.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TenantVerticalsTest {

    @Test
    void unknownAndNullBecomeOtherVerticalWithoutBusinessType() {
        assertEquals("other", TenantVerticals.normalizeVertical(null));
        assertEquals("other", TenantVerticals.normalizeVertical("  "));
        assertEquals("other", TenantVerticals.normalizeVertical("not-a-vertical"));
        assertNull(TenantVerticals.normalizeBusinessType(null));
        assertNull(TenantVerticals.normalizeBusinessType("not-a-vertical"));
    }

    @Test
    void canonicalCodesStayCanonical() {
        assertEquals("medical", TenantVerticals.normalizeVertical("medical"));
        assertEquals("vehicle_rental", TenantVerticals.normalizeVertical("vehicle_rental"));
        assertEquals("hospitality", TenantVerticals.normalizeVertical("hospitality"));
        assertEquals("ecommerce", TenantVerticals.normalizeVertical("ecommerce"));
        assertEquals("doctor", TenantVerticals.normalizeBusinessType("medical"));
        assertEquals("vehicle", TenantVerticals.normalizeBusinessType("vehicle_rental"));
        assertEquals("bnb", TenantVerticals.normalizeBusinessType("hospitality"));
        assertEquals("ecommerce", TenantVerticals.normalizeBusinessType("ecommerce"));
    }
}
