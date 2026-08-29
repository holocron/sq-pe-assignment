package com.sq.caa.domain;

/** Ingestion state of an uploaded knowledge-base document. */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    INDEXED,
    FAILED
}
