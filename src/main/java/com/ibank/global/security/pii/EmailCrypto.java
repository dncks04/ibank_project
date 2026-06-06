package com.ibank.global.security.pii;

import com.ibank.global.config.PiiProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * email 등 PII의 at-rest 암호화와 blind index 생성.
 *
 * <ul>
 *   <li><b>암호화</b>: AES-256-GCM(랜덤 IV). 같은 평문도 매번 다른 암호문 → 패턴 노출 차단.
 *       대신 암호문으로는 동등 비교·유니크 제약이 불가능하다.</li>
 *   <li><b>blind index</b>: 정규화한 평문의 HMAC-SHA256(키 기반). 결정적이라 동등 조회·유니크 제약에 쓰되,
 *       키 없이는 역산이 불가능하다.</li>
 * </ul>
 *
 * <p>두 키는 하나의 {@code pii.secret}에서 서로 다른 라벨로 파생한다.
 * JPA 컨버터/엔티티 리스너가 키 주입 없이 쓰도록 기동 시 {@link EmailCryptoHolder}에 자신을 등록한다.
 */
@Component
public class EmailCrypto {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;

    public EmailCrypto(PiiProperties props) {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException("pii.secret이 설정되지 않았습니다.");
        }
        byte[] secret = props.secret().getBytes(StandardCharsets.UTF_8);
        this.aesKey = new SecretKeySpec(deriveKey(secret, "aes"), "AES");
        this.hmacKey = new SecretKeySpec(deriveKey(secret, "blind-index"), "HmacSHA256");
    }

    @PostConstruct
    void register() {
        EmailCryptoHolder.set(this);
    }

    /** 평문 → base64(IV || 암호문+태그). null은 그대로 통과. */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("PII 암호화 실패", e);
        }
    }

    /** base64(IV || 암호문+태그) → 평문. null은 그대로 통과. */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(all, IV_BYTES, all.length);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII 복호화 실패", e);
        }
    }

    /** 정규화(trim+소문자)한 평문의 HMAC-SHA256 hex. 동등 조회·유니크 제약용 결정적 인덱스. null은 그대로 통과. */
    public String blindIndex(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            String normalized = plain.trim().toLowerCase(Locale.ROOT);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("blind index 생성 실패", e);
        }
    }

    private static byte[] deriveKey(byte[] secret, String label) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(secret);
            digest.update((byte) ':');
            return digest.digest(label.getBytes(StandardCharsets.UTF_8)); // 32 bytes
        } catch (Exception e) {
            throw new IllegalStateException("PII 키 파생 실패", e);
        }
    }
}
