package com.ledgerline.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ownerId is only for admins. Account holders always own what they create. */
public record CreateAccountRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        Long ownerId
) {
}