package com.payment_processing.model;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "simulated_accounts")
@Getter
@Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SimulatedAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String cardNumberHash;      // SHA-256 hash of full PAN

    @Column(nullable = false)
    private String lastFour;            // For display only

    @Column(nullable = false)
    private BigDecimal balance;

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal reservedBalance = BigDecimal.ZERO;  // Authorized balance but not captured
}
