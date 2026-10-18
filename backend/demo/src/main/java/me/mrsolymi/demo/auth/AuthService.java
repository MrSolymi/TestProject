package me.mrsolymi.demo.auth;

import java.nio.charset.StandardCharsets;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.mrsolymi.demo.invite.Invite;
import me.mrsolymi.demo.invite.InviteService;
import me.mrsolymi.demo.user.User;
import me.mrsolymi.demo.user.UserRepository;

@Service
public class AuthService {

	private static final int BCRYPT_MAX_BYTES = 72;

	private final UserRepository users;
	private final InviteService invites;
	private final PasswordEncoder passwordEncoder;
	/** Compared against when the username is unknown, so login timing does not reveal valid usernames. */
	private final String dummyHash;

	public AuthService(UserRepository users, InviteService invites, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.invites = invites;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode("dummy-password");
	}

	@Transactional
	public User register(String username, String password, String inviteToken) {
		if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Password is too long");
		}
		// The invite is checked before the username, so a username can't be probed without a valid invite.
		Invite invite = invites.findUsable(inviteToken)
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired invite"));
		if (users.existsByUsername(username)) {
			throw usernameTaken();
		}

		User user;
		try {
			user = users.saveAndFlush(new User(username, passwordEncoder.encode(password), invite.getCreatedBy()));
		}
		catch (DataIntegrityViolationException e) {
			throw usernameTaken();
		}
		// The atomic UPDATE is the real single-use guard; a concurrent request that lost the race
		// updates 0 rows and rolls back its user insert.
		if (!invites.consume(inviteToken, user.getId())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired invite");
		}
		return user;
	}

	@Transactional(readOnly = true)
	public User login(String username, String password) {
		User user = users.findByUsername(username).orElse(null);
		if (user == null) {
			passwordEncoder.matches(password, dummyHash);
			throw invalidCredentials();
		}
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw invalidCredentials();
		}
		return user;
	}

	private static ApiException usernameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "Username is already taken");
	}

	private static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
	}

}
