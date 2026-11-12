package com.ibank.global.security;

import java.security.Principal;

/**
 * 서비스 간 호출의 주체(다른 서비스). 사용자 계정이 아니므로 {@code users} 행이 없다.
 * {@link Principal}을 구현해 {@code Authentication.getName()}이 클라이언트 ID를 돌려주게 한다 — 감사 로그의 actor가 된다.
 */
public record SystemPrincipal(String clientId) implements Principal {

    @Override
    public String getName() {
        return clientId;
    }
}
