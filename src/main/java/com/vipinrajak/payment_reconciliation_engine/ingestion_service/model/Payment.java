package com.vipinrajak.payment_reconciliation_engine.ingestion_service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Payment {

    private Long id;
    private Double amount;
    private String currency;
    private String payerName;
//    private String status;
//    private Date createdAt;

}
