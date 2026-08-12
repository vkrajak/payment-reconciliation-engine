package com.vipinrajak.payment_reconciliation_engine.ingestion_service.service;

import com.vipinrajak.payment_reconciliation_engine.ingestion_service.model.Payment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PaymentService {

    private final AtomicLong idGenerator = new AtomicLong();
    private final List<Payment> payments = new ArrayList<>();

    public Payment save(Payment payment){
        payment.setId(idGenerator.incrementAndGet());
        payments.add(payment);
        return payment;
    }

    public List<Payment> getAll(){
        return payments;
    }


}
