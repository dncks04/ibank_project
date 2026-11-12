package com.ibank.internal.idempotency;

import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 내부 API 멱등성 키의 조회와 저장.
 *
 * <p><b>호출 규약</b>: 두 메서드 모두 업무 처리와 <b>같은 트랜잭션</b> 안에서 호출한다({@code MANDATORY}).
 * 키를 따로 커밋하면 "처리는 커밋됐는데 키는 없는" 틈이 생기고, 그 틈에 재시도가 오면 이미 처리된 요청을
 * 다시 실행하려 든다. 같은 트랜잭션이면 처리와 키가 함께 남거나 함께 사라진다.
 *
 * <p>같은 키의 동시 요청은 호출자가 먼저 잡는 대상 행 락(거래 행, 계좌 행)에서 직렬화된다. 나중에 들어온 쪽은
 * 락을 얻은 뒤 {@link #find}에서 앞선 요청이 저장한 응답을 보게 된다(READ_COMMITTED).
 */
@Component
@RequiredArgsConstructor
public class InternalIdempotencyStore {

    private final InternalIdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;
    private final BusinessClock businessClock;

    /**
     * 같은 키로 저장된 응답을 찾는다.
     *
     * @return 저장된 응답(같은 요청의 재전송). 처음 보는 키면 빈 값.
     * @throws InternalIdempotencyKeyConflictException 같은 키가 다른 요청에 이미 쓰임
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Optional<T> find(String idempotencyKey, String fingerprint, String operation, Class<T> responseType) {
        return repository.findById(idempotencyKey).map(stored -> {
            if (!stored.getRequestFingerprint().equals(fingerprint)) {
                throw new InternalIdempotencyKeyConflictException(operation);
            }
            return objectMapper.readValue(stored.getResponseBody(), responseType);
        });
    }

    /** 성공한 요청의 키와 응답을 저장한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String idempotencyKey, String fingerprint, String operation, Object response) {
        repository.save(new InternalIdempotencyKey(
                idempotencyKey,
                fingerprint,
                operation,
                currentClientId(),
                objectMapper.writeValueAsString(response),
                businessClock.nowForStorage()));
    }

    private static String currentClientId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        String name = auth.getName();
        return name.length() > 64 ? name.substring(0, 64) : name;
    }
}
