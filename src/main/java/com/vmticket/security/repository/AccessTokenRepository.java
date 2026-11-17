package com.vmticket.security.repository;

import com.vmticket.security.entity.AccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccessTokenRepository extends JpaRepository<AccessToken, UUID> {

    @Query("""
            SELECT t FROM AccessToken t
            JOIN FETCH t.user
            WHERE t.tokenHash = :tokenHash
            """)
    Optional<AccessToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AccessToken t WHERE t.expiresAt <= :now")
    void deleteByExpiresAtLessThanEqual(@Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AccessToken t WHERE t.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AccessToken t WHERE t.tokenHash = :tokenHash")
    void deleteByTokenHash(@Param("tokenHash") String tokenHash);
}
