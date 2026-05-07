package com.kwang.study.discussion.service;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.course.mapper.CourseMapper;
import com.kwang.study.course.pojo.Course;
import com.kwang.study.discussion.dto.request.PostCreateDTO;
import com.kwang.study.discussion.dto.request.PostUpdateDTO;
import com.kwang.study.discussion.mapper.DiscussionPostMapper;
import com.kwang.study.discussion.pojo.DiscussionPost;
import com.kwang.study.discussion.pojo.DiscussionPostDetail;
import com.kwang.study.homework.mapper.HomeworkMapper;
import com.kwang.study.homework.mapper.HomeworkSubmissionMapper;
import com.kwang.study.homework.pojo.HomeworkDetail;
import com.kwang.study.homework.pojo.HomeworkSubmissionDetail;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

import static com.kwang.study.discussion.service.DiscussionService.DiscussionType.*;

@Service
@RequiredArgsConstructor
public class DiscussionService {

    private final DiscussionPostMapper discussionPostMapper;
    private final UserInfoUtils userInfoUtils;

    private final HomeworkMapper homeworkMapper;
    private final HomeworkSubmissionMapper submissionMapper;

    // 定义常量避免魔法字符串
    @Getter
    public enum DiscussionType {
        OWNER_TYPE_HOMEWORK("homework"),
        OWNER_TYPE_SUBMISSION("submission"),
        OWNER_TYPE_COURSE("course"),
        OWNER_TYPE_GLOBAL("global");

        /*
        ownerType对应ownerId的含义：
        homework: homeworkId
        submission: homeSubmissionId
        course: courseId
        global: classId(所要查看班级的classId)
         */

        private final String type;
        DiscussionType(String type) {
            this.type = type;
        }
    }


    @Autowired
    private CourseMapper courseMapper;

    /**
     * 创建一个新的帖子/回复
     */
    @Transactional
    public DiscussionPostDetail createPost(PostCreateDTO dto) {
        checkOwnerType(dto.getOwnerType());
        readAccessOwner(dto.getOwnerId(), dto.getOwnerType());

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
        checkOwnerType(ownerType);

        readAccessOwner(ownerId, ownerType);

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

        Assert.isTrue(canOperatePost(post), "无权删除此帖子");

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

    // 是否能够访问owner
    private void readAccessOwner(Long ownerId, String ownerType) {
        if (AuthenticationUserUtil.currentUserIsAdmin())
            return;

        Long classId = null;
        Long courseId = null;
        if (OWNER_TYPE_HOMEWORK.getType().equals(ownerType)) {
            HomeworkDetail homeworkDetail = homeworkMapper.findById(ownerId);
            classId = homeworkDetail.getClassId();
            courseId = homeworkDetail.getCourseId();
        } else if (OWNER_TYPE_SUBMISSION.getType().equals(ownerType)) {
            HomeworkSubmissionDetail submissionDetail = submissionMapper.findById(ownerId);
            classId = submissionDetail.getClassId();
            HomeworkDetail homeworkDetail = homeworkMapper.findById(submissionDetail.getHomeworkId());
            courseId = homeworkDetail.getCourseId();
        } else if (OWNER_TYPE_COURSE.getType().equals(ownerType)) {
            Course course = courseMapper.findById(ownerId);
            Assert.notNull(course, "课程不存在");
            classId = course.getClassId();
            courseId = course.getId();
        } else if (OWNER_TYPE_GLOBAL.getType().equals(ownerType)) {
            classId = ownerId;
        }

        if (courseId != null) {
            // 如果讨论帖隶属于某个具体课程（或作业），走精细化课程鉴权（拦截越权访客）
            if (userInfoUtils.canAccessCourse(courseId, classId)) return;
        } else {
            // 如果是全局公共区，只需判断是否在班级即可
            if (classId != null && (userInfoUtils.inClassOfSchoolPrincipal(classId) || userInfoUtils.inClass(classId))) return;
        }

        throw new IllegalArgumentException("无权访问");
    }

    private void writeAccessOwner(Long ownerId, String ownerType) {
        if (AuthenticationUserUtil.currentUserIsAdmin())
            return ;

        Long classId = null;
        if (OWNER_TYPE_HOMEWORK.getType().equals(ownerType)) {
            HomeworkDetail homeworkDetail = homeworkMapper.findById(ownerId);
            classId = homeworkDetail.getClassId();
        } else if (OWNER_TYPE_SUBMISSION.getType().equals(ownerType)) {
            HomeworkSubmissionDetail submissionDetail = submissionMapper.findById(ownerId);
            classId = submissionDetail.getClassId();
        } else if (OWNER_TYPE_COURSE.getType().equals(ownerType)) {
            Course course = courseMapper.findById(ownerId);
            Assert.notNull(course, "课程不存在");
            classId = course.getClassId();
        }  else if (OWNER_TYPE_GLOBAL.getType().equals(ownerType)) {
            classId = ownerId;
        }
        if (classId != null && userInfoUtils.inClassOfSchoolPrincipal(classId) || userInfoUtils.inClassTeacher(classId))
            return;

        throw new IllegalArgumentException("无权访问");
    }

    private boolean canOperatePost(DiscussionPostDetail post) {
        if (Objects.equals(post.getUserId(), AuthenticationUserUtil.getCurrentUserId()))
            return true;
        try {
            writeAccessOwner(post.getOwnerId(), post.getOwnerType());
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private void checkOwnerType(String ownerType) {
        Assert.isTrue(Arrays.stream(values()).anyMatch(type -> type.getType().equals(ownerType)),
                ownerType + "为非法值");
    }
}