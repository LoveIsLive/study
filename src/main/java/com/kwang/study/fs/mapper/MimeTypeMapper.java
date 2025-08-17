package com.kwang.study.fs.mapper;

import com.kwang.study.fs.pojo.MimeType;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MimeTypeMapper {

    /**
     * 根据ID查询MIME类型
     * @param id 类型ID
     * @return MimeType对象
     */
    MimeType selectById(Integer id);

    /**
     * 根据MIME类型名称查询
     * @param name MIME类型名称, e.g., "application/json"
     * @return MimeType对象
     */
    MimeType selectByName(String name);

    List<MimeType> selectAll();
}