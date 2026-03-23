package com.kwang.study.index.controller;

import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.common.R;
import com.kwang.study.index.config.TimeLineConfig;
import com.kwang.study.index.pojo.TimeLineItem;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.SchoolMember;
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

        // 修正：从 UserInfoUtils 获取当前激活的身份，而不是直接从 User 对象取第一个
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        SchoolMember activeSM = userInfoUtils.getCurrentActiveSchoolMember();

        // 优先取班级上下文，如果没有班级（如校长），取学校上下文
        Long schoolId = null;
        Long classId = null;

        if (activeCM != null) {
            schoolId = activeCM.getClasses().getSchoolId();
            classId = activeCM.getClassId();
        } else if (activeSM != null) {
            schoolId = activeSM.getSchoolId();
            classId = 0L; // 或者根据业务逻辑处理校长看全校时间轴
        }

        if (schoolId == null) {
            return ResponseEntity.ok(R.success(Collections.emptyList()));
        }

        // 动态拼接 Key: "schoolId-classId"
        String timelineKey = schoolId + "-" + (classId == null ? "0" : classId);
        List<TimeLineItem> itemList = timeLineConfig.getTimeline().get(timelineKey);

        if (CollectionUtils.isEmpty(itemList)) {
            return ResponseEntity.ok(R.success(Collections.emptyList()));
        }
        return ResponseEntity.ok(R.success(itemList));
    }

}
