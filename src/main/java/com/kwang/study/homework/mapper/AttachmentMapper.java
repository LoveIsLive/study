package com.kwang.study.homework.mapper;

import com.kwang.study.homework.pojo.Attachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AttachmentMapper {

    void batchInsert(@Param("attachments") List<Attachment> attachments);

    List<Attachment> findByOwner(@Param("ownerId") Long ownerId, @Param("ownerType") String ownerType);

    int deleteByOwner(@Param("ownerId") Long ownerId, @Param("ownerType") String ownerType);

    int deleteByOwners(@Param("ownerIds") List<Long> ownerIds, @Param("ownerType") String ownerType);
}