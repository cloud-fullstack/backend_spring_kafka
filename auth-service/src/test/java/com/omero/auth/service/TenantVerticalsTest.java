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

    @Test
    void formCodesMatchExpectedVerticalAndHandler() {
        assertEquals("medical", TenantVerticals.normalizeVertical("medical"));
        assertEquals("vehicle_rental", TenantVerticals.normalizeVertical("vehicle-rental"));
        assertEquals("legal", TenantVerticals.normalizeVertical("accountant"));
        assertEquals("accounting", TenantVerticals.normalizeVertical("accounting"));

        assertEquals("doctor", TenantVerticals.normalizeBusinessType("medical"));
        assertEquals("vehicle", TenantVerticals.normalizeBusinessType("vehicle-rental"));
        assertEquals("lawyer", TenantVerticals.normalizeBusinessType("accountant"));
        assertEquals("lawyer", TenantVerticals.normalizeBusinessType("accounting"));
    }

    @Test
    void signupAndDemoAliasesKeepHandlerFk() {
        assertEquals("medical", TenantVerticals.normalizeVertical("Doctor"));
        assertEquals("legal", TenantVerticals.normalizeVertical("lawyer"));
        assertEquals("hospitality", TenantVerticals.normalizeVertical("bnb"));
        assertEquals("hospitality", TenantVerticals.normalizeVertical("bnb-booking"));
        assertEquals("restaurant", TenantVerticals.normalizeVertical("restaurant"));
        assertEquals("restaurant", TenantVerticals.normalizeVertical("pizza-restaurant"));

        assertEquals("doctor", TenantVerticals.normalizeBusinessType("doctor"));
        assertEquals("lawyer", TenantVerticals.normalizeBusinessType("lawyer"));
        assertEquals("lawyer", TenantVerticals.normalizeBusinessType("legal"));
        assertEquals("bnb", TenantVerticals.normalizeBusinessType("bnb"));
        assertEquals("bnb", TenantVerticals.normalizeBusinessType("hospitality"));
        assertEquals("pizzeria", TenantVerticals.normalizeBusinessType("restaurant"));
        assertEquals("ecommerce", TenantVerticals.normalizeBusinessType("ecommerce"));
        assertEquals("vehicle", TenantVerticals.normalizeBusinessType("vehicle_rental"));
    }
}
