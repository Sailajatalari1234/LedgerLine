package com.ledgerline.ledger;

/** The response plus a flag saying whether it is a replay of an earlier identical request. */
public record MoneyOutcome(MoneyResponse response, boolean replayed) {
}