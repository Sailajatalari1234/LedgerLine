package com.ledgerline.account;

import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account endpoints. Who is calling always comes from the server-side session
 * (the Authentication object), never from anything the browser sends.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountController(AccountRepository accountRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request,
                                  Authentication authentication) {
        User caller = currentUser(authentication);
        Long ownerId;

        if (caller.getRole() == Role.ADMIN) {
            if (request.ownerId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ownerId is required for admins");
            }
            User owner = userRepository.findById(request.ownerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Owner not found"));
            if (owner.getRole() != Role.ACCOUNT_HOLDER) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Owner must be an ACCOUNT_HOLDER");
            }
            ownerId = owner.getId();
        } else if (caller.getRole() == Role.ACCOUNT_HOLDER) {
            if (request.ownerId() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ownerId is only for admins");
            }
            ownerId = caller.getId(); // from the session, not from the request
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        }

        Account saved = accountRepository.save(new Account(ownerId, request.name().trim()));
        return AccountResponse.from(saved);
    }

    @GetMapping
    public List<AccountResponse> list(Authentication authentication) {
        User caller = currentUser(authentication);
        List<Account> accounts = isStaff(caller)
                ? accountRepository.findAll(Sort.by("id"))
                : accountRepository.findByOwnerIdOrderById(caller.getId());
        return accounts.stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id, Authentication authentication) {
        User caller = currentUser(authentication);
        // Someone else's account gives the same 404 as a missing one, so nobody can probe account numbers.
        Account account = (isStaff(caller)
                ? accountRepository.findById(id)
                : accountRepository.findByIdAndOwnerId(id, caller.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return AccountResponse.from(account);
    }

    /** Admins and auditors may see every account. Account holders see only their own. */
    private static boolean isStaff(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.AUDITOR;
    }

    private User currentUser(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}