package com.ledgerline.admin;

import com.ledgerline.user.User;

/** What the API returns about a user. There is deliberately no password field here. */
public record UserResponse(Long id, String username, String fullName, String role, boolean enabled) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isEnabled());
    }
}