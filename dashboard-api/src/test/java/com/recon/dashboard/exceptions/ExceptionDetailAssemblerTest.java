package com.recon.dashboard.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.dashboard.exceptions.ExceptionDetail.FieldComparison;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionDetailAssemblerTest {

    private final ExceptionDetailAssembler assembler = new ExceptionDetailAssembler();
    private final ObjectMapper mapper = new ObjectMapper();

    private static FieldComparison field(List<FieldComparison> all, String name) {
        return all.stream().filter(f -> f.field().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void flagsAmountDifferenceButNotMatchingCurrency() throws Exception {
        JsonNode events = mapper.readTree("""
                [{"source":"LEDGER","eventId":"e1","amount":100.00,"currency":"INR","occurredAt":"2026-10-06T06:30:00Z"},
                 {"source":"PSP","eventId":"e2","amount":99.50,"currency":"INR","occurredAt":"2026-10-06T06:30:00Z"}]
                """);
        var cmp = assembler.compare(events);

        assertThat(field(cmp, "amount").differs()).isTrue();
        assertThat(field(cmp, "amount").valuesBySource()).containsEntry("LEDGER", "100.0").containsEntry("PSP", "99.5");
        assertThat(field(cmp, "currency").differs()).isFalse();
        assertThat(field(cmp, "occurredAt").differs()).isFalse();
    }

    @Test
    void amountsAreComparedNumericallyNotAsText() throws Exception {
        JsonNode events = mapper.readTree("""
                [{"source":"LEDGER","eventId":"e1","amount":"100.00","currency":"INR"},
                 {"source":"PSP","eventId":"e2","amount":"100.0","currency":"INR"}]
                """);
        assertThat(field(assembler.compare(events), "amount").differs()).isFalse();
    }

    @Test
    void secondEventFromSameSourceGetsItsOwnColumn() throws Exception {
        JsonNode events = mapper.readTree("""
                [{"source":"PSP","eventId":"e2","amount":100,"currency":"INR"},
                 {"source":"PSP","eventId":"e9","amount":100,"currency":"USD"}]
                """);
        var currency = field(assembler.compare(events), "currency");

        assertThat(currency.valuesBySource()).containsKeys("PSP", "PSP#2");
        assertThat(currency.differs()).isTrue();
    }

    @Test
    void nullOrNonArrayEventsGiveEmptyComparisons() {
        var cmp = assembler.compare(null);
        assertThat(cmp).hasSize(3);
        assertThat(cmp).allSatisfy(f -> {
            assertThat(f.valuesBySource()).isEmpty();
            assertThat(f.differs()).isFalse();
        });
    }
}
