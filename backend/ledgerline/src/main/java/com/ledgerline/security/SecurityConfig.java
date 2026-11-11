package com.ledgerline.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * The security rules of LedgerLine: who may call what.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Scrambles passwords with BCrypt. The number 12 is the "work factor": higher is slower to crack. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protection: the token lives in the XSRF-TOKEN cookie, which JavaScript may read.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)

            // Who may call which URL. Rules are checked from top to bottom.
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/csrf", "/api/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/audit/**").hasAnyRole("ADMIN", "AUDITOR")
                // Only admins and account holders may create accounts. Auditors are read-only.
                .requestMatchers(HttpMethod.POST, "/api/accounts").hasAnyRole("ADMIN", "ACCOUNT_HOLDER")
                // Only the account holder may move money (ownership is checked again in the service).
                .requestMatchers(HttpMethod.POST, "/api/accounts/*/deposits", "/api/accounts/*/withdrawals")
                    .hasRole("ACCOUNT_HOLDER")
                .anyRequest().authenticated())

            // Login with username and password. We answer with small JSON, never an HTML page.
            .formLogin(form -> form
                .loginPage("/api/auth/login")
                .loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) ->
                        writeJson(response, HttpServletResponse.SC_OK, "{\"status\":\"ok\"}"))
                .failureHandler((request, response, exception) ->
                        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                "{\"error\":\"Invalid username or password\"}"))
                .permitAll())

            // Logout ends the session and forgets the session cookie.
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))

            // Not logged in? Answer 401 (no redirect to a login page, because our frontend is React).
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(body);
    }
}