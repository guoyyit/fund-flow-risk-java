package com.github.guoyyit.fundflowriskjava.model;

/** The four supported risk rule categories. */
public enum RuleType {
    /** Circular flow: funds leave an address and eventually come back. */
    CYCLE,
    /** Layered splitting: funds are split to many addresses which forward them again. */
    LAYERING,
    /** Fast aggregation: many distinct sources funnel into one address within a time window. */
    AGGREGATION,
    /** Address reuse: the same address is used repeatedly as sender and receiver. */
    ADDRESS_REUSE
}
