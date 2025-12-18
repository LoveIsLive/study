package com.kwang.study.index.controller;

import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.common.R;
import com.kwang.study.index.config.TimeLineConfig;
import com.kwang.study.index.pojo.TimeLineItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
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

    @Autowired
    private UserInfoUtils userInfoUtils;

    @GetMapping("/timeline")
    public ResponseEntity<R<List<TimeLineItem>>> getTimeLine() {
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return ResponseEntity.ok(R.success(Collections.emptyList()));
        }
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        if (user == null || user.getClassMember() == null) {
            return ResponseEntity.ok(R.success(Collections.emptyList()));
        }

        Long schoolId = user.getClassMember().getClasses().getSchoolId();
        Long classId = user.getClassMember().getClasses().getId();
        List<TimeLineItem> itemList = timeLineConfig.getTimeline().get(schoolId + "-" + classId);
        if (CollectionUtils.isEmpty(itemList)) {
            return ResponseEntity.ok(R.success(Collections.emptyList()));
        }
        return ResponseEntity.ok(R.success(itemList));
    }

}
