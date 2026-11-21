package com.vcube.support;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AccountRepository accounts, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(email -> accounts.findByEmail(email)
                .map(account -> User.withUsername(account.email)
                        .password(account.passwordHash)
                        .roles(account.role.name())
                        .disabled(!account.enabled)
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials")));
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionSecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            HttpSessionSecurityContextRepository contexts,
                                            HttpSessionCsrfTokenRepository tokens) throws Exception {
        return http
                .securityContext(config -> config.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(tokens))
                .requestCache(config -> config.disable())
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/analytics/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/staff/**").hasAnyRole("AGENT", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, failure) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, failure) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN)))
                .logout(config -> config.logoutUrl("/api/v1/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .build();
    }
}