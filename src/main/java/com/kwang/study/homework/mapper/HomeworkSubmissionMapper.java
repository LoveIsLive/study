package com.kwang.study.homework.mapper;

import com.kwang.study.homework.pojo.HomeworkSubmission;
import com.kwang.study.homework.pojo.HomeworkSubmissionDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface HomeworkSubmissionMapper {

    /**
     * 插入新的作业提交记录 (需包含 class_id)
     */
    int insert(HomeworkSubmission submission);

    /**
     * 根据主键ID查询提交详情 (包含关联的作业、附件和学生姓名)
     */
    HomeworkSubmissionDetail findById(@Param("id") Long id);

    /**
     * 【核心修改】学生查看自己在当前激活班级下的所有提交记录
     */
    List<HomeworkSubmissionDetail> findAllByStudentIdAndClassId(@Param("studentId") Long studentId, @Param("classId") Long classId);

    /**
     * 教师查看某个作业的所有学生提交列表
     * (由于作业本身绑定了唯一班级，此查询隐含了班级隔离)
     */
    List<HomeworkSubmissionDetail> findAllByHomeworkId(@Param("homeworkId") Long homeworkId);

    /**
     * 根据作业ID和学生ID精确查找提交记录
     * 用于学生进入作业详情页时校验是否已提交
     */
    HomeworkSubmissionDetail findByHomeworkIdAndStudentId(@Param("homeworkId") Long homeworkId, @Param("studentId") Long studentId);

    /**
     * 获取指定作业的所有提交ID
     * 用于删除作业时关联删除物理附件
     */
    List<Long> findIdsByHomeworkId(@Param("homeworkId") Long homeworkId);

    /**
     * 根据作业ID物理删除所有提交记录 (删除作业时的级联操作)
     */
    int deleteByHomeworkId(@Param("homeworkId") Long homeworkId);

    /**
     * 批量更新提交状态
     * 例如：当教师修改了作业内容，将该作业下所有提交标记为“作业有更新”
     */
    int batchUpdateStatus(@Param("ids") List<Long> ids, @Param("status") String status);

    /**
     * 根据ID动态更新提交信息
     * 用于：学生修改重交、教师批改评分、AI生成评语
     */
    int updateById(HomeworkSubmission submission);
}