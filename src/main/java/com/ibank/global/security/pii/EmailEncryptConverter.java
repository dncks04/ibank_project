package com.ibank.global.security.pii;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * email 컬럼의 at-rest 암호화 컨버터. 저장 시 암호화, 조회 시 복호화하여 엔티티에는 평문만 노출한다.
 * Hibernate가 인스턴스화하므로 키는 {@link EmailCryptoHolder}에서 가져온다(autoApply=false, 필드에 @Convert로 적용).
 */
@Converter
public class EmailEncryptConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String plain) {
        return EmailCryptoHolder.get().encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return EmailCryptoHolder.get().decrypt(stored);
    }
}
