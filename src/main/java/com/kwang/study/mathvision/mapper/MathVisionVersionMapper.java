package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.MathVisionVersion;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MathVisionVersionMapper {

    @Insert("INSERT INTO mathvision_versions(task_id, version, base_version, pn_version, rg_version, " +
            "vs_version, cg_version, rr_version, branch_stage, change_source, change_summary, " +
            "workflow_summary_json, is_current, create_time, update_time) " +
            "VALUES(#{taskId}, #{version}, #{baseVersion}, #{pnVersion}, #{rgVersion}, #{vsVersion}, " +
            "#{cgVersion}, #{rrVersion}, #{branchStage}, #{changeSource}, #{changeSummary}, " +
            "#{workflowSummaryJson}, #{isCurrent}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MathVisionVersion version);

    @Select("SELECT * FROM mathvision_versions WHERE task_id = #{taskId} AND version = #{version}")
    MathVisionVersion findByTaskVersion(@Param("taskId") Long taskId, @Param("version") Integer version);

    @Select("SELECT * FROM mathvision_versions WHERE task_id = #{taskId} AND is_current = 1 LIMIT 1")
    MathVisionVersion findCurrent(Long taskId);

    @Select("SELECT * FROM mathvision_versions WHERE task_id = #{taskId} ORDER BY version ASC")
    List<MathVisionVersion> findByTask(Long taskId);

    @Select("SELECT MAX(version) FROM mathvision_versions WHERE task_id = #{taskId}")
    Integer findMaxVersion(Long taskId);

    /** 更新某个任务版本对各阶段的指针 (NULL 表示该阶段下游失效, 待重新生成) */
    @Update("UPDATE mathvision_versions SET pn_version = #{pnVersion}, rg_version = #{rgVersion}, " +
            "vs_version = #{vsVersion}, cg_version = #{cgVersion}, rr_version = #{rrVersion}, " +
            "update_time = NOW() WHERE id = #{id}")
    void updateStagePointers(MathVisionVersion version);

    /** 单独更新某阶段指针, 阶段产物落库后回填 */
    @Update("<script>" +
            "UPDATE mathvision_versions SET update_time = NOW(), " +
            "<choose>" +
            "<when test='stage == \"problem_normalization\"'>pn_version = #{stageVersion}</when>" +
            "<when test='stage == \"reasoning_graph\"'>rg_version = #{stageVersion}</when>" +
            "<when test='stage == \"visual_storyboard\"'>vs_version = #{stageVersion}</when>" +
            "<when test='stage == \"code_generation\"'>cg_version = #{stageVersion}</when>" +
            "<when test='stage == \"render_result\"'>rr_version = #{stageVersion}</when>" +
            "</choose>" +
            " WHERE task_id = #{taskId} AND version = #{version}" +
            "</script>")
    void updateStagePointer(@Param("taskId") Long taskId,
                            @Param("version") Integer version,
                            @Param("stage") String stage,
                            @Param("stageVersion") Integer stageVersion);

    /** 清除当前任务的 is_current 标记 */
    @Update("UPDATE mathvision_versions SET is_current = 0, update_time = NOW() WHERE task_id = #{taskId}")
    void clearCurrent(Long taskId);

    /** 设置指定任务版本为当前版本 */
    @Update("UPDATE mathvision_versions SET is_current = 1, update_time = NOW() WHERE task_id = #{taskId} AND version = #{version}")
    void setCurrent(@Param("taskId") Long taskId, @Param("version") Integer version);
}
