package com.omero.auth.web;

public class PlanNotAvailableException extends RuntimeException {
    public PlanNotAvailableException() {
        super("Plan not available yet");
    }
}
