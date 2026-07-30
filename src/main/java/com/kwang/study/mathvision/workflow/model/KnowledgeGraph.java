package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class KnowledgeGraph {

    @JsonProperty("start_node_id")
    private String startNodeId;

    @JsonProperty("nodes")
    private Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();

    @JsonProperty("next_edges")
    private Map<String, List<String>> nextEdges = new LinkedHashMap<>();

    @JsonProperty("teaching_order")
    private List<String> teachingOrder = new ArrayList<>();

    public KnowledgeGraph() {
    }

    public KnowledgeGraph(String startNodeId,
                          Map<String, KnowledgeNode> nodes,
                          Map<String, List<String>> nextEdges,
                          List<String> teachingOrder) {
        this.startNodeId = startNodeId;
        this.nodes = nodes != null ? new LinkedHashMap<>(nodes) : new LinkedHashMap<>();
        this.nextEdges = nextEdges != null ? new LinkedHashMap<>(nextEdges) : new LinkedHashMap<>();
        this.teachingOrder = teachingOrder != null ? new ArrayList<>(teachingOrder) : new ArrayList<>();
    }

    public KnowledgeNode getStartNode() {
        return nodes.get(startNodeId);
    }

    public KnowledgeNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public int countNodes() {
        return nodes != null ? nodes.size() : 0;
    }

    public int countEdges() {
        int count = 0;
        if (nextEdges != null) {
            for (List<String> edges : nextEdges.values()) {
                count += edges != null ? edges.size() : 0;
            }
        }
        return count;
    }

    public int getMaxDepth() {
        int maxDepth = 0;
        if (nodes != null) {
            for (KnowledgeNode node : nodes.values()) {
                if (node != null) {
                    maxDepth = Math.max(maxDepth, node.getMinDepth());
                }
            }
        }
        return maxDepth;
    }

    public List<KnowledgeNode> teachingOrderNodes() {
        if (nodes == null || nodes.isEmpty()) {
            return new ArrayList<>();
        }
        if (teachingOrder != null && !teachingOrder.isEmpty()) {
            List<KnowledgeNode> ordered = new ArrayList<>();
            for (String nodeId : teachingOrder) {
                KnowledgeNode node = nodes.get(nodeId);
                if (node != null) {
                    ordered.add(node);
                }
            }
            for (KnowledgeNode node : nodes.values()) {
                if (node != null && !containsNode(ordered, node.getId())) {
                    ordered.add(node);
                }
            }
            return ordered;
        }
        return topologicalOrder();
    }

    public List<KnowledgeNode> getPrerequisites(String nodeId) {
        List<KnowledgeNode> prerequisites = new ArrayList<>();
        if (nodeId == null || nodes == null || nextEdges == null) {
            return prerequisites;
        }
        for (Map.Entry<String, List<String>> entry : nextEdges.entrySet()) {
            List<String> targets = entry.getValue();
            if (targets == null || !targets.contains(nodeId)) {
                continue;
            }
            KnowledgeNode node = nodes.get(entry.getKey());
            if (node != null) {
                prerequisites.add(node);
            }
        }
        prerequisites.sort(Comparator.comparingInt(KnowledgeNode::getMinDepth)
                .thenComparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));
        return prerequisites;
    }

    public List<KnowledgeNode> getDependents(String nodeId) {
        List<KnowledgeNode> dependents = new ArrayList<>();
        if (nodeId == null || nodes == null || nextEdges == null) {
            return dependents;
        }
        for (String targetId : nextEdges.getOrDefault(nodeId, List.of())) {
            KnowledgeNode node = nodes.get(targetId);
            if (node != null) {
                dependents.add(node);
            }
        }
        dependents.sort(Comparator.comparingInt(KnowledgeNode::getMinDepth)
                .thenComparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));
        return dependents;
    }

    public KnowledgeNode findPrimaryTerminalNode() {
        List<KnowledgeNode> ordered = teachingOrderNodes();
        if (ordered.isEmpty()) {
            return null;
        }
        for (int i = ordered.size() - 1; i >= 0; i--) {
            KnowledgeNode node = ordered.get(i);
            if (node != null && KnowledgeNode.NODE_TYPE_CONCLUSION.equalsIgnoreCase(node.getNodeType())) {
                return node;
            }
        }
        return ordered.get(ordered.size() - 1);
    }

    public List<KnowledgeNode> topologicalOrder() {
        List<KnowledgeNode> ordered = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) {
            return ordered;
        }

        Map<String, Integer> remainingPrerequisites = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            remainingPrerequisites.put(nodeId, 0);
        }

        if (nextEdges != null) {
            for (Map.Entry<String, List<String>> entry : nextEdges.entrySet()) {
                String currentNodeId = entry.getKey();
                if (!nodes.containsKey(currentNodeId) || entry.getValue() == null) {
                    continue;
                }
                for (String nextNodeId : entry.getValue()) {
                    if (!nodes.containsKey(nextNodeId) || nextNodeId.equals(currentNodeId)) {
                        continue;
                    }
                    remainingPrerequisites.computeIfPresent(nextNodeId, (ignored, count) -> count + 1);
                }
            }
        }

        Comparator<String> readyComparator = buildDepthAscendingComparator();
        PriorityQueue<String> ready = new PriorityQueue<>(readyComparator);
        for (Map.Entry<String, Integer> entry : remainingPrerequisites.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        while (!ready.isEmpty()) {
            String nodeId = ready.poll();
            KnowledgeNode node = nodes.get(nodeId);
            if (node != null) {
                ordered.add(node);
            }
            for (String nextNodeId : nextEdges.getOrDefault(nodeId, List.of())) {
                if (!remainingPrerequisites.containsKey(nextNodeId)) {
                    continue;
                }
                int updated = remainingPrerequisites.computeIfPresent(nextNodeId, (ignored, count) -> count - 1);
                if (updated == 0) {
                    ready.add(nextNodeId);
                }
            }
        }

        for (KnowledgeNode node : nodes.values()) {
            if (node != null && !containsNode(ordered, node.getId())) {
                ordered.add(node);
            }
        }
        return ordered;
    }

    public boolean isProblemMode() {
        if (nodes == null) {
            return false;
        }
        for (KnowledgeNode node : nodes.values()) {
            if (node != null && KnowledgeNode.NODE_TYPE_PROBLEM.equalsIgnoreCase(node.getNodeType())) {
                return true;
            }
        }
        return false;
    }

    public String printGraph() {
        StringBuilder sb = new StringBuilder();
        sb.append("KnowledgeGraph\n");
        KnowledgeNode start = getStartNode();
        if (start != null) {
            sb.append("Start: ").append(start.getStep())
                    .append(" [depth=").append(start.getMinDepth()).append("]\n");
        }
        sb.append("Nodes: ").append(countNodes())
                .append(", Edges: ").append(countEdges())
                .append(", Max depth: ").append(getMaxDepth())
                .append("\n\n");
        for (KnowledgeNode node : teachingOrderNodes()) {
            sb.append("- ").append(node.getStep())
                    .append(" [id=").append(node.getId())
                    .append(", depth=").append(node.getMinDepth())
                    .append(", type=").append(node.getNodeType())
                    .append("]\n");
            if (node.getReason() != null && !node.getReason().isBlank()) {
                sb.append("  reason: ").append(node.getReason()).append("\n");
            }
        }
        return sb.toString();
    }

    private Comparator<String> buildDepthAscendingComparator() {
        return Comparator.comparingInt((String id) -> {
            KnowledgeNode node = nodes.get(id);
            return node != null ? node.getMinDepth() : Integer.MAX_VALUE;
        }).thenComparing(id -> {
            KnowledgeNode node = nodes.get(id);
            return node != null ? node.getStep() : id;
        }, String.CASE_INSENSITIVE_ORDER);
    }

    private boolean containsNode(List<KnowledgeNode> ordered, String nodeId) {
        for (KnowledgeNode node : ordered) {
            if (node != null && node.getId() != null && node.getId().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }
}
