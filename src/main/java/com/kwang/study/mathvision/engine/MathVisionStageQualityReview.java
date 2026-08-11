package com.kwang.study.mathvision.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.enums.StageEnum;
import org.springframework.util.StringUtils;

/** Shared contract for the optional quality-review step in stages 3, 4 and 5. */
public final class MathVisionStageQualityReview {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();
    public static final String FIELD = "qualityReview";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_REQUESTED = "requested";
    public static final String STATUS_COMPLETED = "completed";

    private MathVisionStageQualityReview() {
    }

    public static boolean supports(StageEnum stage) {
        return StageEnum.VISUAL_STORYBOARD.equals(stage)
                || StageEnum.CODE_GENERATION.equals(stage)
                || StageEnum.RENDER_RESULT.equals(stage);
    }

    public static String nodeName(StageEnum stage) {
        if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            return "StoryboardValidationNode";
        }
        if (StageEnum.CODE_GENERATION.equals(stage)) {
            return "CodeEvaluationNode";
        }
        if (StageEnum.RENDER_RESULT.equals(stage)) {
            return "SceneEvaluationNode";
        }
        return null;
    }

    public static void writeState(ObjectNode result, StageEnum stage, String status) {
        if (result == null || !supports(stage)) {
            return;
        }
        ObjectNode review = result.putObject(FIELD);
        review.put("supported", true);
        review.put("status", normalizeStatus(status));
        review.put("node", nodeName(stage));
    }

    public static String resolveStatus(ObjectMapper mapper, StageEnum stage, String resultJson) {
        if (!supports(stage)) {
            return null;
        }
        JsonNode root = parse(mapper, resultJson);
        String explicit = root.path(FIELD).path("status").asText("");
        if (StringUtils.hasText(explicit)) {
            return normalizeStatus(explicit);
        }
        return legacyReviewCompleted(stage, root) ? STATUS_COMPLETED : STATUS_PENDING;
    }

    public static boolean isRequested(ObjectMapper mapper, StageEnum stage, String resultJson) {
        return STATUS_REQUESTED.equals(resolveStatus(mapper, stage, resultJson));
    }

    public static boolean isRequested(StageEnum stage, String resultJson) {
        return isRequested(DEFAULT_MAPPER, stage, resultJson);
    }

    public static String updateStatus(ObjectMapper mapper,
                                      StageEnum stage,
                                      String resultJson,
                                      String status) {
        JsonNode parsed = parse(mapper, resultJson);
        ObjectNode root = parsed.isObject()
                ? (ObjectNode) parsed.deepCopy()
                : mapper.createObjectNode();
        writeState(root, stage, status);
        if (STATUS_PENDING.equals(normalizeStatus(status))) {
            clearCompletedReviewFields(root, stage);
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update stage quality-review state: " + e.getMessage(), e);
        }
    }

    private static JsonNode parse(ObjectMapper mapper, String resultJson) {
        if (mapper == null || !StringUtils.hasText(resultJson)) {
            return mapper != null ? mapper.createObjectNode() : new ObjectMapper().createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(resultJson);
            return parsed != null ? parsed : mapper.createObjectNode();
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private static boolean legacyReviewCompleted(StageEnum stage, JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            return root.path("validationCompleted").asBoolean(false) || root.has("validationReport");
        }
        if (StageEnum.CODE_GENERATION.equals(stage)) {
            return root.has("codeEvaluationAttempts")
                    || root.has("codeEvaluationApproved")
                    || root.has("codeEvaluationGateReason");
        }
        if (StageEnum.RENDER_RESULT.equals(stage)) {
            return root.has("sceneEvaluation") || root.has("sceneEvaluationApproved");
        }
        return false;
    }

    private static void clearCompletedReviewFields(ObjectNode root, StageEnum stage) {
        if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            root.remove("validationReport");
            root.put("validationCompleted", false);
            return;
        }
        if (StageEnum.CODE_GENERATION.equals(stage)) {
            root.remove("codeEvaluationAttempts");
            root.remove("codeFixTrace");
            root.remove("codeEvaluationApproved");
            root.remove("codeEvaluationGateReason");
            return;
        }
        if (StageEnum.RENDER_RESULT.equals(stage)) {
            root.remove("sceneEvaluation");
            root.remove("sceneEvaluationApproved");
            root.remove("sceneEvaluationWarning");
        }
    }

    private static String normalizeStatus(String status) {
        if (STATUS_REQUESTED.equalsIgnoreCase(status)) {
            return STATUS_REQUESTED;
        }
        if (STATUS_COMPLETED.equalsIgnoreCase(status)) {
            return STATUS_COMPLETED;
        }
        return STATUS_PENDING;
    }
}
