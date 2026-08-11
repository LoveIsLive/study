package com.kwang.study.mathvision.engine;

import lombok.Builder;
import lombok.Data;
import org.springframework.util.StringUtils;

@Data
@Builder
public class MathVisionStageExecutionResult {

    /** 阶段核心产物 JSON, 对应 mathvision_artifacts.artifact_json。 */
    private String artifactJson;
    /** 阶段校验 / 执行结果 JSON, 对应 mathvision_stage_results.result_json。 */
    private String resultJson;
    private String changeSource;
    private String changeSummary;
    private String finalArtifactPath;
    private String finalArtifactType;
    /** Always pause after this stage so the user can run or skip the optional quality review. */
    private boolean waitForUserDecision;
    /** 阶段已产出诊断结果, 但业务上应停在 failed 状态。 */
    private boolean failed;
    private String errorType;
    private String errorMessage;

    public String resolvedChangeSource() {
        return StringUtils.hasText(changeSource) ? changeSource : "initial_generation";
    }
}
