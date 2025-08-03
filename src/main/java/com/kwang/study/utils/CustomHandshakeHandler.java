package com.kwang.study.utils;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * 自定义握手处理器，为每一个匿名的WebSocket连接分配一个唯一的Principal。
 * 这使得我们可以对匿名用户使用 @MessageMapping 和 convertAndSendToUser 功能。
 */
public class CustomHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 为当前连接创建一个唯一的、临时的用户身份
        // 这个身份的名字是一个随机的UUID，确保了唯一性
        return new Principal() {
            private final String name = UUID.randomUUID().toString();
            @Override
            public String getName() {
                return name;
            }
        };
    }
}
