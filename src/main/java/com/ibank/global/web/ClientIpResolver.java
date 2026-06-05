package com.ibank.global.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 실제 클라이언트 IP를 신뢰 프록시 화이트리스트 기반으로 해석한다.
 *
 * <p>{@code X-Forwarded-For}(XFF)는 클라이언트가 임의로 위조할 수 있으므로 무조건 신뢰하면 안 된다.
 * 따라서 <b>직접 연결된 peer({@code getRemoteAddr()})가 신뢰 프록시일 때만</b> XFF를 해석한다.
 * <ul>
 *   <li>peer가 신뢰 프록시가 아니면 → peer가 곧 클라이언트이므로 {@code getRemoteAddr()} 반환(XFF 무시).</li>
 *   <li>peer가 신뢰 프록시면 → XFF 체인을 오른쪽(가장 가까운 프록시)에서 왼쪽으로 훑어
 *       신뢰 프록시를 건너뛴 <b>첫 비신뢰 IP</b>를 실제 클라이언트로 본다.</li>
 * </ul>
 *
 * <p>신뢰 프록시 목록({@code ibank.audit.trusted-proxies})이 비어 있으면(기본값) XFF를 절대 신뢰하지 않고
 * 항상 {@code getRemoteAddr()}를 사용한다 — 프록시가 없는 개발/직결 환경의 안전한 기본 동작.
 */
@Slf4j
@Component
public class ClientIpResolver {

    private static final String XFF_HEADER = "X-Forwarded-For";

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(
            @Value("${ibank.audit.trusted-proxies:}") List<String> trustedProxyCidrs) {
        this.trustedProxies = trustedProxyCidrs.stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .map(String::trim)
                .map(IpAddressMatcher::new)
                .toList();
    }

    /** 요청의 실제 클라이언트 IP. 해석 불가 시 {@code getRemoteAddr()}로 폴백. */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // 직접 연결한 peer가 신뢰 프록시가 아니면 XFF는 위조 가능 → remoteAddr가 곧 클라이언트
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String xff = request.getHeader(XFF_HEADER);
        if (xff == null || xff.isBlank()) {
            return remoteAddr;
        }

        // "client, proxy1, proxy2" — 오른쪽부터 신뢰 프록시를 건너뛰고 첫 비신뢰 IP가 실제 클라이언트
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String ip = hops[i].trim();
            if (!ip.isEmpty() && !isTrustedProxy(ip)) {
                return ip;
            }
        }

        // 체인이 전부 신뢰 프록시이거나 비어 있으면 remoteAddr 폴백
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ip) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // 호스트명 등 IP가 아닌 값 → 신뢰하지 않음
                log.debug("IP 매칭 불가(무시): {}", ip);
            }
        }
        return false;
    }
}
