package com.kwang.study.discussion.service;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.discussion.dto.request.PostCreateDTO;
import com.kwang.study.discussion.dto.request.PostUpdateDTO;
import com.kwang.study.discussion.mapper.DiscussionPostMapper;
import com.kwang.study.discussion.pojo.DiscussionPost;
import com.kwang.study.discussion.pojo.DiscussionPostDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscussionService {

    private final DiscussionPostMapper discussionPostMapper;
    private final UserInfoUtils userInfoUtils;

    // 定义常量避免魔法字符串
    public static final String OWNER_TYPE_HOMEWORK = "homework";
    public static final String OWNER_TYPE_SUBMISSION = "submission";

    /**
     * 创建一个新的帖子/回复
     */
    @Transactional
    public DiscussionPostDetail createPost(PostCreateDTO dto) {
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        dto.setUserId(currentUserId);

        DiscussionPost post = DiscussionPost.builder()
                .ownerId(dto.getOwnerId())
                .ownerType(dto.getOwnerType())
                .parentId(dto.getParentId())
                .userId(dto.getUserId())
                .content(dto.getContent())
                .build();

        discussionPostMapper.insert(post);
        return discussionPostMapper.findDetailById(post.getId());
    }

    /**
     * 获取指定对象的整个讨论树
     */
    public List<DiscussionPostDetail> getDiscussionTree(Long ownerId, String ownerType) {
        // 1. 获取该对象下的所有帖子（扁平列表）
        List<DiscussionPostDetail> allPosts = discussionPostMapper.findByOwner(ownerId, ownerType);

        // 2. 将扁平列表构建成树状结构
        return buildTree(allPosts);
    }

    /**
     * 更新帖子
     */
    @Transactional
    public DiscussionPostDetail updatePost(Long postId, PostUpdateDTO dto) {
        DiscussionPostDetail post = discussionPostMapper.findDetailById(postId);
        Assert.notNull(post, "帖子不存在或已被删除");

        // 权限校验：只能修改自己的帖子
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        Assert.isTrue(Objects.equals(post.getUserId(), currentUserId), "无权修改此帖子");

        discussionPostMapper.updateContent(postId, dto.getContent());
        return discussionPostMapper.findDetailById(postId);
    }

    /**
     * (软)删除帖子
     */
    @Transactional
    public void deletePost(Long postId) {
        DiscussionPostDetail post = discussionPostMapper.findDetailById(postId);
        Assert.notNull(post, "帖子不存在或已被删除");

        // 权限校验：可以删除自己的帖子，或者管理员/作业发布教师也可以删除
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        boolean isOwner = Objects.equals(post.getUserId(), currentUserId);
        boolean isManage = AuthenticationUserUtil.currentUserIsAdmin() || userInfoUtils.currentUserInClassIsTeacher();

        Assert.isTrue(isOwner || isManage, "无权删除此帖子");

        discussionPostMapper.softDeleteById(postId);
    }

    /**
     * 辅助方法：将帖子列表构建为树状结构
     * 算法复杂度 O(n)
     */
    private List<DiscussionPostDetail> buildTree(List<DiscussionPostDetail> posts) {
        if (posts == null || posts.isEmpty()) {
            return new ArrayList<>();
        }

        // 使用Map进行高效查找
        Map<Long, DiscussionPostDetail> postMap = posts.stream()
                .collect(Collectors.toMap(DiscussionPost::getId, p -> p));

        List<DiscussionPostDetail> tree = new ArrayList<>();

        for (DiscussionPostDetail post : posts) {
            // 如果帖子被软删除，则将其内容替换为提示信息
            if (Boolean.TRUE.equals(post.getIsDeleted())) {
                post.setContent("该评论已被删除");
                // 也可以选择不显示发帖人信息
                // post.setUsername("未知用户");
            }

            Long parentId = post.getParentId();
            if (parentId == null) {
                // 这是一个顶级帖子
                tree.add(post);
            } else {
                // 这是一个回复
                DiscussionPostDetail parent = postMap.get(parentId);
                if (parent != null) {
                    // 将当前帖子添加到其父帖子的replies列表中
                    parent.getReplies().add(post);
                } else {
                    // 如果父帖子找不到（可能因为数据问题或已被硬删除），也将其视为顶级帖子
                    tree.add(post);
                }
            }
        }
        return tree;
    }
}