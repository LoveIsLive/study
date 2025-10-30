package com.kwang.study.discussion.pojo;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class DiscussionPostDetail extends DiscussionPost {
    // 发帖人信息
    private String username;
    // 嵌套的回复列表
    private List<DiscussionPostDetail> replies = new ArrayList<>();

    // All-args constructor for builder compatibility in DiscussionPost
    @Builder(builderMethodName = "detailBuilder")
    public DiscussionPostDetail(Long id, Long ownerId, String ownerType, Long parentId, Long userId, String content, Boolean isDeleted, java.time.LocalDateTime createTime, java.time.LocalDateTime updateTime, String username, List<DiscussionPostDetail> replies) {
        super(id, ownerId, ownerType, parentId, userId, content, isDeleted, createTime, updateTime);
        this.username = username;
        this.replies = replies == null ? new ArrayList<>() : replies;
    }
}