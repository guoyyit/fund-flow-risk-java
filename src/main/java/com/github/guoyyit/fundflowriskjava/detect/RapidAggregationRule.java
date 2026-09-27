package com.github.guoyyit.fundflowriskjava.detect;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 快速汇聚：某地址在时间窗口内从不少于 fanIn 个不同来源收款，
 * 且窗口内入账总额不低于 minAmount。每个地址只报告一次，
 * 窗口锚点按入边规范顺序选取，输出确定。
 */
public class RapidAggregationRule implements RiskRule {

    @Override
    public RuleType type() {
        return RuleType.RAPID_AGGREGATION;
    }

    @Override
    public List<RiskHit> detect(FlowGraph graph, RuleConfig config) {
        List<RiskHit> hits = new ArrayList<>();
        for (String addr : graph.addresses()) {
            List<TransferRecord> inEdges = graph.inEdges(addr);
            Set<Long> anchors = new LinkedHashSet<>();
            for (TransferRecord e : inEdges) {
                anchors.add(e.timestamp());
            }
            for (long anchor : anchors) {
                long toTs = anchor + config.timeWindowMillis();
                Set<String> sources = new TreeSet<>();
                BigDecimal total = BigDecimal.ZERO;
                List<String> recordIds = new ArrayList<>();
                for (TransferRecord e : inEdges) {
                    if (e.timestamp() < anchor || e.timestamp() > toTs) {
                        continue;
                    }
                    sources.add(e.from());
                    total = total.add(e.amount());
                    recordIds.add(e.id());
                }
                if (sources.size() >= config.fanIn() && total.compareTo(config.minAmount()) >= 0) {
                    String key = RuleType.RAPID_AGGREGATION + "|" + addr;
                    String evidence = "sources=" + sources
                            + ", total=" + total.toPlainString()
                            + ", windowStart=" + anchor
                            + ", records=" + recordIds.stream().sorted().toList();
                    List<String> path = new ArrayList<>(sources);
                    path.add(addr);
                    hits.add(new RiskHit(RuleType.RAPID_AGGREGATION,
                            List.copyOf(new TreeSet<>(Set.copyOf(path))),
                            List.copyOf(path), evidence, key));
                    break;
                }
            }
        }
        return hits;
    }
}
