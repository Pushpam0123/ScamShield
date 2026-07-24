package com.scamshield.core.model

/** Opaque identifier for a single checked message. UUIDv4 string form. */
@JvmInline
value class MessageId(val value: String)

/**
 * The four analyzers of `architecture.md` §7.
 *
 * Identity is an enum rather than a string so that the fusion policy's weight table
 * is exhaustive at compile time — adding an analyzer without giving it a fusion weight
 * must not compile.
 */
enum class AnalyzerId {
    CLASSIFIER,
    URL,
    SENDER,
    PATTERN,
}
