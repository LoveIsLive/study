package com.kwang.study.auth.config;

import com.kwang.study.auth.filter.ContextConflictFilter;
import com.kwang.study.auth.filter.ExceptionHandlerFilter;
import com.kwang.study.auth.filter.JwtAuthenticationFilter;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static com.kwang.study.constant.ApiPrefixConstant.*;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true) // 开启方法级别的权限注解
@AllArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final ContextConflictFilter identityConflictFilter;
    private final ExceptionHandlerFilter exceptionHandlerFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 允许各个模块的入口访问、登录接口，以及所有的静态资源
                        .antMatchers(AUTH_BASE_PREFIX + "/login",
                                AUTH_BASE_PREFIX + "/public/**",
                                "/",
                                "/index.html",
                                "/favicon.ico",
                                "/assets/**",
                                "/webfonts/**"
                                ).permitAll()
                        // 特殊端点
                        .antMatchers(
                                WARE_BASE_PREFIX + "/home/download",
                                ATTACHE_DOWNLOAD_BASE_PREFIX + "/download",
                                LLM_BASE_PREFIX + "/download"
                        ).permitAll()
                        // 允许websocket握手请求
                        .antMatchers("/ws/search/**").permitAll()
                        // API请求被保护
                        .antMatchers("/api/**").authenticated()
                        // 非API请求
                        .anyRequest().permitAll()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 无状态session
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(identityConflictFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(exceptionHandlerFilter, JwtAuthenticationFilter.class)
                .formLogin().disable()
                .httpBasic().disable()
//                .oauth2Login().disable()
                .csrf().disable();

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

}
