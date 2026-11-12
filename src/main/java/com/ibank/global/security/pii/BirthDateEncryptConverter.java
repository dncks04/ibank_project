package com.ibank.global.security.pii;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDate;

/**
 * 생년월일 컬럼의 at-rest 암호화 컨버터. ISO 날짜 문자열(yyyy-MM-dd)을 email과 같은 키·방식(AES-GCM)으로 암호화한다.
 * Hibernate가 인스턴스화하므로 키는 {@link EmailCryptoHolder}에서 가져온다(autoApply=false, 필드에 @Convert로 적용).
 */
@Converter
public class BirthDateEncryptConverter implements AttributeConverter<LocalDate, String> {

    @Override
    public String convertToDatabaseColumn(LocalDate birthDate) {
        return birthDate == null ? null : EmailCryptoHolder.get().encrypt(birthDate.toString());
    }

    @Override
    public LocalDate convertToEntityAttribute(String stored) {
        return stored == null ? null : LocalDate.parse(EmailCryptoHolder.get().decrypt(stored));
    }
}
