package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.detect.AddressReuseRule;
import com.github.guoyyit.fundflowriskjava.detect.CycleBackflowRule;
import com.github.guoyyit.fundflowriskjava.detect.DetectionEngine;
import com.github.guoyyit.fundflowriskjava.detect.LayeredSplitRule;
import com.github.guoyyit.fundflowriskjava.detect.RapidAggregationRule;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.persistence.WalStore;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 乱序、重复、迟到记录与固定种子可复现性测试。
 */
class OutOfOrderDuplicateTest {

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

    /** 一组同时覆盖四类规则的记录。 */
    private static List<TransferRecord> baseRecords() {
        return List.of(
                // 环 A->B->C->A
                rec("c1", "A", "B", "100", 100),
                rec("c2", "B", "C", "100", 200),
                rec("c3", "C", "A", "100", 300),
                // 分层拆分 R->X1,X2; X1->Y1,Y2
                rec("s1", "R", "X1", "100", 100),
                rec("s2", "R", "X2", "100", 200),
                rec("s3", "X1", "Y1", "50", 300),
                rec("s4", "X1", "Y2", "50", 400),
                // 汇聚 S1,S2,S3 -> H
                rec("a1", "S1", "H", "50", 100),
                rec("a2", "S2", "H", "50", 200),
                rec("a3", "S3", "H", "50", 300),
                // 复用 U <-> P1,P2,P3
                rec("u1", "U", "P1", "5", 100),
                rec("u2", "U", "P2", "5", 200),
                rec("u3", "P3", "U", "5", 300));
    }

    @Test
    void shuffledInputWithFixedSeedsProducesIdenticalStateAndHits() {
        List<TransferRecord> base = baseRecords();
        List<TransferRecord> referenceRecords = null;
        List<RiskHit> referenceHits = null;

        for (long seed : new long[]{1L, 42L, 999L, 20260927L}) {
            List<TransferRecord> shuffled = new ArrayList<>(base);
            Collections.shuffle(shuffled, new Random(seed));
            // 混入重复记录
            shuffled.addAll(shuffled.subList(0, 5));
            shuffled.addAll(shuffled.subList(0, 3));

            FundFlowService service = newService("shuffle-" + seed);
            // 分多批乱序写入
            int added = 0;
            for (int i = 0; i < shuffled.size(); i += 4) {
                added += service.ingest(shuffled.subList(i, Math.min(i + 4, shuffled.size())));
            }
            assertEquals(base.size(), added, "seed=" + seed);
            assertEquals(base.size(), service.recordCount(), "seed=" + seed);

            if (referenceRecords == null) {
                referenceRecords = service.records();
                referenceHits = service.detect(CONFIG);
            } else {
                assertEquals(referenceRecords, service.records(), "seed=" + seed);
                assertEquals(referenceHits, service.detect(CONFIG), "seed=" + seed);
            }
        }
    }

    @Test
    void duplicateIngestIsIdempotent() {
        FundFlowService service = newService("dup");
        List<TransferRecord> base = baseRecords();
        assertEquals(base.size(), service.ingest(base));
        List<RiskHit> hitsBefore = service.detect(CONFIG);
        // 原样重复写入，不产生重复边或重复命中
        assertEquals(0, service.ingest(base));
        assertEquals(0, service.ingest(base.subList(0, 3)));
        assertEquals(base.size(), service.recordCount());
        assertEquals(hitsBefore, service.detect(CONFIG));
    }

    @Test
    void lateRecordYieldsSameResultAsBatchIngest() {
        List<TransferRecord> base = baseRecords();
        TransferRecord late = base.get(0); // 环的第一条边最后才到

        FundFlowService incremental = newService("late");
        incremental.ingest(base.subList(1, base.size()));
        incremental.ingest(List.of(late)); // 迟到记录

        FundFlowService batch = newService("batch");
        batch.ingest(base);

        assertEquals(batch.records(), incremental.records());
        assertEquals(batch.detect(CONFIG), incremental.detect(CONFIG));
    }
}
