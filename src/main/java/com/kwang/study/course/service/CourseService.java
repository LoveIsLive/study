package com.kwang.study.course.service;

import cn.hutool.core.lang.UUID;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.course.dto.request.CourseDTO;
import com.kwang.study.course.mapper.CourseMapper;
import com.kwang.study.course.pojo.Course;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.VoidResult;
import com.kwang.study.fs.exception.PathAlreadyExistsException;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.pojo.ClassMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static com.kwang.study.enums.FileStorageModuleNameEnum.COVERIMAGE_NAME;
import static com.kwang.study.enums.FileStorageModuleNameEnum.HOMEWORK_NAME;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseMapper courseMapper;
    private final FileStorageService fileStorageService;
    private final UserInfoUtils userInfoUtils;
    private final FileStorageService fsService;

    @Transactional(rollbackFor = Exception.class)
    public Course createCourse(CourseDTO dto, MultipartFile coverImage) {
        // 1. 强校验：仅当前班级的激活教师可创建课程
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        Assert.isTrue(activeCM != null && ClassesRoleEnum.TEACHER.getRole().equals(activeCM.getRole()), "请先切换到教师身份再创建课程");

        // 存储图片
        String coverPath = "";
        if (coverImage != null) {
            coverPath = produceAttachPath(coverImage.getName());
            try (InputStream input = coverImage.getInputStream()) {
                fsService.createFile(coverPath, input, coverImage.getContentType());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 2. 插入课程记录
        Course course = new Course();
        course.setClassId(activeCM.getClassId());
        course.setTeacherId(AuthenticationUserUtil.getCurrentUserId());
        course.setName(dto.getName());
        course.setDescription(dto.getDescription());
        course.setCoverImage(coverPath);
        courseMapper.insert(course);

        // 3. 自动在 VFS (虚拟文件系统) 中创建课程隔离目录
        // 路径为: /ware/{schoolId}/{classId}/{courseId}
        initCourseWareDirectory(activeCM.getClasses().getSchoolId(), activeCM.getClassId(), course.getId());

        return course;
    }

    @Transactional(rollbackFor = Exception.class)
    public Course updateCourse(Long courseId, CourseDTO dto, MultipartFile coverImage) {
        Course existing = getValidatedCourse(courseId, true);
        String originCoverPath = existing.getCoverImage();

        // 存储图片
        String coverPath = null;
        if (coverImage != null) {
            coverPath = produceAttachPath(coverImage.getName());
            try (InputStream input = coverImage.getInputStream()) {
                fsService.createFile(coverPath, input, coverImage.getContentType());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());
        if (coverPath != null) {
            existing.setCoverImage(coverPath);
        }
        courseMapper.update(existing);

        // 删除之前封面
        try {
            fsService.deleteFileObject(originCoverPath);
        } catch (IOException e) {
            log.warn(e.getMessage());
        }

        return existing;
    }

    /**
     * 删除课程 (遵守不级联删除的原则)
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCourse(Long courseId) {
        Course existing = getValidatedCourse(courseId, true);

        // 仅删除课程主表数据。
        // 保留底层 /ware 下的文件系统数据，保留 homework 试卷记录，保障数据安全。
        courseMapper.deleteById(courseId);
        log.info("课程 {} 已被删除。关联文件及试卷予以保留。", courseId);
    }

    public Course getCourseById(Long courseId) {
        return getValidatedCourse(courseId, false);
    }

    public List<Course> getCoursesByClassId(Long classId) {
        validateReadAccess(classId);
        return courseMapper.findAllByClassId(classId);
    }

    // ================== 权限校验辅助方法 ==================

    private Course getValidatedCourse(Long courseId, boolean isWrite) {
        Course course = courseMapper.findById(courseId);
        Assert.notNull(course, "课程不存在");

        if (isWrite) validateWriteAccess(course.getClassId());
        else validateReadAccess(course.getClassId());

        return course;
    }

    private void validateWriteAccess(Long classId) {
        if (AuthenticationUserUtil.currentUserIsAdmin()) return;
        if (userInfoUtils.inClassOfSchoolPrincipal(classId) || userInfoUtils.inClassTeacher(classId)) return;
        throw new IllegalArgumentException("无权操作该课程数据");
    }

    private void validateReadAccess(Long classId) {
        if (AuthenticationUserUtil.currentUserIsAdmin()) return;
        if (userInfoUtils.inClassOfSchoolPrincipal(classId) || userInfoUtils.inClass(classId)) return;
        throw new IllegalArgumentException("您无法访问该班级的课程");
    }

    private void initCourseWareDirectory(Long schoolId, Long classId, Long courseId) {
        String baseWare = FileStorageModuleNameEnum.WARE_NAME.getModuleName() + "/" + schoolId + "/" + classId;
        String currentCourseDir = baseWare + "/" + courseId;
        try {
            try { fileStorageService.createDirectory(currentCourseDir); } catch (PathAlreadyExistsException ignored) {}
        } catch (IOException e) {
            log.error("初始化课程目录失败: {}", currentCourseDir, e);
        }
    }

    private String produceAttachPath(String fileName) {
        String fileExtension = "";
        if (fileName != null && fileName.contains(".")) {
            fileExtension = fileName.substring(fileName.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString(true) + fileExtension;
        return COVERIMAGE_NAME.getModuleName() + "/" + uniqueFileName;
    }
}