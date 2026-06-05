package com.ibank.global.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private MockHttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        return req;
    }

    @Test
    @DisplayName("신뢰 프록시 미설정이면 XFF가 있어도 무시하고 직접 연결 IP를 쓴다 (위조 방지)")
    void noTrustedProxy_ignoresXff() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());
        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("peer가 신뢰 프록시면 XFF의 클라이언트 IP를 쓴다")
    void trustedProxy_usesXffClient() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("XFF 체인에서 신뢰 프록시는 건너뛰고 첫 비신뢰 IP를 클라이언트로 본다")
    void trustedProxy_skipsTrustedHopsInChain() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        // client, 내부프록시(10.x), peer(10.x) → 실제 클라이언트는 203.0.113.7
        assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.7, 10.1.1.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("peer가 신뢰 프록시가 아니면 위조된 XFF를 무시하고 직접 연결 IP를 쓴다")
    void untrustedPeer_ignoresSpoofedXff() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("신뢰 프록시 peer지만 XFF가 없으면 직접 연결 IP로 폴백")
    void trustedProxy_noXff_fallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.1", null)))
                .isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("XFF 체인이 전부 신뢰 프록시면 직접 연결 IP로 폴백")
    void trustedProxy_allHopsTrusted_fallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.1", "10.2.2.2, 10.1.1.1")))
                .isEqualTo("10.0.0.1");
    }
}
