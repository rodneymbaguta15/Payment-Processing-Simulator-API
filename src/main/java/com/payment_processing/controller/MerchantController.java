package com.payment_processing.controller;

import com.payment_processing.dto.MerchantSummaryResponse;
import com.payment_processing.service.MerchantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;


    // GET /api/v1/merchants/{id}/summary
    @GetMapping("/{id}/summary")
    public ResponseEntity<MerchantSummaryResponse> getMerchantSummary(@PathVariable String id) {
        return ResponseEntity.ok(merchantService.getMerchantSummary(id));
    }
}
