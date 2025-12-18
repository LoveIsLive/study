package com.kwang.study.organization.mapper;

import com.kwang.study.organization.dto.result.ClassDetailDTO;
import com.kwang.study.organization.pojo.Classes;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ClassesMapper {
    // --- 基本信息查询 ---

    /**
     * 查询指定学校下的所有班级
     */
    List<Classes> findAllBySchoolId(@Param("schoolId") Long schoolId);

    /**
     * 根据ID查询班级 (主键查询，全局唯一)
     */
    Classes findById(Long id);

    /**
     * 批量查询班级
     * @param ids 班级ID列表
     * @param schoolId 学校ID过滤 (可为null，为null时不通过schoolId过滤)
     */
    List<Classes> selectBatchIds(@Param("ids") List<Long> ids, @Param("schoolId") Long schoolId);

    /**
     * 根据名称和学校ID查询班级 (用于判重)
     */
    Classes findByNameAndSchoolId(@Param("name") String name, @Param("schoolId") Long schoolId);

    /**
     * 根据关键字模糊搜索指定学校下的班级
     */
    List<Classes> matchByNameAndSchoolId(@Param("key") String key, @Param("schoolId") Long schoolId);

    // --- 详细信息查询 (包含成员计数) ---

    /**
     * 查询指定学校下所有班级的详细信息
     */
    List<ClassDetailDTO> findAllDetailBySchoolId(@Param("schoolId") Long schoolId);

    /**
     * 根据ID查询班级详细信息
     */
    ClassDetailDTO findClassDetailById(Long id);

    /**
     * 根据名称和学校ID查询班级详细信息
     */
    ClassDetailDTO findClassDetailByNameAndSchoolId(@Param("name") String name, @Param("schoolId") Long schoolId);

    /**
     * 根据关键字模糊搜索指定学校下的班级详细信息
     */
    List<ClassDetailDTO> matchClassDetailByNameAndSchoolId(@Param("key") String key, @Param("schoolId") Long schoolId);

    // --- 写操作 ---

    /**
     * 插入班级 (必须包含 school_id)
     */
    int insert(Classes classes);

    /**
     * 更新班级信息
     */
    int update(Classes classes);

    /**
     * 删除班级
     */
    int deleteById(Long id);
}