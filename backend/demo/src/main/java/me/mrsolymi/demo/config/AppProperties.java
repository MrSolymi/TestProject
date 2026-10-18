package me.mrsolymi.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(Jwt jwt, Invite invite, Cookie cookie, Cors cors, Bootstrap bootstrap) {

	public record Jwt(String secret, Duration ttl) {
	}

	public record Invite(Duration ttl) {
	}

	public record Cookie(String name, boolean secure) {
	}

	public record Cors(String allowedOrigin) {
	}

	public record Bootstrap(String username, String password) {
	}

}
