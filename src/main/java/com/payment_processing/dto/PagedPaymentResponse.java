package com.payment_processing.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedPaymentResponse {

    private List<PaymentResponse> payments;

    // Pagination metadata
    private int page;           // current page (0-indexed)
    private int size;           // page size requested
    private long totalElements; // total number of matching records
    private int totalPages;     // total number of pages
    private boolean first;      // is this the first page?
    private boolean last;       // is this the last page?
}
