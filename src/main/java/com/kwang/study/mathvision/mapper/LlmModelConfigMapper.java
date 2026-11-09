package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.LlmModelConfig;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface LlmModelConfigMapper {

    @Insert("INSERT INTO llm_model_configs(owner_user_id, provider, is_custom, provider_name, " +
            "compatibility_type, base_url, model_name, support_vision, context_window, max_output_tokens, " +
            "api_key_encrypted, api_key_masked, status, temperature, enable_thinking, top_p, extra_headers_json, " +
            "create_time, update_time) " +
            "VALUES(#{ownerUserId}, #{provider}, #{isCustom}, #{providerName}, #{compatibilityType}, " +
            "#{baseUrl}, #{modelName}, #{supportVision}, #{contextWindow}, #{maxOutputTokens}, " +
            "#{apiKeyEncrypted}, #{apiKeyMasked}, " +
            "#{status}, #{temperature}, #{enableThinking}, #{topP}, #{extraHeadersJson}, " +
            "NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LlmModelConfig config);

    @Select("SELECT * FROM llm_model_configs WHERE id = #{id}")
    LlmModelConfig findById(Long id);

    @Select("SELECT * FROM llm_model_configs WHERE owner_user_id = #{ownerUserId} AND provider = #{provider}")
    LlmModelConfig findByOwnerAndProvider(@Param("ownerUserId") Long ownerUserId,
                                          @Param("provider") String provider);

    @Select("SELECT * FROM llm_model_configs WHERE owner_user_id = #{ownerUserId} ORDER BY provider ASC")
    List<LlmModelConfig> findByOwner(Long ownerUserId);

    @Select("SELECT * FROM llm_model_configs WHERE owner_user_id = #{ownerUserId} " +
            "AND is_custom = 1 ORDER BY create_time ASC")
    List<LlmModelConfig> findCustomByOwner(Long ownerUserId);

    /** 更新 / 覆盖 API Key 凭据 */
    @Update("UPDATE llm_model_configs SET api_key_encrypted = #{apiKeyEncrypted}, " +
            "api_key_masked = #{apiKeyMasked}, status = #{status}, update_time = NOW() " +
            "WHERE id = #{id}")
    void updateCredential(LlmModelConfig config);

    @Update("UPDATE llm_model_configs SET provider_name = #{providerName}, " +
            "compatibility_type = #{compatibilityType}, base_url = #{baseUrl}, model_name = #{modelName}, " +
            "support_vision = #{supportVision}, context_window = #{contextWindow}, " +
            "max_output_tokens = #{maxOutputTokens}, api_key_encrypted = #{apiKeyEncrypted}, " +
            "api_key_masked = #{apiKeyMasked}, status = #{status}, temperature = #{temperature}, " +
            "top_p = #{topP}, last_test_time = NULL, last_test_result = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND owner_user_id = #{ownerUserId} AND is_custom = 1")
    int updateCustom(LlmModelConfig config);

    /** 更新测试结果 */
    @Update("UPDATE llm_model_configs SET status = #{status}, last_test_time = #{lastTestTime}, " +
            "last_test_result = #{lastTestResult}, update_time = NOW() WHERE id = #{id}")
    void updateTestResult(LlmModelConfig config);

    /** Do not let a slow test overwrite an edited key, URL, protocol or model. */
    @Update("UPDATE llm_model_configs SET status = #{result.status}, last_test_time = #{result.lastTestTime}, " +
            "last_test_result = #{result.lastTestResult}, update_time = NOW() " +
            "WHERE id = #{expected.id} AND owner_user_id = #{expected.ownerUserId} AND status <> 'disabled' " +
            "AND BINARY api_key_encrypted <=> BINARY #{expected.apiKeyEncrypted} " +
            "AND BINARY provider <=> BINARY #{expected.provider} AND is_custom <=> #{expected.isCustom} " +
            "AND BINARY compatibility_type <=> BINARY #{expected.compatibilityType} " +
            "AND BINARY base_url <=> BINARY #{expected.baseUrl} AND BINARY model_name <=> BINARY #{expected.modelName} " +
            "AND BINARY extra_headers_json <=> BINARY #{expected.extraHeadersJson}")
    int updateTestResultIfUnchanged(@Param("result") LlmModelConfig result, @Param("expected") LlmModelConfig expected);

    @Delete("DELETE FROM llm_model_configs WHERE owner_user_id = #{ownerUserId} AND provider = #{provider}")
    int deleteByOwnerAndProvider(@Param("ownerUserId") Long ownerUserId,
                                 @Param("provider") String provider);
}
