package com.github.guoyyit.fundflowriskjava.rule;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;

import java.util.List;

/**
 * A detection rule. Implementations must be pure functions of
 * (graph, config) and must return deterministically ordered hits.
 */
public interface RiskRule {

    RuleType type();

    List<RiskHit> evaluate(FlowGraph graph, RuleConfig config);
}
