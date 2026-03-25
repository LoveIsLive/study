package com.kwang.study.auth.service;

import com.kwang.study.auth.dto.request.PasswordUpdateDTO;
import com.kwang.study.auth.dto.result.PasswordUpdateResultDTO;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder; // 注入 Spring Security 的密码编码器

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
        resultDTO.setSuccess(Boolean.TRUE);
        return resultDTO;
    }
}
