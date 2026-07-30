package com.kwang.study.mathvision.mapper;

import com.kwang.study.mathvision.pojo.MathVisionSquarePost;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MathVisionSquarePostMapper {

    @Insert("INSERT INTO mathvision_square_posts(task_id, version, load_count, create_time) " +
            "VALUES(#{taskId}, #{version}, 0, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MathVisionSquarePost post);

    @Select("SELECT p.id, p.task_id, p.version, p.load_count, p.create_time, " +
            "t.user_id AS owner_user_id, " +
            "COALESCE(NULLIF(s.title, ''), 'MathVision 教学成果') AS title, " +
            "LEFT(t.input_text, 500) AS summary, t.output_target, u.username AS author_name, " +
            "JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.artifactPath')) AS artifact_path, " +
            "JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.artifactType')) AS artifact_type " +
            "FROM mathvision_square_posts p " +
            "INNER JOIN mathvision_tasks t ON t.id = p.task_id AND t.deleted = 0 " +
            "INNER JOIN mathvision_versions v ON v.task_id = p.task_id AND v.version = p.version " +
            "LEFT JOIN mathvision_artifacts a ON a.task_id = p.task_id " +
            "AND a.stage = 'render_result' AND a.version = v.rr_version " +
            "LEFT JOIN chat_sessions s ON s.session_id = t.session_id " +
            "LEFT JOIN users u ON u.id = t.user_id " +
            "WHERE p.id = #{id}")
    @Results(id = "MathVisionSquarePostResultMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "version", property = "version"),
            @Result(column = "load_count", property = "loadCount"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "owner_user_id", property = "ownerUserId"),
            @Result(column = "title", property = "title"),
            @Result(column = "summary", property = "summary"),
            @Result(column = "output_target", property = "outputTarget"),
            @Result(column = "artifact_path", property = "artifactPath"),
            @Result(column = "artifact_type", property = "artifactType"),
            @Result(column = "author_name", property = "authorName")
    })
    MathVisionSquarePost findById(Long id);

    @Select("SELECT p.id, p.task_id, p.version, p.load_count, p.create_time, " +
            "t.user_id AS owner_user_id, " +
            "COALESCE(NULLIF(s.title, ''), 'MathVision 教学成果') AS title, " +
            "LEFT(t.input_text, 500) AS summary, t.output_target, u.username AS author_name, " +
            "JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.artifactPath')) AS artifact_path, " +
            "JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.artifactType')) AS artifact_type " +
            "FROM mathvision_square_posts p " +
            "INNER JOIN mathvision_tasks t ON t.id = p.task_id AND t.deleted = 0 " +
            "INNER JOIN mathvision_versions v ON v.task_id = p.task_id AND v.version = p.version " +
            "LEFT JOIN mathvision_artifacts a ON a.task_id = p.task_id " +
            "AND a.stage = 'render_result' AND a.version = v.rr_version " +
            "LEFT JOIN chat_sessions s ON s.session_id = t.session_id " +
            "LEFT JOIN users u ON u.id = t.user_id " +
            "WHERE p.task_id = #{taskId} AND p.version = #{version} LIMIT 1")
    @org.apache.ibatis.annotations.ResultMap("MathVisionSquarePostResultMap")
    MathVisionSquarePost findByTaskVersion(@Param("taskId") Long taskId,
                                           @Param("version") Integer version);

    @Select("<script>" +
            "SELECT p.id, p.task_id, p.version, p.load_count, p.create_time, " +
            "t.user_id AS owner_user_id, " +
            "COALESCE(NULLIF(s.title, ''), 'MathVision 教学成果') AS title, " +
            "LEFT(t.input_text, 500) AS summary, t.output_target, u.username AS author_name, " +
            "JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.artifactPath')) AS artifact_path, " +
            "JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.artifactType')) AS artifact_type " +
            "FROM mathvision_square_posts p " +
            "INNER JOIN mathvision_tasks t ON t.id = p.task_id AND t.deleted = 0 " +
            "INNER JOIN mathvision_versions v ON v.task_id = p.task_id AND v.version = p.version " +
            "LEFT JOIN mathvision_artifacts a ON a.task_id = p.task_id " +
            "AND a.stage = 'render_result' AND a.version = v.rr_version " +
            "LEFT JOIN chat_sessions s ON s.session_id = t.session_id " +
            "LEFT JOIN users u ON u.id = t.user_id " +
            "WHERE 1 = 1 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (s.title LIKE CONCAT('%', #{keyword}, '%') OR t.input_text LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='outputTarget != null and outputTarget != \"\"'>AND t.output_target = #{outputTarget} </if>" +
            "<if test='ownerUserId != null'>AND t.user_id = #{ownerUserId} </if>" +
            "ORDER BY p.create_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    @org.apache.ibatis.annotations.ResultMap("MathVisionSquarePostResultMap")
    List<MathVisionSquarePost> page(@Param("keyword") String keyword,
                                    @Param("outputTarget") String outputTarget,
                                    @Param("ownerUserId") Long ownerUserId,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    @Select("<script>" +
            "SELECT COUNT(*) FROM mathvision_square_posts p " +
            "INNER JOIN mathvision_tasks t ON t.id = p.task_id AND t.deleted = 0 " +
            "LEFT JOIN chat_sessions s ON s.session_id = t.session_id " +
            "WHERE 1 = 1 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (s.title LIKE CONCAT('%', #{keyword}, '%') OR t.input_text LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='outputTarget != null and outputTarget != \"\"'>AND t.output_target = #{outputTarget} </if>" +
            "<if test='ownerUserId != null'>AND t.user_id = #{ownerUserId} </if>" +
            "</script>")
    long count(@Param("keyword") String keyword,
               @Param("outputTarget") String outputTarget,
               @Param("ownerUserId") Long ownerUserId);

    @Update("UPDATE mathvision_square_posts SET load_count = load_count + 1 WHERE id = #{id}")
    int incrementLoadCount(Long id);

    @Delete("DELETE p FROM mathvision_square_posts p " +
            "INNER JOIN mathvision_tasks t ON t.id = p.task_id " +
            "WHERE p.id = #{id} AND t.user_id = #{ownerUserId}")
    int deleteOwned(@Param("id") Long id, @Param("ownerUserId") Long ownerUserId);
}
