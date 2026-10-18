package me.mrsolymi.demo.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.util.WebUtils;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.Cookie;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/** Endpoints that must work with a stale or invalid session cookie, so the cookie is ignored there. */
	private static final Set<String> COOKIE_IGNORED_PATHS = Set.of("/api/auth/login", "/api/auth/register",
			"/api/auth/logout");

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenResolver resolver) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register", "/api/auth/logout")
						.permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(o -> o.bearerTokenResolver(resolver).jwt(Customizer.withDefaults()))
				.addFilterAfter(new CsrfGuardFilter(), CorsFilter.class);
		return http.build();
	}

	/** Reads the JWT from the HttpOnly session cookie instead of the Authorization header. */
	@Bean
	BearerTokenResolver bearerTokenResolver(AppProperties props) {
		return request -> {
			if (COOKIE_IGNORED_PATHS.contains(request.getRequestURI())) {
				return null;
			}
			Cookie cookie = WebUtils.getCookie(request, props.cookie().name());
			return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
		};
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AppProperties props) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(List.of(props.cors().allowedOrigin()));
		config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		config.setAllowedHeaders(List.of("Content-Type", CsrfGuardFilter.HEADER));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	SecretKey jwtSecretKey(AppProperties props) {
		byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
		if (secret.length < 32) {
			throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
		}
		return new SecretKeySpec(secret, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

}
