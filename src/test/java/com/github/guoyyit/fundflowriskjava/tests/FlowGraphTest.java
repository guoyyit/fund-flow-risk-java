package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowGraphTest {

    private static TransferRecord tx(String id, String from, String to, long amount, long ts) {
        return new TransferRecord(id, from, to, amount, ts);
    }

    @Test
    void addAndQueryEdges() {
        FlowGraph g = new FlowGraph();
        assertTrue(g.add(tx("t2", "a", "b", 10, 200)));
        assertTrue(g.add(tx("t1", "a", "c", 10, 100)));
        assertTrue(g.add(tx("t3", "c", "a", 10, 300)));

        assertEquals(3, g.edgeCount());
        // sorted by (timestamp, txId)
        assertEquals(List.of("t1", "t2"), g.outEdges("a").stream().map(TransferRecord::txId).toList());
        assertEquals(List.of("t3"), g.inEdges("a").stream().map(TransferRecord::txId).toList());
        assertEquals(List.of("a", "b", "c"), List.copyOf(g.addresses()));
    }

    @Test
    void duplicateTxIdIgnored() {
        FlowGraph g = new FlowGraph();
        assertTrue(g.add(tx("t1", "a", "b", 10, 100)));
        assertFalse(g.add(tx("t1", "a", "b", 10, 100)));
        assertFalse(g.add(tx("t1", "x", "y", 99, 999)));
        assertEquals(1, g.edgeCount());
        assertEquals(List.of("t1"), g.outEdges("a").stream().map(TransferRecord::txId).toList());
    }

    @Test
    void removeRestoresState() {
        FlowGraph g = new FlowGraph();
        g.add(tx("t1", "a", "b", 10, 100));
        g.add(tx("t2", "b", "c", 10, 200));
        assertTrue(g.remove("t1"));
        assertFalse(g.remove("t1"));
        assertEquals(1, g.edgeCount());
        assertTrue(g.outEdges("a").isEmpty());
        assertTrue(g.inEdges("b").isEmpty());
        assertEquals(List.of("b", "c"), List.copyOf(g.addresses()));
    }

    @Test
    void invalidRecordsRejected() {
        assertThrows(IllegalArgumentException.class, () -> tx("", "a", "b", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> tx("t", "a", "b", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> tx("t", "a", "b", 1, -1));
    }
}
