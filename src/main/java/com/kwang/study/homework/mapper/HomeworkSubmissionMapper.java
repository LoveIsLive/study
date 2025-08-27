package com.kwang.study.homework.mapper;


import com.kwang.study.homework.pojo.HomeworkSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface HomeworkSubmissionMapper {

    int insert(HomeworkSubmission submission);

    HomeworkSubmission findById(@Param("id") Long id);

    List<HomeworkSubmission> findAllByStudentId(@Param("studentId") Long studentId);

    List<HomeworkSubmission> findAllByHomeworkId(@Param("homeworkId") Long homeworkId);

    HomeworkSubmission findByHomeworkIdAndStudentId(@Param("homeworkId") Long homeworkId, @Param("studentId") Long studentId);

    List<Long> findIdsByHomeworkId(@Param("homeworkId") Long homeworkId);

    int deleteByHomeworkId(@Param("homeworkId") Long homeworkId);
}