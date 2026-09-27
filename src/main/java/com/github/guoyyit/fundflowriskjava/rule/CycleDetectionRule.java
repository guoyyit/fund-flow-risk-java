package com.github.guoyyit.fundflowriskjava.rule;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Detects circular fund flow (money leaving an address and coming back within
 * {@code maxHops} hops). Only transfers with amount &gt;= {@code minAmount}
 * participate. Each simple cycle is reported exactly once, anchored at its
 * lexicographically smallest address, so output is deterministic.
 *
 * <p>Parallel edges between the same address pair are collapsed to the
 * earliest one (by timestamp, then txId): cycles are keyed by their address
 * path, so one representative edge per hop is sufficient evidence and keeps
 * the search polynomial in the number of addresses.
 */
public class CycleDetectionRule implements RiskRule {

    @Override
    public RuleType type() {
        return RuleType.CYCLE;
    }

    @Override
    public List<RiskHit> evaluate(FlowGraph graph, RuleConfig config) {
        // address -> (neighbor -> representative edge), neighbor order sorted
        Map<String, Map<String, TransferRecord>> adj = new HashMap<>();
        for (String address : graph.addresses()) {
            for (TransferRecord e : graph.outEdges(address)) {
                if (e.amount() < config.minAmount()) {
                    continue;
                }
                // outEdges is sorted by (timestamp, txId): the first edge we
                // see for a neighbor is the earliest, keep it.
                adj.computeIfAbsent(address, k -> new TreeMap<>())
                        .putIfAbsent(e.to(), e);
            }
        }

        Set<String> reported = new HashSet<>();
        List<RiskHit> hits = new ArrayList<>();
        for (String start : graph.addresses()) {
            Deque<String> path = new ArrayDeque<>();
            path.addLast(start);
            dfs(start, start, adj, config.maxHops(), path,
                    new ArrayList<>(), reported, hits);
        }
        hits.sort(RiskHit::compare);
        return hits;
    }

    private void dfs(String start,
                     String current,
                     Map<String, Map<String, TransferRecord>> adj,
                     int maxHops,
                     Deque<String> path,
                     List<TransferRecord> edgePath,
                     Set<String> reported,
                     List<RiskHit> hits) {
        for (Map.Entry<String, TransferRecord> next : adj.getOrDefault(current, Map.of()).entrySet()) {
            String neighbor = next.getKey();
            TransferRecord edge = next.getValue();
            // Anchor each cycle at its smallest address: never descend into
            // an address smaller than the start.
            if (neighbor.compareTo(start) < 0) {
                continue;
            }
            if (neighbor.equals(start)) {
                int cycleLength = edgePath.size() + 1;
                if (cycleLength <= maxHops) {
                    recordCycle(path, edgePath, edge, reported, hits);
                }
                continue;
            }
            if (path.contains(neighbor)) {
                continue;
            }
            if (edgePath.size() + 1 >= maxHops) {
                // No room to both take this edge and return to start.
                continue;
            }
            path.addLast(neighbor);
            edgePath.add(edge);
            dfs(start, neighbor, adj, maxHops, path, edgePath, reported, hits);
            edgePath.remove(edgePath.size() - 1);
            path.removeLast();
        }
    }

    private void recordCycle(Deque<String> path,
                             List<TransferRecord> edgePath,
                             TransferRecord closingEdge,
                             Set<String> reported,
                             List<RiskHit> hits) {
        List<String> cycle = new ArrayList<>(path);
        String key = String.join("->", cycle);
        if (!reported.add(key)) {
            return;
        }
        List<String> txIds = new ArrayList<>();
        for (TransferRecord e : edgePath) {
            txIds.add(e.txId());
        }
        txIds.add(closingEdge.txId());
        txIds.sort(String::compareTo);

        List<String> displayPath = new ArrayList<>(cycle);
        displayPath.add(cycle.get(0));

        List<String> involved = new ArrayList<>(new TreeSet<>(cycle));
        String summary = "cycle of " + cycle.size() + " address(es): " + String.join("->", displayPath);
        hits.add(new RiskHit(RuleType.CYCLE, involved, displayPath, txIds, summary));
    }
}
