package com.kwang.study.mathvision.config;

import com.kwang.study.mathvision.transfer.MathVisionTransferSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.servlet.*;
import javax.servlet.http.*;

@Configuration
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "false", matchIfMissing = true)
public class MathVisionInternalSecurityConfig {
    @Bean @Order(-100)
    public SecurityFilterChain mathVisionInternalChain(HttpSecurity http, MathVisionTransferSettings settings) throws Exception {
        return http.antMatcher("/internal/mathvision/**").csrf().disable()
                .sessionManagement().sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS).and()
                .authorizeRequests().anyRequest().permitAll().and()
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                            throws IOException, ServletException {
                        try {
                            String supplied = req.getHeader("X-MathVision-Key");
                            if (supplied == null || !MessageDigest.isEqual(settings.secret().getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                                res.sendError(401); return;
                            }
                        } catch (IllegalStateException disabled) { res.sendError(503); return; }
                        chain.doFilter(req, res);
                    }
                }, UsernamePasswordAuthenticationFilter.class).build();
    }
}
