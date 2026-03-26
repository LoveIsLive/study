package com.kwang.study.auth.mapper;

import com.kwang.study.auth.pojo.Role;
import com.kwang.study.auth.pojo.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {
    // 注意：返回的都是去掉后缀的，用户角度的用户名
    User findById(@Param("id") Long id);

    User findByIdWithOrgInfo(Long id);

    User findByUsername(String username);

    User findByUsernameWithOrgInfo(String username);

    List<Role> findRolesByUserId(Long userId);

    /**
     * 新增用户
     * @param user 用户对象
     * @return 受影响的行数
     */
    int insertUser(User user);

    /**
     * 批量插入用户
     * @param userList 用户列表
     * @return 插入的行数
     */
    int insertUserBatch(@Param("userList") List<User> userList);

    /**
     * 修改用户信息
     * @param user 用户对象
     * @return 受影响的行数
     */
    int updateUser(User user);

    /**
     * 根据用户ID删除用户
     * @param userId 用户ID
     * @return 受影响的行数
     */
    int deleteUser(Long userId);

    /**
     * 为用户分配角色
     * @param userId 用户ID
     * @param roleName 角色名
     * @return 受影响的行数
     */
    int insertUserRoleByName(@Param("userId") Long userId, @Param("roleName") String roleName);

    /**
     * 根据用户ID删除该用户的所有角色关联
     * @param userId 用户ID
     * @return 受影响的行数
     */
    int deleteRolesByUserId(Long userId);

    /**
     * 删除用户的指定角色
     * @param userId 用户ID
     * @param roleName 角色名
     * @return 受影响的行数
     */
    int deleteUserRoleByName(@Param("userId") Long userId, @Param("roleName") String roleName);
}
