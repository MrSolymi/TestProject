package me.mrsolymi.demo.user;

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
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true)
	private String username;

	@Column(nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private Instant createdAt;

	private UUID invitedBy;

	public User(String username, String passwordHash, UUID invitedBy) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.invitedBy = invitedBy;
		this.createdAt = Instant.now();
	}

}
