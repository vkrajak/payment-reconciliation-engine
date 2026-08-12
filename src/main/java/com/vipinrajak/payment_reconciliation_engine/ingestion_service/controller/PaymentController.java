package com.vipinrajak.payment_reconciliation_engine.ingestion_service.controller;

import com.vipinrajak.payment_reconciliation_engine.ingestion_service.model.Payment;
import com.vipinrajak.payment_reconciliation_engine.ingestion_service.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Payment cratePayment(@RequestBody Payment payment){
        return paymentService.save(payment);
    }

    @GetMapping
    public List<Payment> getPayments(){
        return paymentService.getAll();
    }

}
