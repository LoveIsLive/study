package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.LlmModelConfig;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface LlmModelConfigMapper {

    @Insert("INSERT INTO llm_model_configs(owner_user_id, provider, base_url, api_key_encrypted, " +
            "api_key_masked, status, temperature, enable_thinking, top_p, extra_headers_json, " +
            "models_cache_json, last_sync_time, create_time, update_time) " +
            "VALUES(#{ownerUserId}, #{provider}, #{baseUrl}, #{apiKeyEncrypted}, #{apiKeyMasked}, " +
            "#{status}, #{temperature}, #{enableThinking}, #{topP}, #{extraHeadersJson}, " +
            "#{modelsCacheJson}, #{lastSyncTime}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LlmModelConfig config);

    @Select("SELECT * FROM llm_model_configs WHERE id = #{id}")
    LlmModelConfig findById(Long id);

    @Select("SELECT * FROM llm_model_configs WHERE owner_user_id = #{ownerUserId} AND provider = #{provider}")
    LlmModelConfig findByOwnerAndProvider(@Param("ownerUserId") Long ownerUserId,
                                          @Param("provider") String provider);

    @Select("SELECT * FROM llm_model_configs WHERE owner_user_id = #{ownerUserId} ORDER BY provider ASC")
    List<LlmModelConfig> findByOwner(Long ownerUserId);

    /** 更新 / 覆盖 API Key 凭据 */
    @Update("UPDATE llm_model_configs SET api_key_encrypted = #{apiKeyEncrypted}, " +
            "api_key_masked = #{apiKeyMasked}, status = #{status}, update_time = NOW() " +
            "WHERE id = #{id}")
    void updateCredential(LlmModelConfig config);

    /** 更新测试结果 */
    @Update("UPDATE llm_model_configs SET status = #{status}, last_test_time = #{lastTestTime}, " +
            "last_test_result = #{lastTestResult}, update_time = NOW() WHERE id = #{id}")
    void updateTestResult(LlmModelConfig config);

    /** 更新模型列表缓存 */
    @Update("UPDATE llm_model_configs SET models_cache_json = #{modelsCacheJson}, " +
            "last_sync_time = #{lastSyncTime}, update_time = NOW() WHERE id = #{id}")
    void updateModelsCache(LlmModelConfig config);

    @Delete("DELETE FROM llm_model_configs WHERE owner_user_id = #{ownerUserId} AND provider = #{provider}")
    int deleteByOwnerAndProvider(@Param("ownerUserId") Long ownerUserId,
                                 @Param("provider") String provider);
}
