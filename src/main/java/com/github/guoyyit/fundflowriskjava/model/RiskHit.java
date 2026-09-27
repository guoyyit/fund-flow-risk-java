package com.github.guoyyit.fundflowriskjava.model;

import java.util.List;

/**
 * 一次规则命中：规则类型、相关地址、路径与证据摘要。
 * hitKey 为规范化后的稳定标识，用于去重与确定性排序。
 */
public record RiskHit(RuleType ruleType, List<String> addresses, List<String> path, String evidence, String hitKey) {
}
