package com.recon.sim.bank.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.sim.bank.model.PendingBankLine;
import com.recon.sim.bank.writer.PendingLineBuffer;
import com.recon.sim.common.ParticipantBehavior;
import com.recon.sim.common.ScenarioPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Consumes {@link ScenarioPlan} from 'sim-scenarios' and realizes
 * plan.bankBehavior() as pending statement line(s) in {@link PendingLineBuffer}.
 * Nothing here talks to S3 directly -- that's StatementFileWriter's job, on
 * its own schedule, once these lines are marked ready.
 */
@Slf4j
@Component
public class ScenarioPlanListener {

    private final ObjectMapper objectMapper;
    private final PendingLineBuffer buffer;

    public ScenarioPlanListener(ObjectMapper objectMapper, PendingLineBuffer buffer) {
        this.objectMapper = objectMapper;
        this.buffer = buffer;
    }

    @KafkaListener(topics = "${sim.bank.scenario-topic}", containerFactory = "scenarioKafkaListenerContainerFactory")
    public void onScenario(String scenarioJson) {
        ScenarioPlan plan;
        try {
            plan = objectMapper.readValue(scenarioJson, ScenarioPlan.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ScenarioPlan, dropping: {}", scenarioJson, e);
            return;
        }

        long now = Instant.now().toEpochMilli();
        LocalDate valueDate = Instant.ofEpochMilli(plan.getLedgerPostedAtEpochMillis())
                .atZone(ZoneOffset.UTC).toLocalDate()
                .plusDays(plan.getBankValueDateOffsetDays());

        ParticipantBehavior behavior = plan.getBankBehavior();
        switch (behavior) {
            case EMIT_MATCHING -> buffer.add(newLine(plan, plan.getAmount(), valueDate, now));

            case EMIT_MISMATCHED_AMOUNT -> buffer.add(newLine(plan, plan.getBankAmountOverride(), valueDate, now));

            case SKIP -> log.info("txn_ref={} bank behavior=SKIP -- no statement line will be written (MISSING_SOURCE scenario)",
                    plan.getTransactionRef());

            case EMIT_DUPLICATE -> {
                // two independent statement lines, two independent statement_line_ids
                // (and therefore two independent derived event_ids downstream), same
                // transaction_ref+amount -- exercises DUPLICATE_TRANSACTION_REF.
                buffer.add(newLine(plan, plan.getAmount(), valueDate, now));
                buffer.add(newLine(plan, plan.getAmount(), valueDate, now));
            }

            case EMIT_DELAYED -> {
                long readyAt = now + plan.getBankDelayMs();
                log.info("txn_ref={} bank behavior=EMIT_DELAYED, line eligible for a statement file in {}ms",
                        plan.getTransactionRef(), plan.getBankDelayMs());
                buffer.add(newLine(plan, plan.getAmount(), valueDate, readyAt));
            }
        }
    }

    private PendingBankLine newLine(ScenarioPlan plan, java.math.BigDecimal amount, LocalDate valueDate, long readyAtEpochMillis) {
        return new PendingBankLine(
                UUID.randomUUID().toString(),
                plan.getTransactionRef(),
                plan.getAccountId(),
                amount,
                plan.getCurrency(),
                plan.getTxnType(),
                valueDate,
                readyAtEpochMillis
        );
    }
}
