package com.kwang.study.mapper;

import com.kwang.study.pojo.HashRefNum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HashRefNumMapper {
    HashRefNum selectHash(@Param("hash") String hash);
    HashRefNum selectHashForUpdate(@Param("hash") String hash);
    int addNum(@Param("hash") String hash);
    int decNum(@Param("hash") String hash);
    int insertHash(HashRefNum hashRefNum);
    int deleteHash(@Param("hash") String hash);
}
