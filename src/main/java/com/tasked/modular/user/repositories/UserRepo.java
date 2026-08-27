package com.tasked.modular.user.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tasked.modular.user.entities.UserEntity;

import jakarta.persistence.LockModeType;

public interface UserRepo extends JpaRepository<UserEntity, UUID> {

    /** Login lookup. The unique index on {@code email} guarantees at most one row. */
    Optional<UserEntity> findByEmail(String email);

    /**
     * Registration pre-check. Cheaper than {@code findByEmail} because it compiles to a
     * {@code SELECT count(*)} and never materialises an entity.
     *
     * <p>This is a read-then-write check and therefore racy on its own: two simultaneous
     * signups with the same email can both see "available". Correctness is actually enforced
     * by the unique index, whose violation the global handler maps to 409. The check exists to
     * make the common case return a clean error rather than a database exception.
     */
    boolean existsByEmail(String email);

    /**
     * Loads a user with a {@code SELECT ... FOR UPDATE} row lock, used only by the refresh
     * rotation flow.
     *
     * <p>Without the lock, two requests replaying the same refresh token can both verify it
     * against the stored hash before either writes the rotated value. Both would be issued a
     * fresh session, and the reuse that should have revoked the session goes undetected. The
     * lock serialises the compare-and-swap so exactly one of them wins and the other is
     * correctly flagged as reuse.
     *
     * <p>The lock is held until the surrounding transaction commits, which is why the calling
     * method is {@code @Transactional} and why it stays short.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);
}
