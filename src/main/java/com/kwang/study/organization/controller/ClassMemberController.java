package com.kwang.study.organization.controller;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.organization.dto.request.ClassMemberAddDTO;
import com.kwang.study.organization.dto.request.ClassMemberDeleteDTO;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.service.ClassMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;



@RestController
@RequestMapping(ApiPrefixConstant.CLASSMEMBER_BASE_PREFIX + "/{classId}")
@RequiredArgsConstructor
@Validated
public class ClassMemberController {

    private final ClassMemberService classMemberService;

    /**
     * 向班级中批量添加成员
     * @param classId 班级ID
     * @param dto 请求体，包含用户ID列表和角色
     * @return 操作结果
     */
    @PostMapping("/add")
    public ResponseEntity<R<Void>> addMembers(@PathVariable Long classId, @Valid @RequestBody ClassMemberAddDTO dto) {
        if (!AuthenticationUserUtil.currentUserIsAdmin())
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        classMemberService.addMembers(classId, dto);
        return ResponseEntity.ok(R.success(null, "添加成员成功"));
    }

    /**
     * 从班级中批量删除成员
     * @param classId 班级ID
     * @param dto 请求体，包含要删除的用户ID列表
     * @return 操作结果
     */
    @DeleteMapping("/remove")
    public ResponseEntity<R<Void>> removeMembers(@PathVariable Long classId, @Valid @RequestBody ClassMemberDeleteDTO dto) {
        if (!AuthenticationUserUtil.currentUserIsAdmin())
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        classMemberService.removeMembers(classId, dto.getUserIds());
        return ResponseEntity.ok(R.success(null, "删除成员成功"));
    }

    /**
     * 获取班级的所有学生列表
     * @param classId 班级ID
     * @return 学生列表
     */
    @GetMapping("/students")
    public ResponseEntity<R<List<ClassMember>>> getStudentsInClass(@PathVariable Long classId) {
        List<ClassMember> students = classMemberService.getStudentsInClass(classId);
        return ResponseEntity.ok(R.success(students));
    }

    /**
     * 获取班级的所有成员列表（包括教师）
     * @param classId 班级ID
     * @return 成员列表
     */
    @GetMapping("/all")
    public ResponseEntity<R<List<ClassMember>>> getAllMembersInClass(@PathVariable Long classId) {
        List<ClassMember> members = classMemberService.getAllMembersInClass(classId);
        return ResponseEntity.ok(R.success(members));
    }

    /**
     * 统计一个班级内有多少成员
     * @param classId 班级ID
     * @return 成员数量
     */
    @GetMapping("/count")
    public ResponseEntity<R<Long>> countMemberByClassId(Long classId) {
        Long count = classMemberService.countMemberByClassId(classId);
        return ResponseEntity.ok(R.success(count));
    }
}