package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {

    private static final int WRITERS = 4;
    private static final int READERS = 4;
    private static final int BATCHES_PER_WRITER = 50;
    private static final int BATCH_SIZE = 3;

    private static TransferRecord tx(String id, String from, String to, long amount, long ts) {
        return new TransferRecord(id, from, to, amount, ts);
    }

    @Test
    void concurrentWritesAndQueriesAreConsistent() throws Exception {
        FundFlowService svc = new FundFlowService();
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stopReaders = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Throwable> readerErrors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> invariantViolations = new ConcurrentLinkedQueue<>();

        List<Future<?>> writerFutures = new ArrayList<>();
        List<Future<?>> readerFutures = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            final int writer = w;
            writerFutures.add(pool.submit(() -> {
                await(start);
                for (int b = 0; b < BATCHES_PER_WRITER; b++) {
                    List<TransferRecord> batch = new ArrayList<>();
                    for (int i = 0; i < BATCH_SIZE; i++) {
                        String id = "w" + writer + "-b" + b + "-t" + i;
                        batch.add(tx(id, "addr-" + (writer % 3), "addr-" + ((writer + 1) % 3),
                                100, b * 1000L + i));
                    }
                    svc.apply(batch);
                }
            }));
        }
        for (int r = 0; r < READERS; r++) {
            readerFutures.add(pool.submit(() -> {
                await(start);
                try {
                    while (!stopReaders.get()) {
                        // invariant: batches commit atomically, so the visible
                        // edge count is always a multiple of BATCH_SIZE
                        int edges = svc.stats().edges();
                        if (edges % BATCH_SIZE != 0) {
                            invariantViolations.add("observed half-updated graph, edges=" + edges);
                        }
                        svc.detect(RuleConfig.DEFAULT); // must never throw or block forever
                    }
                } catch (Throwable t) {
                    readerErrors.add(t);
                }
            }));
        }

        start.countDown();
        for (Future<?> f : writerFutures) {
            f.get(60, TimeUnit.SECONDS);
        }
        stopReaders.set(true);
        for (Future<?> f : readerFutures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(invariantViolations.isEmpty(), invariantViolations.toString());
        assertTrue(readerErrors.isEmpty(), readerErrors.toString());

        // every committed update is visible after the writers finish
        assertEquals(WRITERS * BATCHES_PER_WRITER * BATCH_SIZE, svc.stats().edges());

        // and the result equals a purely sequential computation
        FundFlowService sequential = new FundFlowService();
        for (int w = 0; w < WRITERS; w++) {
            for (int b = 0; b < BATCHES_PER_WRITER; b++) {
                List<TransferRecord> batch = new ArrayList<>();
                for (int i = 0; i < BATCH_SIZE; i++) {
                    String id = "w" + w + "-b" + b + "-t" + i;
                    batch.add(tx(id, "addr-" + (w % 3), "addr-" + ((w + 1) % 3), 100, b * 1000L + i));
                }
                sequential.apply(batch);
            }
        }
        assertEquals(sequential.detect(RuleConfig.DEFAULT), svc.detect(RuleConfig.DEFAULT));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
