package com.kwang.study.mathvision.workflow.util;

import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.KnowledgeNode;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;

import java.util.List;

public final class TargetDescriptionBuilder {

    private static final int MAX_REASON_LENGTH = 200;

    private TargetDescriptionBuilder() {
    }

    public static String build(ProblemBundle bundle, KnowledgeGraph graph, KnowledgeNode currentNode) {
        StringBuilder sb = new StringBuilder();
        if (ProblemBundleContextBuilder.isProblemMode(bundle) || (bundle == null && graph != null && graph.isProblemMode())) {
            sb.append("This is a problem-solving workflow. The target is the math problem described by the ProblemBundle.");
        } else {
            sb.append("The target is the math concept described by the ProblemBundle.");
        }

        if (currentNode != null) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("Current step: ").append(currentNode.getStep());
            if (currentNode.getReason() != null && !currentNode.getReason().isBlank()) {
                sb.append("\nWhy this step matters: ").append(shorten(currentNode.getReason()));
            }
        }
        return sb.toString().trim();
    }

    public static String buildSolutionChain(KnowledgeGraph graph, KnowledgeNode currentStep) {
        if (graph == null || !graph.isProblemMode()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Solution step chain:\n");

        List<KnowledgeNode> ordered = graph.teachingOrderNodes();
        int stepNumber = 1;
        int currentStepNumber = -1;
        for (KnowledgeNode node : ordered) {
            String marker = "";
            if (currentStep != null && node.getId().equals(currentStep.getId())) {
                marker = " <-- current";
                currentStepNumber = stepNumber;
            }
            sb.append(stepNumber).append(". ").append(node.getStep());
            String nodeType = node.getNodeType();
            if (nodeType != null && !nodeType.isBlank()
                    && !KnowledgeNode.NODE_TYPE_CONCEPT.equals(nodeType)) {
                sb.append(" [").append(nodeType).append("]");
            }
            sb.append(marker).append("\n");
            if (node.getReason() != null && !node.getReason().isBlank()) {
                sb.append("   -> ").append(shorten(node.getReason())).append("\n");
            }
            stepNumber++;
        }

        if (currentStepNumber > 0) {
            sb.append("\nCurrently processing step ").append(currentStepNumber)
                    .append(" of ").append(ordered.size()).append(".");
        }
        return sb.toString().trim();
    }

    private static String shorten(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        return normalized.length() > MAX_REASON_LENGTH
                ? normalized.substring(0, MAX_REASON_LENGTH) + "..."
                : normalized;
    }
}
