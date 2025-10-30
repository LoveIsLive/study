package com.kwang.study.discussion.mapper;

import com.kwang.study.discussion.pojo.DiscussionPost;
import com.kwang.study.discussion.pojo.DiscussionPostDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DiscussionPostMapper {

    /**
     * 插入一条新的帖子
     */
    int insert(DiscussionPost post);

    /**
     * 根据ID查找帖子详情 (包含用户信息)
     */
    DiscussionPostDetail findDetailById(@Param("id") Long id);

    /**
     * 根据所属对象获取其下所有帖子的扁平列表 (包含用户信息)
     */
    List<DiscussionPostDetail> findByOwner(@Param("ownerId") Long ownerId, @Param("ownerType") String ownerType);

    /**
     * 更新帖子内容
     */
    int updateContent(@Param("id") Long id, @Param("content") String content);

    /**
     * 软删除一个帖子
     */
    int softDeleteById(@Param("id") Long id);
}