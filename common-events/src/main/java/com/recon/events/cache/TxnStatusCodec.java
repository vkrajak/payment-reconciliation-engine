package com.recon.events.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** One JSON format for txn:status shared by engine, classifier and dashboard-api. */
public final class TxnStatusCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private TxnStatusCodec() { }

    public static String toJson(TxnStatusDoc doc) {
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise TxnStatusDoc", e);
        }
    }

    public static TxnStatusDoc fromJson(String json) {
        try {
            return MAPPER.readValue(json, TxnStatusDoc.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot parse TxnStatusDoc", e);
        }
    }
}
