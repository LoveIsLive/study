package com.kwang.study.mathvision.controller;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.mathvision.dto.CredentialTestResultVO;
import com.kwang.study.mathvision.dto.CustomProviderConfigDTO;
import com.kwang.study.mathvision.dto.ProviderCredentialDTO;
import com.kwang.study.mathvision.dto.ProviderInfoVO;
import com.kwang.study.mathvision.dto.ProviderModelsVO;
import com.kwang.study.mathvision.service.LlmModelConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * MathVision 模型厂家与 API Key 配置接口。
 * userId 一律取登录态, 前端不传。
 */
@RestController
@RequestMapping(ApiPrefixConstant.MATHVISION_BASE_PREFIX + "/llm")
@Validated
public class MathVisionLlmController {

    private final LlmModelConfigService service;

    public MathVisionLlmController(LlmModelConfigService service) {
        this.service = service;
    }

    /** 获取系统支持的模型厂家 + 当前用户配置状态 */
    @GetMapping("/providers")
    public ResponseEntity<R<List<ProviderInfoVO>>> listProviders() {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        return ResponseEntity.ok(R.success(service.listProviders(userId)));
    }

    /** 设置 / 更新厂家 API Key */
    @PutMapping("/providers/{providerCode}/credential")
    public ResponseEntity<R<ProviderInfoVO>> upsertCredential(
            @PathVariable String providerCode,
            @Valid @RequestBody ProviderCredentialDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        ProviderInfoVO vo = service.upsertCredential(userId, providerCode, request.getApiKey());
        return ResponseEntity.ok(R.success(vo));
    }

    @PostMapping("/providers/custom")
    public ResponseEntity<R<ProviderInfoVO>> createCustomProvider(
            @Valid @RequestBody CustomProviderConfigDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        return ResponseEntity.ok(R.success(service.createCustomProvider(userId, request)));
    }

    @PutMapping("/providers/{providerCode}/custom")
    public ResponseEntity<R<ProviderInfoVO>> updateCustomProvider(
            @PathVariable String providerCode,
            @Valid @RequestBody CustomProviderConfigDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        return ResponseEntity.ok(R.success(service.updateCustomProvider(userId, providerCode, request)));
    }

    /** 删除厂家 API Key */
    @DeleteMapping("/providers/{providerCode}/credential")
    public ResponseEntity<R<Void>> deleteCredential(@PathVariable String providerCode) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        service.deleteCredential(userId, providerCode);
        return ResponseEntity.ok(R.success(null));
    }

    /** 测试厂家 API Key */
    @PostMapping("/providers/{providerCode}/credential/test")
    public ResponseEntity<R<CredentialTestResultVO>> testCredential(@PathVariable String providerCode) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        return ResponseEntity.ok(R.success(service.testCredential(userId, providerCode)));
    }

    /** 获取某厂家在目录中的可用模型列表 (含能力声明) */
    @GetMapping("/providers/{providerCode}/models")
    public ResponseEntity<R<ProviderModelsVO>> listModels(@PathVariable String providerCode) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        return ResponseEntity.ok(R.success(service.listModels(userId, providerCode)));
    }
}
