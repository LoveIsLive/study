package com.kwang.study.auth.controller;

import com.kwang.study.auth.dto.request.PasswordUpdateDTO;
import com.kwang.study.auth.dto.result.PasswordUpdateResultDTO;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.service.UserService;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.common.R;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

import java.util.Map;

import static com.kwang.study.constant.ApiPrefixConstant.USER_BASE_PREFIX;

@RestController
@RequestMapping(USER_BASE_PREFIX)
@Validated
@Slf4j
@AllArgsConstructor
public class UserController {
    private final UserInfoUtils userInfoUtils;

    private final UserService userService;

    @GetMapping("/detailInfo")
    public ResponseEntity<R<User>> currentUserDetailInfo() {
        return ResponseEntity.ok(R.success(userInfoUtils.getCurrentUserInfoWithOrgInfo()));
    }

    /**
     * 用户修改自己的密码
     */
    @PutMapping("/password")
    public ResponseEntity<R<Void>> updateUserPassword(@Valid @RequestBody PasswordUpdateDTO dto) {
        // Service 层会处理所有业务逻辑和权限验证
        PasswordUpdateResultDTO resultDTO = userService.updateUserPassword(dto);
        if (Boolean.TRUE.equals(resultDTO.getSuccess())) {
            return ResponseEntity.ok(R.success(null, "修改密码成功"));
        } else {
            return ResponseEntity.ok(R.error(resultDTO.getErrorMessage()));
        }
    }

    @PutMapping("/username")
    public ResponseEntity<R<Void>> updateUsername(@RequestBody Map<String, String> body) {
        String newUsername = body.get("username");
        // 调用 UserService 中已经写好的 updateUsername 方法
        return ResponseEntity.ok(userService.updateUsername(newUsername));
    }
}
