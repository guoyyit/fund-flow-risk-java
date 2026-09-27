package com.github.guoyyit.fundflowriskjava.detect;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;

import java.util.List;

/**
 * 风险检测规则：对当前已提交图状态做纯函数式计算。
 */
public interface RiskRule {

    RuleType type();

    List<RiskHit> detect(FlowGraph graph, RuleConfig config);
}
