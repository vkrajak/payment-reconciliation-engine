package com.recon.classifier.service;

/** Structurally invalid candidate. Retrying cannot help, so it goes straight to the DLT. */
public class MalformedCandidateException extends RuntimeException {
    public MalformedCandidateException(String message) {
        super(message);
    }
}
