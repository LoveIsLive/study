package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persisted artifacts and results use snake_case keys only. Rows written before that convention are
 * converted once by scripts/migrate_mathvision_json_keys.py; the code does not read camelCase keys.
 */
class PersistedJsonNamingTest {

    private static final Pattern HAS_UPPERCASE = Pattern.compile(".*[A-Z].*");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void codeResultRoundTripsInSnakeCase() throws Exception {
        CodeResult code = new CodeResult();
        code.setGeneratedCode("from manim import *");
        code.setHeaderCode("header");
        code.setSceneName("MainScene");
        code.setArtifactName("MainScene");
        code.getSceneEntries().add(new SceneCodeEntry(0, "scene_1", "scene_1", "pass", true));

        JsonNode json = mapper.valueToTree(code);
        assertNoUppercaseKeys(json, "$");
        assertEquals("from manim import *", json.path("generated_code").asText());
        assertEquals("scene_1", json.path("scene_entries").path(0).path("scene_id").asText());

        CodeResult back = mapper.treeToValue(json, CodeResult.class);
        assertEquals("from manim import *", back.getGeneratedCode());
        assertEquals("MainScene", back.getSceneName());
        assertEquals(1, back.getSceneEntries().size());
    }

    @Test
    void camelCaseKeysAreNoLongerRead() throws Exception {
        CodeResult legacy = mapper.readValue("{\"generatedCode\":\"old\",\"sceneName\":\"S\"}", CodeResult.class);
        assertFalse(legacy.hasCode(), "camelCase rows must be migrated, the model does not alias them");
        assertTrue(legacy.getSceneName() == null);
    }

    @Test
    void renderResultRoundTripsInSnakeCase() throws Exception {
        RenderResult render = new RenderResult();
        render.setSuccess(true);
        render.setArtifactPath("/final.mp4");
        render.setVideoPath("/final.mp4");
        render.setFinalGeneratedCode("code");
        render.setAttempts(2);

        JsonNode json = mapper.valueToTree(render);
        assertNoUppercaseKeys(json, "$");
        assertEquals("/final.mp4", json.path("artifact_path").asText());

        RenderResult back = mapper.treeToValue(json, RenderResult.class);
        assertEquals("/final.mp4", back.getArtifactPath());
        assertEquals("code", back.getFinalGeneratedCode());
        assertEquals(2, back.getAttempts());
    }

    @Test
    void evaluationAndFixResultsAreWrittenInSnakeCase() throws Exception {
        CodeEvaluationResult evaluation = new CodeEvaluationResult();
        evaluation.setApprovedForRender(true);
        evaluation.setGateReason("ok");
        CodeEvaluationResult.ReviewSnapshot review = new CodeEvaluationResult.ReviewSnapshot();
        review.getBlockingIssues().add("issue");
        evaluation.setFinalReview(review);
        JsonNode evaluationJson = mapper.valueToTree(evaluation);
        assertNoUppercaseKeys(evaluationJson, "$");
        CodeEvaluationResult back = mapper.treeToValue(evaluationJson, CodeEvaluationResult.class);
        assertTrue(back.isApprovedForRender());
        assertEquals("issue", back.getFinalReview().getBlockingIssues().get(0));

        SceneEvaluationResult scene = new SceneEvaluationResult();
        scene.setApproved(true);
        scene.setGateReason("passed");
        JsonNode sceneJson = mapper.valueToTree(scene);
        assertNoUppercaseKeys(sceneJson, "$");
        assertEquals("passed", mapper.treeToValue(sceneJson, SceneEvaluationResult.class).getGateReason());

        CodeFixResult fix = new CodeFixResult();
        fix.setFixedGeneratedCode("fixed");
        JsonNode fixJson = mapper.valueToTree(fix);
        assertNoUppercaseKeys(fixJson, "$");
        assertEquals("fixed", mapper.treeToValue(fixJson, CodeFixResult.class).getFixedGeneratedCode());
    }

    private void assertNoUppercaseKeys(JsonNode node, String path) {
        List<String> offenders = new ArrayList<>();
        collectUppercaseKeys(node, path, offenders);
        assertTrue(offenders.isEmpty(), "non snake_case keys: " + offenders);
    }

    private void collectUppercaseKeys(JsonNode node, String path, List<String> offenders) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (HAS_UPPERCASE.matcher(name).matches()) {
                    offenders.add(path + "." + name);
                }
                collectUppercaseKeys(node.get(name), path + "." + name, offenders);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectUppercaseKeys(node.get(i), path + "[" + i + "]", offenders);
            }
        }
    }
}
