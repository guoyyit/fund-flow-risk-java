package com.github.guoyyit.fundflowriskjava.service;

import com.github.guoyyit.fundflowriskjava.detect.DetectionEngine;
import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.persistence.WalStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 资金流风险服务。
 * 写操作（写入/回滚）持写锁：先落 WAL 再改图，整批为一个事务，
 * 崩溃后未提交事务不产生部分命中；查询持读锁，
 * 读不到半更新图，也不会漏掉已完成提交的更新。
 */
public class FundFlowService {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final FlowGraph graph = new FlowGraph();
    private final WalStore wal;
    private final DetectionEngine engine;

    public FundFlowService(WalStore wal, DetectionEngine engine) {
        this.wal = wal;
        this.engine = engine;
    }

    /** 启动时重放 WAL 恢复已提交状态。 */
    public void recover() {
        lock.writeLock().lock();
        try {
            wal.replay(new WalStore.ReplayHandler() {
                @Override
                public void onRecord(TransferRecord record) {
                    graph.add(record);
                }

                @Override
                public void onDelete(String recordId) {
                    graph.remove(recordId);
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量增量写入，整批为一个事务。批内与历史中同 id 记录去重（首个生效），
     * 返回实际新增条数。重复、乱序、迟到记录的处理结果确定。
     */
    public int ingest(List<TransferRecord> records) {
        lock.writeLock().lock();
        try {
            List<TransferRecord> fresh = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (TransferRecord r : records) {
                if (seen.add(r.id()) && !graph.contains(r.id())) {
                    fresh.add(r);
                }
            }
            if (fresh.isEmpty()) {
                return 0;
            }
            wal.appendCommitted(fresh, List.of());
            fresh.forEach(graph::add);
            return fresh.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 回滚一条记录；不存在时返回 false。回滚后图状态与命中同从未写入一致。 */
    public boolean delete(String recordId) {
        lock.writeLock().lock();
        try {
            if (!graph.contains(recordId)) {
                return false;
            }
            wal.appendCommitted(List.of(), List.of(recordId));
            graph.remove(recordId);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 按给定参数对已提交图做检测，结果确定性排序，可重复计算。 */
    public List<RiskHit> detect(RuleConfig config) {
        lock.readLock().lock();
        try {
            RuleConfig effective = config == null ? RuleConfig.defaults() : config;
            return engine.detectAll(graph, effective);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<TransferRecord> records() {
        lock.readLock().lock();
        try {
            return graph.allRecords();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int recordCount() {
        lock.readLock().lock();
        try {
            return graph.recordCount();
        } finally {
            lock.readLock().unlock();
        }
    }
}
