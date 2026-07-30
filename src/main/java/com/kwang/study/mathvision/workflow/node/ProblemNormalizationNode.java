package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.mathvision.dto.InputAssetDTO;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.ProblemDiagram;
import com.kwang.study.mathvision.workflow.model.ProblemSource;
import com.kwang.study.mathvision.workflow.model.SourceAsset;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.prompt.ProblemNormalizationPrompts;
import com.kwang.study.mathvision.workflow.prompt.SystemPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.util.SceneModeUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class ProblemNormalizationNode {

    private static final int TEXT_ATTACHMENT_LIMIT_BYTES = 200_000;

    private final MathVisionAiChatService aiChatService;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public ProblemNormalizationNode(MathVisionAiChatService aiChatService,
                                    FileStorageService fileStorageService,
                                    ObjectMapper objectMapper) {
        this.aiChatService = aiChatService;
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
    }

    public Result run(MathVisionTask task, MathVisionStageExecutionContext context) {
        return run(task, StageGenerationRequest.initialGeneration(), context);
    }

    public Result run(MathVisionTask task,
                      StageGenerationRequest<ProblemBundle> request,
                      MathVisionStageExecutionContext context) {
        StageGenerationRequest<ProblemBundle> resolvedRequest = request != null
                ? request
                : StageGenerationRequest.initialGeneration();
        validateRequest(resolvedRequest);
        SourcePayload sourcePayload = buildSourcePayload(task);
        if (!StringUtils.hasText(sourcePayload.source.getRawText()) && sourcePayload.imageParts.isEmpty()) {
            throw new IllegalArgumentException("Problem text and image inputs cannot both be empty.");
        }

        context.checkCanceled();
        JsonNode normalizedPayload = aiChatService.requestJson(
                task,
                buildNormalizationMessages(task.getOutputTarget(), sourcePayload, resolvedRequest),
                ToolSchemas.PROBLEM_BUNDLE
        );
        ProblemBundle generated = parseProblemBundle(normalizedPayload, sourcePayload.source);

        context.checkCanceled();
        JsonNode reviewedPayload = aiChatService.requestJson(
                task,
                buildReviewMessages(task.getOutputTarget(), sourcePayload, generated, resolvedRequest),
                ToolSchemas.PROBLEM_BUNDLE
        );
        ProblemBundle reviewed = parseProblemBundle(reviewedPayload, sourcePayload.source);
        reviewed.setOutputTarget(task.getOutputTarget());
        reviewed.setSource(sourcePayload.source);

        int statementLength = reviewed.getStatement() != null ? reviewed.getStatement().length() : 0;
        return new Result(reviewed, 2, sourcePayload.source.getSourceType(),
                sourcePayload.imageParts.size(), statementLength);


    }

    private List<AiMessage> buildNormalizationMessages(String outputTarget,
                                                       SourcePayload payload,
                                                       StageGenerationRequest<ProblemBundle> request) {
        List<AiMessage> messages = new ArrayList<>();
        String rulesPrompt = ProblemNormalizationPrompts.buildRulesPrompt();
        String fixedContext = ProblemNormalizationPrompts.buildFixedContextPrompt(outputTarget);
        if (request.isUserRevision()) {
            rulesPrompt += "\n\n" + buildRevisionRulesAppendix();
            fixedContext += "\n\n" + buildRevisionFixedContextAppendix(
                    request.getBaseStageVersion());
        }
        messages.add(AiMessage.system(rulesPrompt));
        messages.add(AiMessage.system(fixedContext));

        List<AiContentPart> userParts = new ArrayList<>();
        String rawText = payload.source.getRawText();
        if (request.isUserRevision()) {
            userParts.add(AiContentPart.text(buildRevisionUserPrompt(
                    rawText,
                    outputTarget,
                    payload.imageParts.size(),
                    toPrettyJson(request.getExistingArtifact()),
                    request.getInstruction())));
            userParts.addAll(payload.imageParts);
        } else if (payload.imageParts.isEmpty()) {
            userParts.add(AiContentPart.text(ProblemNormalizationPrompts.buildUserPrompt(rawText, outputTarget)));
        } else {
            userParts.add(AiContentPart.text(ProblemNormalizationPrompts.buildMultimodalUserPrompt(
                    rawText, outputTarget, payload.imageParts.size())));
            userParts.addAll(payload.imageParts);
        }
        messages.add(AiMessage.user(userParts));
        return messages;
    }

    private List<AiMessage> buildReviewMessages(String outputTarget,
                                                SourcePayload payload,
                                                ProblemBundle generatedBundle,
                                                StageGenerationRequest<ProblemBundle> request) {
        List<AiMessage> messages = new ArrayList<>();
        String rulesPrompt = ProblemNormalizationPrompts.buildReviewRulesPrompt();
        String fixedContext = ProblemNormalizationPrompts.buildReviewFixedContextPrompt(outputTarget);
        if (request.isUserRevision()) {
            rulesPrompt += "\n\n" + buildRevisionRulesAppendix();
            fixedContext += "\n\n" + buildRevisionFixedContextAppendix(
                    request.getBaseStageVersion());
        }
        messages.add(AiMessage.system(rulesPrompt));
        messages.add(AiMessage.system(fixedContext));

        List<AiContentPart> userParts = new ArrayList<>();
        userParts.add(AiContentPart.text(request.isUserRevision()
                ? buildRevisionReviewUserPrompt(
                        payload.source.getRawText(),
                        outputTarget,
                        payload.imageParts.size(),
                        toPrettyJson(request.getExistingArtifact()),
                        toPrettyJson(generatedBundle),
                        request.getInstruction())
                : ProblemNormalizationPrompts.buildReviewUserPrompt(
                        payload.source.getRawText(),
                        outputTarget,
                        payload.imageParts.size(),
                        toPrettyJson(generatedBundle))));
        userParts.addAll(payload.imageParts);
        messages.add(AiMessage.user(userParts));
        return messages;
    }

    private void validateRequest(StageGenerationRequest<ProblemBundle> request) {
        if (!request.isUserRevision()) {
            return;
        }
        if (!StringUtils.hasText(request.getInstruction())) {
            throw new IllegalArgumentException("User revision instruction cannot be empty.");
        }
        if (request.getExistingArtifact() == null) {
            throw new IllegalArgumentException("Existing ProblemBundle is required for user revision.");
        }
    }

    private SourcePayload buildSourcePayload(MathVisionTask task) {
        List<InputAssetDTO> inputAssets = readInputAssets(task.getInputAssetsJson());
        List<SourceAsset> sourceAssets = new ArrayList<>();
        List<AiContentPart> imageParts = new ArrayList<>();
        StringBuilder text = new StringBuilder(task.getInputText() != null ? task.getInputText().trim() : "");

        int index = 0;
        for (InputAssetDTO asset : inputAssets) {
            if (asset == null || !StringUtils.hasText(asset.getFilePath())) {
                continue;
            }
            String mimeType = asset.getMimeTypeName();
            boolean textAsset = isTextAsset(asset);
            SourceAsset sourceAsset = new SourceAsset();
            sourceAsset.setId(StringUtils.hasText(asset.getFileName()) ? asset.getFileName() : "asset_" + index);
            sourceAsset.setType(isImage(mimeType) ? "image" : (textAsset ? "text" : "file"));
            sourceAsset.setPath(asset.getFilePath());
            sourceAsset.setMimeType(mimeType);
            sourceAssets.add(sourceAsset);

            if (isImage(mimeType)) {
                byte[] bytes = readFileBytes(asset.getFilePath());
                imageParts.add(AiContentPart.image(
                        StringUtils.hasText(mimeType) ? mimeType : "image/png",
                        Base64.getEncoder().encodeToString(bytes)
                ));
            } else if (textAsset) {
                appendTextAttachment(text, asset, readFileBytes(asset.getFilePath()));
            }
            index++;
        }

        ProblemSource source = new ProblemSource();
        source.setRawText(text.toString().trim());
        source.setSourceType(resolveSourceType(task.getInputSourceType(), source.getRawText(), imageParts));
        source.setAssets(sourceAssets);
        return new SourcePayload(source, imageParts);
    }

    private List<InputAssetDTO> readInputAssets(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, InputAssetDTO.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse input asset metadata: " + e.getMessage(), e);
        }
    }

    private byte[] readFileBytes(String path) {
        try {
            FileObjectResult fileObject = fileStorageService.getFileObject(path);
            if (fileObject == null || fileObject.getContent() == null) {
                throw new IllegalArgumentException("Input asset content is unavailable: " + path);
            }
            try (InputStream inputStream = fileObject.getContent()) {
                return inputStream.readAllBytes();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read input asset: " + path + ", " + e.getMessage(), e);
        }
    }

    private void appendTextAttachment(StringBuilder text, InputAssetDTO asset, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        int length = Math.min(bytes.length, TEXT_ATTACHMENT_LIMIT_BYTES);
        String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
        if (text.length() > 0) {
            text.append("\n\n");
        }
        text.append("[Attachment: ")
                .append(StringUtils.hasText(asset.getFileName()) ? asset.getFileName() : asset.getFilePath())
                .append("]\n")
                .append(content);
        if (bytes.length > TEXT_ATTACHMENT_LIMIT_BYTES) {
            text.append("\n[Attachment truncated at ")
                    .append(TEXT_ATTACHMENT_LIMIT_BYTES)
                    .append(" bytes]");
        }
    }

    private ProblemBundle parseProblemBundle(JsonNode payload, ProblemSource source) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalStateException("ProblemBundle LLM response contained no usable payload");
        }
        if (!looksLikeProblemBundlePayload(payload)) {
            throw new IllegalStateException("ProblemBundle LLM response did not look like a ProblemBundle");
        }
        try {
            ProblemBundle bundle = objectMapper.treeToValue(payload, ProblemBundle.class);
            if (!StringUtils.hasText(bundle.getStatement())) {
                bundle.setStatement(source.getRawText());
            }
            if (!StringUtils.hasText(bundle.getInputMode())) {
                bundle.setInputMode("problem");
            }
            bundle.setSceneMode(SceneModeUtils.normalize(bundle.getSceneMode()));
            if (bundle.getDiagram() == null) {
                ProblemDiagram diagram = new ProblemDiagram();
                diagram.setPresent(false);
                diagram.setSourceObserved(false);
                bundle.setDiagram(diagram);
            }
            migrateLegacyDiagramPayload(bundle, payload);
            return bundle;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ProblemBundle from LLM response: " + e.getMessage(), e);
        }
    }

    private boolean looksLikeProblemBundlePayload(JsonNode payload) {
        return payload != null
                && payload.isObject()
                && (payload.has("statement")
                || payload.has("diagram")
                || payload.has("input_mode")
                || payload.has("scene_mode"));
    }

    private void migrateLegacyDiagramPayload(ProblemBundle bundle, JsonNode payload) {
        if (bundle == null || bundle.getDiagram() == null || payload == null) {
            return;
        }
        JsonNode diagramPayload = payload.path("diagram");
        if (!diagramPayload.isObject()) {
            return;
        }
        ProblemDiagram diagram = bundle.getDiagram();
        if (!diagram.isPresent()) {
            diagram.setSourceObserved(false);
            return;
        }
        if (!diagram.isSourceObserved()) {
            diagram.setSourceObserved(true);
        }
        if (diagram.hasDescriptionPayload()) {
            return;
        }

        ObjectNode description = objectMapper.createObjectNode();
        String legacyDescription = diagramPayload.path("description").asText("");
        if (StringUtils.hasText(legacyDescription)) {
            description.put("overall_shape", legacyDescription);
        }
        if (diagramPayload.has("objects")) {
            description.set("legacy_objects", diagramPayload.get("objects"));
        }
        if (diagramPayload.has("constraints")) {
            description.set("legacy_constraints", diagramPayload.get("constraints"));
        }
        if (description.size() > 0) {
            diagram.setDiagramDescription(description);
        }

        JsonNode legacyNotes = diagramPayload.get("construction_notes");
        if (legacyNotes != null && legacyNotes.isArray()) {
            List<String> normalizationNotes = new ArrayList<>();
            for (JsonNode note : legacyNotes) {
                if (StringUtils.hasText(note.asText(""))) {
                    normalizationNotes.add(note.asText());
                }
            }
            if (!normalizationNotes.isEmpty()) {
                diagram.setNormalizationNotes(normalizationNotes);
            }
        }
    }

    private boolean isImage(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }

    private boolean isTextAsset(InputAssetDTO asset) {
        String mimeType = asset.getMimeTypeName();
        if (mimeType != null && (mimeType.startsWith("text/")
                || "application/json".equals(mimeType)
                || "application/xml".equals(mimeType))) {
            return true;
        }
        String fileName = asset.getFileName();
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".md")
                || lower.endsWith(".markdown")
                || lower.endsWith(".txt")
                || lower.endsWith(".json")
                || lower.endsWith(".tex");
    }

    private String resolveSourceType(String declared, String rawText, List<AiContentPart> imageParts) {
        if (StringUtils.hasText(declared)) {
            return declared;
        }
        boolean hasText = StringUtils.hasText(rawText);
        boolean hasImage = imageParts != null && !imageParts.isEmpty();
        if (hasText && hasImage) {
            return "mixed";
        }
        return hasImage ? "image" : "text";
    }

    private String buildRevisionRulesAppendix() {
        return SystemPrompts.buildRulesSection(
                "ProblemBundle user-revision mode:\n"
                        + "- Treat the supplied ProblemBundle as the revision baseline and the original source as mathematical authority.\n"
                        + "- Apply the user instruction wherever it remains faithful to the original source.\n"
                        + "- Preserve unrelated normalized content, labels, quantities, source-observed diagram facts, input mode, and scene mode.\n"
                        + "- Regenerate and return the complete ProblemBundle through the normal output contract.\n"
                        + "- Do not return a patch, diff, review report, or explanation.");
    }

    private String buildRevisionFixedContextAppendix(Integer baseStageVersion) {
        StringBuilder sb = new StringBuilder("Operation mode: user_revision.\n");
        if (baseStageVersion != null) {
            sb.append("Base problem_normalization stage version: ")
                    .append(baseStageVersion)
                    .append(".\n");
        }
        sb.append("The current request contains the existing ProblemBundle and the user's revision instruction.\n");
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    private String buildRevisionUserPrompt(String rawText,
                                           String outputTarget,
                                           int imageCount,
                                           String existingBundleJson,
                                           String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("Regenerate the complete ProblemBundle for output target `")
                .append(outputTarget)
                .append("` according to the user instruction.\n\n");
        appendOriginalSourceContext(sb, rawText, imageCount);
        sb.append("\nExisting ProblemBundle (revision baseline):\n```json\n")
                .append(StringUtils.hasText(existingBundleJson) ? existingBundleJson : "{}")
                .append("\n```\n\nUser revision instruction:\n")
                .append(instruction == null ? "" : instruction.trim())
                .append("\n\nReturn the complete revised canonical ProblemBundle through the normal tool contract.");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private String buildRevisionReviewUserPrompt(String rawText,
                                                 String outputTarget,
                                                 int imageCount,
                                                 String existingBundleJson,
                                                 String generatedBundleJson,
                                                 String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("Review the regenerated ProblemBundle for output target `")
                .append(outputTarget)
                .append("` against the original source and the valid parts of the user instruction.\n\n");
        appendOriginalSourceContext(sb, rawText, imageCount);
        sb.append("\nExisting ProblemBundle (revision baseline):\n```json\n")
                .append(StringUtils.hasText(existingBundleJson) ? existingBundleJson : "{}")
                .append("\n```\n\nUser revision instruction:\n")
                .append(instruction == null ? "" : instruction.trim())
                .append("\n\nRegenerated ProblemBundle to review:\n```json\n")
                .append(StringUtils.hasText(generatedBundleJson) ? generatedBundleJson : "{}")
                .append("\n```\n\nReturn the complete corrected ProblemBundle only. Preserve requested changes that do not conflict with the source.");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private void appendOriginalSourceContext(StringBuilder sb, String rawText, int imageCount) {
        if (StringUtils.hasText(rawText)) {
            sb.append("Original text input:\n").append(rawText).append("\n\n");
        } else {
            sb.append("No separate original text input was provided.\n\n");
        }
        sb.append("Attached image count: ").append(Math.max(imageCount, 0)).append(".\n");
        if (imageCount <= 0) {
            sb.append("No source image is attached; do not claim a source-observed diagram.\n");
        } else {
            sb.append("The original source image assets are attached to this request again.\n");
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }

    public static final class Result {
        private final ProblemBundle problemBundle;
        private final int apiCalls;
        private final String sourceType;
        private final int imageCount;
        private final int statementLength;

        private Result(ProblemBundle problemBundle,
                       int apiCalls,
                       String sourceType,
                       int imageCount,
                       int statementLength) {
            this.problemBundle = problemBundle;
            this.apiCalls = apiCalls;
            this.sourceType = sourceType;
            this.imageCount = imageCount;
            this.statementLength = statementLength;
        }

        public ProblemBundle getProblemBundle() {
            return problemBundle;
        }

        public int getApiCalls() {
            return apiCalls;
        }

        public String getSourceType() {
            return sourceType;
        }

        public int getImageCount() {
            return imageCount;
        }

        public int getStatementLength() {
            return statementLength;
        }
    }

    private static final class SourcePayload {
        private final ProblemSource source;
        private final List<AiContentPart> imageParts;

        private SourcePayload(ProblemSource source, List<AiContentPart> imageParts) {
            this.source = source;
            this.imageParts = imageParts;
        }
    }
}
