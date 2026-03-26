package com.kwang.study.auth.filter;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class ContextConflictFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String classId = request.getHeader("X-Active-Class-Id");
        String schoolId = request.getHeader("X-Active-School-Id");

        // 逻辑硬校验：不能同时拥有班级上下文和学校上下文
        if (classId != null && schoolId != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"message\":\"Context Conflict: Cannot act as School and Class concurrently.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}