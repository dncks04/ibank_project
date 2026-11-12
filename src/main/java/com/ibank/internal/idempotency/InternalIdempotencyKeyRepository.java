package com.ibank.internal.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InternalIdempotencyKeyRepository extends JpaRepository<InternalIdempotencyKey, String> {
}
