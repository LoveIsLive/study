package com.kwang.study.homework.mapper;


import com.kwang.study.homework.pojo.Homework;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface HomeworkMapper {

    int insert(Homework homework);

    Homework findById(@Param("id") Long id);

    List<Homework> findAllByTeacherId(@Param("teacherId") Long teacherId);

    List<Homework> findAll();

    int deleteById(@Param("id") Long id);
}
