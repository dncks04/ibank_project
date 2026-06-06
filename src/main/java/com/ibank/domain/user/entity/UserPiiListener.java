package com.ibank.domain.user.entity;

import com.ibank.global.security.pii.EmailCryptoHolder;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * 저장/수정 직전에 email로부터 blind index를 계산해 채운다.
 *
 * <p>이 시점의 {@code user.email}은 아직 평문(암호화 컨버터는 컬럼 기록 시점에 적용)이므로,
 * 정규화한 평문의 HMAC을 그대로 blind index 컬럼에 넣을 수 있다. 엔티티가 키를 들고 있지 않도록
 * 키 접근은 {@link EmailCryptoHolder}에 위임한다.
 */
public class UserPiiListener {

    @PrePersist
    @PreUpdate
    void fillBlindIndex(User user) {
        user.assignEmailBlindIndex(EmailCryptoHolder.get().blindIndex(user.getEmail()));
    }
}
