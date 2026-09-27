package com.github.guoyyit.fundflowriskjava.graph;

import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Directed fund-flow graph. Not thread-safe by itself; external locking is
 * provided by the service layer. All returned collections are deterministically
 * ordered so that rule evaluation is reproducible regardless of insertion order.
 */
public class FlowGraph {

    private final Map<String, TransferRecord> byTxId = new HashMap<>();
    private final Map<String, List<TransferRecord>> outAdj = new HashMap<>();
    private final Map<String, List<TransferRecord>> inAdj = new HashMap<>();

    /**
     * Adds a transfer as an edge. A record whose txId already exists is ignored
     * (first write wins), so re-delivering the same record never creates a
     * duplicate edge.
     *
     * @return true if the edge was actually added
     */
    public boolean add(TransferRecord record) {
        if (byTxId.putIfAbsent(record.txId(), record) != null) {
            return false;
        }
        outAdj.computeIfAbsent(record.from(), k -> new ArrayList<>()).add(record);
        inAdj.computeIfAbsent(record.to(), k -> new ArrayList<>()).add(record);
        return true;
    }

    /**
     * Removes the edge with the given txId.
     *
     * @return true if an edge was removed
     */
    public boolean remove(String txId) {
        TransferRecord removed = byTxId.remove(txId);
        if (removed == null) {
            return false;
        }
        removeFromAdjacency(outAdj, removed.from(), txId);
        removeFromAdjacency(inAdj, removed.to(), txId);
        return true;
    }

    private static void removeFromAdjacency(Map<String, List<TransferRecord>> adj, String address, String txId) {
        List<TransferRecord> edges = adj.get(address);
        if (edges != null) {
            edges.removeIf(e -> e.txId().equals(txId));
            if (edges.isEmpty()) {
                adj.remove(address);
            }
        }
    }

    /** Outgoing edges of an address, sorted by (timestamp, txId). */
    public List<TransferRecord> outEdges(String address) {
        return sorted(outAdj.getOrDefault(address, List.of()));
    }

    /** Incoming edges of an address, sorted by (timestamp, txId). */
    public List<TransferRecord> inEdges(String address) {
        return sorted(inAdj.getOrDefault(address, List.of()));
    }

    private static List<TransferRecord> sorted(List<TransferRecord> edges) {
        List<TransferRecord> copy = new ArrayList<>(edges);
        copy.sort(TransferRecord::compareByTimeThenId);
        return copy;
    }

    /** All addresses that have at least one edge, sorted lexicographically. */
    public Set<String> addresses() {
        Set<String> all = new TreeSet<>(outAdj.keySet());
        all.addAll(inAdj.keySet());
        return all;
    }

    public Optional<TransferRecord> edge(String txId) {
        return Optional.ofNullable(byTxId.get(txId));
    }

    public int edgeCount() {
        return byTxId.size();
    }

    public int addressCount() {
        return addresses().size();
    }
}
