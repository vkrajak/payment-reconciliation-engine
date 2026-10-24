package com.recon.dashboard.transactions;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionLookupService service;

    public TransactionController(TransactionLookupService service) {
        this.service = service;
    }

    @GetMapping("/{transactionRef}")
    public TransactionStatusResponse byRef(@PathVariable String transactionRef) {
        return service.lookup(transactionRef);
    }
}
