package com.recon.events.exception;

/** Why the engine emitted a candidate. A hint only: the classifier re-derives the code from the data. */
public enum CandidateTrigger {
    WINDOW_EXPIRED,          // session window closed with < 3 sources
    SOURCES_DISAGREE,        // all/most sources present, values differ beyond tolerance
    DUPLICATE_REF,           // new event_id, same source, txn_ref already seen
    POST_RESOLUTION_EVENT    // event arrived after txn_ref already MATCHED/EXCEPTION
}
