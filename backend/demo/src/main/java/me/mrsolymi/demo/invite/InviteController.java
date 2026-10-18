package me.mrsolymi.demo.invite;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/invites")
@RequiredArgsConstructor
public class InviteController {

	private final InviteService inviteService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public InviteResponse create(@AuthenticationPrincipal Jwt jwt) {
		InviteService.CreatedInvite invite = inviteService.create(UUID.fromString(jwt.getSubject()));
		return new InviteResponse(invite.token(), invite.expiresAt());
	}

	public record InviteResponse(String token, Instant expiresAt) {
	}

}
