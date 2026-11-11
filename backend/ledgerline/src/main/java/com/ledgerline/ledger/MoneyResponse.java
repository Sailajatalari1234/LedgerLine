package com.ledgerline.ledger;

/** What the API returns. balancePaise is the account balance right after this operation. */
public record MoneyResponse(long transactionId, String type, long accountId, long amountPaise, long balancePaise) {
}