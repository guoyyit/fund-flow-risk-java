package com.github.guoyyit.fundflowriskjava.detect;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 环形回流：有向环（至少 2 跳），环上每笔金额均不低于 minAmount，
 * 跳数不超过 maxHops，首尾时间跨度不超过 timeWindowMillis。
 * 每个环按其最小地址为起点规范化，只报告一次，输出与写入顺序无关。
 */
public class CycleBackflowRule implements RiskRule {

    @Override
    public RuleType type() {
        return RuleType.CYCLE_BACKFLOW;
    }

    @Override
    public List<RiskHit> detect(FlowGraph graph, RuleConfig config) {
        List<RiskHit> hits = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (String start : graph.addresses()) {
            dfs(graph, config, start, start, new ArrayList<>(), new ArrayList<>(),
                    Long.MAX_VALUE, Long.MIN_VALUE, reported, hits);
        }
        return hits;
    }

    private void dfs(FlowGraph graph, RuleConfig config, String start, String current,
                     List<String> path, List<TransferRecord> edges,
                     long minTs, long maxTs, Set<String> reported, List<RiskHit> hits) {
        if (edges.size() >= config.maxHops()) {
            return;
        }
        for (TransferRecord edge : graph.outEdges(current)) {
            if (edge.amount().compareTo(config.minAmount()) < 0) {
                continue;
            }
            long newMin = Math.min(minTs, edge.timestamp());
            long newMax = Math.max(maxTs, edge.timestamp());
            if (newMax - newMin > config.timeWindowMillis()) {
                continue;
            }
            String next = edge.to();
            if (next.equals(start)) {
                // 至少 2 跳才构成回流环，排除自转账
                if (edges.size() + 1 >= 2) {
                    recordHit(start, path, edges, edge, newMin, newMax, reported, hits);
                }
                continue;
            }
            // 只访问字典序大于起点的中间节点，保证每个环只从最小地址枚举一次
            if (next.compareTo(start) <= 0 || path.contains(next)) {
                continue;
            }
            path.add(next);
            edges.add(edge);
            dfs(graph, config, start, next, path, edges, newMin, newMax, reported, hits);
            edges.remove(edges.size() - 1);
            path.remove(path.size() - 1);
        }
    }

    private void recordHit(String start, List<String> path, List<TransferRecord> edges,
                           TransferRecord closingEdge, long minTs, long maxTs,
                           Set<String> reported, List<RiskHit> hits) {
        List<String> cycle = new ArrayList<>(path.size() + 2);
        cycle.add(start);
        cycle.addAll(path);
        cycle.add(start);
        String key = RuleType.CYCLE_BACKFLOW + "|" + String.join("->", cycle);
        if (!reported.add(key)) {
            return;
        }
        List<TransferRecord> allEdges = new ArrayList<>(edges);
        allEdges.add(closingEdge);
        List<String> recordIds = allEdges.stream().map(TransferRecord::id).sorted().toList();
        Set<String> involved = new LinkedHashSet<>(cycle);
        String evidence = "hops=" + allEdges.size()
                + ", spanMs=" + (maxTs - minTs)
                + ", records=" + recordIds;
        hits.add(new RiskHit(RuleType.CYCLE_BACKFLOW, List.copyOf(involved),
                List.copyOf(cycle), evidence, key));
    }
}
