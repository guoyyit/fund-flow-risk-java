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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启恢复：已提交更新不丢失，未提交事务不产生部分命中。
 */
class RecoveryTest {

    @TempDir
    Path tempDir;

    private static final RuleConfig CONFIG = new RuleConfig(
            new BigDecimal("10"), 4, 1_000L, 2, 3, 3);

    private FundFlowService newService(Path walFile) {
        DetectionEngine engine = new DetectionEngine(List.of(
                new CycleBackflowRule(), new LayeredSplitRule(),
                new RapidAggregationRule(), new AddressReuseRule()));
        FundFlowService service = new FundFlowService(new WalStore(walFile), engine);
        service.recover();
        return service;
    }

    private static TransferRecord rec(String id, String from, String to, String amount, long ts) {
        return new TransferRecord(id, from, to, new BigDecimal(amount), ts);
    }

    @Test
    void committedUpdatesSurviveRestart() {
        Path wal = tempDir.resolve("restart.wal");
        List<TransferRecord> records = List.of(
                rec("t1", "A", "B", "100", 100),
                rec("t2", "B", "C", "100", 200),
                rec("t3", "C", "A", "100", 300),
                rec("t4", "X", "Y", "20", 100));

        FundFlowService first = newService(wal);
        first.ingest(records.subList(0, 2));
        first.ingest(records.subList(2, 4));
        first.delete("t4");
        List<TransferRecord> expectedRecords = first.records();
        List<RiskHit> expectedHits = first.detect(CONFIG);

        // 模拟重启：同一 WAL 文件重新构建服务
        FundFlowService restarted = newService(wal);
        assertEquals(expectedRecords, restarted.records());
        assertEquals(expectedHits, restarted.detect(CONFIG));
        assertTrue(restarted.detect(CONFIG).stream()
                .anyMatch(h -> h.ruleType() == RuleType.CYCLE_BACKFLOW));
    }

    @Test
    void uncommittedTailIsIgnoredOnRecovery() throws IOException {
        Path wal = tempDir.resolve("tail.wal");
        FundFlowService first = newService(wal);
        first.ingest(List.of(rec("t1", "A", "B", "100", 100)));
        int committedCount = first.recordCount();

        // 模拟崩溃残留的未提交事务：半行 JSON
        Files.writeString(wal, "{\"adds\":[{\"id\":\"t2\",\"from\":\"B\",",
                StandardOpenOption.APPEND);

        FundFlowService restarted = newService(wal);
        assertEquals(committedCount, restarted.recordCount());
        assertEquals(List.of(rec("t1", "A", "B", "100", 100)), restarted.records());
    }

    @Test
    void emptyWalRecoversToEmptyState() {
        FundFlowService service = newService(tempDir.resolve("empty.wal"));
        assertEquals(0, service.recordCount());
        assertTrue(service.detect(CONFIG).isEmpty());
    }
}
