package com.github.guoyyit.fundflowriskjava.model;

import java.math.BigDecimal;

/**
 * 一条转账记录。id 全局唯一，用于去重与回滚。
 */
public record TransferRecord(String id, String from, String to, BigDecimal amount, long timestamp) {
}
