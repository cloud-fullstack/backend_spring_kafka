package com.omero.auth.web;

public class RefundNotAvailableException extends RuntimeException {
    public RefundNotAvailableException() {
        super("Refund not available");
    }
}
