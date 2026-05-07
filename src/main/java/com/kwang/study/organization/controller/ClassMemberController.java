package com.kwang.study.organization.controller;

import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.organization.dto.request.ClassMemberAddDTO;
import com.kwang.study.organization.dto.request.ClassMemberDeleteDTO;
import com.kwang.study.organization.dto.request.GuestCourseUpdateDTO;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.service.ClassMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<R<List<String>>> addMembers(@PathVariable Long classId, @Valid @RequestBody ClassMemberAddDTO dto) {
        return ResponseEntity.ok(R.success(classMemberService.addMembers(classId, dto)));
    }

    /**
     * 从班级中批量删除成员
     * @param classId 班级ID
     * @param dto 请求体，包含要删除的用户ID列表
     * @return 操作结果
     */
    @DeleteMapping("/remove")
    public ResponseEntity<R<Void>> removeMembers(@PathVariable Long classId, @Valid @RequestBody ClassMemberDeleteDTO dto) {
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
    public ResponseEntity<R<Long>> countMemberByClassId(@PathVariable Long classId) {
        Long count = classMemberService.countMemberByClassId(classId);
        return ResponseEntity.ok(R.success(count));
    }

    /**
     * 获取班级的所有访客列表（附带授权的课程ID）
     * @param classId 班级ID
     * @return 访客列表
     */
    @GetMapping("/guests")
    public ResponseEntity<R<List<ClassMember>>> getGuestsInClass(@PathVariable Long classId) {
        List<ClassMember> guests = classMemberService.getGuestsInClass(classId);
        return ResponseEntity.ok(R.success(guests));
    }

    /**
     * 修改访客的课程可见权限
     * @param classId 班级ID
     * @param userId 访客的用户ID
     * @param dto 包含全量覆盖的 courseIds 列表
     * @return 操作结果
     */
    @PutMapping("/guest/{userId}/courses")
    public ResponseEntity<R<Void>> updateGuestCourses(
            @PathVariable Long classId,
            @PathVariable Long userId,
            @Valid @RequestBody GuestCourseUpdateDTO dto) {

        classMemberService.updateGuestCourses(classId, userId, dto);
        return ResponseEntity.ok(R.success(null, "访客课程权限更新成功"));
    }
}