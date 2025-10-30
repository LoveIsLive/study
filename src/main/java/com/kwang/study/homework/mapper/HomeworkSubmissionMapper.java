package com.kwang.study.homework.mapper;


import com.kwang.study.homework.pojo.HomeworkSubmission;
import com.kwang.study.homework.pojo.HomeworkSubmissionDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface HomeworkSubmissionMapper {

    int insert(HomeworkSubmission submission);

    HomeworkSubmissionDetail findById(@Param("id") Long id);

    List<HomeworkSubmissionDetail> findAllByStudentId(@Param("studentId") Long studentId);

    List<HomeworkSubmissionDetail> findAllByHomeworkId(@Param("homeworkId") Long homeworkId);

    HomeworkSubmissionDetail findByHomeworkIdAndStudentId(@Param("homeworkId") Long homeworkId, @Param("studentId") Long studentId);

    List<Long> findIdsByHomeworkId(@Param("homeworkId") Long homeworkId);

    int deleteByHomeworkId(@Param("homeworkId") Long homeworkId);

    int batchUpdateStatus(@Param("ids") List<Long> ids, @Param("status") String status);

    int updateById(HomeworkSubmission submission);
}