package com.ledgerline.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records every state-changing request under /api/ in the audit log.
 * It runs BEFORE Spring Security, so refused requests (401 and 403) are recorded too.
 * It never stores request bodies or passwords.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final AuditService auditService;

    public AuditFilter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (READ_ONLY_METHODS.contains(method) || !path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // Find out WHO is acting before the request runs (a logout ends the session during the request).
        String actor = resolveActor(request, path);

        try {
            chain.doFilter(request, response);
        } finally {
            try {
                int httpStatus = response.getStatus();
                String status = httpStatus >= 200 && httpStatus < 300 ? "SUCCESS"
                        : (httpStatus == 401 || httpStatus == 403) ? "DENIED" : "FAILED";
                String action = method + " " + path.replaceAll("/\\d+", "/{id}");
                String key = request.getHeader("Idempotency-Key");
                if (key != null && !KEY_FORMAT.matcher(key).matches()) {
                    key = null;
                }
                auditService.record(actor, truncate(action, 150), truncate(path, 255), status,
                        httpStatus, truncate(request.getRemoteAddr(), 64), key);
            } catch (RuntimeException e) {
                // Audit trouble must never break the actual request.
                log.warn("Could not write audit record: {}", e.toString());
            }
        }
    }

    private static String resolveActor(HttpServletRequest request, String path) {
        if (path.equals("/api/auth/login")) {
            // The person is not logged in yet, so we record the username they TRIED (never the password).
            return clean(request.getParameter("username"));
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context instanceof SecurityContext securityContext && securityContext.getAuthentication() != null) {
                return clean(securityContext.getAuthentication().getName());
            }
        }
        return "anonymous";
    }

    /** Keeps only safe characters, so nobody can inject fake lines into the log. */
    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "anonymous";
        }
        String safe = value.replaceAll("[^A-Za-z0-9._@-]", "_");
        return truncate(safe, 50);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}