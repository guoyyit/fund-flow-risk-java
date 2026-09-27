package com.github.guoyyit.fundflowriskjava.model;

import java.util.List;

/**
 * A single rule hit.
 *
 * @param rule      the rule that fired
 * @param addresses involved addresses, sorted lexicographically for determinism
 * @param path      address path describing the evidence (e.g. the cycle or the fan-in chain)
 * @param txIds     evidence transfer ids, sorted lexicographically for determinism
 * @param summary   human readable evidence summary
 */
public record RiskHit(RuleType rule,
                      List<String> addresses,
                      List<String> path,
                      List<String> txIds,
                      String summary) {

    public RiskHit {
        addresses = List.copyOf(addresses);
        path = List.copyOf(path);
        txIds = List.copyOf(txIds);
    }

    /** Canonical ordering key so hit lists are reproducible. */
    public static int compare(RiskHit a, RiskHit b) {
        int c = a.rule.compareTo(b.rule);
        if (c != 0) return c;
        c = String.join(",", a.addresses).compareTo(String.join(",", b.addresses));
        if (c != 0) return c;
        return String.join(",", a.txIds).compareTo(String.join(",", b.txIds));
    }
}
