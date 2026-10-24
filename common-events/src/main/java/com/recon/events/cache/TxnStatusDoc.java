package com.recon.events.cache;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Value stored at txn:status:{transaction_ref}. */
public record TxnStatusDoc(
        @JsonProperty("status") String status,
        @JsonProperty("matched_at") Instant matchedAt,
        @JsonProperty("sources") List<String> sources,
        @JsonProperty("exception_code") String exceptionCode,
        @JsonProperty("updated_at") Instant updatedAt) {

    public static final String MATCHED = "MATCHED";
    public static final String MATCHED_WITH_TOLERANCE = "MATCHED_WITH_TOLERANCE";
    public static final String EXCEPTION = "EXCEPTION";
    public static final String RESOLVED = "RESOLVED";
}
