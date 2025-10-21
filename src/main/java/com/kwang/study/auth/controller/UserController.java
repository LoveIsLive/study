package com.kwang.study.auth.controller;

import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.common.R;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.kwang.study.constant.ApiPrefixConstant.USER_BASE_PREFIX;

@RestController
@RequestMapping(USER_BASE_PREFIX)
@Validated
@Slf4j
@AllArgsConstructor
public class UserController {
    private final UserInfoUtils userInfoUtils;

    @GetMapping("/detailInfo")
    public ResponseEntity<R<User>> currentUserDetailInfo() {
        return ResponseEntity.ok(R.success(userInfoUtils.getCurrentUserInfoWithClasses()));
    }

}
