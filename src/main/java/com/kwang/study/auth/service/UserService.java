package com.kwang.study.auth.service;

import cn.hutool.core.util.RandomUtil;
import com.kwang.study.auth.dto.request.PasswordUpdateDTO;
import com.kwang.study.auth.dto.result.PasswordUpdateResultDTO;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static com.kwang.study.auth.utils.UserInfoUtils.USERINFO_PREFIX;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder; // 注入 Spring Security 的密码编码器
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 修改当前用户的密码
     * @param dto 包含旧密码和新密码
     */
    @Transactional
    public PasswordUpdateResultDTO updateUserPassword(PasswordUpdateDTO dto) {
        PasswordUpdateResultDTO resultDTO = new PasswordUpdateResultDTO();
        // 1. 获取当前登录用户的 ID
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        if (currentUserId == null) {
            resultDTO.setSuccess(Boolean.FALSE);
            resultDTO.setErrorMessage("无法获取登录信息");
            return resultDTO;
        }

        // 2. 从数据库中获取完整的用户信息
        User currentUser = userMapper.findById(currentUserId);
        if (currentUser == null) {
            resultDTO.setSuccess(Boolean.FALSE);
            resultDTO.setErrorMessage("用户不存在");
            return resultDTO;
        }

        // 3. 验证旧密码是否匹配
        //    passwordEncoder.matches(原始密码, 加密后的密码)
        boolean isOldPasswordMatch = passwordEncoder.matches(dto.getOldPassword(), currentUser.getPassword());
        if (!isOldPasswordMatch) {
            resultDTO.setSuccess(Boolean.FALSE);
            resultDTO.setErrorMessage("旧密码错误");
            return resultDTO;
        }

        // 5. 将新密码加密
        String encodedNewPassword = passwordEncoder.encode(dto.getNewPassword());

        // 6. 更新用户信息
        User userToUpdate = new User();
        userToUpdate.setId(currentUserId);
        userToUpdate.setPassword(encodedNewPassword);

        // 7. 调用 Mapper 更新数据库
        int updatedRows = userMapper.updateUser(userToUpdate);

        if (updatedRows <= 0) {
            resultDTO.setSuccess(Boolean.FALSE);
            resultDTO.setErrorMessage("修改密码失败");
            return resultDTO;
        }
        // 清除用户数据
        redisTemplate.delete(USERINFO_PREFIX + currentUserId);
        return new PasswordUpdateResultDTO(true, "密码修改成功，请重新登录");
    }

    @Transactional
    public R<Void> updateUsername(String newUsername) {
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();

        // 1. 格式校验
        if (newUsername == null || newUsername.length() < 2) {
            return R.error("用户名长度至少2位");
        }

        // 2. 唯一性校验
        User existing = userMapper.findByUsername(newUsername);
        if (existing != null && !existing.getId().equals(currentUserId)) {
            return R.error("该用户名已被他人使用");
        }
        if (existing != null && Objects.equals(existing.getUsername(), newUsername)) {
            return R.error("用户名未改变");
        }

        // 3. 更新
        User updateParam = new User();
        updateParam.setId(currentUserId);
        updateParam.setUsername(newUsername);
        userMapper.updateUser(updateParam);

        // 清除用户数据
        redisTemplate.delete(USERINFO_PREFIX + currentUserId);
        
        // 4. 【关键点】JWT 模式下，我们无法直接更新客户端已持有的 Token
        // 返回一个特殊的标识，让前端在成功后强制清除 Token 并跳转登录
        return R.success(null, "用户名修改成功，请重新登录");
    }

    /**
     * 确保用户名全局唯一。如果冲突，则追加随机字符。
     * @return 最终可用的唯一用户名
     */
    public String getUniqueUsername(String rawUsername) {
        String candidate = rawUsername;
        // 如果已存在，则循环尝试添加2位随机小写字母，直到唯一
        int tryCnt = 0;
        while (userMapper.findByUsername(candidate) != null && tryCnt++ < 5) {
            String suffix = RandomUtil.randomString(2).toLowerCase();
            candidate = rawUsername + "_" + suffix;
        }
        if (tryCnt > 5) {
            throw new IllegalStateException("用户名策略生成，重试次数过多");
        }
        return candidate;
    }
}
