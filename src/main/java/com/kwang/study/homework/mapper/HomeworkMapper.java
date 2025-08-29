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

    List<HomeworkDetail> findAllByTeacherId(@Param("teacherId") Long teacherId);

    List<HomeworkDetail> findAll();

    int deleteById(@Param("id") Long id);
}
