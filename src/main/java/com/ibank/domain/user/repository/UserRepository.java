package com.ibank.domain.user.repository;

import com.ibank.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    /** email 중복 검사는 암호문이 아닌 blind index(결정적 HMAC)로 한다. */
    boolean existsByEmailBlindIndex(String emailBlindIndex);

    /** 토큰 버전 증가 → 기존 access 토큰 즉시 무효화. */
    @Modifying
    @Query("UPDATE User u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :userId")
    void incrementTokenVersion(@Param("userId") Long userId);
}
