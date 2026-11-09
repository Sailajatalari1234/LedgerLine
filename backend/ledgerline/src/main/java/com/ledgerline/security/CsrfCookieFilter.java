package com.ledgerline.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring creates the CSRF token lazily (only when something asks for it).
 * Our React app needs the XSRF-TOKEN cookie early, so this small filter
 * asks for the token on every request, which makes Spring send the cookie.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            csrfToken.getToken(); // reading the value forces the cookie to be sent
        }
        filterChain.doFilter(request, response);
    }
}