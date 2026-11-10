package com.ledgerline.admin;

import com.ledgerline.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The JSON an admin sends to create a user.
 * The annotations are the validation rules. Spring checks them before our code runs.
 */
public record CreateUserRequest(

        @NotBlank
        @Pattern(regexp = "^[a-z0-9._-]{3,50}$",
                message = "must be 3-50 characters: lowercase letters, digits, dot, underscore or hyphen")
        String username,

        // BCrypt only uses the first 72 bytes, so we cap the length there.
        @NotBlank
        @Size(min = 12, max = 72, message = "must be 12 to 72 characters")
        String password,

        @NotBlank
        @Size(max = 100)
        String fullName,

        @NotNull
        Role role
) {
}