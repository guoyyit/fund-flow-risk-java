package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.persistence.WalStore;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    @TempDir
    Path dir;

    private static TransferRecord tx(String id, String from, String to, long amount, long ts) {
        return new TransferRecord(id, from, to, amount, ts);
    }

    private static List<TransferRecord> cycleData() {
        return List.of(
                tx("t1", "a", "b", 100, 1_000),
                tx("t2", "b", "c", 100, 2_000),
                tx("t3", "c", "a", 100, 3_000));
    }

    @Test
    void restartRestoresCommittedState() {
        Path wal = dir.resolve("fundflow.wal");
        FundFlowService first = new FundFlowService(new WalStore(wal));
        first.apply(cycleData());
        List<RiskHit> beforeRestart = first.detect(RuleConfig.DEFAULT);
        assertFalse(beforeRestart.isEmpty());

        // simulate restart: brand new service over the same WAL file
        FundFlowService second = new FundFlowService(new WalStore(wal));
        assertEquals(first.stats(), second.stats());
        assertEquals(beforeRestart, second.detect(RuleConfig.DEFAULT));
    }

    @Test
    void deletesSurviveRestart() {
        Path wal = dir.resolve("fundflow.wal");
        FundFlowService first = new FundFlowService(new WalStore(wal));
        first.apply(cycleData());
        assertTrue(first.delete("t2"));

        FundFlowService second = new FundFlowService(new WalStore(wal));
        assertEquals(2, second.stats().edges());
        assertTrue(second.detect(RuleConfig.DEFAULT).isEmpty());
    }

    @Test
    void tornTailLineIsNotReplayed() throws Exception {
        Path wal = dir.resolve("fundflow.wal");
        FundFlowService first = new FundFlowService(new WalStore(wal));
        first.apply(cycleData());

        // simulate a crash mid-write: append a partial JSON line
        Files.writeString(wal, "{\"type\":\"batch\",\"records\":[{\"txId\":\"t4\",\"from\":\"a\"",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        FundFlowService second = new FundFlowService(new WalStore(wal));
        // only the committed batch is visible; the torn write is gone
        assertEquals(3, second.stats().edges());
        assertEquals(first.detect(RuleConfig.DEFAULT), second.detect(RuleConfig.DEFAULT));

        // and the WAL remains usable for further appends after truncation
        second.apply(List.of(tx("t5", "x", "y", 10, 5_000)));
        FundFlowService third = new FundFlowService(new WalStore(wal));
        assertEquals(4, third.stats().edges());
    }

    @Test
    void emptyWalStartsEmpty() {
        FundFlowService svc = new FundFlowService(new WalStore(dir.resolve("fundflow.wal")));
        assertEquals(0, svc.stats().edges());
        assertTrue(svc.detect(RuleConfig.DEFAULT).isEmpty());
    }

    @Test
    void walContentIsUtf8JsonLines() throws Exception {
        Path wal = dir.resolve("fundflow.wal");
        FundFlowService svc = new FundFlowService(new WalStore(wal));
        svc.apply(cycleData());
        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"batch\""));
        assertTrue(lines.get(0).contains("\"txId\":\"t1\""));
    }
}
