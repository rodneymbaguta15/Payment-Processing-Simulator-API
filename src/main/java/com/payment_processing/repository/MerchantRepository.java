package com.payment_processing.repository;

import com.payment_processing.model.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, String> {

    Optional<Merchant> findByApiKey(String apiKey);     // To retrieve a merchant by their API key, which is used for authentication in payment requests
}
