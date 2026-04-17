package com.kwang.study.course.controller;

import com.kwang.study.common.R;
import com.kwang.study.course.dto.request.CourseDTO;
import com.kwang.study.course.pojo.Course;
import com.kwang.study.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/course")
@RequiredArgsConstructor
@Validated
public class CourseController {

    private final CourseService courseService;

    @PostMapping("/create")
    public R<Course> createCourse(@Valid @RequestBody CourseDTO dto) {
        return R.success(courseService.createCourse(dto));
    }

    @PutMapping("/{courseId}")
    public R<Course> updateCourse(@PathVariable Long courseId, @Valid @RequestBody CourseDTO dto) {
        return R.success(courseService.updateCourse(courseId, dto));
    }

    @DeleteMapping("/{courseId}")
    public R<Void> deleteCourse(@PathVariable Long courseId) {
        courseService.deleteCourse(courseId);
        return R.success(null, "课程删除成功（关联资源予以保留）");
    }

    @GetMapping("/{courseId}")
    public R<Course> getCourse(@PathVariable Long courseId) {
        return R.success(courseService.getCourseById(courseId));
    }

    @GetMapping("/class/{classId}")
    public R<List<Course>> getCoursesByClassId(@PathVariable Long classId) {
        return R.success(courseService.getCoursesByClassId(classId));
    }
}