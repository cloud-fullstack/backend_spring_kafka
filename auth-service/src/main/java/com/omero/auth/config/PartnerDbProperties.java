package com.omero.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("partner.datasource")
public record PartnerDbProperties(String url, String username, String password) {
    @Override
    public String toString() {
        return "PartnerDbProperties[url=" + url + ", username=" + username + ", password=***]";
    }
}
