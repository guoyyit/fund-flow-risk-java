package com.github.guoyyit.fundflowriskjava.rule;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Detects layered splitting: an address fans out to at least
 * {@code minCounterparties} distinct destinations (amount &gt;=
 * {@code minAmount}) inside a window of {@code timeWindowMillis}, and at
 * least one of those destinations splits again within the same window length
 * after receiving the funds. The chain of splits is followed up to
 * {@code maxHops} layers; {@code maxHops} &lt; 2 therefore disables the rule.
 * At most one hit per root address is reported, using the deterministic
 * earliest windows and lexicographic child order.
 */
public class LayeringRule implements RiskRule {

    private record SplitWindow(long start, List<TransferRecord> edges, List<String> destinations) {
    }

    @Override
    public RuleType type() {
        return RuleType.LAYERING;
    }

    @Override
    public List<RiskHit> evaluate(FlowGraph graph, RuleConfig config) {
        List<RiskHit> hits = new ArrayList<>();
        if (config.maxHops() < 2) {
            return hits;
        }
        for (String root : graph.addresses()) {
            SplitWindow first = splitWindow(graph, root, 0L, config);
            if (first == null) {
                continue;
            }
            List<String> chain = new ArrayList<>();
            chain.add(root);
            List<SplitWindow> windows = new ArrayList<>();
            windows.add(first);
            extendChain(graph, chain, windows, config);
            if (chain.size() >= 2) {
                hits.add(buildHit(chain, windows));
            }
        }
        hits.sort(RiskHit::compare);
        return hits;
    }

    /**
     * Greedily extends the split chain: at each layer pick the
     * lexicographically smallest destination that itself splits within the
     * allowed time after receiving funds. Deterministic by construction.
     */
    private void extendChain(FlowGraph graph, List<String> chain, List<SplitWindow> windows, RuleConfig config) {
        Set<String> inChain = new HashSet<>(chain);
        while (chain.size() < config.maxHops()) {
            SplitWindow current = windows.get(windows.size() - 1);
            String currentAddress = chain.get(chain.size() - 1);
            String next = null;
            SplitWindow nextWindow = null;
            for (String child : current.destinations()) {
                if (inChain.contains(child)) {
                    continue;
                }
                long receivedAt = current.edges().stream()
                        .filter(e -> e.to().equals(child))
                        .mapToLong(TransferRecord::timestamp)
                        .min()
                        .orElse(Long.MAX_VALUE);
                SplitWindow childWindow = splitWindow(graph, child, receivedAt, config);
                if (childWindow != null && childWindow.start() - receivedAt <= config.timeWindowMillis()) {
                    next = child;
                    nextWindow = childWindow;
                    break;
                }
            }
            if (next == null) {
                return;
            }
            chain.add(next);
            windows.add(nextWindow);
            inChain.add(next);
        }
    }

    /**
     * Finds the earliest window [t, t+timeWindowMillis] with t &gt;=
     * earliestStart in which the address sends to at least minCounterparties
     * distinct destinations. Returns null if there is none.
     */
    private SplitWindow splitWindow(FlowGraph graph, String address, long earliestStart, RuleConfig config) {
        List<TransferRecord> out = graph.outEdges(address).stream()
                .filter(e -> e.amount() >= config.minAmount())
                .filter(e -> !e.from().equals(e.to()))
                .filter(e -> e.timestamp() >= earliestStart)
                .toList();
        // out is sorted by (timestamp, txId); slide a window over it.
        for (int i = 0; i < out.size(); i++) {
            Set<String> destinations = new HashSet<>();
            List<TransferRecord> windowEdges = new ArrayList<>();
            for (int j = i; j < out.size()
                    && out.get(j).timestamp() - out.get(i).timestamp() <= config.timeWindowMillis(); j++) {
                windowEdges.add(out.get(j));
                destinations.add(out.get(j).to());
            }
            if (destinations.size() >= config.minCounterparties()) {
                List<String> sortedDestinations = new ArrayList<>(new TreeSet<>(destinations));
                return new SplitWindow(out.get(i).timestamp(), windowEdges, sortedDestinations);
            }
        }
        return null;
    }

    private RiskHit buildHit(List<String> chain, List<SplitWindow> windows) {
        Set<String> addresses = new TreeSet<>(chain);
        List<String> txIds = new ArrayList<>();
        for (SplitWindow window : windows) {
            for (TransferRecord e : window.edges()) {
                addresses.add(e.to());
                txIds.add(e.txId());
            }
        }
        txIds.sort(String::compareTo);
        String summary = "layered split across " + chain.size() + " layer(s): "
                + String.join(" -> ", chain);
        return new RiskHit(RuleType.LAYERING,
                new ArrayList<>(addresses),
                List.copyOf(chain),
                txIds,
                summary);
    }
}
