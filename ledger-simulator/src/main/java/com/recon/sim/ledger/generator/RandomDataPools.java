package com.recon.sim.ledger.generator;

import java.security.SecureRandom;
import java.util.List;

/**
 * Deliberately not using an external "faker" library -- one fewer dependency,
 * one fewer thing that can fail to resolve in a restricted-network build
 * environment, and these lists are trivial to extend by hand anyway.
 */
public final class RandomDataPools {

    private RandomDataPools() {}

    static final SecureRandom RANDOM = new SecureRandom();

    static final List<String> ACCOUNT_IDS = List.of(
            "ACC-1001", "ACC-1002", "ACC-1003", "ACC-1004", "ACC-1005",
            "ACC-2001", "ACC-2002", "ACC-2003", "ACC-2004", "ACC-2005",
            "ACC-3001", "ACC-3002", "ACC-3003"
    );

    static final List<String> CURRENCIES = List.of("INR", "USD", "EUR", "GBP");

    static String randomAccountId() {
        return ACCOUNT_IDS.get(RANDOM.nextInt(ACCOUNT_IDS.size()));
    }

    static String randomCurrency() {
        return CURRENCIES.get(RANDOM.nextInt(CURRENCIES.size()));
    }

    static String randomTxnType() {
        return RANDOM.nextBoolean() ? "DEBIT" : "CREDIT";
    }

    /** Realistic-ish amount distribution: mostly small-to-mid transactions, occasional large one. */
    static java.math.BigDecimal randomAmount() {
        double base = RANDOM.nextBoolean()
                ? 10 + RANDOM.nextDouble() * 490          // 10 - 500, everyday txns
                : 500 + RANDOM.nextDouble() * 49500;      // 500 - 50000, occasional big txns
        return java.math.BigDecimal.valueOf(base).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    static String randomTransactionRef() {
        return "TXN-" + java.util.UUID.randomUUID();
    }
}
