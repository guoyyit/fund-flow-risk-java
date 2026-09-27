package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RollbackTest {

    private static TransferRecord tx(String id, String from, String to, long amount, long ts) {
        return new TransferRecord(id, from, to, amount, ts);
    }

    @Test
    void rollbackRemovesHitsAndRestoresState() {
        List<TransferRecord> base = List.of(
                tx("t1", "a", "b", 100, 1_000),
                tx("t2", "b", "c", 100, 2_000));
        TransferRecord closing = tx("t3", "c", "a", 100, 3_000);

        FundFlowService reference = new FundFlowService();
        reference.apply(base);

        FundFlowService svc = new FundFlowService();
        svc.apply(base);
        svc.apply(List.of(closing));
        // cycle hit exists before rollback
        assertEquals(1, svc.detect(RuleConfig.DEFAULT).stream()
                .filter(h -> h.rule() == RuleType.CYCLE).count());

        assertTrue(svc.delete("t3"));
        assertEquals(reference.stats(), svc.stats());
        assertEquals(reference.detect(RuleConfig.DEFAULT), svc.detect(RuleConfig.DEFAULT));
    }

    @Test
    void rollbackOfUnknownTxIdIsNoOp() {
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(tx("t1", "a", "b", 100, 1_000)));
        assertFalse(svc.delete("nope"));
        assertEquals(1, svc.stats().edges());
    }

    @Test
    void rollbackThenReapplyIsConsistent() {
        TransferRecord record = tx("t1", "a", "b", 100, 1_000);
        FundFlowService svc = new FundFlowService();
        svc.apply(List.of(record));
        assertTrue(svc.delete("t1"));
        // after rollback the same txId can be applied again
        FundFlowService.ApplyResult result = svc.apply(List.of(record));
        assertEquals(1, result.accepted());
        assertEquals(1, svc.stats().edges());

        FundFlowService reference = new FundFlowService();
        reference.apply(List.of(record));
        assertEquals(reference.detect(RuleConfig.DEFAULT), svc.detect(RuleConfig.DEFAULT));
    }

    @Test
    void rollbackWholeBatchRestoresExactHits() {
        List<TransferRecord> batch1 = List.of(
                tx("a1", "s1", "hub", 50, 10_000),
                tx("a2", "s2", "hub", 50, 20_000));
        List<TransferRecord> batch2 = List.of(
                tx("a3", "s3", "hub", 50, 30_000),
                tx("a4", "hub", "x", 50, 40_000));

        FundFlowService reference = new FundFlowService();
        reference.apply(batch1);

        FundFlowService svc = new FundFlowService();
        svc.apply(batch1);
        svc.apply(batch2);
        svc.delete("a4");
        svc.delete("a3");

        assertEquals(reference.stats(), svc.stats());
        assertEquals(reference.detect(RuleConfig.DEFAULT), svc.detect(RuleConfig.DEFAULT));
    }
}
