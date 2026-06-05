package com.ibank.global.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * REQUIRES_NEW: 비즈니스 트랜잭션이 롤백되어도(실패 감사) 감사 기록은 독립적으로 남도록 별도 트랜잭션에서 저장.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String target, String result, String detail, String ip) {
        auditLogRepository.save(AuditLog.builder()
                .actor(actor)
                .action(action)
                .target(target)
                .result(result)
                .detail(detail)
                .ip(ip)
                .build());
    }
}
