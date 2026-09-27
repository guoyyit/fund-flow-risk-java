package com.github.guoyyit.fundflowriskjava.model;

import java.math.BigDecimal;

/**
 * 规则参数：金额阈值、跳数上限、时间窗口、扇入扇出等。
 */
public record RuleConfig(
        BigDecimal minAmount,
        int maxHops,
        long timeWindowMillis,
        int fanOut,
        int fanIn,
        int minCounterparties) {

    public static RuleConfig defaults() {
        return new RuleConfig(BigDecimal.ONE, 6, 86_400_000L, 3, 3, 5);
    }
}
