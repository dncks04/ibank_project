package com.ibank.global.security;

import com.ibank.global.config.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 토큰 서명·검증에 쓰는 RSA 키.
 *
 * <p>대칭키(HS256)에서 비대칭키(RS256)로 바꾼 이유: 대칭키는 검증하는 쪽도 서명 키를 가져야 하므로,
 * 다른 서비스가 토큰을 직접 검증하게 하려면 서명 키를 나눠 줘야 한다(그 서비스가 토큰을 위조할 수 있게 된다).
 * 비대칭키는 ibank만 개인키로 서명하고, 다른 서비스는 {@code /.well-known/jwks.json}의 공개키로 검증만 한다.
 *
 * <p>각 키에는 식별자({@code kid}, 공개키의 SHA-256 지문)가 있고 토큰 헤더에 실린다. 검증할 때 kid로 키를
 * 고르므로, 새 키로 서명을 시작한 뒤에도 직전 키로 서명된 토큰이 만료될 때까지 함께 검증할 수 있다(무중단 키 교체).
 */
@Slf4j
@Component
public class JwtKeys {

    private static final int GENERATED_KEY_BITS = 2048;

    private final PrivateKey signingKey;
    private final String signingKeyId;
    /** kid → 공개키. 현재 키가 맨 앞이다. */
    private final Map<String, RSAPublicKey> verificationKeys = new LinkedHashMap<>();

    public JwtKeys(JwtProperties props) {
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey;
            if (props.privateKey() == null || props.privateKey().isBlank()) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(GENERATED_KEY_BITS);
                privateKey = (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
                log.warn("jwt.private-key가 없어 임시 RSA 키를 생성했습니다. 재기동하면 발급된 토큰이 모두 무효가 됩니다. "
                        + "운영에서는 JWT_PRIVATE_KEY를 설정하세요.");
            } else {
                privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(
                        new PKCS8EncodedKeySpec(decodePem(props.privateKey())));
            }
            // 공개키는 개인키에서 유도한다(설정 두 개가 서로 다른 키를 가리키는 실수를 없앤다)
            RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));

            this.signingKey = privateKey;
            this.signingKeyId = keyIdOf(publicKey);
            this.verificationKeys.put(signingKeyId, publicKey);

            if (props.previousPublicKey() != null && !props.previousPublicKey().isBlank()) {
                RSAPublicKey previous = (RSAPublicKey) rsa.generatePublic(
                        new X509EncodedKeySpec(decodePem(props.previousPublicKey())));
                this.verificationKeys.putIfAbsent(keyIdOf(previous), previous);
            }
        } catch (Exception e) {
            throw new IllegalStateException("JWT RSA 키를 준비하지 못했습니다. PEM 형식(PKCS#8 개인키, X.509 공개키)을 확인하세요.", e);
        }
    }

    public PrivateKey signingKey() {
        return signingKey;
    }

    public String signingKeyId() {
        return signingKeyId;
    }

    /** kid에 해당하는 검증용 공개키. 모르는 kid면 null. */
    public RSAPublicKey verificationKey(String keyId) {
        return keyId == null ? null : verificationKeys.get(keyId);
    }

    /** JWKS(RFC 7517) 형식의 공개키 목록. 개인키 성분은 들어가지 않는다. */
    public Map<String, Object> jwks() {
        List<Map<String, Object>> keys = verificationKeys.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> jwk = new LinkedHashMap<>();
                    jwk.put("kty", "RSA");
                    jwk.put("kid", entry.getKey());
                    jwk.put("use", "sig");
                    jwk.put("alg", "RS256");
                    jwk.put("n", base64Url(entry.getValue().getModulus()));
                    jwk.put("e", base64Url(entry.getValue().getPublicExponent()));
                    return jwk;
                })
                .toList();
        return Map.of("keys", keys);
    }

    private static String keyIdOf(RSAPublicKey key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
    }

    private static byte[] decodePem(String pem) {
        // 환경변수로 넣을 때 줄바꿈이 \n 문자열로 들어오는 경우까지 받아 준다
        String body = pem.replace("\\n", "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /** JWK의 n, e는 부호 없는 big-endian 바이트의 base64url이다(앞의 부호 바이트 0을 떼야 한다). */
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
