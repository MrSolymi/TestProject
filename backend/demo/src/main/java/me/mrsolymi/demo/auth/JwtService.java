package me.mrsolymi.demo.auth;

import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import me.mrsolymi.demo.config.AppProperties;
import me.mrsolymi.demo.user.User;

@Service
@RequiredArgsConstructor
public class JwtService {

	private final JwtEncoder encoder;
	private final AppProperties props;

	public String issue(User user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(user.getId().toString())
				.claim("username", user.getUsername())
				.issuedAt(now)
				.expiresAt(now.plus(props.jwt().ttl()))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
