package com.kwang.study.home.mapper;

import com.kwang.study.home.pojo.ClassHome;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ClassHomeMapper {
    ClassHome selectByClassId(Long classId);
    int insert(ClassHome classHome);
    int update(ClassHome classHome);
}