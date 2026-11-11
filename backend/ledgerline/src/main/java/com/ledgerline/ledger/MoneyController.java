package com.ledgerline.ledger;

import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/accounts/{accountId}")
public class MoneyController {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final MoneyService moneyService;
    private final UserRepository userRepository;

    public MoneyController(MoneyService moneyService, UserRepository userRepository) {
        this.moneyService = moneyService;
        this.userRepository = userRepository;
    }

    @PostMapping("/deposits")
    public ResponseEntity<MoneyResponse> deposit(
            @PathVariable Long accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MoneyRequest request,
            Authentication authentication) {
        MoneyOutcome outcome = moneyService.deposit(callerId(authentication), accountId,
                wholePaise(request), request.description(), requireKey(idempotencyKey));
        return respond(outcome);
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<MoneyResponse> withdraw(
            @PathVariable Long accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MoneyRequest request,
            Authentication authentication) {
        MoneyOutcome outcome = moneyService.withdraw(callerId(authentication), accountId,
                wholePaise(request), request.description(), requireKey(idempotencyKey));
        return respond(outcome);
    }

    /** Converts the validated amount to a long. longValueExact refuses anything with a fraction. */
    private static long wholePaise(MoneyRequest request) {
        try {
            return request.amountPaise().longValueExact();
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountPaise must be a whole number");
        }
    }

    private static ResponseEntity<MoneyResponse> respond(MoneyOutcome outcome) {
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

    /** Who is calling comes from the server-side session, never from the request. */
    private Long callerId(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}