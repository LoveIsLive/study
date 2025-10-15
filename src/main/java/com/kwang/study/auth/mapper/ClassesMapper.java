package com.kwang.study.auth.mapper;

import com.kwang.study.auth.pojo.Classes;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ClassesMapper {
    /**
     * 查询所有班级信息
     *
     * @return 班级列表
     */
    List<Classes> findAll();

    /**
     * 新增一个班级
     *
     * @param classes 待新增的班级对象 (只需要name属性)
     * @return 影响的行数, 成功应为1
     */
    int insert(Classes classes);

    /**
     * 修改一个班级的信息 (目前仅支持修改名称)
     *
     * @param classes 包含ID和新名称的班级对象
     * @return 影响的行数, 成功应为1
     */
    int update(Classes classes);

    /**
     * 根据ID删除一个班级
     *
     * @param id 要删除的班级ID
     * @return 影响的行数, 成功应为1
     */
    int deleteById(Long id);
}
