package com.kwang.study.mathvision.workflow.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowToolSchemaParityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizationExplorationAndEnrichmentUseUpstreamContracts() throws Exception {
        JsonNode problemBundle = function(ToolSchemas.PROBLEM_BUNDLE);
        assertEquals("write_problem_bundle", problemBundle.path("name").asText());
        assertEquals(
                Set.of("diagram", "id", "input_mode", "scene_mode", "statement", "title"),
                propertyNames(problemBundle));
        assertEquals(
                Set.of("diagram", "id", "input_mode", "scene_mode", "statement", "title"),
                stringValues(problemBundle.path("parameters").path("required")));

        JsonNode inputMode = function(ToolSchemas.INPUT_MODE);
        assertEquals("write_input_mode", inputMode.path("name").asText());
        assertEquals(Set.of("input_mode", "reason"), propertyNames(inputMode));

        assertGraphContract(ToolSchemas.CONCEPT_GRAPH, "write_concept_graph");
        assertGraphContract(ToolSchemas.PROBLEM_GRAPH, "write_problem_step_graph");

        JsonNode enrichment = function(ToolSchemas.MATH_ENRICHMENT);
        assertEquals("write_enrichment", enrichment.path("name").asText());
        assertEquals(
                Set.of("definitions", "equations", "examples", "interpretation", "reason", "step"),
                propertyNames(enrichment));
    }

    @Test
    void visualDesignAndStoryboardValidationUseUpstreamContracts() throws Exception {
        JsonNode sceneDesign = function(ToolSchemas.sceneDesign("manim", "2d"));
        assertEquals("write_scene_design", sceneDesign.path("name").asText());
        assertEquals(
                Set.of("coordinate_bounds_update", "new_objects", "scene"),
                propertyNames(sceneDesign));

        JsonNode storyboard = function(ToolSchemas.storyboard("manim", "2d"));
        assertEquals("write_storyboard", storyboard.path("name").asText());
        assertEquals(
                Set.of("continuity_plan", "coordinate_bounds", "global_visual_rules", "object_registry", "scenes"),
                propertyNames(storyboard));

        JsonNode placement = function(ToolSchemas.placementPatches("2d"));
        assertEquals("write_placement_patches", placement.path("name").asText());
        assertEquals(Set.of("placement_patches"), propertyNames(placement));
    }

    @Test
    void codeGenerationAndCodeFixUseTheSameUpstreamContracts() throws Exception {
        JsonNode manim = function(ToolSchemas.MANIM_CODE);
        assertEquals("write_manim_code", manim.path("name").asText());
        assertEquals(Set.of("description", "manimCode", "scene_name"), propertyNames(manim));
        assertEquals(Set.of("manimCode"), stringValues(manim.path("parameters").path("required")));
        assertFalse(manim.path("parameters").has("additionalProperties"));

        JsonNode geogebra = function(ToolSchemas.GEOGEBRA_CODE);
        assertEquals("write_geogebra_code", geogebra.path("name").asText());
        assertEquals(
                Set.of("artifact_format", "description", "figure_name", "geogebraCode"),
                propertyNames(geogebra));
        assertEquals(Set.of("geogebraCode"), stringValues(geogebra.path("parameters").path("required")));
    }

    @Test
    void sceneCodeAndCodeReviewUseUpstreamFieldNames() throws Exception {
        JsonNode scene = function(ToolSchemas.SCENE_CODE);
        assertEquals("write_scene_code", scene.path("name").asText());
        assertEquals(Set.of("sceneCode"), propertyNames(scene));
        assertEquals(Set.of("sceneCode"), stringValues(scene.path("parameters").path("required")));

        JsonNode review = function(ToolSchemas.CODE_REVIEW);
        assertEquals("write_code_review", review.path("name").asText());
        assertEquals(
                Set.of(
                        "approved_for_render",
                        "blocking_issues",
                        "revision_directives",
                        "rule_checks",
                        "strengths",
                        "summary"),
                propertyNames(review));
        assertEquals(
                Set.of(
                        "approved_for_render",
                        "blocking_issues",
                        "revision_directives",
                        "rule_checks",
                        "summary"),
                stringValues(review.path("parameters").path("required")));

        JsonNode checkProperties = review.path("parameters")
                .path("properties")
                .path("rule_checks")
                .path("items")
                .path("properties");
        assertTrue(checkProperties.has("rule_id"));
        assertFalse(checkProperties.has("ruleId"));
        assertEquals(
                Set.of("fail", "not_applicable", "pass", "warn"),
                stringValues(checkProperties.path("status").path("enum")));
        assertEquals(
                Set.of("advisory", "mandatory", "recommended"),
                stringValues(checkProperties.path("severity").path("enum")));
    }

    private JsonNode function(String schema) throws Exception {
        return objectMapper.readTree(schema).path(0).path("function");
    }

    private void assertGraphContract(String schema, String functionName) throws Exception {
        JsonNode graph = function(schema);
        assertEquals(functionName, graph.path("name").asText());
        assertEquals(Set.of("next_edges", "nodes", "start_id", "teaching_order"), propertyNames(graph));
        assertEquals(
                Set.of("next_edges", "nodes", "start_id", "teaching_order"),
                stringValues(graph.path("parameters").path("required")));
    }

    private Set<String> propertyNames(JsonNode function) {
        Set<String> names = new TreeSet<>();
        function.path("parameters").path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Set<String> stringValues(JsonNode array) {
        Set<String> values = new TreeSet<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
