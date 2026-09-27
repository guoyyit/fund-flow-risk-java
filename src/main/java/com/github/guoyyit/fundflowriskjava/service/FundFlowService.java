package com.github.guoyyit.fundflowriskjava.service;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.persistence.WalStore;
import com.github.guoyyit.fundflowriskjava.rule.AddressReuseRule;
import com.github.guoyyit.fundflowriskjava.rule.AggregationRule;
import com.github.guoyyit.fundflowriskjava.rule.CycleDetectionRule;
import com.github.guoyyit.fundflowriskjava.rule.LayeringRule;
import com.github.guoyyit.fundflowriskjava.rule.RiskRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Core service: owns the graph, the WAL and the rule engine.
 *
 * <p>Concurrency: writes (apply/delete) run under the write lock and commit
 * atomically (WAL append + fsync first, then graph mutation); queries run
 * under the read lock, so they never observe a half-updated graph and always
 * observe every committed update.
 *
 * <p>Determinism: the graph is a set of edges keyed by txId, so the final
 * state is independent of arrival order; duplicate txIds are ignored (first
 * write wins); rule output is sorted canonically, which makes results
 * reproducible for the same set of inputs.
 */
public class FundFlowService {

    /** Result of applying a batch. */
    public record ApplyResult(int accepted, int duplicates, int rejected) {
    }

    /** Snapshot of graph statistics. */
    public record GraphStats(int addresses, int edges) {
    }

    private final FlowGraph graph = new FlowGraph();
    private final WalStore wal;
    private final List<RiskRule> rules = List.of(
            new CycleDetectionRule(),
            new LayeringRule(),
            new AggregationRule(),
            new AddressReuseRule());
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** Creates an in-memory service without persistence (for tests). */
    public FundFlowService() {
        this(null);
    }

    /** Creates a service backed by the given WAL and replays committed state. */
    public FundFlowService(WalStore wal) {
        this.wal = wal;
        if (wal != null) {
            wal.replay(batch -> batch.forEach(graph::add), graph::remove);
        }
    }

    /**
     * Applies a batch of transfers atomically. Duplicates (by txId, against
     * the graph or within the batch) are ignored; invalid records are
     * rejected. The accepted subset is appended to the WAL and fsynced before
     * the graph is mutated, so a crash never leaves a partially committed
     * batch visible.
     */
    public ApplyResult apply(List<TransferRecord> batch) {
        lock.writeLock().lock();
        try {
            List<TransferRecord> accepted = new ArrayList<>();
            Set<String> seenInBatch = new HashSet<>();
            int duplicates = 0;
            int rejected = 0;
            for (TransferRecord record : batch) {
                if (record == null || !seenInBatch.add(record.txId())) {
                    if (record != null) duplicates++; else rejected++;
                    continue;
                }
                if (graph.edge(record.txId()).isPresent()) {
                    duplicates++;
                    continue;
                }
                accepted.add(record);
            }
            if (!accepted.isEmpty()) {
                accepted.sort(TransferRecord::compareByTimeThenId);
                if (wal != null) {
                    wal.appendBatch(accepted);
                }
                accepted.forEach(graph::add);
            }
            return new ApplyResult(accepted.size(), duplicates, rejected);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rolls back a previously applied transfer. After the delete commits, the
     * graph and all future hits are exactly as if the record had never been
     * written.
     */
    public boolean delete(String txId) {
        lock.writeLock().lock();
        try {
            if (graph.edge(txId).isEmpty()) {
                return false;
            }
            if (wal != null) {
                wal.appendDelete(txId);
            }
            return graph.remove(txId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Runs all rules against the current committed state. */
    public List<RiskHit> detect(RuleConfig config) {
        lock.readLock().lock();
        try {
            List<RiskHit> hits = new ArrayList<>();
            for (RiskRule rule : rules) {
                hits.addAll(rule.evaluate(graph, config));
            }
            hits.sort(RiskHit::compare);
            return List.copyOf(hits);
        } finally {
            lock.readLock().unlock();
        }
    }

    public GraphStats stats() {
        lock.readLock().lock();
        try {
            return new GraphStats(graph.addressCount(), graph.edgeCount());
        } finally {
            lock.readLock().unlock();
        }
    }
}
