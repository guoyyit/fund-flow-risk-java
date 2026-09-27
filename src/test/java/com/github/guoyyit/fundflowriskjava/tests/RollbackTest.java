package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.detect.AddressReuseRule;
import com.github.guoyyit.fundflowriskjava.detect.CycleBackflowRule;
import com.github.guoyyit.fundflowriskjava.detect.DetectionEngine;
import com.github.guoyyit.fundflowriskjava.detect.LayeredSplitRule;
import com.github.guoyyit.fundflowriskjava.detect.RapidAggregationRule;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚场景：删除记录后图状态与风险命中同从未写入该记录时一致。
 */
class RollbackTest {

    @TempDir
    Path tempDir;

    private static final RuleConfig CONFIG = new RuleConfig(
            new BigDecimal("10"), 4, 1_000L, 2, 3, 3);

    private FundFlowService newService(String name) {
        DetectionEngine engine = new DetectionEngine(List.of(
                new CycleBackflowRule(), new LayeredSplitRule(),
                new RapidAggregationRule(), new AddressReuseRule()));
        FundFlowService service = new FundFlowService(
                new WalStore(tempDir.resolve(name + ".wal")), engine);
        service.recover();
        return service;
    }

    private static TransferRecord rec(String id, String from, String to, String amount, long ts) {
        return new TransferRecord(id, from, to, new BigDecimal(amount), ts);
    }

    private static List<TransferRecord> goodRecords() {
        return List.of(
                rec("g1", "M", "N", "20", 100),
                rec("g2", "N", "O", "20", 200));
    }

    private static List<TransferRecord> badRecords() {
        // 构成环的错误记录
        return List.of(
                rec("b1", "A", "B", "100", 100),
                rec("b2", "B", "C", "100", 200),
                rec("b3", "C", "A", "100", 300));
    }

    @Test
    void rollbackRestoresStateAsIfNeverWritten() {
        FundFlowService service = newService("main");
        service.ingest(goodRecords());
        service.ingest(badRecords());
        // 删除前确有环命中
        assertTrue(service.detect(CONFIG).stream()
                .anyMatch(h -> h.ruleType() == RuleType.CYCLE_BACKFLOW));

        for (TransferRecord bad : badRecords()) {
            assertTrue(service.delete(bad.id()));
        }

        FundFlowService reference = newService("ref");
        reference.ingest(goodRecords());
        assertEquals(reference.records(), service.records());
        assertEquals(reference.detect(CONFIG), service.detect(CONFIG));
    }

    @Test
    void deletingOneCycleEdgeRemovesHit() {
        FundFlowService service = newService("edge");
        service.ingest(badRecords());
        assertTrue(service.delete("b2"));
        assertFalse(service.detect(CONFIG).stream()
                .anyMatch(h -> h.ruleType() == RuleType.CYCLE_BACKFLOW));
        assertEquals(2, service.recordCount());
    }

    @Test
    void deleteIsIdempotentAndUnknownIdIsNoop() {
        FundFlowService service = newService("idem");
        service.ingest(goodRecords());
        var hitsBefore = service.detect(CONFIG);
        assertFalse(service.delete("no-such-id"));
        assertTrue(service.delete("g1"));
        assertFalse(service.delete("g1"));
        assertEquals(1, service.recordCount());
        assertEquals(hitsBefore.stream()
                        .filter(h -> !h.evidence().contains("g1")).collect(Collectors.toList()),
                service.detect(CONFIG).stream()
                        .filter(h -> !h.evidence().contains("g1")).collect(Collectors.toList()));
    }
}
