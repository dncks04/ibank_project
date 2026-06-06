package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        long accessExpirationMs,
        long refreshExpirationMs,
        long refreshReuseLeewayMs
) {}
