package com.kwang.study.organization.mapper;

import com.kwang.study.organization.pojo.ClassMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 班级成语数据访问层
 */
@Mapper
public interface ClassMemberMapper {

    /**
     * 批量向班级中添加成员
     *
     * @param members 成员列表
     * @return 影响的行数
     */
    int batchInsert(@Param("members") List<ClassMember> members);

    /**
     * 根据班级ID和用户ID列表，批量删除成员
     *
     * @param classId 班级ID
     * @param userIds 用户ID列表
     * @return 影响的行数
     */
    int deleteByClassIdAndUserIds(@Param("classId") Long classId, @Param("userIds") List<Long> userIds);

    /**
     * 根据班级ID删除所有成员
     *
     * @param classId 班级ID
     * @return 影响的行数
     */
    int deleteByClassId(Long classId);

    /**
     * 查询指定班级的所有成员（包含教师和学生）
     * 已对结果（用户名）进行处理
     * @param classId 班级ID
     * @return 成员列表，包含用户信息
     */
    List<ClassMember> findMembersByClassId(Long classId);

    /**
     * 查询指定班级某个角色的所有用户
     * 已对结果（用户名）进行处理
     * @param classId 班级ID
     * @param role 用户角色
     * @return 学生列表，包含用户信息
     */
    List<ClassMember> findUsersByClassIdAndRole(Long classId, String role);

    /**
     * 根据班级ID和用户ID查询成员，用于检查成员是否存在
     * @param classId 班级ID
     * @param userId 用户ID
     * @return 班级成员对象
     */
    ClassMember findByClassIdAndUserId(@Param("classId") Long classId, @Param("userId") Long userId);

    /**
     * 根据班级ID和用户ID列表，查询已存在的成员
     * @param classId 班级ID
     * @param userIds 用户ID列表
     * @return 已存在的成员列表
     */
    List<ClassMember> findByClassIdAndUserIds(@Param("classId") Long classId, @Param("userIds") List<Long> userIds);

    /**
     * 统计一个班级内有多少成员
     * @param classId 班级ID
     * @return 成员数量
     */
    Long countMemberByClassId(Long classId);
}