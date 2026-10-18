package me.mrsolymi.demo.auth;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import me.mrsolymi.demo.config.AppProperties;

@Component
@RequiredArgsConstructor
public class AuthCookies {

	private final AppProperties props;

	public ResponseCookie issue(String jwt) {
		return base(jwt).maxAge(props.jwt().ttl()).build();
	}

	public ResponseCookie clear() {
		return base("").maxAge(0).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(props.cookie().name(), value)
				.httpOnly(true)
				.secure(props.cookie().secure())
				.sameSite("Lax")
				.path("/api");
	}

}
