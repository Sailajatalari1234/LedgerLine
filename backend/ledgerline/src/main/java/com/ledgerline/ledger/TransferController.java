package com.ledgerline.ledger;

import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final MoneyService moneyService;
    private final UserRepository userRepository;

    public TransferController(MoneyService moneyService, UserRepository userRepository) {
        this.moneyService = moneyService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<MoneyResponse> transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            Authentication authentication) {

        User caller = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        // Only account holders move money. The role comes from the database, not the request.
        if (caller.getRole() != Role.ACCOUNT_HOLDER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        }

        long amount;
        try {
            amount = request.amountPaise().longValueExact();
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountPaise must be a whole number");
        }

        MoneyOutcome outcome = moneyService.transfer(caller.getId(), request.fromAccountId(),
                request.toAccountId(), amount, request.description(), requireKey(idempotencyKey));

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder = builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.response());
    }

    /** Every money request MUST carry an Idempotency-Key. */
    private static String requireKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header is required (8-100 letters, digits, . _ : -)");
        }
        return key;
    }
}