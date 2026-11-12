package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * 내부 API(서비스 간 호출) 설정.
 *
 * @param clients  시스템 토큰을 발급받을 수 있는 클라이언트. 키가 클라이언트 ID, 값이 시크릿이다.
 *                 예) {@code ibank.internal.clients.fds-service=...}. 비어 있으면 아무도 발급받지 못한다.
 * @param tokenTtl 시스템 토큰 수명. 짧게 두고 만료되면 다시 발급받게 한다.
 */
@ConfigurationProperties(prefix = "ibank.internal")
public record InternalApiProperties(
        Map<String, String> clients,
        @DefaultValue("5m") Duration tokenTtl
) {
    public InternalApiProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }
}
