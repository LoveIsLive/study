package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MathVisionArtifactMapper {

    @Insert("INSERT INTO mathvision_artifacts(task_id, session_id, user_id, stage, version, " +
            "base_version, artifact_json, change_source, change_summary, create_time, update_time) " +
            "VALUES(#{taskId}, #{sessionId}, #{userId}, #{stage}, #{version}, #{baseVersion}, " +
            "#{artifactJson}, #{changeSource}, #{changeSummary}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MathVisionArtifact artifact);

    @Select("SELECT * FROM mathvision_artifacts WHERE id = #{id}")
    MathVisionArtifact findById(Long id);

    /** 取某任务某阶段的指定版本 */
    @Select("SELECT * FROM mathvision_artifacts WHERE task_id = #{taskId} AND stage = #{stage} AND version = #{version}")
    MathVisionArtifact findByTaskStageVersion(@Param("taskId") Long taskId,
                                              @Param("stage") String stage,
                                              @Param("version") Integer version);

    /** 取某任务某阶段当前最大版本号 (用于阶段级版本自增); 无记录返回 null */
    @Select("SELECT MAX(version) FROM mathvision_artifacts WHERE task_id = #{taskId} AND stage = #{stage}")
    Integer findMaxVersion(@Param("taskId") Long taskId, @Param("stage") String stage);

    /** 取某任务某阶段所有版本 */
    @Select("SELECT * FROM mathvision_artifacts WHERE task_id = #{taskId} AND stage = #{stage} ORDER BY version ASC")
    List<MathVisionArtifact> findByTaskStage(@Param("taskId") Long taskId, @Param("stage") String stage);

    @Delete("DELETE FROM mathvision_artifacts WHERE task_id = #{taskId}")
    int deleteByTaskId(Long taskId);

    /** 原地更新产物内容 (仅未确认的私有行可用, 见 Copy-on-Write 不变式) */
    @Update("UPDATE mathvision_artifacts SET artifact_json = #{artifactJson}, " +
            "change_source = #{changeSource}, change_summary = #{changeSummary}, update_time = NOW() " +
            "WHERE id = #{id}")
    void updateArtifactJson(MathVisionArtifact artifact);
}
