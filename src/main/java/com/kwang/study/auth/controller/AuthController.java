package com.kwang.study.auth.controller;

import com.kwang.study.auth.dto.request.LoginRequestDTO;
import com.kwang.study.common.R;
import com.kwang.study.auth.component.JwtUtil;
import com.kwang.study.auth.service.LoginAttemptService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

import static com.kwang.study.constant.ApiPrefixConstant.AUTH_BASE_PREFIX;

@RestController
@RequestMapping(AUTH_BASE_PREFIX)
@Validated
@Slf4j
@AllArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    private final LoginAttemptService loginAttemptService;

    @PostMapping("/login")
    public ResponseEntity<R<String>> login(@Valid @RequestBody LoginRequestDTO request) {
        String username = request.getUsername();

        if (loginAttemptService.isBlocked(username)) {
            // 返回 429 Too Many Requests 状态码，或者自定义的错误体
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(R.error("登录尝试次数过多，请明天再试。"));
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            loginAttemptService.loginSucceeded(username);
            // 如果认证成功，生成JWT
            String token = jwtUtil.generateToken(authentication);
            return ResponseEntity.ok(R.success(token));
        } catch (BadCredentialsException e) {
            // 3. 如果认证失败（密码错误），记录失败次数
            loginAttemptService.loginFailed(username);
            // 返回标准的认证失败信息
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.error("用户名或密码错误！"));
        }
    }
}
