package com.kwang.study.index.controller;

import com.kwang.study.common.R;
import com.kwang.study.index.config.TimeLineConfig;
import com.kwang.study.index.pojo.TimeLineItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.INDEX_BASE_PREFIX;

/**
 * @author kwang
 * @date 2025/09/03
 */
@RestController
@RequestMapping(INDEX_BASE_PREFIX)
@Validated
@Slf4j
public class IndexController {

    @Autowired
    private TimeLineConfig timeLineConfig;

    @GetMapping("/timeline")
    public ResponseEntity<R<List<TimeLineItem>>> getTimeLine() {
        return ResponseEntity.ok(R.success(timeLineConfig.getTimeline()));
    }

}
