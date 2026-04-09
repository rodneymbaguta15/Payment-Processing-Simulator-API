package com.payment_processing.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Entity
@Table(name = "merchants")
@Getter
@Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String apiKey;              // Used to identify merchant in requests

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal settlementBalance = BigDecimal.ZERO;  // Accumulated settled funds

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
