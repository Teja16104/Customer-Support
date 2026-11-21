package com.vcube.support;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class Account {
    public enum Role { CUSTOMER, AGENT, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 254)
    String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    Role role;

    @Column(nullable = false)
    boolean enabled;

    protected Account() {}

    Account(String email, String displayName, String passwordHash) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = Role.CUSTOMER;
        this.enabled = true;
    }
}