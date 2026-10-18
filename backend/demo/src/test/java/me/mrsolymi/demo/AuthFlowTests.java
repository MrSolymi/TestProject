package me.mrsolymi.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import jakarta.servlet.http.Cookie;
import me.mrsolymi.demo.auth.ApiException;
import me.mrsolymi.demo.auth.AuthService;
import me.mrsolymi.demo.invite.InviteService;
import me.mrsolymi.demo.user.User;
import me.mrsolymi.demo.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthFlowTests {

	private static final String PASSWORD = "correct-horse";

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired InviteService invites;
	@Autowired AuthService authService;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JdbcTemplate jdbc;

	private User inviter;

	@BeforeEach
	void createInviter() {
		inviter = users.save(new User(uniqueName(), passwordEncoder.encode(PASSWORD), null));
	}

	@Test
	void registerWithValidInviteCreatesUserAndSession() throws Exception {
		String name = uniqueName();

		register(name, PASSWORD, newInvite())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(name))
				.andExpect(cookie().httpOnly("session", true))
				.andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")));

		assertThat(users.findByUsername(name)).get().extracting(User::getInvitedBy).isEqualTo(inviter.getId());
	}

	@Test
	void inviteCanOnlyBeUsedOnce() throws Exception {
		String token = newInvite();

		register(uniqueName(), PASSWORD, token).andExpect(status().isCreated());
		register(uniqueName(), PASSWORD, token).andExpect(status().isBadRequest());
	}

	@Test
	void unknownInviteIsRejected() throws Exception {
		register(uniqueName(), PASSWORD, "not-a-real-token").andExpect(status().isBadRequest());
	}

	@Test
	void expiredInviteIsRejected() throws Exception {
		String token = newInvite();
		jdbc.update("update invites set expires_at = now() - interval '1 hour' where created_by = ?", inviter.getId());

		register(uniqueName(), PASSWORD, token).andExpect(status().isBadRequest());
	}

	@Test
	void duplicateUsernameIsRejectedAndDoesNotBurnTheInvite() throws Exception {
		String name = uniqueName();
		register(name, PASSWORD, newInvite()).andExpect(status().isCreated());

		String second = newInvite();
		register(name, PASSWORD, second).andExpect(status().isConflict());

		assertThat(invites.findUsable(second)).isPresent();
	}

	@Test
	void invalidInputIsRejectedWithFieldErrors() throws Exception {
		register("x", "short", newInvite())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.username").exists())
				.andExpect(jsonPath("$.errors.password").exists());
	}

	@Test
	void loginSetsSessionCookieThatAuthenticatesMe() throws Exception {
		String name = uniqueName();
		register(name, PASSWORD, newInvite()).andExpect(status().isCreated());

		Cookie session = login(name, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getCookie("session");

		assertThat(session).isNotNull();
		mvc.perform(get("/api/auth/me").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(name));
	}

	@Test
	void meRequiresASession() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void wrongPasswordAndUnknownUserBothGet401() throws Exception {
		login(inviter.getUsername(), "wrong-password").andExpect(status().isUnauthorized());
		login(uniqueName(), PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void staleSessionCookieDoesNotBlockLogin() throws Exception {
		mvc.perform(post("/api/auth/login")
						.cookie(new Cookie("session", "garbage.not.ajwt"))
						.header("X-Requested-By", "spa")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json("username", inviter.getUsername(), "password", PASSWORD)))
				.andExpect(status().isOk());
	}

	@Test
	void logoutClearsTheCookie() throws Exception {
		mvc.perform(post("/api/auth/logout").header("X-Requested-By", "spa"))
				.andExpect(status().isNoContent())
				.andExpect(cookie().maxAge("session", 0));
	}

	@Test
	void stateChangingRequestWithoutCsrfHeaderIsForbidden() throws Exception {
		mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json("username", inviter.getUsername(), "password", PASSWORD)))
				.andExpect(status().isForbidden());
	}

	@Test
	void creatingAnInviteNeedsASessionAndReturnsAUsableToken() throws Exception {
		mvc.perform(post("/api/invites").header("X-Requested-By", "spa")).andExpect(status().isUnauthorized());

		Cookie session = login(inviter.getUsername(), PASSWORD).andReturn().getResponse().getCookie("session");
		String body = mvc.perform(post("/api/invites").cookie(session).header("X-Requested-By", "spa"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
		register(uniqueName(), PASSWORD, token).andExpect(status().isCreated());
	}

	@Test
	void concurrentRegistrationsWithOneInviteSucceedExactlyOnce() throws Exception {
		String token = newInvite();
		int attempts = 8;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger succeeded = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				String name = uniqueName();
				Callable<Void> task = () -> {
					start.await();
					try {
						authService.register(name, PASSWORD, token);
						succeeded.incrementAndGet();
					}
					catch (ApiException expected) {
						// lost the race
					}
					return null;
				};
				futures.add(pool.submit(task));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(succeeded.get()).isEqualTo(1);
	}

	private String newInvite() {
		return invites.create(inviter.getId()).token();
	}

	private ResultActions register(String username, String password, String inviteToken) throws Exception {
		return mvc.perform(post("/api/auth/register")
				.header("X-Requested-By", "spa")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json("username", username, "password", password, "inviteToken", inviteToken)));
	}

	private ResultActions login(String username, String password) throws Exception {
		return mvc.perform(post("/api/auth/login")
				.header("X-Requested-By", "spa")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json("username", username, "password", password)));
	}

	/** Builds a flat JSON object from alternating key/value strings. */
	private static String json(String... keyValues) {
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i < keyValues.length; i += 2) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(keyValues[i]).append("\":\"").append(keyValues[i + 1]).append('"');
		}
		return sb.append('}').toString();
	}

	private static String uniqueName() {
		return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
	}

}
