package com.kwang.study.auth.filter;

import com.kwang.study.auth.component.JwtUtil;
import com.kwang.study.auth.custom.CustomUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        final String jwt = resolveToken(request);
        String username = null;

        if (jwt != null && jwtUtil.validateToken(jwt)) {
            username = jwtUtil.getUsernameFromToken(jwt);
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            CustomUserDetails userDetails = (CustomUserDetails) this.userDetailsService.loadUserByUsername(username);

            // 【核心安全策略】：如果用户需要强制改密，且当前请求不是“修改密码”接口，也不是公开资源，则予以拦截
            if (userDetails.isPasswordExpired() && !isAllowedPathForExpiredUser(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                // 返回自定义 code（例如 40301），方便前端拦截器识别
                response.getWriter().write("{\"code\":40301,\"message\":\"Password expired, please update your password first.\"}");
                return;
            }

            List<GrantedAuthority> currentAuthorities = calculateCurrentAuthorities(request, userDetails);
            userDetails.setAuthorities(currentAuthorities);

            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    userDetails, null, currentAuthorities);

            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 判断是否是处于强制改密期的用户允许访问的路径
     */
    private boolean isAllowedPathForExpiredUser(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        // 允许调用修改密码接口 (例如：PUT /api/v1/user/password)
        // 请根据 com.kwang.study.constant.ApiPrefixConstant.USER_BASE_PREFIX 的具体值进行匹配
        return uri.endsWith("/user/password") && "PUT".equalsIgnoreCase(method);
    }

    /**
     * 辅助方法：从请求头中获取 Token
     */
    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    /**
     * 核心逻辑：根据当前激活身份 Header 计算权限列表
     */
    private List<GrantedAuthority> calculateCurrentAuthorities(HttpServletRequest request, CustomUserDetails userDetails) {

        // A. 注入基础权限 (如：ROLE_ADMIN)
        // 注意：userDetails.getAuthorities() 在 loadUserByUsername 时已填充了系统角色
        List<GrantedAuthority> auths = new ArrayList<>(userDetails.getAuthorities());

        // B. 获取上下文切换 Header
        String activeClassIdStr = request.getHeader("X-Active-Class-Id");
        String activeSchoolIdStr = request.getHeader("X-Active-School-Id");

        // C. 处理班级角色切换
        if (activeClassIdStr != null && userDetails.getClassMembers() != null) {
            try {
                Long activeClassId = Long.parseLong(activeClassIdStr);
                userDetails.getClassMembers().stream()
                        .filter(cm -> cm.getClassId().equals(activeClassId))
                        .findFirst()
                        .ifPresent(cm -> auths.add(new SimpleGrantedAuthority(cm.getRole())));
            } catch (NumberFormatException ignored) {}
        }

        // D. 处理学校角色切换 (校长)
        if (activeSchoolIdStr != null && userDetails.getSchoolMembers() != null) {
            try {
                Long activeSchoolId = Long.parseLong(activeSchoolIdStr);
                userDetails.getSchoolMembers().stream()
                        .filter(sm -> sm.getSchoolId().equals(activeSchoolId))
                        .findFirst()
                        .ifPresent(sm -> auths.add(new SimpleGrantedAuthority(sm.getRole())));
            } catch (NumberFormatException ignored) {}
        }

        // E. 防御性处理：如果没有任何特定角色且有身份列表，默认取第一个（兼容不传 Header 的老请求）
        if (auths.isEmpty() || (auths.size() == 1 && auths.get(0).getAuthority().startsWith("ROLE_ADMIN"))) {
            if (!CollectionUtils.isEmpty(userDetails.getSchoolMembers())) {
                auths.add(new SimpleGrantedAuthority(userDetails.getSchoolMembers().get(0).getRole()));
            } else if (!CollectionUtils.isEmpty(userDetails.getClassMembers())) {
                auths.add(new SimpleGrantedAuthority(userDetails.getClassMembers().get(0).getRole()));
            }
        }

        return auths;
    }
}