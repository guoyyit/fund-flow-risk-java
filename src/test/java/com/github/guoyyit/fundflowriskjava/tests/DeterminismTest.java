package com.github.guoyyit.fundflowriskjava.tests;

import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DeterminismTest {

    private static TransferRecord tx(String id, String from, String to, long amount, long ts) {
        return new TransferRecord(id, from, to, amount, ts);
    }

    /** A dataset exercising all four rules. */
    private static List<TransferRecord> dataset() {
        return List.of(
                // cycle a -> b -> c -> a
                tx("c1", "a", "b", 100, 1_000),
                tx("c2", "b", "c", 100, 2_000),
                tx("c3", "c", "a", 100, 3_000),
                // aggregation into hub
                tx("g1", "s1", "hub", 50, 10_000),
                tx("g2", "s2", "hub", 50, 20_000),
                tx("g3", "s3", "hub", 50, 30_000),
                // layering root -> x,y,z ; x -> p,q,r
                tx("l1", "root", "x", 100, 1_000),
                tx("l2", "root", "y", 100, 2_000),
                tx("l3", "root", "z", 100, 3_000),
                tx("l4", "x", "p", 100, 4_000),
                tx("l5", "x", "q", 100, 5_000),
                tx("l6", "x", "r", 100, 6_000),
                // address reuse
                tx("u1", "k1", "reuse", 50, 1_000),
                tx("u2", "reuse", "k2", 50, 2_000),
                tx("u3", "reuse", "k3", 50, 3_000));
    }

    @Test
    void shuffledInputOrderProducesIdenticalHits() {
        List<TransferRecord> base = dataset();
        List<RiskHit> reference = null;
        // fixed seeds make the shuffles themselves reproducible
        for (long seed = 0; seed < 10; seed++) {
            List<TransferRecord> shuffled = new ArrayList<>(base);
            Collections.shuffle(shuffled, new Random(seed));
            FundFlowService svc = new FundFlowService();
            // also split into randomly sized batches to vary apply granularity
            int i = 0;
            Random rng = new Random(seed * 31 + 7);
            while (i < shuffled.size()) {
                int n = 1 + rng.nextInt(4);
                svc.apply(shuffled.subList(i, Math.min(i + n, shuffled.size())));
                i += n;
            }
            List<RiskHit> hits = svc.detect(RuleConfig.DEFAULT);
            if (reference == null) {
                reference = hits;
                assertFalse(reference.isEmpty());
            } else {
                assertEquals(reference, hits, "hits diverged for shuffle seed " + seed);
            }
        }
    }

    @Test
    void duplicatesDoNotChangeStateOrHits() {
        List<TransferRecord> base = dataset();
        FundFlowService clean = new FundFlowService();
        clean.apply(base);

        FundFlowService duped = new FundFlowService();
        List<TransferRecord> withDups = new ArrayList<>(base);
        withDups.addAll(base); // exact duplicates
        Collections.shuffle(withDups, new Random(42));
        FundFlowService.ApplyResult result = duped.apply(withDups);

        assertEquals(base.size(), result.accepted());
        assertEquals(base.size(), result.duplicates());
        assertEquals(clean.stats(), duped.stats());
        assertEquals(clean.detect(RuleConfig.DEFAULT), duped.detect(RuleConfig.DEFAULT));
    }

    @Test
    void lateArrivingRecordProducesSameFinalState() {
        List<TransferRecord> base = dataset();
        TransferRecord late = base.get(0);

        FundFlowService inOrder = new FundFlowService();
        inOrder.apply(base);

        FundFlowService lateSvc = new FundFlowService();
        lateSvc.apply(base.subList(1, base.size()));
        lateSvc.apply(List.of(late));

        assertEquals(inOrder.stats(), lateSvc.stats());
        assertEquals(inOrder.detect(RuleConfig.DEFAULT), lateSvc.detect(RuleConfig.DEFAULT));
    }
}
