package me.mrsolymi.demo.auth;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.mrsolymi.demo.user.User;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final JwtService jwtService;
	private final AuthCookies cookies;

	@PostMapping("/register")
	public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		User user = authService.register(request.username(), request.password(), request.inviteToken());
		return withSession(HttpStatus.CREATED, user);
	}

	@PostMapping("/login")
	public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
		User user = authService.login(request.username(), request.password());
		return withSession(HttpStatus.OK, user);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout() {
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return new UserResponse(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("username"));
	}

	private ResponseEntity<UserResponse> withSession(HttpStatus status, User user) {
		String jwt = jwtService.issue(user);
		return ResponseEntity.status(status)
				.header(HttpHeaders.SET_COOKIE, cookies.issue(jwt).toString())
				.body(UserResponse.from(user));
	}

}
