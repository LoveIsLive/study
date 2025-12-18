package com.kwang.study.organization.mapper;

import com.kwang.study.organization.pojo.School;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SchoolMapper {
    /**
     * 查询所有学校
     */
    List<School> findAll();

    /**
     * 批量查询学校
     * @param ids 学校ID列表
     */
    List<School> selectBatchIds(@Param("ids") List<Long> ids);

    /**
     * 根据ID查询学校
     */
    School findById(Long id);

    /**
     * 根据名称查询学校（用于判重）
     */
    School findByName(@Param("name") String name);

    /**
     * 根据关键字模糊搜索学校
     */
    List<School> matchByName(@Param("key") String key);

    /**
     * 新增学校
     */
    int insert(School school);

    /**
     * 更新学校信息
     */
    int update(School school);

    /**
     * 删除学校
     */
    int deleteById(Long id);
}