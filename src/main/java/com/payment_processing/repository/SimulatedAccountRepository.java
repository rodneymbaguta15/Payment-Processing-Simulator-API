package com.payment_processing.repository;

import com.payment_processing.model.SimulatedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SimulatedAccountRepository extends JpaRepository<SimulatedAccount, String> {

    // To retrieve a simulated account by the hash of the card number, which is used to simulate card-based transactions without storing sensitive card data
    Optional<SimulatedAccount> findByCardNumberHash(String cardNumberHash);
}
