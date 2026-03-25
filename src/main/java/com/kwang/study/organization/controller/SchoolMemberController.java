package com.kwang.study.organization.controller;

import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.organization.dto.request.SchoolMemberAddDTO;
import com.kwang.study.organization.dto.request.SchoolMemberDeleteDTO;
import com.kwang.study.organization.pojo.SchoolMember;
import com.kwang.study.organization.service.SchoolMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping(ApiPrefixConstant.SCHOOL_MEMBER_BASE_PREFIX)
@RequiredArgsConstructor
@Validated
public class SchoolMemberController {

    private final SchoolMemberService schoolMemberService;

    /**
     * 向学校添加成员 (校长)
     */
    @PostMapping("/{schoolId}/add")
    public ResponseEntity<R<Void>> addMembers(@PathVariable Long schoolId, @Valid @RequestBody SchoolMemberAddDTO dto) {
        schoolMemberService.addMembers(schoolId, dto);
        return ResponseEntity.ok(R.success(null, "添加成功"));
    }

    /**
     * 从学校移除成员
     */
    @DeleteMapping("/{schoolId}/remove")
    public ResponseEntity<R<Void>> removeMembers(@PathVariable Long schoolId, @Valid @RequestBody SchoolMemberDeleteDTO dto) {
        schoolMemberService.removeMembers(schoolId, dto.getUserIds());
        return ResponseEntity.ok(R.success(null, "移除成功"));
    }

    /**
     * 获取学校成员列表
     */
    @GetMapping("/{schoolId}/list")
    public ResponseEntity<R<List<SchoolMember>>> getMembers(@PathVariable Long schoolId) {
        return ResponseEntity.ok(R.success(schoolMemberService.getMembers(schoolId)));
    }
}