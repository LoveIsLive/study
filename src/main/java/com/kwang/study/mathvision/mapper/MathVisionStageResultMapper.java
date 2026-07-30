package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MathVisionStageResultMapper {

    @Insert("INSERT INTO mathvision_stage_results(task_id, artifact_id, session_id, user_id, " +
            "stage, version, result_json, create_time, update_time) " +
            "VALUES(#{taskId}, #{artifactId}, #{sessionId}, #{userId}, #{stage}, #{version}, " +
            "#{resultJson}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MathVisionStageResult result);

    @Select("SELECT * FROM mathvision_stage_results WHERE artifact_id = #{artifactId}")
    MathVisionStageResult findByArtifactId(Long artifactId);

    @Select("SELECT * FROM mathvision_stage_results WHERE task_id = #{taskId} AND stage = #{stage} AND version = #{version}")
    MathVisionStageResult findByTaskStageVersion(@Param("taskId") Long taskId,
                                                 @Param("stage") String stage,
                                                 @Param("version") Integer version);

    @Delete("DELETE FROM mathvision_stage_results WHERE task_id = #{taskId}")
    int deleteByTaskId(Long taskId);

    @Update("UPDATE mathvision_stage_results SET result_json = #{resultJson}, update_time = NOW() WHERE id = #{id}")
    void updateResultJson(MathVisionStageResult result);
}
