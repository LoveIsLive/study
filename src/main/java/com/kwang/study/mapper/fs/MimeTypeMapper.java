package com.kwang.study.mapper.fs;

import com.kwang.study.pojo.fs.MimeType;
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

    /**
     * 插入新的MIME类型
     * @param mimeType MimeType对象
     * @return 影响的行数 (会设置对象的id)
     */
    int insertMimeType(MimeType mimeType);

    List<MimeType> selectAll();
}