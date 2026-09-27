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
 * Detects address reuse: within a sliding window of
 * {@code timeWindowMillis} an address both sends and receives transfers
 * (amount &gt;= {@code minAmount}, self-transfers ignored) involving at least
 * {@code minCounterparties} distinct counterparties. At most one hit per
 * address is reported, anchored at the earliest qualifying window.
 */
public class AddressReuseRule implements RiskRule {

    private record Event(TransferRecord record, boolean incoming, String counterparty) {
    }

    @Override
    public RuleType type() {
        return RuleType.ADDRESS_REUSE;
    }

    @Override
    public List<RiskHit> evaluate(FlowGraph graph, RuleConfig config) {
        List<RiskHit> hits = new ArrayList<>();
        for (String address : graph.addresses()) {
            List<Event> events = eventsOf(graph, address, config);
            RiskHit hit = findWindow(address, events, config);
            if (hit != null) {
                hits.add(hit);
            }
        }
        hits.sort(RiskHit::compare);
        return hits;
    }

    private List<Event> eventsOf(FlowGraph graph, String address, RuleConfig config) {
        List<Event> events = new ArrayList<>();
        for (TransferRecord e : graph.inEdges(address)) {
            if (e.amount() >= config.minAmount() && !e.from().equals(address)) {
                events.add(new Event(e, true, e.from()));
            }
        }
        for (TransferRecord e : graph.outEdges(address)) {
            if (e.amount() >= config.minAmount() && !e.to().equals(address)) {
                events.add(new Event(e, false, e.to()));
            }
        }
        events.sort((a, b) -> TransferRecord.compareByTimeThenId(a.record(), b.record()));
        return events;
    }

    private RiskHit findWindow(String address, List<Event> events, RuleConfig config) {
        Map<String, Integer> counterparties = new HashMap<>();
        int inCount = 0;
        int outCount = 0;
        int left = 0;
        for (int right = 0; right < events.size(); right++) {
            Event r = events.get(right);
            counterparties.merge(r.counterparty(), 1, Integer::sum);
            if (r.incoming()) inCount++; else outCount++;
            while (r.record().timestamp() - events.get(left).record().timestamp() > config.timeWindowMillis()) {
                Event l = events.get(left);
                if (counterparties.merge(l.counterparty(), -1, Integer::sum) == 0) {
                    counterparties.remove(l.counterparty());
                }
                if (l.incoming()) inCount--; else outCount--;
                left++;
            }
            if (inCount > 0 && outCount > 0 && counterparties.size() >= config.minCounterparties()) {
                return buildHit(address, events.subList(left, right + 1), inCount, outCount, config);
            }
        }
        return null;
    }

    private RiskHit buildHit(String address, List<Event> window, int inCount, int outCount, RuleConfig config) {
        List<String> counterparties = new ArrayList<>(new TreeSet<>(
                window.stream().map(Event::counterparty).toList()));
        List<String> txIds = window.stream()
                .map(e -> e.record().txId())
                .distinct()
                .sorted()
                .toList();

        List<String> addresses = new ArrayList<>(counterparties);
        addresses.add(address);
        addresses.sort(String::compareTo);

        List<String> path = new ArrayList<>(counterparties);
        path.add(address);

        String summary = "address " + address + " reused with " + counterparties.size()
                + " distinct counterparty(ies) (in=" + inCount + ", out=" + outCount
                + ") within " + config.timeWindowMillis() + "ms";
        return new RiskHit(RuleType.ADDRESS_REUSE, addresses, path, txIds, summary);
    }
}
