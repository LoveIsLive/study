package com.kwang.study.mapper.fs;

import com.kwang.study.pojo.fs.HashRefNum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HashRefNumMapper {

    /**
     * 新增一个文件hash记录
     * @param hashRefNum hash记录对象
     * @return 影响的行数
     */
    int insertHash(HashRefNum hashRefNum);

    /**
     * 根据hash值查询记录
     * @param hash 文件内容的sha-256 hash
     * @return hash记录对象
     */
    HashRefNum selectByHash(@Param("hash") String hash);

    /**
     * 根据hash值查询记录并锁定行（用于事务中更新）
     * @param hash 文件内容的sha-256 hash
     * @return hash记录对象
     */
    HashRefNum selectByHashForUpdate(@Param("hash") String hash);

    /**
     * 根据ID查询并锁定
     */
    HashRefNum selectByIdForUpdate(Long id);

    /**
     * 根据ID查询记录
     * @param id 主键ID
     * @return hash记录对象
     */
    HashRefNum selectById(Long id);

    /**
     * 增加指定hash记录的引用计数
     * @param id 主键ID
     * @return 影响的行数
     */
    int incrementRefNum(Long id);

    /**
     * 减少指定hash记录的引用计数
     * @param id 主键ID
     * @return 影响的行数
     */
    int decrementRefNum(Long id);

    /**
     * 批量减少指定hash记录的引用计数，不会变为负数
     * @param id 主键ID
     * @return 影响的行数
     */
    int batchDecrementRefNum(@Param("id") Long id, @Param("cnt") Long cnt);

    /**
     * 根据ID删除hash记录（当引用计数为0时调用）
     * @param id 主键ID
     * @return 影响的行数
     */
    int deleteById(Long id);
}