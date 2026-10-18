package me.mrsolymi.demo.invite;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invite {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true)
	private String tokenHash;

	@Column(nullable = false)
	private UUID createdBy;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant expiresAt;

	private UUID usedBy;

	private Instant usedAt;

	public Invite(String tokenHash, UUID createdBy, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.createdBy = createdBy;
		this.createdAt = Instant.now();
		this.expiresAt = expiresAt;
	}

	public boolean isUsable(Instant now) {
		return usedAt == null && expiresAt.isAfter(now);
	}

}
