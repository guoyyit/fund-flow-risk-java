package com.github.guoyyit.fundflowriskjava.model;

/**
 * A single transfer between two addresses.
 *
 * @param txId      globally unique transfer id; used for de-duplication and rollback
 * @param from      source address
 * @param to        destination address
 * @param amount    amount in the smallest currency unit (integer to keep results deterministic)
 * @param timestamp event time in epoch milliseconds
 */
public record TransferRecord(String txId, String from, String to, long amount, long timestamp) {

    public TransferRecord {
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId must not be blank");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("from must not be blank");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("to must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
    }

    /** Canonical key used for deterministic ordering: (timestamp, txId). */
    public static int compareByTimeThenId(TransferRecord a, TransferRecord b) {
        int c = Long.compare(a.timestamp, b.timestamp);
        return c != 0 ? c : a.txId.compareTo(b.txId);
    }
}
