package com.kwang.study.homework.mapper;

import com.kwang.study.homework.pojo.AttachmentDetail;
import com.kwang.study.homework.pojo.Attachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AttachmentMapper {

    int batchInsert(@Param("attachments") List<Attachment> attachments);

    int deleteBatchIds(@Param("ids") List<Long> ids);

    List<AttachmentDetail> findByOwner(@Param("ownerId") Long ownerId, @Param("ownerType") String ownerType);

    List<AttachmentDetail> findByIds(@Param("ids") List<Long> ids);

    int deleteByOwner(@Param("ownerId") Long ownerId, @Param("ownerType") String ownerType);

    int deleteByOwners(@Param("ownerIds") List<Long> ownerIds, @Param("ownerType") String ownerType);
}