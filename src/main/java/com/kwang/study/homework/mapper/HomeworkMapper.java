package com.kwang.study.homework.mapper;


import com.kwang.study.homework.pojo.Homework;
import com.kwang.study.homework.pojo.HomeworkDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface HomeworkMapper {

    int insert(Homework homework);

    HomeworkDetail findById(@Param("id") Long id);

    List<HomeworkDetail> findAllByTeacherIdAndClassId(@Param("teacherId") Long teacherId, @Param("classId") Long classId);

    List<HomeworkDetail> findAllByClassId(@Param("classId") Long classId);

    List<HomeworkDetail> findAllByCourseId(@Param("courseId") Long courseId);

    int deleteById(@Param("id") Long id);

    int updateById(Homework homework);
}
