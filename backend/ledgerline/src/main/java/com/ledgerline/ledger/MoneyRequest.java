package com.ledgerline.ledger;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The JSON for a deposit or withdrawal. amountPaise must be a WHOLE number of paise
 * (10000 means Rs 100.00). It is read as an exact decimal first, so that 10.5 is rejected
 * instead of being silently rounded down. The maximum is Rs 10,000,000 per request.
 */
public record MoneyRequest(

        @NotNull(message = "is required")
        @DecimalMin(value = "1", message = "must be at least 1 paise")
        @DecimalMax(value = "1000000000", message = "is too large")
        @Digits(integer = 10, fraction = 0, message = "must be a whole number of paise")
        BigDecimal amountPaise,

        @Size(max = 255)
        String description
) {
}