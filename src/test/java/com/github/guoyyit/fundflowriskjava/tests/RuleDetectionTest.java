package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleDetectionTest {

    private static final long HOUR = 3_600_000L;

    private static TransferRecord tx(String id, String from, String to, long amount, long ts) {
        return new TransferRecord(id, from, to, amount, ts);
    }

    private static List<RiskHit> hitsOf(FundFlowService svc, RuleType type, RuleConfig config) {
        return svc.detect(config).stream().filter(h -> h.rule() == type).toList();
    }

    @Test
    void cycleDetected() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "a", "b", 100, 1000),
                tx("t2", "b", "c", 100, 2000),
                tx("t3", "c", "a", 100, 3000)));

        List<RiskHit> hits = hitsOf(svc, RuleType.CYCLE, RuleConfig.DEFAULT);
        assertEquals(1, hits.size());
        RiskHit hit = hits.get(0);
        assertEquals(List.of("a", "b", "c"), hit.addresses());
        assertEquals(List.of("a", "b", "c", "a"), hit.path());
        assertEquals(List.of("t1", "t2", "t3"), hit.txIds());
        assertFalse(hit.summary().isBlank());
    }

    @Test
    void selfLoopIsACycle() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(tx("t1", "a", "a", 100, 1000)));
        List<RiskHit> hits = hitsOf(svc, RuleType.CYCLE, RuleConfig.DEFAULT);
        assertEquals(1, hits.size());
        assertEquals(List.of("a"), hits.get(0).addresses());
    }

    @Test
    void cycleRespectsMaxHopsAndMinAmount() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "a", "b", 100, 1000),
                tx("t2", "b", "c", 100, 2000),
                tx("t3", "c", "a", 100, 3000)));

        RuleConfig tooFewHops = new RuleConfig(1, 2, HOUR * 24, 3, 0);
        assertTrue(hitsOf(svc, RuleType.CYCLE, tooFewHops).isEmpty());

        RuleConfig tooHighAmount = new RuleConfig(101, 6, HOUR * 24, 3, 0);
        assertTrue(hitsOf(svc, RuleType.CYCLE, tooHighAmount).isEmpty());

        RuleConfig ok = new RuleConfig(100, 3, HOUR * 24, 3, 0);
        assertEquals(1, hitsOf(svc, RuleType.CYCLE, ok).size());
    }

    @Test
    void aggregationDetected() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "s1", "hub", 50, 10_000),
                tx("t2", "s2", "hub", 50, 20_000),
                tx("t3", "s3", "hub", 50, 30_000)));

        List<RiskHit> hits = hitsOf(svc, RuleType.AGGREGATION, RuleConfig.DEFAULT);
        assertEquals(1, hits.size());
        RiskHit hit = hits.get(0);
        assertEquals(List.of("hub", "s1", "s2", "s3"), hit.addresses());
        assertEquals(List.of("t1", "t2", "t3"), hit.txIds());
    }

    @Test
    void aggregationRespectsWindowAndCounterparties() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "s1", "hub", 50, 10_000),
                tx("t2", "s2", "hub", 50, 20_000),
                tx("t3", "s3", "hub", 50, 30_000)));

        // window too small to cover all three
        RuleConfig tinyWindow = new RuleConfig(1, 6, 5_000, 3, 0);
        assertTrue(hitsOf(svc, RuleType.AGGREGATION, tinyWindow).isEmpty());

        // requires 4 distinct sources
        RuleConfig tooManySources = new RuleConfig(1, 6, HOUR, 4, 0);
        assertTrue(hitsOf(svc, RuleType.AGGREGATION, tooManySources).isEmpty());

        RuleConfig ok = new RuleConfig(1, 6, HOUR, 3, 0);
        assertEquals(1, hitsOf(svc, RuleType.AGGREGATION, ok).size());
    }

    @Test
    void layeringDetected() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                // layer 1: root splits to x, y, z
                tx("t1", "root", "x", 100, 1_000),
                tx("t2", "root", "y", 100, 2_000),
                tx("t3", "root", "z", 100, 3_000),
                // layer 2: x splits again after receiving
                tx("t4", "x", "p", 100, 4_000),
                tx("t5", "x", "q", 100, 5_000),
                tx("t6", "x", "r", 100, 6_000)));

        RuleConfig config = new RuleConfig(1, 6, HOUR, 3, 0);
        List<RiskHit> hits = hitsOf(svc, RuleType.LAYERING, config);
        assertEquals(1, hits.size());
        RiskHit hit = hits.get(0);
        assertEquals(List.of("root", "x"), hit.path());
        assertTrue(hit.addresses().containsAll(List.of("root", "x", "y", "z", "p", "q", "r")));
        assertEquals(6, hit.txIds().size());
    }

    @Test
    void layeringRequiresTwoLayersAndEnoughHops() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "root", "x", 100, 1_000),
                tx("t2", "root", "y", 100, 2_000),
                tx("t3", "root", "z", 100, 3_000)));

        RuleConfig config = new RuleConfig(1, 6, HOUR, 3, 0);
        // single layer split only -> no hit
        assertTrue(hitsOf(svc, RuleType.LAYERING, config).isEmpty());

        svc.apply(List.of(
                tx("t4", "x", "p", 100, 4_000),
                tx("t5", "x", "q", 100, 5_000),
                tx("t6", "x", "r", 100, 6_000)));
        assertEquals(1, hitsOf(svc, RuleType.LAYERING, config).size());

        // maxHops = 1 disables the rule
        RuleConfig oneHop = new RuleConfig(1, 1, HOUR, 3, 0);
        assertTrue(hitsOf(svc, RuleType.LAYERING, oneHop).isEmpty());
    }

    @Test
    void addressReuseDetected() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "s1", "addr", 50, 1_000),
                tx("t2", "addr", "d1", 50, 2_000),
                tx("t3", "addr", "d2", 50, 3_000)));

        RuleConfig config = new RuleConfig(1, 6, HOUR, 3, 0);
        List<RiskHit> hits = hitsOf(svc, RuleType.ADDRESS_REUSE, config);
        assertEquals(1, hits.size());
        RiskHit hit = hits.get(0);
        assertEquals(List.of("addr", "d1", "d2", "s1"), hit.addresses());
        assertEquals(List.of("t1", "t2", "t3"), hit.txIds());
    }

    @Test
    void addressReuseNeedsBothDirections() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "addr", "d1", 50, 1_000),
                tx("t2", "addr", "d2", 50, 2_000),
                tx("t3", "addr", "d3", 50, 3_000)));

        RuleConfig config = new RuleConfig(1, 6, HOUR, 3, 0);
        assertTrue(hitsOf(svc, RuleType.ADDRESS_REUSE, config).isEmpty());
    }

    @Test
    void detectionIsRepeatableUnderSameConfig() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(
                tx("t1", "a", "b", 100, 1000),
                tx("t2", "b", "c", 100, 2000),
                tx("t3", "c", "a", 100, 3000)));
        List<RiskHit> first = svc.detect(RuleConfig.DEFAULT);
        List<RiskHit> second = svc.detect(RuleConfig.DEFAULT);
        assertEquals(first, second);
    }
}
