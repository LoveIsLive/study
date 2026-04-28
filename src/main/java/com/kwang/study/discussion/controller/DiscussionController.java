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

    @GetMapping("/getall")
    public ResponseEntity<R<List<DiscussionPostDetail>>> getAllDiscussion(@RequestParam("ownerId") Long ownerId,
                                                                          @RequestParam("ownerType") String ownerType) {
        List<DiscussionPostDetail> discussionTree = discussionService.getDiscussionTree(ownerId, ownerType);
        return ResponseEntity.ok(R.success(discussionTree));
    }

    /**
     * 发布新帖子或回复
     */
    @PostMapping("/")
    public ResponseEntity<R<DiscussionPostDetail>> createPost(@Valid @RequestBody PostCreateDTO createDTO) {
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