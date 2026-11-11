package com.ledgerline.ledger;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** The JSON for a transfer. amountPaise must be a whole number of paise (10000 means Rs 100.00). */
public record TransferRequest(

        @NotNull
        Long fromAccountId,

        @NotNull
        Long toAccountId,

        @NotNull(message = "is required")
        @DecimalMin(value = "1", message = "must be at least 1 paise")
        @DecimalMax(value = "1000000000", message = "is too large")
        @Digits(integer = 10, fraction = 0, message = "must be a whole number of paise")
        BigDecimal amountPaise,

        @Size(max = 255)
        String description
) {
}