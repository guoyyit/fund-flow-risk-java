package com.github.guoyyit.fundflowriskjava.graph;

import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 有向资金流图。边以记录 id 索引，保证重复写入幂等、删除可回滚。
 * 邻接表按 (timestamp, id) 排序维护，使遍历顺序与写入顺序无关，
 * 乱序/迟到写入得到完全相同的图状态。
 * 非线程安全，并发控制由上层服务负责。
 */
public class FlowGraph {

    /** 边的规范顺序：先按时间戳，再按记录 id。 */
    private static final Comparator<TransferRecord> EDGE_ORDER =
            Comparator.comparingLong(TransferRecord::timestamp).thenComparing(TransferRecord::id);

    private final Map<String, TransferRecord> byId = new TreeMap<>();
    private final Map<String, List<TransferRecord>> out = new TreeMap<>();
    private final Map<String, List<TransferRecord>> in = new TreeMap<>();

    /** 幂等写入：同 id 重复写入不产生重复边，返回是否真正新增。 */
    public boolean add(TransferRecord record) {
        if (byId.containsKey(record.id())) {
            return false;
        }
        byId.put(record.id(), record);
        insertSorted(out.computeIfAbsent(record.from(), k -> new ArrayList<>()), record);
        insertSorted(in.computeIfAbsent(record.to(), k -> new ArrayList<>()), record);
        return true;
    }

    /** 按 id 删除，记录不存在时为 no-op。 */
    public boolean remove(String recordId) {
        TransferRecord record = byId.remove(recordId);
        if (record == null) {
            return false;
        }
        removeEdge(out, record.from(), record);
        removeEdge(in, record.to(), record);
        return true;
    }

    public boolean contains(String recordId) {
        return byId.containsKey(recordId);
    }

    public List<TransferRecord> outEdges(String address) {
        return copyOf(out.get(address));
    }

    public List<TransferRecord> inEdges(String address) {
        return copyOf(in.get(address));
    }

    public Set<String> addresses() {
        Set<String> all = new TreeSet<>(out.keySet());
        all.addAll(in.keySet());
        return all;
    }

    /** 全部已提交记录，按记录 id 排序，与写入顺序无关。 */
    public List<TransferRecord> allRecords() {
        return new ArrayList<>(byId.values());
    }

    public int recordCount() {
        return byId.size();
    }

    private static void insertSorted(List<TransferRecord> list, TransferRecord record) {
        int pos = Collections.binarySearch(list, record, EDGE_ORDER);
        if (pos < 0) {
            pos = -pos - 1;
        }
        list.add(pos, record);
    }

    private static void removeEdge(Map<String, List<TransferRecord>> index, String address, TransferRecord record) {
        List<TransferRecord> edges = index.get(address);
        if (edges == null) {
            return;
        }
        edges.removeIf(e -> e.id().equals(record.id()));
        if (edges.isEmpty()) {
            index.remove(address);
        }
    }

    private static List<TransferRecord> copyOf(List<TransferRecord> edges) {
        return edges == null ? List.of() : List.copyOf(edges);
    }
}
