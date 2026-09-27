package com.github.guoyyit.fundflowriskjava.rule;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Detects fast aggregation: at least {@code minCounterparties} distinct
 * sources send transfers (amount &gt;= {@code minAmount}) to the same address
 * within any sliding window of {@code timeWindowMillis}. Self-transfers are
 * ignored. At most one hit per address is reported, anchored at the earliest
 * qualifying window, which keeps the output deterministic.
 */
public class AggregationRule implements RiskRule {

    @Override
    public RuleType type() {
        return RuleType.AGGREGATION;
    }

    @Override
    public List<RiskHit> evaluate(FlowGraph graph, RuleConfig config) {
        List<RiskHit> hits = new ArrayList<>();
        for (String address : graph.addresses()) {
            List<TransferRecord> in = graph.inEdges(address).stream()
                    .filter(e -> e.amount() >= config.minAmount())
                    .filter(e -> !e.from().equals(e.to()))
                    .toList();
            RiskHit hit = findWindow(address, in, config);
            if (hit != null) {
                hits.add(hit);
            }
        }
        hits.sort(RiskHit::compare);
        return hits;
    }

    private RiskHit findWindow(String target, List<TransferRecord> in, RuleConfig config) {
        // in is sorted by (timestamp, txId). Two-pointer sliding window.
        Map<String, Integer> senderCount = new HashMap<>();
        int left = 0;
        for (int right = 0; right < in.size(); right++) {
            TransferRecord r = in.get(right);
            senderCount.merge(r.from(), 1, Integer::sum);
            while (r.timestamp() - in.get(left).timestamp() > config.timeWindowMillis()) {
                TransferRecord l = in.get(left);
                if (senderCount.merge(l.from(), -1, Integer::sum) == 0) {
                    senderCount.remove(l.from());
                }
                left++;
            }
            if (senderCount.size() >= config.minCounterparties()) {
                return buildHit(target, in, left, right, config);
            }
        }
        return null;
    }

    private RiskHit buildHit(String target, List<TransferRecord> in, int left, int right, RuleConfig config) {
        List<TransferRecord> evidence = new ArrayList<>(in.subList(left, right + 1));
        List<String> senders = new ArrayList<>(new TreeSet<>(
                evidence.stream().map(TransferRecord::from).toList()));
        List<String> txIds = evidence.stream().map(TransferRecord::txId).sorted().toList();

        List<String> addresses = new ArrayList<>(senders);
        addresses.add(target);
        addresses.sort(String::compareTo);

        List<String> path = new ArrayList<>(senders);
        path.add(target);

        long windowStart = evidence.get(0).timestamp();
        String summary = senders.size() + " distinct sources sent " + evidence.size()
                + " transfer(s) to " + target + " within " + config.timeWindowMillis()
                + "ms starting at " + windowStart;
        return new RiskHit(RuleType.AGGREGATION, addresses, path, txIds, summary);
    }
}
