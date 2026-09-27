package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.detect.AddressReuseRule;
import com.github.guoyyit.fundflowriskjava.detect.CycleBackflowRule;
import com.github.guoyyit.fundflowriskjava.detect.DetectionEngine;
import com.github.guoyyit.fundflowriskjava.detect.LayeredSplitRule;
import com.github.guoyyit.fundflowriskjava.detect.RapidAggregationRule;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.persistence.WalStore;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发场景：多线程增量写入与并发查询并存，
 * 查询读不到半更新图，也不漏已提交更新。
 */
class ConcurrencyTest {

    @TempDir
    Path tempDir;

    private static final RuleConfig CONFIG = new RuleConfig(
            new BigDecimal("10"), 4, 1_000L, 2, 3, 3);

    private static final int WRITERS = 4;
    private static final int BATCHES_PER_WRITER = 50;
    private static final int EDGES_PER_BATCH = 3;

    private FundFlowService newService() {
        DetectionEngine engine = new DetectionEngine(List.of(
                new CycleBackflowRule(), new LayeredSplitRule(),
                new RapidAggregationRule(), new AddressReuseRule()));
        FundFlowService service = new FundFlowService(
                new WalStore(tempDir.resolve("concurrent.wal")), engine);
        service.recover();
        return service;
    }

    /** 每批 3 条边构成一个环，id 前缀标识批次，用于检验读取原子性。 */
    private static List<TransferRecord> batch(int writer, int batchNo) {
        String prefix = "w" + writer + "-b" + batchNo;
        String a = prefix + "-A";
        String b = prefix + "-B";
        String c = prefix + "-C";
        return List.of(
                new TransferRecord(prefix + "-e1", a, b, new BigDecimal("100"), 100),
                new TransferRecord(prefix + "-e2", b, c, new BigDecimal("100"), 200),
                new TransferRecord(prefix + "-e3", c, a, new BigDecimal("100"), 300));
    }

    @Test
    void concurrentIngestAndQuery() throws InterruptedException {
        FundFlowService service = newService();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writersDone = new AtomicBoolean(false);
        AtomicReference<String> violation = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            final int writer = w;
            threads.add(new Thread(() -> {
                try {
                    start.await();
                    for (int b = 0; b < BATCHES_PER_WRITER; b++) {
                        service.ingest(batch(writer, b));
                    }
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            }, "writer-" + w));
        }
        for (int r = 0; r < 2; r++) {
            threads.add(new Thread(() -> {
                try {
                    start.await();
                    while (!writersDone.get()) {
                        // 查询不能抛异常，且每批要么全见要么全不见
                        service.detect(CONFIG);
                        Map<String, Long> byBatch = service.records().stream()
                                .collect(Collectors.groupingBy(
                                        t -> t.id().substring(0, t.id().lastIndexOf('-')),
                                        Collectors.counting()));
                        for (Map.Entry<String, Long> e : byBatch.entrySet()) {
                            if (e.getValue() != EDGES_PER_BATCH) {
                                violation.compareAndSet(null,
                                        "半更新批次 " + e.getKey() + " 只有 " + e.getValue() + " 条");
                                return;
                            }
                        }
                    }
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            }, "reader-" + r));
        }

        List<Thread> writerThreads = threads.subList(0, WRITERS);
        List<Thread> readerThreads = threads.subList(WRITERS, threads.size());
        threads.forEach(Thread::start);
        start.countDown();
        // 先等写线程全部完成，再通知读线程退出
        for (Thread t : writerThreads) {
            t.join(TimeUnit.SECONDS.toMillis(60));
        }
        writersDone.set(true);
        for (Thread t : readerThreads) {
            t.join(TimeUnit.SECONDS.toMillis(60));
        }

        assertNull(unexpected.get(), () -> "并发执行异常: " + unexpected.get());
        assertNull(violation.get(), () -> "读到半更新图: " + violation.get());
        // 不漏已提交更新
        assertEquals(WRITERS * BATCHES_PER_WRITER * EDGES_PER_BATCH, service.recordCount());
        // 每个环都应命中
        long cycleHits = service.detect(CONFIG).stream()
                .filter(h -> h.ruleType().name().equals("CYCLE_BACKFLOW")).count();
        assertTrue(cycleHits >= WRITERS * BATCHES_PER_WRITER,
                "已提交的环应全部命中, 实际: " + cycleHits);
    }
}
