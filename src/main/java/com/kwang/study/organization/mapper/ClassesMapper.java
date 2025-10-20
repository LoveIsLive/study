package com.kwang.study.organization.mapper;

import com.kwang.study.organization.dto.result.ClassDetailDTO;
import com.kwang.study.organization.pojo.Classes;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ClassesMapper {
    // --- 基本信息查询 ---
    List<Classes> findAll();
    Classes findById(Long id);
    Classes findByName(String name);
    List<Classes> matchByName(@Param("key") String key);

    // --- 详细信息查询 ---
    List<ClassDetailDTO> findAllDetail();
    ClassDetailDTO findClassDetailById(Long id);
    ClassDetailDTO findClassDetailByName(String name);
    List<ClassDetailDTO> matchClassDetailByName(@Param("key") String key);

    // --- 写操作 ---
    int insert(Classes classes);
    int update(Classes classes);
    int deleteById(Long id);
}
