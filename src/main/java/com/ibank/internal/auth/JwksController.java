package com.ibank.internal.auth;

import com.ibank.global.security.JwtKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 토큰 서명 공개키(JWKS). 다른 서비스가 ibank가 발급한 토큰의 서명을 직접 검증할 때 쓴다.
 * 공개키만 내보내므로 인증 없이 열어 둔다. 봉투({@code ApiResponse})를 쓰지 않고 표준 형식 그대로 응답한다.
 *
 * <p>주의: 서명 검증만으로는 고객 토큰의 즉시 무효화(token_version)를 알 수 없다. 그 확인은 ibank DB 조회가
 * 필요하므로, 다른 서비스가 서명만 보면 무효화된 토큰이 만료(최대 15분)까지 그 서비스에서는 통한다.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeys jwtKeys;

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(jwtKeys.jwks());
    }
}
