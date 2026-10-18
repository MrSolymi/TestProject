package me.mrsolymi.demo.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "must be 3-32 letters, digits or underscores") String username,
		@NotBlank @Size(min = 8, max = 72) String password,
		@NotBlank @Size(max = 200) String inviteToken) {
}
