package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** 用户自定义兼容模型配置。一个配置对应一个可选择的模型。 */
@Data
public class CustomProviderConfigDTO {

    @NotBlank(message = "请输入厂家名称")
    @Size(max = 128, message = "厂家名称不能超过 128 个字符")
    private String providerName;

    @NotBlank(message = "请选择兼容协议")
    @Pattern(regexp = "(?i)openai|anthropic|google", message = "仅支持 OpenAI、Anthropic 或 Gemini 兼容协议")
    private String compatibilityType;

    @NotBlank(message = "请输入 Base URL")
    @Size(max = 512, message = "Base URL 不能超过 512 个字符")
    private String baseUrl;

    /** 新增时必填，编辑时留空表示保留原 Key。 */
    @Size(max = 4096, message = "API Key 过长")
    private String apiKey;

    @NotBlank(message = "请输入模型名称")
    @Size(max = 128, message = "模型名称不能超过 128 个字符")
    private String modelName;

    private Boolean supportVision;

    @Min(value = 1024, message = "上下文窗口不能小于 1024")
    private Integer contextWindow;

    @Min(value = 256, message = "最大输出 Token 数不能小于 256")
    private Integer maxOutputTokens;

    @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
    private Double temperature;

    @DecimalMin(value = "0.0", inclusive = false, message = "topP 必须大于 0")
    @DecimalMax(value = "1.0", message = "topP 不能大于 1")
    private Double topP;
}
