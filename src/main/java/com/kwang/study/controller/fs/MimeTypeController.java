package com.kwang.study.controller.fs;

import com.kwang.study.common.R;
import com.kwang.study.service.MimeTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.FS_BASE_PREFIX;

@RestController
@RequestMapping(FS_BASE_PREFIX + "/mime-types")
public class MimeTypeController {

    @Autowired
    private MimeTypeService mimeTypeService;

    /**
     * 获取所有支持的MIME类型名称
     * @return MIME类型名称列表
     */
    @GetMapping("/all")
    public ResponseEntity<R<List<String>>> getAllMimeTypes() {
        List<String> mimeTypeNames = mimeTypeService.getAllMimeTypeNames();
        return ResponseEntity.ok(R.success(mimeTypeNames));
    }
}