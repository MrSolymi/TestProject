package me.mrsolymi.demo.invite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import me.mrsolymi.demo.config.AppProperties;

@Service
@RequiredArgsConstructor
public class InviteService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final InviteRepository invites;
	private final AppProperties props;

	/** Creates an invite and returns the plaintext token, which is never stored. */
	public CreatedInvite create(UUID createdBy) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = Instant.now().plus(props.invite().ttl());
		invites.save(new Invite(hash(token), createdBy, expiresAt));
		return new CreatedInvite(token, expiresAt);
	}

	public Optional<Invite> findUsable(String token) {
		return invites.findByTokenHash(hash(token)).filter(invite -> invite.isUsable(Instant.now()));
	}

	/** Atomically burns the invite. Returns false if it was already used, expired or unknown. */
	public boolean consume(String token, UUID userId) {
		return invites.consume(hash(token), userId, Instant.now()) == 1;
	}

	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public record CreatedInvite(String token, Instant expiresAt) {
	}

}
