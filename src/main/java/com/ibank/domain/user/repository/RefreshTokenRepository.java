package com.ibank.domain.user.repository;

import com.ibank.domain.user.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 비관적 쓰기 락: 회전(rotate)은 check-then-act(폐기 여부 확인 후 회전)이므로,
     * 동일 토큰 동시 /refresh가 둘 다 통과해 토큰 패밀리가 갈라지는 것을 막기 위해 행 락으로 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    /** 특정 사용자의 모든 토큰 폐기 (비밀번호 변경/전체 로그아웃 등에 활용 가능). */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user.id = :userId AND r.revoked = false")
    int revokeAllByUserId(@Param("userId") Long userId);
}
