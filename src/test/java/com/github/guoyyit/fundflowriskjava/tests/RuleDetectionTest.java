package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.detect.AddressReuseRule;
import com.github.guoyyit.fundflowriskjava.detect.CycleBackflowRule;
import com.github.guoyyit.fundflowriskjava.detect.DetectionEngine;
import com.github.guoyyit.fundflowriskjava.detect.LayeredSplitRule;
import com.github.guoyyit.fundflowriskjava.detect.RapidAggregationRule;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.persistence.WalStore;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四类规则的命中与参数敏感性测试。
 */
class RuleDetectionTest {

    @TempDir
    Path tempDir;

    private static final RuleConfig CONFIG = new RuleConfig(
            new BigDecimal("10"), 4, 1_000L, 2, 3, 3);

    private FundFlowService newService() {
        DetectionEngine engine = new DetectionEngine(List.of(
                new CycleBackflowRule(), new LayeredSplitRule(),
                new RapidAggregationRule(), new AddressReuseRule()));
        FundFlowService service = new FundFlowService(
                new WalStore(tempDir.resolve("test-" + System.nanoTime() + ".wal")), engine);
        service.recover();
        return service;
    }

    private static TransferRecord rec(String id, String from, String to, String amount, long ts) {
        return new TransferRecord(id, from, to, new BigDecimal(amount), ts);
    }

    private static Set<RuleType> ruleTypes(List<RiskHit> hits) {
        return hits.stream().map(RiskHit::ruleType).collect(Collectors.toSet());
    }

    @Test
    void cycleBackflowHit() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "A", "B", "100", 100),
                rec("t2", "B", "C", "100", 200),
                rec("t3", "C", "A", "100", 300)));

        List<RiskHit> hits = service.detect(CONFIG);
        assertTrue(ruleTypes(hits).contains(RuleType.CYCLE_BACKFLOW));
        RiskHit hit = hits.stream()
                .filter(h -> h.ruleType() == RuleType.CYCLE_BACKFLOW).findFirst().orElseThrow();
        assertEquals(List.of("A", "B", "C", "A"), hit.path());
        assertEquals(Set.of("A", "B", "C"), Set.copyOf(hit.addresses()));
        assertTrue(hit.evidence().contains("hops=3"));
    }

    @Test
    void cycleBackflowRespectsAmountThreshold() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "A", "B", "100", 100),
                rec("t2", "B", "C", "5", 200),
                rec("t3", "C", "A", "100", 300)));
        assertFalse(ruleTypes(service.detect(CONFIG)).contains(RuleType.CYCLE_BACKFLOW));
    }

    @Test
    void cycleBackflowRespectsTimeWindow() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "A", "B", "100", 100),
                rec("t2", "B", "C", "100", 200),
                rec("t3", "C", "A", "100", 100_000)));
        assertFalse(ruleTypes(service.detect(CONFIG)).contains(RuleType.CYCLE_BACKFLOW));
    }

    @Test
    void layeredSplitHit() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "R", "X1", "100", 100),
                rec("t2", "R", "X2", "100", 200),
                rec("t3", "X1", "Y1", "50", 300),
                rec("t4", "X1", "Y2", "50", 400)));

        List<RiskHit> hits = service.detect(CONFIG);
        assertTrue(ruleTypes(hits).contains(RuleType.LAYERED_SPLIT));
        RiskHit hit = hits.stream()
                .filter(h -> h.ruleType() == RuleType.LAYERED_SPLIT).findFirst().orElseThrow();
        assertTrue(hit.addresses().contains("R"));
        assertTrue(hit.evidence().contains("layer1=[X1, X2]"));
        assertTrue(hit.evidence().contains("layer2=[Y1, Y2]"));
    }

    @Test
    void layeredSplitRequiresSecondLayer() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "R", "X1", "100", 100),
                rec("t2", "R", "X2", "100", 200),
                rec("t3", "X1", "Y1", "50", 300)));
        assertFalse(ruleTypes(service.detect(CONFIG)).contains(RuleType.LAYERED_SPLIT));
    }

    @Test
    void rapidAggregationHit() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "S1", "H", "50", 100),
                rec("t2", "S2", "H", "50", 200),
                rec("t3", "S3", "H", "50", 300)));

        List<RiskHit> hits = service.detect(CONFIG);
        assertTrue(ruleTypes(hits).contains(RuleType.RAPID_AGGREGATION));
        RiskHit hit = hits.stream()
                .filter(h -> h.ruleType() == RuleType.RAPID_AGGREGATION).findFirst().orElseThrow();
        assertTrue(hit.addresses().contains("H"));
        assertTrue(hit.evidence().contains("total=150"));
    }

    @Test
    void rapidAggregationRequiresEnoughSources() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "S1", "H", "50", 100),
                rec("t2", "S2", "H", "50", 200)));
        assertFalse(ruleTypes(service.detect(CONFIG)).contains(RuleType.RAPID_AGGREGATION));
    }

    @Test
    void addressReuseHit() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "U", "P1", "5", 100),
                rec("t2", "U", "P2", "5", 200),
                rec("t3", "P3", "U", "5", 300)));

        List<RiskHit> hits = service.detect(CONFIG);
        assertTrue(ruleTypes(hits).contains(RuleType.ADDRESS_REUSE));
        RiskHit hit = hits.stream()
                .filter(h -> h.ruleType() == RuleType.ADDRESS_REUSE).findFirst().orElseThrow();
        assertEquals(List.of("U"), hit.path());
        assertTrue(hit.evidence().contains("outCount=2"));
        assertTrue(hit.evidence().contains("inCount=1"));
    }

    @Test
    void hitsAreDeterministicAndSorted() {
        FundFlowService service = newService();
        service.ingest(List.of(
                rec("t1", "A", "B", "100", 100),
                rec("t2", "B", "C", "100", 200),
                rec("t3", "C", "A", "100", 300),
                rec("t4", "S1", "H", "50", 100),
                rec("t5", "S2", "H", "50", 200),
                rec("t6", "S3", "H", "50", 300)));
        List<RiskHit> first = service.detect(CONFIG);
        List<RiskHit> second = service.detect(CONFIG);
        assertEquals(first, second);
        List<String> keys = first.stream().map(RiskHit::hitKey).toList();
        assertEquals(keys.stream().sorted().toList(), keys);
    }
}
