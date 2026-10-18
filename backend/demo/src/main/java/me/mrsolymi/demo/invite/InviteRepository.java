package me.mrsolymi.demo.invite;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

	Optional<Invite> findByTokenHash(String tokenHash);

	/**
	 * Marks the invite as used if it is still unused and unexpired. Returns the number of rows
	 * updated, so 0 means the invite is unknown, already used or expired. Single statement, so
	 * two concurrent callers cannot both succeed.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Invite i set i.usedBy = :userId, i.usedAt = :now
			where i.tokenHash = :tokenHash and i.usedAt is null and i.expiresAt > :now
			""")
	int consume(@Param("tokenHash") String tokenHash, @Param("userId") UUID userId, @Param("now") Instant now);

}
