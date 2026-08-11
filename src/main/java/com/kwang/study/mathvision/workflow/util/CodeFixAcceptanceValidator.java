package com.kwang.study.mathvision.workflow.util;

import com.kwang.study.mathvision.workflow.model.CodeFixSource;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates an LLM-produced code-fix candidate before it is allowed to replace
 * the last accepted artifact. The validator protects stable structural
 * contracts while deliberately allowing local implementation changes, added
 * helpers, and moderate simplification.
 */
public final class CodeFixAcceptanceValidator {

    private static final Pattern MAIN_SCENE_BASE = Pattern.compile(
            "(?m)^\\s*class\\s+MainScene\\s*\\(([^)]*)\\)\\s*:");
    private static final Pattern SCENE_METHOD = Pattern.compile(
            "(?m)^\\s*def\\s+(scene_[A-Za-z0-9_]+)\\s*\\(");
    private static final Pattern SCENE_CALL = Pattern.compile(
            "\\bself\\.(scene_[A-Za-z0-9_]+)\\s*\\(");
    private static final Pattern VOICEOVER_CALL = Pattern.compile(
            "\\bself\\.voiceover\\s*\\(");
    private static final Pattern BEHAVIOR_CALL = Pattern.compile(
            "\\bself\\.(?:play|add|remove|wait|voiceover|add_subcaption)\\s*\\(");

    private CodeFixAcceptanceValidator() {
    }

    public static Decision evaluate(String originalCode,
                                    String candidateCode,
                                    String outputTarget,
                                    CodeFixSource source) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return evaluateGeoGebra(originalCode, candidateCode, source);
        }
        return evaluateManim(originalCode, candidateCode, source);
    }

    private static Decision evaluateManim(String originalCode,
                                          String candidateCode,
                                          CodeFixSource source) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String blocker : ManimCodeUtils.validateRenderBlockers(candidateCode)) {
            issues.add("candidate render blocker: " + blocker);
        }

        ManimContract baseline = ManimContract.extract(originalCode);
        ManimContract candidate = ManimContract.extract(candidateCode);

        if (StringUtils.hasText(baseline.mainSceneBase)
                && StringUtils.hasText(candidate.mainSceneBase)
                && !baseline.mainSceneBase.equals(candidate.mainSceneBase)) {
            warnings.add("MainScene base class changed from `" + baseline.mainSceneBase
                    + "` to `" + candidate.mainSceneBase + "`");
        }

        List<String> missingMethods = missing(baseline.sceneMethods, candidate.sceneMethods);
        if (!missingMethods.isEmpty()) {
            issues.add("generated scene methods were removed: " + missingMethods);
        }

        List<String> missingCalls = missing(baseline.constructSceneCalls, candidate.constructSceneCalls);
        if (!missingCalls.isEmpty()) {
            issues.add("construct() no longer calls generated scenes: " + missingCalls);
        }
        if (!baseline.constructSceneCalls.isEmpty()
                && !relativeOrderPreserved(baseline.constructSceneCalls, candidate.constructSceneCalls)) {
            issues.add("construct() changed the established scene execution order from "
                    + baseline.constructSceneCalls + " to " + candidate.constructSceneCalls);
        }

        if (baseline.voiceoverCalls > 0 && candidate.voiceoverCalls == 0) {
            issues.add("all existing voiceover blocks were removed");
        }

        List<String> addedMethods = missing(candidate.sceneMethods, baseline.sceneMethods);
        if (!addedMethods.isEmpty()) {
            warnings.add("candidate added scene methods: " + addedMethods);
        }

        boolean severeSizeReduction = baseline.nonBlankLines >= 80
                && (long) candidate.nonBlankLines * 100L < (long) baseline.nonBlankLines * 20L;
        boolean severeBehaviorReduction = baseline.behaviorCalls >= 10
                && (long) candidate.behaviorCalls * 100L < (long) baseline.behaviorCalls * 25L;
        if (severeSizeReduction) {
            warnings.add("candidate retained less than 20% of the non-blank source lines ("
                    + baseline.nonBlankLines + " -> " + candidate.nonBlankLines + ")");
        }
        if (severeBehaviorReduction) {
            warnings.add("candidate retained less than 25% of the animation behavior calls ("
                    + baseline.behaviorCalls + " -> " + candidate.behaviorCalls + ")");
        }
        // Size reduction alone is not a rejection: compact rewrites can be valid.
        // It becomes destructive only when independent behavioral evidence also
        // collapses, which keeps the gate permissive for focused repairs.
        if (severeSizeReduction && severeBehaviorReduction) {
            issues.add("candidate appears to replace the teaching program with a substantially reduced behavior set");
        }

        return new Decision(issues.isEmpty(), issues, warnings, source);
    }

    private static Decision evaluateGeoGebra(String originalCode,
                                             String candidateCode,
                                             CodeFixSource source) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String blocker : GeoGebraCodeUtils.validateRenderBlockers(candidateCode)) {
            issues.add("candidate render blocker: " + blocker);
        }

        List<String> baselineSceneIds = sceneDirectiveIds(originalCode, warnings, "baseline");
        List<String> candidateSceneIds = sceneDirectiveIds(candidateCode, issues, "candidate");
        List<String> missingSceneIds = missing(baselineSceneIds, candidateSceneIds);
        if (!missingSceneIds.isEmpty()) {
            issues.add("GeoGebra scene directives were removed: " + missingSceneIds);
        }
        if (!baselineSceneIds.isEmpty() && !relativeOrderPreserved(baselineSceneIds, candidateSceneIds)) {
            issues.add("GeoGebra scene directive order changed from "
                    + baselineSceneIds + " to " + candidateSceneIds);
        }

        int baselineCommands = GeoGebraCodeUtils.extractCommands(originalCode).size();
        int candidateCommands = GeoGebraCodeUtils.extractCommands(candidateCode).size();
        int baselineLines = countNonBlankLines(originalCode);
        int candidateLines = countNonBlankLines(candidateCode);
        boolean severeSizeReduction = baselineLines >= 40
                && (long) candidateLines * 100L < (long) baselineLines * 20L;
        boolean severeCommandReduction = baselineCommands >= 20
                && (long) candidateCommands * 100L < (long) baselineCommands * 25L;
        if (severeSizeReduction) {
            warnings.add("candidate retained less than 20% of the non-blank command program lines ("
                    + baselineLines + " -> " + candidateLines + ")");
        }
        if (severeCommandReduction) {
            warnings.add("candidate retained less than 25% of executable commands ("
                    + baselineCommands + " -> " + candidateCommands + ")");
        }
        if (severeSizeReduction && severeCommandReduction) {
            issues.add("candidate appears to replace the GeoGebra teaching construction with a reduced command set");
        }

        return new Decision(issues.isEmpty(), issues, warnings, source);
    }

    private static List<String> sceneDirectiveIds(String code,
                                                  List<String> diagnostics,
                                                  String label) {
        try {
            List<String> ids = new ArrayList<>();
            for (GeoGebraCodeUtils.SceneDirective directive : GeoGebraCodeUtils.extractSceneDirectives(code)) {
                if (directive != null && StringUtils.hasText(directive.id)) {
                    ids.add(directive.id.trim());
                }
            }
            return unique(ids);
        } catch (RuntimeException e) {
            diagnostics.add(label + " GeoGebra scene directives could not be parsed: " + e.getMessage());
            return List.of();
        }
    }

    private static boolean relativeOrderPreserved(List<String> baseline, List<String> candidate) {
        Set<String> expected = new LinkedHashSet<>(baseline);
        List<String> relevantCandidate = new ArrayList<>();
        for (String value : candidate) {
            if (expected.contains(value) && !relevantCandidate.contains(value)) {
                relevantCandidate.add(value);
            }
        }
        return unique(baseline).equals(relevantCandidate);
    }

    private static List<String> missing(List<String> expected, List<String> actual) {
        Set<String> actualSet = new LinkedHashSet<>(actual);
        List<String> missing = new ArrayList<>();
        for (String value : unique(expected)) {
            if (!actualSet.contains(value)) {
                missing.add(value);
            }
        }
        return missing;
    }

    private static List<String> unique(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static int countMatches(String code, Pattern pattern) {
        if (!StringUtils.hasText(code)) {
            return 0;
        }
        int count = 0;
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countNonBlankLines(String code) {
        if (!StringUtils.hasText(code)) {
            return 0;
        }
        int count = 0;
        for (String line : code.split("\\R", -1)) {
            if (StringUtils.hasText(line)) {
                count++;
            }
        }
        return count;
    }

    private static String extractMethodBody(String code, String methodName) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(methodName)) {
            return "";
        }
        String[] lines = code.split("\\R", -1);
        Pattern definition = Pattern.compile("^([ \\t]*)def\\s+"
                + Pattern.quote(methodName) + "\\s*\\(");
        int methodLine = -1;
        int methodIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = definition.matcher(lines[i]);
            if (matcher.find()) {
                methodLine = i;
                methodIndent = indentationWidth(matcher.group(1));
                break;
            }
        }
        if (methodLine < 0) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (int i = methodLine + 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.isEmpty()
                    && !trimmed.startsWith("#")
                    && indentationWidth(line) <= methodIndent) {
                break;
            }
            body.append(line).append('\n');
        }
        return body.toString();
    }

    private static int indentationWidth(String line) {
        int index = 0;
        int width = 0;
        while (index < line.length()) {
            char ch = line.charAt(index);
            if (ch == ' ') {
                width++;
            } else if (ch == '\t') {
                width += 4;
            } else {
                break;
            }
            index++;
        }
        return width;
    }

    private static final class ManimContract {
        private final String mainSceneBase;
        private final List<String> sceneMethods;
        private final List<String> constructSceneCalls;
        private final int voiceoverCalls;
        private final int behaviorCalls;
        private final int nonBlankLines;

        private ManimContract(String mainSceneBase,
                              List<String> sceneMethods,
                              List<String> constructSceneCalls,
                              int voiceoverCalls,
                              int behaviorCalls,
                              int nonBlankLines) {
            this.mainSceneBase = mainSceneBase;
            this.sceneMethods = sceneMethods;
            this.constructSceneCalls = constructSceneCalls;
            this.voiceoverCalls = voiceoverCalls;
            this.behaviorCalls = behaviorCalls;
            this.nonBlankLines = nonBlankLines;
        }

        private static ManimContract extract(String code) {
            String normalized = code == null ? "" : code.replace("\r\n", "\n").replace('\r', '\n');
            Matcher baseMatcher = MAIN_SCENE_BASE.matcher(normalized);
            String base = baseMatcher.find()
                    ? baseMatcher.group(1).replaceAll("\\s+", "").trim()
                    : "";
            List<String> methods = new ArrayList<>();
            Matcher methodMatcher = SCENE_METHOD.matcher(normalized);
            while (methodMatcher.find()) {
                methods.add(methodMatcher.group(1));
            }
            List<String> calls = new ArrayList<>();
            Matcher callMatcher = SCENE_CALL.matcher(extractMethodBody(normalized, "construct"));
            while (callMatcher.find()) {
                calls.add(callMatcher.group(1));
            }
            return new ManimContract(
                    base,
                    unique(methods),
                    unique(calls),
                    countMatches(normalized, VOICEOVER_CALL),
                    countMatches(normalized, BEHAVIOR_CALL),
                    countNonBlankLines(normalized));
        }
    }

    public static final class Decision {
        private final boolean accepted;
        private final List<String> issues;
        private final List<String> warnings;
        private final CodeFixSource source;

        private Decision(boolean accepted,
                         List<String> issues,
                         List<String> warnings,
                         CodeFixSource source) {
            this.accepted = accepted;
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.source = source;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public List<String> getIssues() {
            return issues;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public CodeFixSource getSource() {
            return source;
        }

        public String summarizeIssues() {
            return String.join("; ", issues);
        }
    }
}
