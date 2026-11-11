package com.ledgerline.ledger;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/** Thrown when a withdrawal is bigger than the balance. Answers 422. Rolls back the transaction. */
public class InsufficientFundsException extends ResponseStatusException {

    public InsufficientFundsException() {
        super(HttpStatusCode.valueOf(422), "Insufficient funds");
    }
}