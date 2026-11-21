package com.vcube.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrfTokens;

    public AuthController(AccountRepository accounts, PasswordEncoder passwords,
                          AuthenticationManager authenticationManager,
                          HttpSessionSecurityContextRepository contexts,
                          HttpSessionCsrfTokenRepository csrfTokens) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Profile register(@Valid @RequestBody Registration request) {
        if (passwordBytes(request.password()) > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes.");
        }

        var account = new Account(normalizeEmail(request.email()), request.displayName().strip(),
                passwords.encode(request.password()));
        try {
            return profile(accounts.saveAndFlush(account));
        } catch (DataIntegrityViolationException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 1062) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered.");
            }
            throw failure;
        }
    }

    @PostMapping("/login")
    public Profile login(@Valid @RequestBody Login request, HttpServletRequest servlet,
                         HttpServletResponse response) {
        if (passwordBytes(request.password()) > 72) {
            throw invalidCredentials();
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            normalizeEmail(request.email()), request.password()));
        } catch (BadCredentialsException | AccountStatusException failure) {
            throw invalidCredentials();
        }

        Profile signedIn = loadProfile(authentication);
        servlet.getSession();
        servlet.changeSessionId();

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, servlet, response);

        csrfTokens.saveToken(null, servlet, response);
        return signedIn;
    }

    @GetMapping("/me")
    public Profile me(Authentication authentication) {
        return loadProfile(authentication);
    }

    private Profile loadProfile(Authentication authentication) {
        return accounts.findByEmail(authentication.getName())
                .filter(account -> account.enabled)
                .map(this::profile)
                .orElseThrow(this::invalidCredentials);
    }

    private Profile profile(Account account) {
        return new Profile(account.id.toString(), account.email, account.displayName, account.role);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static int passwordBytes(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }

    public record Registration(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(min = 12, max = 64) String password) {}

    public record Login(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 64) String password) {}

    public record Profile(String id, String email, String displayName, Account.Role role) {}
    public record CsrfResponse(String headerName, String token) {}
}