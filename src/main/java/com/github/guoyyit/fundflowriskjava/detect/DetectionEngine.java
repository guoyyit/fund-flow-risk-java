package com.github.guoyyit.fundflowriskjava.detect;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检测引擎：运行全部规则，命中结果按 hitKey 去重并排序，
 * 与记录写入顺序无关，保证同一图状态下输出可复现。
 */
public class DetectionEngine {

    private final List<RiskRule> rules;

    public DetectionEngine(List<RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<RiskHit> detectAll(FlowGraph graph, RuleConfig config) {
        Map<String, RiskHit> dedup = new LinkedHashMap<>();
        for (RiskRule rule : rules) {
            for (RiskHit hit : rule.detect(graph, config)) {
                dedup.putIfAbsent(hit.hitKey(), hit);
            }
        }
        List<RiskHit> hits = new ArrayList<>(dedup.values());
        hits.sort(Comparator.comparing(h -> h.hitKey()));
        return List.copyOf(hits);
    }
}
