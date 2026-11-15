package com.omero.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cloudflare.turnstile")
public class TurnstileProperties {
    private boolean enabled = false;
    private String secret = "";

    @Override
    public String toString() {
        return "TurnstileProperties[enabled=" + enabled + ", secret=***]";
    }
}
