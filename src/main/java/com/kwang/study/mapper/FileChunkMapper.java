package com.kwang.study.mapper;

import com.kwang.study.pojo.FileChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FileChunkMapper {
    int insertChunk(FileChunk chunk);
    List<FileChunk> selectAllByFileIdOrderByChunkIndex(Long fileId);
    int updateAllStatusByFileId(@Param("fileId") Long fileId, @Param("status") Integer status);
    int deleteByFileId(Long fileId);
    int countStatusChunks(@Param("fileId") Long fileId, @Param("status") Integer status);
}
