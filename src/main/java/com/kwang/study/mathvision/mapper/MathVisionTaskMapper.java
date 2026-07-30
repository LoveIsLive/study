package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MathVisionTaskMapper {

    @Insert("INSERT INTO mathvision_tasks(session_id, user_id, input_text, input_source_type, " +
            "input_assets_json, mode, output_target, status, current_stage, cancel_requested, selected_model_config_id, " +
            "provider_code, model_name, current_version, request_id, deleted, create_time, update_time) " +
            "VALUES(#{sessionId}, #{userId}, #{inputText}, #{inputSourceType}, #{inputAssetsJson}, " +
            "#{mode}, #{outputTarget}, #{status}, #{currentStage}, 0, #{selectedModelConfigId}, " +
            "#{providerCode}, #{modelName}, #{currentVersion}, #{requestId}, 0, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MathVisionTask task);

    @Select("SELECT * FROM mathvision_tasks WHERE id = #{id} AND deleted = 0")
    @Results(id = "MathVisionTaskResultMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "input_text", property = "inputText"),
            @Result(column = "input_source_type", property = "inputSourceType"),
            @Result(column = "input_assets_json", property = "inputAssetsJson"),
            @Result(column = "mode", property = "mode"),
            @Result(column = "output_target", property = "outputTarget"),
            @Result(column = "status", property = "status"),
            @Result(column = "current_stage", property = "currentStage"),
            @Result(column = "failed_stage", property = "failedStage"),
            @Result(column = "error_type", property = "errorType"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "selected_model_config_id", property = "selectedModelConfigId"),
            @Result(column = "provider_code", property = "providerCode"),
            @Result(column = "model_name", property = "modelName"),
            @Result(column = "current_version", property = "currentVersion"),
            @Result(column = "last_confirmed_stage", property = "lastConfirmedStage"),
            @Result(column = "auto_fix_count", property = "autoFixCount"),
            @Result(column = "cancel_requested", property = "cancelRequested"),
            @Result(column = "final_artifact_path", property = "finalArtifactPath"),
            @Result(column = "final_artifact_type", property = "finalArtifactType"),
            @Result(column = "square_share_id", property = "squareShareId"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "deleted", property = "deleted"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    MathVisionTask findById(Long id);

    @Select("SELECT * FROM mathvision_tasks WHERE id = #{id} AND user_id = #{userId} AND deleted = 1")
    @ResultMap("MathVisionTaskResultMap")
    MathVisionTask findDeletedById(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM mathvision_tasks WHERE session_id = #{sessionId} AND deleted = 0")
    @ResultMap("MathVisionTaskResultMap")
    MathVisionTask findBySessionId(String sessionId);

    @Select("SELECT * FROM mathvision_tasks WHERE request_id = #{requestId} AND deleted = 0")
    @ResultMap("MathVisionTaskResultMap")
    MathVisionTask findByRequestId(String requestId);

    @Select("<script>" +
            "SELECT * FROM mathvision_tasks " +
            "WHERE deleted = 0 AND status = 'queued' AND cancel_requested = 0 " +
            "AND current_stage IN " +
            "<foreach collection='stages' item='stage' open='(' separator=',' close=')'>#{stage}</foreach> " +
            "ORDER BY update_time ASC LIMIT #{limit}" +
            "</script>")
    @ResultMap("MathVisionTaskResultMap")
    List<MathVisionTask> findRunnableTasks(@Param("stages") List<String> stages,
                                           @Param("limit") int limit);

    /** 分页查询任务列表 (按 keyword/status/outputTarget 过滤) */
    @Select("<script>" +
            "SELECT t.*, p.id AS square_share_id FROM mathvision_tasks t " +
            "LEFT JOIN chat_sessions s ON t.session_id = s.session_id " +
            "LEFT JOIN mathvision_square_posts p ON p.task_id = t.id AND p.version = t.current_version " +
            "WHERE t.user_id = #{userId} AND t.deleted = 0 " +
            "<if test='keyword != null and keyword != \"\"'>AND s.title LIKE CONCAT('%', #{keyword}, '%') </if>" +
            "<if test='status != null and status != \"\"'>AND t.status = #{status} </if>" +
            "<if test='outputTarget != null and outputTarget != \"\"'>AND t.output_target = #{outputTarget} </if>" +
            "ORDER BY t.update_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    @ResultMap("MathVisionTaskResultMap")
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

    /** 分页查询当前用户回收站中的任务。 */
    @Select("<script>" +
            "SELECT t.* FROM mathvision_tasks t " +
            "LEFT JOIN chat_sessions s ON t.session_id = s.session_id " +
            "WHERE t.user_id = #{userId} AND t.deleted = 1 " +
            "<if test='keyword != null and keyword != \"\"'>AND s.title LIKE CONCAT('%', #{keyword}, '%') </if>" +
            "ORDER BY t.update_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    @ResultMap("MathVisionTaskResultMap")
    List<MathVisionTask> pageDeletedList(@Param("userId") Long userId,
                                         @Param("keyword") String keyword,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    @Select("<script>" +
            "SELECT COUNT(*) FROM mathvision_tasks t " +
            "LEFT JOIN chat_sessions s ON t.session_id = s.session_id " +
            "WHERE t.user_id = #{userId} AND t.deleted = 1 " +
            "<if test='keyword != null and keyword != \"\"'>AND s.title LIKE CONCAT('%', #{keyword}, '%') </if>" +
            "</script>")
    long countDeletedList(@Param("userId") Long userId,
                          @Param("keyword") String keyword);

    /** 更新任务状态 / 阶段 / 失败信息 */
    @Update("UPDATE mathvision_tasks SET status = #{status}, current_stage = #{currentStage}, " +
            "failed_stage = #{failedStage}, error_type = #{errorType}, error_message = #{errorMessage}, " +
            "update_time = NOW() WHERE id = #{id}")
    void updateStatus(MathVisionTask task);

    @Update("UPDATE mathvision_tasks SET status = 'running', cancel_requested = 0, " +
            "failed_stage = NULL, error_type = NULL, error_message = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND deleted = 0 AND status = 'queued' AND cancel_requested = 0")
    int claimRunnableTask(Long id);

    @Update("<script>" +
            "UPDATE mathvision_tasks SET status = 'queued', current_stage = #{currentStage}, " +
            "cancel_requested = 0, failed_stage = NULL, error_type = NULL, error_message = NULL, " +
            "<if test='lastConfirmedStage != null'>last_confirmed_stage = #{lastConfirmedStage},</if> " +
            "update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 " +
            "AND status IN ('created', 'failed', 'waiting_confirm', 'canceled')" +
            "</script>")
    int queueTaskForRun(@Param("id") Long id,
                        @Param("userId") Long userId,
                        @Param("currentStage") String currentStage,
                        @Param("lastConfirmedStage") String lastConfirmedStage);

    @Update("UPDATE mathvision_tasks SET current_version = #{currentVersion}, status = 'queued', " +
            "current_stage = #{currentStage}, last_confirmed_stage = #{lastConfirmedStage}, " +
            "cancel_requested = 0, failed_stage = NULL, error_type = NULL, error_message = NULL, " +
            "final_artifact_path = NULL, final_artifact_type = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 " +
            "AND status NOT IN ('queued', 'running')")
    int queueAutoEdit(@Param("id") Long id,
                      @Param("userId") Long userId,
                      @Param("currentVersion") Integer currentVersion,
                      @Param("currentStage") String currentStage,
                      @Param("lastConfirmedStage") String lastConfirmedStage);

    @Update("UPDATE mathvision_tasks SET current_version = #{currentVersion}, status = 'queued', " +
            "current_stage = #{currentStage}, last_confirmed_stage = #{lastConfirmedStage}, " +
            "cancel_requested = 0, failed_stage = NULL, error_type = NULL, error_message = NULL, " +
            "final_artifact_path = NULL, final_artifact_type = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 " +
            "AND status NOT IN ('queued', 'running')")
    int queueRegenerateVersion(@Param("id") Long id,
                               @Param("userId") Long userId,
                               @Param("currentVersion") Integer currentVersion,
                               @Param("currentStage") String currentStage,
                               @Param("lastConfirmedStage") String lastConfirmedStage);

    @Update("UPDATE mathvision_tasks SET current_version = #{currentVersion}, status = 'queued', " +
            "current_stage = #{currentStage}, last_confirmed_stage = #{lastConfirmedStage}, " +
            "cancel_requested = 0, failed_stage = NULL, error_type = NULL, error_message = NULL, " +
            "final_artifact_path = NULL, final_artifact_type = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'failed'")
    int queueRetryVersion(@Param("id") Long id,
                          @Param("userId") Long userId,
                          @Param("currentVersion") Integer currentVersion,
                          @Param("currentStage") String currentStage,
                          @Param("lastConfirmedStage") String lastConfirmedStage);

    @Update("UPDATE mathvision_tasks SET status = 'waiting_confirm', current_stage = #{currentStage}, " +
            "failed_stage = NULL, error_type = NULL, error_message = NULL, cancel_requested = 0, update_time = NOW() " +
            "WHERE id = #{id}")
    void markWaitingConfirm(@Param("id") Long id, @Param("currentStage") String currentStage);

    @Update("UPDATE mathvision_tasks SET status = 'queued', current_stage = #{currentStage}, " +
            "failed_stage = NULL, error_type = NULL, error_message = NULL, cancel_requested = 0, update_time = NOW() " +
            "WHERE id = #{id}")
    void queueNextStage(@Param("id") Long id, @Param("currentStage") String currentStage);

    @Update("UPDATE mathvision_tasks SET status = 'completed', current_stage = 'completed', " +
            "failed_stage = NULL, error_type = NULL, error_message = NULL, cancel_requested = 0, update_time = NOW() " +
            "WHERE id = #{id}")
    void markCompleted(Long id);

    @Update("UPDATE mathvision_tasks SET status = 'failed', failed_stage = #{failedStage}, " +
            "error_type = #{errorType}, error_message = #{errorMessage}, cancel_requested = 0, update_time = NOW() " +
            "WHERE id = #{id}")
    void markFailed(@Param("id") Long id,
                    @Param("failedStage") String failedStage,
                    @Param("errorType") String errorType,
                    @Param("errorMessage") String errorMessage);

    @Update("UPDATE mathvision_tasks SET status = 'canceled', cancel_requested = 0, update_time = NOW() " +
            "WHERE id = #{id}")
    void markCanceled(Long id);

    @Update("UPDATE mathvision_tasks SET status = 'canceled', cancel_requested = 0, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 " +
            "AND status IN ('created', 'queued', 'waiting_confirm')")
    int cancelIdleTask(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE mathvision_tasks SET cancel_requested = 1, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'running'")
    int requestCancelRunning(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE mathvision_tasks SET status = 'failed', failed_stage = current_stage, " +
            "error_type = 'workflow_error', error_message = '服务重启导致任务中断, 请重试当前阶段', " +
            "cancel_requested = 0, update_time = NOW() " +
            "WHERE deleted = 0 AND status = 'running'")
    int resetRunningToFailed();

    /** 更新当前激活版本 */
    @Update("UPDATE mathvision_tasks SET current_version = #{currentVersion}, update_time = NOW() WHERE id = #{id}")
    void updateCurrentVersion(@Param("id") Long id, @Param("currentVersion") Integer currentVersion);

    @Update("UPDATE mathvision_tasks SET mode = #{mode}, selected_model_config_id = #{selectedModelConfigId}, " +
            "provider_code = #{providerCode}, model_name = #{modelName}, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    int updateRuntimeSettings(@Param("id") Long id,
                              @Param("userId") Long userId,
                              @Param("mode") String mode,
                              @Param("selectedModelConfigId") Long selectedModelConfigId,
                              @Param("providerCode") String providerCode,
                              @Param("modelName") String modelName);

    /** 激活历史版本后同步任务的用户可见状态。 */
    @Update("UPDATE mathvision_tasks SET current_version = #{currentVersion}, status = #{status}, " +
            "current_stage = #{currentStage}, last_confirmed_stage = #{lastConfirmedStage}, " +
            "failed_stage = #{failedStage}, error_type = #{errorType}, error_message = #{errorMessage}, cancel_requested = 0, " +
            "final_artifact_path = #{finalArtifactPath}, final_artifact_type = #{finalArtifactType}, " +
            "update_time = NOW() WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 " +
            "AND status NOT IN ('queued', 'running')")
    int activateVersionState(@Param("id") Long id,
                             @Param("userId") Long userId,
                             @Param("currentVersion") Integer currentVersion,
                             @Param("status") String status,
                             @Param("currentStage") String currentStage,
                             @Param("lastConfirmedStage") String lastConfirmedStage,
                             @Param("failedStage") String failedStage,
                             @Param("errorType") String errorType,
                             @Param("errorMessage") String errorMessage,
                             @Param("finalArtifactPath") String finalArtifactPath,
                             @Param("finalArtifactType") String finalArtifactType);

    @Update("UPDATE mathvision_tasks SET status = #{status}, current_stage = #{currentStage}, " +
            "last_confirmed_stage = #{lastConfirmedStage}, failed_stage = NULL, error_type = NULL, " +
            "error_message = NULL, final_artifact_path = NULL, final_artifact_type = NULL, " +
            "cancel_requested = 0, update_time = NOW() WHERE id = #{id}")
    void updateManualEditState(@Param("id") Long id,
                               @Param("status") String status,
                               @Param("currentStage") String currentStage,
                               @Param("lastConfirmedStage") String lastConfirmedStage);

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

    /** 移入回收站。运行中或排队中的任务必须先取消。 */
    @Update("UPDATE mathvision_tasks SET deleted = 1, request_id = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 " +
            "AND status NOT IN ('queued', 'running')")
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE mathvision_tasks SET deleted = 0, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 1")
    int restore(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("DELETE FROM mathvision_tasks WHERE id = #{id} AND user_id = #{userId} AND deleted = 1")
    int hardDelete(@Param("id") Long id, @Param("userId") Long userId);
}
