package com.tasked.modular.user.entities;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.tasked.modular.shared.enums.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class UserEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password", nullable = false, columnDefinition = "TEXT")
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    /**
     * {@code BCrypt(SHA256_HEX_UPPER(refresh_jwt))} of the one currently-valid refresh token,
     * or {@code null} when there is no active session.
     *
     * <p>This single slot is what makes rotation and reuse detection work: every successful
     * refresh overwrites it, so a previously-rotated token still has a valid signature but no
     * longer matches what is stored. Storing a hash rather than the token means a database
     * dump cannot be replayed.
     *
     * <p>One slot also means one session per user - signing in on a second device silently
     * ends the first. See the implementation doc for the child-table design that lifts this.
     */
    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    /**
     * Server-side expiry for the stored hash, independent of the {@code exp} inside the JWT.
     *
     * <p>Without it the only expiry lives in a token the server does not keep, so a stale hash
     * would linger in the row forever. Checking this column lets the server end a session
     * without having to decode anything.
     */
    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    /**
     * Optimistic-lock counter. Hibernate appends {@code WHERE version = ?} to every UPDATE and
     * raises {@link org.springframework.dao.OptimisticLockingFailureException} when the row
     * moved underneath us.
     *
     * <p>It is the second line of defence for token rotation: the service takes a pessimistic
     * row lock, but this also protects any other concurrent write to the row.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
