package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MathVisionTaskMapper {

    @Insert("INSERT INTO mathvision_tasks(session_id, user_id, input_text, input_source_type, " +
            "input_assets_json, mode, output_target, status, current_stage, selected_model_config_id, " +
            "provider_code, model_name, current_version, request_id, deleted, create_time, update_time) " +
            "VALUES(#{sessionId}, #{userId}, #{inputText}, #{inputSourceType}, #{inputAssetsJson}, " +
            "#{mode}, #{outputTarget}, #{status}, #{currentStage}, #{selectedModelConfigId}, " +
            "#{providerCode}, #{modelName}, #{currentVersion}, #{requestId}, 0, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MathVisionTask task);

    @Select("SELECT * FROM mathvision_tasks WHERE id = #{id} AND deleted = 0")
    MathVisionTask findById(Long id);

    @Select("SELECT * FROM mathvision_tasks WHERE session_id = #{sessionId} AND deleted = 0")
    MathVisionTask findBySessionId(String sessionId);

    @Select("SELECT * FROM mathvision_tasks WHERE request_id = #{requestId} AND deleted = 0")
    MathVisionTask findByRequestId(String requestId);

    /** 分页查询任务列表 (按 keyword/status/outputTarget 过滤) */
    @Select("<script>" +
            "SELECT t.* FROM mathvision_tasks t " +
            "LEFT JOIN chat_sessions s ON t.session_id = s.session_id " +
            "WHERE t.user_id = #{userId} AND t.deleted = 0 " +
            "<if test='keyword != null and keyword != \"\"'>AND s.title LIKE CONCAT('%', #{keyword}, '%') </if>" +
            "<if test='status != null and status != \"\"'>AND t.status = #{status} </if>" +
            "<if test='outputTarget != null and outputTarget != \"\"'>AND t.output_target = #{outputTarget} </if>" +
            "ORDER BY t.update_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<MathVisionTask> pageList(@Param("userId") Long userId,
                                  @Param("keyword") String keyword,
                                  @Param("status") String status,
                                  @Param("outputTarget") String outputTarget,
                                  @Param("offset") int offset,
                                  @Param("size") int size);

    @Select("<script>" +
            "SELECT COUNT(*) FROM mathvision_tasks t " +
            "LEFT JOIN chat_sessions s ON t.session_id = s.session_id " +
            "WHERE t.user_id = #{userId} AND t.deleted = 0 " +
            "<if test='keyword != null and keyword != \"\"'>AND s.title LIKE CONCAT('%', #{keyword}, '%') </if>" +
            "<if test='status != null and status != \"\"'>AND t.status = #{status} </if>" +
            "<if test='outputTarget != null and outputTarget != \"\"'>AND t.output_target = #{outputTarget} </if>" +
            "</script>")
    long countList(@Param("userId") Long userId,
                   @Param("keyword") String keyword,
                   @Param("status") String status,
                   @Param("outputTarget") String outputTarget);

    /** 更新任务状态 / 阶段 / 失败信息 */
    @Update("UPDATE mathvision_tasks SET status = #{status}, current_stage = #{currentStage}, " +
            "failed_stage = #{failedStage}, error_type = #{errorType}, error_message = #{errorMessage}, " +
            "update_time = NOW() WHERE id = #{id}")
    void updateStatus(MathVisionTask task);

    /** 更新当前激活版本 */
    @Update("UPDATE mathvision_tasks SET current_version = #{currentVersion}, update_time = NOW() WHERE id = #{id}")
    void updateCurrentVersion(@Param("id") Long id, @Param("currentVersion") Integer currentVersion);

    /** 更新最近确认阶段 */
    @Update("UPDATE mathvision_tasks SET last_confirmed_stage = #{lastConfirmedStage}, update_time = NOW() WHERE id = #{id}")
    void updateLastConfirmedStage(@Param("id") Long id, @Param("lastConfirmedStage") String lastConfirmedStage);

    /** 更新最终产物 */
    @Update("UPDATE mathvision_tasks SET final_artifact_path = #{finalArtifactPath}, " +
            "final_artifact_type = #{finalArtifactType}, update_time = NOW() WHERE id = #{id}")
    void updateFinalArtifact(MathVisionTask task);

    /** 自动修复次数 +1 */
    @Update("UPDATE mathvision_tasks SET auto_fix_count = auto_fix_count + 1, update_time = NOW() WHERE id = #{id}")
    void incrAutoFixCount(Long id);

    /** 软删除 */
    @Update("UPDATE mathvision_tasks SET deleted = 1, update_time = NOW() WHERE id = #{id}")
    void softDelete(Long id);
}
