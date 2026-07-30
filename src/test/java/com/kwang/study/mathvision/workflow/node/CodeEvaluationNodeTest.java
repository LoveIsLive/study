package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.CodeEvaluationResult;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeEvaluationNodeTest {

    @Test
    void reportsStoryboardSceneCountAndUpstreamCodeMetrics() {
        ObjectMapper mapper = new ObjectMapper();
        MathVisionAiChatService aiChatService = mock(MathVisionAiChatService.class);
        ObjectNode review = mapper.createObjectNode();
        review.put("approved_for_render", true);
        review.put("summary", "approved");
        review.putArray("blocking_issues");
        review.putArray("revision_directives");
        review.putArray("rule_checks");
        when(aiChatService.requestJson(any(), anyList(), anyString())).thenReturn(review);

        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        storyboard.setScenes(List.of(scene("scene_1"), scene("scene_2")));
        Narrative narrative = new Narrative("target", storyboard);

        CodeResult codeResult = new CodeResult();
        codeResult.setOutputTarget("manim");
        codeResult.setSceneName("MainScene");
        codeResult.setGeneratedCode(String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        square = Square()",
                "        circle = Circle()",
                "        self.play(FadeIn(square))",
                "        self.play(Transform(square, circle))"));

        MathVisionTask task = new MathVisionTask();
        task.setId(7L);
        task.setOutputTarget("manim");
        CodeEvaluationNode node = new CodeEvaluationNode(aiChatService, mapper);
        CodeEvaluationResult result = node.run(
                task,
                new ProblemBundle(),
                narrative,
                codeResult,
                0,
                false,
                MathVisionStageExecutionContext.builder().task(task).build())
                .getEvaluationResult();

        assertTrue(result.isApprovedForRender());
        assertEquals("Rule compliance review passed", result.getGateReason());
        assertEquals(2, result.getFinalStaticAnalysis().getSceneCount());
        assertEquals(1, result.getFinalStaticAnalysis().getFadeInCount());
        assertEquals(1, result.getFinalStaticAnalysis().getTransformCount());
    }

    @Test
    void blocksStaticMovingPointAndStaticAttachedLabelBeforeAiReview() {
        ObjectMapper mapper = new ObjectMapper();
        MathVisionAiChatService aiChatService = mock(MathVisionAiChatService.class);
        Narrative narrative = movingPointNarrative();

        CodeResult codeResult = codeResult(String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.objects = {}",
                "        dot_P = Dot(ORIGIN)",
                "        self.objects[\"P\"] = dot_P",
                "        label_P = Text(\"P\")",
                "        label_P.next_to(dot_P, UR)",
                "        self.objects[\"labelP\"] = label_P",
                "        theta_tracker = ValueTracker(0)",
                "        self.play(theta_tracker.animate.set_value(1))"));

        CodeEvaluationResult result = run(aiChatService, mapper, narrative, codeResult);

        assertFalse(result.isApprovedForRender());
        Set<String> ruleIds = result.getFinalStaticAnalysis().getFindings().stream()
                .map(CodeEvaluationResult.StaticFinding::getRuleId)
                .collect(Collectors.toSet());
        assertTrue(ruleIds.contains("motion_constraint_binding"));
        assertTrue(ruleIds.contains("dynamic_attachment_binding"));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void acceptsUpdaterBackedMovingPointAndAttachedLabel() {
        ObjectMapper mapper = new ObjectMapper();
        MathVisionAiChatService aiChatService = mock(MathVisionAiChatService.class);
        ObjectNode review = approvedReview(mapper);
        when(aiChatService.requestJson(any(), anyList(), anyString())).thenReturn(review);

        CodeResult codeResult = codeResult(String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.objects = {}",
                "        theta_tracker = ValueTracker(0)",
                "        dot_P = always_redraw(lambda: Dot(RIGHT * theta_tracker.get_value()))",
                "        self.objects[\"P\"] = dot_P",
                "        label_P = always_redraw(lambda: Text(\"P\").next_to(dot_P, UR))",
                "        self.objects[\"labelP\"] = label_P",
                "        self.play(theta_tracker.animate.set_value(1))"));

        CodeEvaluationResult result = run(aiChatService, mapper, movingPointNarrative(), codeResult);

        assertTrue(result.isApprovedForRender());
        assertFalse(result.getFinalStaticAnalysis().hasBlockingFindings());
    }

    @Test
    void normalizesFailedMandatoryReviewStatusAndBlocksRender() {
        ObjectMapper mapper = new ObjectMapper();
        MathVisionAiChatService aiChatService = mock(MathVisionAiChatService.class);
        ObjectNode review = approvedReview(mapper);
        ObjectNode check = review.putArray("rule_checks").addObject();
        check.put("rule_id", "angle_and_attachment");
        check.put("requirement", "Moving labels must use an updater");
        check.put("status", "failed");
        check.put("severity", "MANDATORY");
        check.put("evidence", "labelP is static");
        when(aiChatService.requestJson(any(), anyList(), anyString())).thenReturn(review);

        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        storyboard.setScenes(List.of(scene("scene_1")));
        CodeEvaluationResult result = run(
                aiChatService,
                mapper,
                new Narrative("target", storyboard),
                codeResult(String.join("\n",
                        "from manim import *",
                        "class MainScene(Scene):",
                        "    def construct(self):",
                        "        self.add(Dot())")));

        assertFalse(result.isApprovedForRender());
        assertEquals("fail", result.getFinalReview().getRuleChecks().get(0).getStatus());
        assertEquals("mandatory", result.getFinalReview().getRuleChecks().get(0).getSeverity());
    }

    private CodeEvaluationResult run(MathVisionAiChatService aiChatService,
                                     ObjectMapper mapper,
                                     Narrative narrative,
                                     CodeResult codeResult) {
        MathVisionTask task = new MathVisionTask();
        task.setId(7L);
        task.setOutputTarget("manim");
        return new CodeEvaluationNode(aiChatService, mapper).run(
                task,
                new ProblemBundle(),
                narrative,
                codeResult,
                0,
                false,
                MathVisionStageExecutionContext.builder().task(task).build())
                .getEvaluationResult();
    }

    private CodeResult codeResult(String code) {
        CodeResult result = new CodeResult();
        result.setOutputTarget("manim");
        result.setSceneName("MainScene");
        result.setGeneratedCode(code);
        return result;
    }

    private ObjectNode approvedReview(ObjectMapper mapper) {
        ObjectNode review = mapper.createObjectNode();
        review.put("approved_for_render", true);
        review.put("summary", "approved");
        review.putArray("strengths");
        review.putArray("blocking_issues");
        review.putArray("revision_directives");
        review.putArray("rule_checks");
        return review;
    }

    private Narrative movingPointNarrative() {
        Narrative.StoryboardConstraint motion = new Narrative.StoryboardConstraint();
        motion.setDomain("motion");
        motion.setRelation("moves_on_object");
        motion.setStrength("hard");
        motion.setRefs(Map.of("point", "P", "support", "arc_AB"));

        Narrative.StoryboardObject point = new Narrative.StoryboardObject();
        point.setId("P");
        point.setKind("point");
        point.setConstraints(List.of(motion));

        Narrative.StoryboardConstraint attachment = new Narrative.StoryboardConstraint();
        attachment.setDomain("attachment");
        attachment.setRelation("label_for");
        attachment.setStrength("hard");
        attachment.setRefs(Map.of("label", "labelP", "anchor", "P"));

        Narrative.StoryboardObject label = new Narrative.StoryboardObject();
        label.setId("labelP");
        label.setKind("text");
        label.setConstraints(List.of(attachment));

        Narrative.StoryboardAction move = new Narrative.StoryboardAction();
        move.setOrder(1);
        move.setType("move");
        move.setTargets(List.of("P"));

        Narrative.StoryboardScene scene = scene("scene_1");
        scene.setPersistentObjects(new ArrayList<>(List.of(point, label)));
        scene.setActions(new ArrayList<>(List.of(move)));

        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        storyboard.setObjectRegistry(new ArrayList<>(List.of(point, label)));
        storyboard.setScenes(new ArrayList<>(List.of(scene)));
        return new Narrative("target", storyboard);
    }

    private Narrative.StoryboardScene scene(String id) {
        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId(id);
        scene.setTitle(id);
        scene.setGoal("teach");
        return scene;
    }
}
