package com.recon.sim.bank.writer;

import com.recon.sim.bank.model.PendingBankLine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Simple concurrent queue standing in for "the bank's internal staging area
 * before end-of-day file generation". ScenarioPlanListener adds lines as
 * scenarios arrive; StatementFileWriter periodically drains whatever is
 * ready. Not persisted -- if bank-file-ingester restarts, buffered-but-not-yet-flushed
 * lines are lost. Acceptable for a dev simulator; a real bank's staging
 * layer would obviously be durable.
 */
@Component
public class PendingLineBuffer {

    private final ConcurrentLinkedQueue<PendingBankLine> queue = new ConcurrentLinkedQueue<>();

    public void add(PendingBankLine line) {
        queue.add(line);
    }

    /** Removes and returns every line whose delay has elapsed, leaving not-yet-ready
     *  (EMIT_DELAYED) lines in the queue for a future call. */
    public List<PendingBankLine> drainReady(long nowEpochMillis) {
        List<PendingBankLine> ready = new ArrayList<>();
        List<PendingBankLine> notReady = new ArrayList<>();

        PendingBankLine line;
        while ((line = queue.poll()) != null) {
            if (line.readyAtEpochMillis() <= nowEpochMillis) {
                ready.add(line);
            } else {
                notReady.add(line);
            }
        }
        queue.addAll(notReady); // put delayed lines back for the next tick
        return ready;
    }
}
