package com.kwang.study.discussion.controller;

import com.kwang.study.common.R;
import com.kwang.study.discussion.dto.request.PostCreateDTO;
import com.kwang.study.discussion.dto.request.PostUpdateDTO;
import com.kwang.study.discussion.pojo.DiscussionPostDetail;
import com.kwang.study.discussion.service.DiscussionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.DISCUSSION_BASE_PREFIX;

@RestController
@RequestMapping(DISCUSSION_BASE_PREFIX)
@RequiredArgsConstructor
@Validated
public class DiscussionController {

    private final DiscussionService discussionService;

    /**
     * 为一个作业获取讨论区内容
     */
    @GetMapping("/homework/{homeworkId}")
    public ResponseEntity<R<List<DiscussionPostDetail>>> getHomeworkDiscussion(@PathVariable Long homeworkId) {
        List<DiscussionPostDetail> discussionTree = discussionService.getDiscussionTree(homeworkId, DiscussionService.OWNER_TYPE_HOMEWORK);
        return ResponseEntity.ok(R.success(discussionTree));
    }

    /**
     * 为一个作业提交获取讨论区内容
     */
    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<R<List<DiscussionPostDetail>>> getSubmissionDiscussion(@PathVariable Long submissionId) {
        List<DiscussionPostDetail> discussionTree = discussionService.getDiscussionTree(submissionId, DiscussionService.OWNER_TYPE_SUBMISSION);
        return ResponseEntity.ok(R.success(discussionTree));
    }

    /**
     * 【新增】为一个课程获取讨论区内容
     */
    @GetMapping("/course/{courseId}")
    public ResponseEntity<R<List<DiscussionPostDetail>>> getCourseDiscussion(@PathVariable Long courseId) {
        List<DiscussionPostDetail> discussionTree = discussionService.getDiscussionTree(courseId, DiscussionService.OWNER_TYPE_COURSE);
        return ResponseEntity.ok(R.success(discussionTree));
    }

    /**
     * 发布新帖子或回复
     */
    @PostMapping("/")
    public ResponseEntity<R<DiscussionPostDetail>> createPost(@Valid @RequestBody PostCreateDTO createDTO) {
        // 参数校验：ownerType必须是预定义的值, homework, submission
        if (!DiscussionService.OWNER_TYPE_HOMEWORK.equals(createDTO.getOwnerType()) &&
                !DiscussionService.OWNER_TYPE_SUBMISSION.equals(createDTO.getOwnerType()) &&
                !DiscussionService.OWNER_TYPE_COURSE.equals(createDTO.getOwnerType())) { // 增加放行
            return ResponseEntity.badRequest().body(R.error("Invalid ownerType"));
        }

        DiscussionPostDetail newPost = discussionService.createPost(createDTO);
        return ResponseEntity.ok(R.success(newPost));
    }

    /**
     * 修改帖子内容
     */
    @PutMapping("/{postId}")
    public ResponseEntity<R<DiscussionPostDetail>> updatePost(@PathVariable Long postId, @Valid @RequestBody PostUpdateDTO updateDTO) {
        DiscussionPostDetail updatedPost = discussionService.updatePost(postId, updateDTO);
        return ResponseEntity.ok(R.success(updatedPost, "更新成功"));
    }

    /**
     * 删除帖子
     */
    @DeleteMapping("/{postId}")
    public ResponseEntity<R<Void>> deletePost(@PathVariable Long postId) {
        discussionService.deletePost(postId);
        return ResponseEntity.ok(R.success(null, "删除成功"));
    }
}