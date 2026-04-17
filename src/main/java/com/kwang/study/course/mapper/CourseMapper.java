// course/mapper/CourseMapper.java
package com.kwang.study.course.mapper;

import com.kwang.study.course.pojo.Course;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface CourseMapper {
    int insert(Course course);
    int update(Course course);
    int deleteById(Long id);
    Course findById(Long id);
    List<Course> findAllByClassId(Long classId);
}