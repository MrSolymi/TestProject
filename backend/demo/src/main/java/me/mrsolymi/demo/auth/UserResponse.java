package me.mrsolymi.demo.auth;

import java.util.UUID;

import me.mrsolymi.demo.user.User;

public record UserResponse(UUID id, String username) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getUsername());
	}

}
