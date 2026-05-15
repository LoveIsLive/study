package com.kwang.study.home.service;

import cn.hutool.core.lang.UUID;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.home.dto.request.UpdateClassHomeDTO;
import com.kwang.study.home.mapper.ClassHomeMapper;
import com.kwang.study.home.pojo.ClassHome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClassHomeService {

    private final ClassHomeMapper classHomeMapper;
    private final FileStorageService fsService;
    private final UserInfoUtils userInfoUtils;

    public ClassHome getClassHomeDetail(Long classId) {
        Assert.isTrue(userInfoUtils.classCommonRole(classId), "无权查看本班级");
        return classHomeMapper.selectByClassId(classId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ClassHome updateClassHome(UpdateClassHomeDTO dto, MultipartFile coverImage) {
        // 权限校验
        Assert.isTrue(userInfoUtils.classTeacherUp(dto.getClassId()), "无权修改本班级");

        ClassHome existing = classHomeMapper.selectByClassId(dto.getClassId());
        String originCoverPath = existing != null ? existing.getCoverImage() : null;

        // 存储新图片
        String newCoverPath = null;
        if (coverImage != null) {
            newCoverPath = produceAttachPath(coverImage.getOriginalFilename());
            try (InputStream input = coverImage.getInputStream()) {
                fsService.createFile(newCoverPath, input, coverImage.getContentType());
            } catch (IOException e) {
                throw new RuntimeException("封面图上传失败", e);
            }
        }

        if (existing == null) {
            // 新增
            ClassHome classHome = ClassHome.builder()
                    .classId(dto.getClassId())
                    .description(dto.getDescription())
                    .coverImage(newCoverPath)
                    .build();
            classHomeMapper.insert(classHome);
            return classHome;
        } else {
            // 更新
            existing.setDescription(dto.getDescription());
            if (newCoverPath != null) {
                existing.setCoverImage(newCoverPath);
            }
            classHomeMapper.update(existing);

            // 如果上传了新封面且旧封面存在，则删除旧封面，避免产生垃圾文件
            if (newCoverPath != null && StringUtils.hasText(originCoverPath)) {
                try {
                    fsService.deleteFileObject(originCoverPath);
                } catch (IOException e) {
                    log.warn("清理旧封面图失败: {}", e.getMessage());
                }
            }
            return existing;
        }
    }

    private String produceAttachPath(String fileName) {
        String fileExtension = "";
        if (fileName != null && fileName.contains(".")) {
            fileExtension = fileName.substring(fileName.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString(true) + fileExtension;
        return FileStorageModuleNameEnum.COVERIMAGE_NAME.getModuleName() + "/" + uniqueFileName;
    }
}