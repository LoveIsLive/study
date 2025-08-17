package com.kwang.study.auth.interceptor;

import com.kwang.study.auth.component.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;

@Component
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public JwtChannelInterceptor(JwtUtil jwtUtil, UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // 1. 判断是否为连接请求
        Assert.isTrue(accessor != null, "StompHeaderAccessor为空");
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            // 2. 从 header 中获取 token
            // StompHeaderAccessor.getNativeHeader returns a List
            List<String> authorization = accessor.getNativeHeader("Authorization");
            log.debug("Authorization header: {}", authorization);

            if (authorization == null || authorization.isEmpty()) {
                // 如果需要，可以在这里抛出异常或拒绝连接
                return message;
            }
            String token = authorization.get(0);

            // 3. 验证 token
            if (token != null && token.startsWith("Bearer ")) {
                String jwt = token.substring(7);
                try {
                    if (jwtUtil.validateToken(jwt)) {
                        String username = jwtUtil.getUsernameFromToken(jwt);
                        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                        // 4. 创建 Authentication 对象并设置给 SecurityContext
                        // 注意：这里我们设置的是 STOMP session 的 user, Spring Security会自动处理
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());

                        // 关键一步：将认证信息设置到 STOMP 的 session 属性中
                        accessor.setUser(authentication);
                    }
                } catch (Exception e) {
                    log.error("WebSocket authentication error: {}", e.getMessage());
                    // 可以在这里处理认证失败的情况，例如记录日志
                }
            }
        }
        return message;
    }
}
