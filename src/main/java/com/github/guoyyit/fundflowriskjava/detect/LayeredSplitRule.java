package com.github.guoyyit.fundflowriskjava.detect;

import com.github.guoyyit.fundflowriskjava.graph.FlowGraph;
import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.RuleType;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 分层拆分：根地址在窗口内向不少于 fanOut 个不同地址拆分（第一层），
 * 且第一层中至少一个地址在其收款后的窗口内继续向不少于 fanOut 个
 * 不同地址拆分（第二层）。拆分层数至少为 2，受 maxHops 上限约束。
 * 每个根地址只报告一次，窗口锚点按边规范顺序选取，输出确定。
 */
public class LayeredSplitRule implements RiskRule {

    private static final int REQUIRED_LAYERS = 2;

    @Override
    public RuleType type() {
        return RuleType.LAYERED_SPLIT;
    }

    @Override
    public List<RiskHit> detect(FlowGraph graph, RuleConfig config) {
        List<RiskHit> hits = new ArrayList<>();
        if (config.maxHops() < REQUIRED_LAYERS || config.fanOut() < 2) {
            return hits;
        }
        for (String root : graph.addresses()) {
            // 窗口锚点：按规范顺序枚举根地址的每条合格出边时间戳
            Set<Long> anchors = new LinkedHashSet<>();
            for (TransferRecord e : graph.outEdges(root)) {
                if (e.amount().compareTo(config.minAmount()) >= 0) {
                    anchors.add(e.timestamp());
                }
            }
            for (long anchor : anchors) {
                List<Map<String, Long>> layers = findSplitChain(
                        graph, config, root, anchor, REQUIRED_LAYERS, new HashSet<>(Set.of(root)));
                if (layers != null) {
                    hits.add(buildHit(root, anchor, layers));
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * 递归查找拆分层链：addr 在 [fromTs, fromTs+window] 内向不少于 fanOut 个
     * 不同地址转出；layersLeft > 1 时要求至少一个下层地址继续拆分。
     * 返回每层的 {目标地址: 入金最早时间}，未命中返回 null。
     */
    private List<Map<String, Long>> findSplitChain(FlowGraph graph, RuleConfig config,
                                                   String addr, long fromTs, int layersLeft,
                                                   Set<String> visited) {
        Map<String, Long> targets = splitTargets(graph, config, addr, fromTs);
        if (targets.size() < config.fanOut()) {
            return null;
        }
        if (layersLeft == 1) {
            return new ArrayList<>(List.of(targets));
        }
        for (Map.Entry<String, Long> target : targets.entrySet()) {
            if (visited.contains(target.getKey())) {
                continue;
            }
            Set<String> nextVisited = new HashSet<>(visited);
            nextVisited.add(target.getKey());
            List<Map<String, Long>> sub = findSplitChain(
                    graph, config, target.getKey(), target.getValue(), layersLeft - 1, nextVisited);
            if (sub != null) {
                List<Map<String, Long>> chain = new ArrayList<>();
                chain.add(targets);
                chain.addAll(sub);
                return chain;
            }
        }
        return null;
    }

    /** addr 在窗口 [fromTs, fromTs+window] 内的合格出边，按目标地址聚合最早时间。 */
    private Map<String, Long> splitTargets(FlowGraph graph, RuleConfig config,
                                           String addr, long fromTs) {
        long toTs = fromTs + config.timeWindowMillis();
        Map<String, Long> targets = new TreeMap<>();
        for (TransferRecord e : graph.outEdges(addr)) {
            if (e.amount().compareTo(config.minAmount()) < 0) {
                continue;
            }
            if (e.timestamp() < fromTs || e.timestamp() > toTs) {
                continue;
            }
            targets.merge(e.to(), e.timestamp(), Math::min);
        }
        return targets;
    }

    private RiskHit buildHit(String root, long anchor, List<Map<String, Long>> layers) {
        Set<String> involved = new LinkedHashSet<>();
        involved.add(root);
        StringBuilder evidence = new StringBuilder("root=").append(root)
                .append(", windowStart=").append(anchor);
        List<String> path = new ArrayList<>();
        path.add(root);
        for (int i = 0; i < layers.size(); i++) {
            List<String> targets = List.copyOf(layers.get(i).keySet());
            involved.addAll(targets);
            evidence.append(", layer").append(i + 1).append("=").append(targets);
        }
        // 路径取第一层中触发下一层拆分的首个目标（按字典序确定）
        layers.get(0).keySet().stream().findFirst().ifPresent(path::add);
        String key = RuleType.LAYERED_SPLIT + "|" + root;
        return new RiskHit(RuleType.LAYERED_SPLIT, List.copyOf(involved),
                List.copyOf(path), evidence.toString(), key);
    }
}
