package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Narrative {

    @JsonProperty("target_description")
    private String targetDescription;

    @JsonProperty("storyboard")
    private Storyboard storyboard;

    public Narrative() {
    }

    public Narrative(String targetDescription, Storyboard storyboard) {
        this.targetDescription = targetDescription;
        this.storyboard = storyboard;
    }

    public boolean hasStoryboard() {
        return storyboard != null
                && storyboard.getScenes() != null
                && !storyboard.getScenes().isEmpty();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Storyboard {

        @JsonProperty("coordinate_bounds")
        private StoryboardCoordinateBounds coordinateBounds;

        @JsonProperty("continuity_plan")
        private String continuityPlan;

        @JsonProperty("global_visual_rules")
        private List<String> globalVisualRules = new ArrayList<>();

        @JsonProperty("object_registry")
        private List<StoryboardObject> objectRegistry = new ArrayList<>();

        @JsonProperty("scenes")
        private List<StoryboardScene> scenes = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardCoordinateBounds {

        public static final double DEFAULT_PADDING = 1.0D;

        @JsonProperty("x")
        private StoryboardCoordinateBoundsAxis x;

        @JsonProperty("y")
        private StoryboardCoordinateBoundsAxis y;

        @JsonProperty("z")
        private StoryboardCoordinateBoundsAxis z;

        @JsonProperty("padding")
        private Double padding = DEFAULT_PADDING;

        public boolean hasData() {
            return (x != null && x.hasData())
                    || (y != null && y.hasData())
                    || (z != null && z.hasData());
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoryboardCoordinateBoundsAxis {

        @JsonProperty("min")
        private Double min;

        @JsonProperty("max")
        private Double max;

        public StoryboardCoordinateBoundsAxis() {
        }

        public StoryboardCoordinateBoundsAxis(Double min, Double max) {
            this.min = min;
            this.max = max;
        }

        public boolean hasData() {
            return min != null || max != null;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardScene {

        @JsonProperty("scene_id")
        private String sceneId;

        @JsonProperty("title")
        private String title;

        @JsonProperty("goal")
        private String goal;

        @JsonProperty("narration")
        private String narration;

        @JsonProperty("duration_seconds")
        private int durationSeconds;

        @JsonProperty("camera_anchor")
        private String cameraAnchor;

        @JsonProperty("camera_plan")
        private String cameraPlan;

        @JsonProperty("layout_goal")
        private String layoutGoal;

        @JsonProperty("safe_area_plan")
        private String safeAreaPlan;

        @JsonProperty("screen_overlay_plan")
        private String screenOverlayPlan;

        @JsonProperty("constraints")
        private List<StoryboardConstraint> constraints = new ArrayList<>();

        @JsonProperty("step_refs")
        private List<String> stepRefs = new ArrayList<>();

        @JsonProperty("entering_objects")
        private List<StoryboardObject> enteringObjects = new ArrayList<>();

        @JsonProperty("persistent_objects")
        private List<StoryboardObject> persistentObjects = new ArrayList<>();

        @JsonProperty("exiting_objects")
        private List<StoryboardObject> exitingObjects = new ArrayList<>();

        @JsonProperty("actions")
        private List<StoryboardAction> actions = new ArrayList<>();

        @JsonProperty("notes_for_codegen")
        private List<String> notesForCodegen = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardObject {

        @JsonProperty("id")
        private String id;

        @JsonProperty("kind")
        private String kind;

        @JsonProperty("content")
        private String content;

        @JsonProperty("placement")
        private StoryboardPlacement placement;

        @JsonProperty("style")
        private StoryboardStyle style;

        @JsonProperty("constraints")
        private List<StoryboardConstraint> constraints = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardConstraint {

        @JsonProperty("id")
        private String id;

        @JsonProperty("domain")
        private String domain;

        @JsonProperty("relation")
        private String relation;

        @JsonProperty("refs")
        private Map<String, Object> refs = new LinkedHashMap<>();

        @JsonProperty("parameters")
        private Map<String, Object> parameters = new LinkedHashMap<>();

        @JsonProperty("strength")
        private String strength;

        @JsonProperty("reason")
        private String reason;

        public boolean hasData() {
            return hasText(id)
                    || hasText(domain)
                    || hasText(relation)
                    || (refs != null && !refs.isEmpty())
                    || (parameters != null && !parameters.isEmpty())
                    || hasText(strength)
                    || hasText(reason);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardPlacement {

        public static final String POSITIONING_ABSOLUTE = "absolute";
        public static final String POSITIONING_RELATIVE = "relative";

        @JsonProperty("positioning")
        private String positioning;

        @JsonProperty("x")
        private StoryboardPlacementAxis x;

        @JsonProperty("y")
        private StoryboardPlacementAxis y;

        @JsonProperty("z")
        private StoryboardPlacementAxis z;

        public boolean hasData() {
            return hasText(positioning)
                    || (x != null && x.hasData())
                    || (y != null && y.hasData())
                    || (z != null && z.hasData());
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoryboardPlacementAxis {

        @JsonProperty("value")
        private Double value;

        @JsonProperty("min")
        private Double min;

        @JsonProperty("max")
        private Double max;

        public boolean hasData() {
            return value != null || min != null || max != null;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoryboardStyle {

        @JsonProperty("color")
        private String color;

        @JsonProperty("fill_color")
        private String fillColor;

        @JsonProperty("stroke_color")
        private String strokeColor;

        @JsonProperty("highlight_color")
        private String highlightColor;

        @JsonProperty("opacity")
        private Double opacity;

        @JsonProperty("fill_opacity")
        private Double fillOpacity;

        @JsonProperty("stroke_opacity")
        private Double strokeOpacity;

        @JsonProperty("stroke_width")
        private Double strokeWidth;

        @JsonProperty("line_style")
        private String lineStyle;

        @JsonProperty("font_size")
        private Double fontSize;

        @JsonProperty("font_family")
        private String fontFamily;

        @JsonProperty("font_weight")
        private String fontWeight;

        @JsonProperty("font_style")
        private String fontStyle;

        @JsonProperty("radius")
        private Double radius;

        @JsonProperty("point_size")
        private Double pointSize;

        @JsonProperty("marker_size")
        private Double markerSize;

        @JsonProperty("padding")
        private Double padding;

        @JsonProperty("corner_radius")
        private Double cornerRadius;

        @JsonProperty("z_index")
        private Double zIndex;

        @JsonProperty("point_style")
        private Double pointStyle;

        @JsonProperty("decoration")
        private Double decoration;

        @JsonProperty("label_visible")
        private Boolean labelVisible;

        public boolean hasData() {
            return color != null
                    || fillColor != null
                    || strokeColor != null
                    || highlightColor != null
                    || opacity != null
                    || fillOpacity != null
                    || strokeOpacity != null
                    || strokeWidth != null
                    || lineStyle != null
                    || fontSize != null
                    || fontFamily != null
                    || fontWeight != null
                    || fontStyle != null
                    || radius != null
                    || pointSize != null
                    || markerSize != null
                    || padding != null
                    || cornerRadius != null
                    || zIndex != null
                    || pointStyle != null
                    || decoration != null
                    || labelVisible != null;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StoryboardAction {

        @JsonProperty("order")
        private int order;

        @JsonProperty("type")
        private String type;

        @JsonProperty("targets")
        private List<String> targets = new ArrayList<>();

        @JsonProperty("description")
        private String description;

        @JsonProperty("voiceover_text")
        private String voiceoverText;

        @JsonProperty("expected_seconds")
        private Double expectedSeconds;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
