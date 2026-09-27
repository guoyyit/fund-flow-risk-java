package com.github.guoyyit.fundflowriskjava.detect;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 地址复用：同一地址作为收付双方，与不少于 minCounterparties 个
 * 不同对手方发生转账。每个地址只报告一次，对手方按字典序输出。
 */
public class AddressReuseRule implements RiskRule {

    @Override
    public RuleType type() {
        return RuleType.ADDRESS_REUSE;
    }

    @Override
    public List<RiskHit> detect(FlowGraph graph, RuleConfig config) {
        List<RiskHit> hits = new ArrayList<>();
        for (String addr : graph.addresses()) {
            Set<String> counterparties = new TreeSet<>();
            int outCount = 0;
            for (TransferRecord e : graph.outEdges(addr)) {
                counterparties.add(e.to());
                outCount++;
            }
            int inCount = 0;
            for (TransferRecord e : graph.inEdges(addr)) {
                counterparties.add(e.from());
                inCount++;
            }
            if (counterparties.size() >= config.minCounterparties()) {
                String key = RuleType.ADDRESS_REUSE + "|" + addr;
                String evidence = "counterparties=" + counterparties
                        + ", outCount=" + outCount
                        + ", inCount=" + inCount;
                List<String> addresses = new ArrayList<>();
                addresses.add(addr);
                addresses.addAll(counterparties);
                hits.add(new RiskHit(RuleType.ADDRESS_REUSE, List.copyOf(addresses),
                        List.of(addr), evidence, key));
            }
        }
        return hits;
    }
}
