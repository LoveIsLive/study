package com.kwang.study.configuration;

import com.kwang.study.auth.interceptor.JwtChannelInterceptor;
import com.kwang.study.utils.CustomHandshakeHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtChannelInterceptor jwtChannelInterceptor;

    public WebSocketConfig(JwtChannelInterceptor jwtChannelInterceptor) {
        this.jwtChannelInterceptor = jwtChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 注册一个STOMP端点，客户端将使用它来连接 const socket = new SockJS('/ws/search');
        registry.addEndpoint("/ws/search")
                .setHandshakeHandler(new CustomHandshakeHandler())
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 定义了服务端接收消息的前缀 stompClient.send("/app/#{MessageMapping}")
        registry.setApplicationDestinationPrefixes("/app");
        // 定义了向客户端发送消息的前缀，客户端需要订阅这些前缀
        // enableSimpleBroker会启用一个简单的基于内存的消息代理 stompClient.subscribe('user/queue/#{}')
        registry.enableSimpleBroker("/queue");
        // 为特定用户目标（一对一消息）设置前缀
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 将我们的JWT认证拦截器添加到入站管道
        registration.interceptors(jwtChannelInterceptor);
    }
}
