package com.github.guoyyit.fundflowriskjava.model;

/**
 * Tunable rule parameters. Detection is a pure function of (graph state, config),
 * so hits can be recomputed repeatably under different parameters.
 *
 * @param minAmount         transfers below this amount are ignored by all rules
 * @param maxHops           hop limit for cycle search and layering depth
 * @param timeWindowMillis  time window for aggregation / address reuse
 * @param minCounterparties minimum distinct counterparties for aggregation / layering fan-out
 * @param seed              fixed seed reserved for deterministic tie-breaking
 */
public record RuleConfig(long minAmount,
                         int maxHops,
                         long timeWindowMillis,
                         int minCounterparties,
                         long seed) {

    public static final RuleConfig DEFAULT = new RuleConfig(1L, 6, 86_400_000L, 3, 0L);

    public RuleConfig {
        if (minAmount < 0) throw new IllegalArgumentException("minAmount must not be negative");
        if (maxHops < 1) throw new IllegalArgumentException("maxHops must be >= 1");
        if (timeWindowMillis < 0) throw new IllegalArgumentException("timeWindowMillis must not be negative");
        if (minCounterparties < 2) throw new IllegalArgumentException("minCounterparties must be >= 2");
    }
}
