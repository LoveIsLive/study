package com.kwang.study.organization.mapper;

import com.kwang.study.organization.pojo.CourseGuest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseGuestMapper {

    /**
     * 批量插入访客课程授权
     */
    int batchInsert(@Param("guests") List<CourseGuest> guests);

    /**
     * 删除某个访客在某班级下的所有课程授权 (修改权限或踢出班级时使用)
     */
    int deleteByClassIdAndUserId(@Param("classId") Long classId, @Param("userId") Long userId);

    /**
     * 批量删除多个用户在某班级下的课程授权 (踢出班级时使用)
     */
    int deleteByClassIdAndUserIds(@Param("classId") Long classId, @Param("userIds") List<Long> userIds);

    /**
     * 删除课程时，级联清理所有访客对此课程的权限
     */
    int deleteByCourseId(@Param("courseId") Long courseId);

    /**
     * 查询某用户在某班级下被授权的所有课程ID (配合 MyBatis 嵌套查询使用)
     */
    List<Long> findCourseIdsByClassAndUser(@Param("classId") Long classId, @Param("userId") Long userId);
}