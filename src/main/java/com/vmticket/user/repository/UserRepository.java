package com.vmticket.user.repository;

import com.vmticket.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("""
            SELECT u FROM User u
            WHERE (:includeDeleted = TRUE OR u.deletedAt IS NULL)
              AND (:q IS NULL OR LOWER(u.username) LIKE LOWER(:q) ESCAPE '\\')
            """)
    Page<User> search(
            @Param("q") String q,
            @Param("includeDeleted") boolean includeDeleted,
            Pageable pageable);
}
