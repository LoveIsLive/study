package com.kwang.study.organization.mapper;

import com.kwang.study.organization.pojo.SchoolMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SchoolMemberMapper {

    /**
     * 单条插入
     */
    int insert(SchoolMember member);

    /**
     * 批量插入
     */
    int batchInsert(@Param("members") List<SchoolMember> members);

    /**
     * 根据用户ID查询成员信息
     */
    SchoolMember findByUserId(Long userId);

    /**
     * 查询指定学校的所有成员（关联查询User信息）
     * 已对用户名进行处理
     */
    List<SchoolMember> findMembersBySchoolId(Long schoolId);

    /**
     * 根据用户ID删除
     */
    int deleteByUserId(Long userId);

    /**
     * 批量删除指定学校下的成员
     */
    int deleteBySchoolIdAndUserIds(@Param("schoolId") Long schoolId, @Param("userIds") List<Long> userIds);
}