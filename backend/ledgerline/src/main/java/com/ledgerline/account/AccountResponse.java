package com.ledgerline.account;

/** What the API returns. balancePaise is a whole number of paise, so 150000 means Rs 1,500.00. */
public record AccountResponse(Long id, Long ownerId, String name, long balancePaise) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerId(),
                account.getName(), account.getBalance());
    }
}