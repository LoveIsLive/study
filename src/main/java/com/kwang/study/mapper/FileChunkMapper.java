package com.kwang.study.mapper;

import com.kwang.study.pojo.FileChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface FileChunkMapper {
    /**
     * 插入单个文件分片记录
     * @param chunk 分片对象
     * @return 影响的行数
     */
    int insertChunk(FileChunk chunk);

    /**
     * 批量插入文件分片记录
     * @param chunks 分片列表
     * @return 影响的行数
     */
    int batchInsertChunks(@Param("chunks") List<FileChunk> chunks);

    /**
     * 查询文件的所有分片，并按索引排序
     * @param fileId 关联的node.id
     * @return 分片列表
     */
    List<FileChunk> selectAllByFileIdOrderByChunkIndex(Long fileId);

    /**
     * 更新文件的所有分片状态
     * @param fileId 关联的node.id
     * @param status 新状态
     * @return 影响的行数
     */
    int updateAllStatusByFileId(@Param("fileId") Long fileId, @Param("status") Integer status);

    /**
     * 根据文件ID删除所有分片记录
     * @param fileId 关联的node.id
     * @return 影响的行数
     */
    int deleteByFileId(Long fileId);

    /**
     * 统计指定状态的分片数量
     * @param fileId 关联的node.id
     * @param status 状态
     * @return 对应状态的分片数量
     */
    int countChunksByStatus(@Param("fileId") Long fileId, @Param("status") Integer status);
}