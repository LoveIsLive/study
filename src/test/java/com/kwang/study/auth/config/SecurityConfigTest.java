package com.kwang.study.auth.config;

import com.kwang.study.auth.component.JwtUtil;
import com.kwang.study.auth.filter.ContextConflictFilter;
import com.kwang.study.auth.filter.ExceptionHandlerFilter;
import com.kwang.study.auth.filter.JwtAuthenticationFilter;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.result.DownloadDTO;
import com.kwang.study.mathvision.controller.MathVisionFileDownloadController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static com.kwang.study.constant.RedisKeyPrefixConstant.DOWNLOAD_ID_PREFIX;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MathVisionFileDownloadController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        ContextConflictFilter.class,
        ExceptionHandlerFilter.class
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private FileStorageService fileStorageService;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private ValueOperations<String, Object> valueOperations;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void mathVisionDownloadUsesDownloadTokenWithoutJwtHeader() throws Exception {
        mockMvc.perform(get("/api/v1/mathvision/download/download")
                        .param("path", "/mathvision/task-2/v5/final/final.mp4")
                        .param("mode", "inline")
                        .param("token", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mathVisionInlineDownloadAllowsSameOriginFrame() throws Exception {
        String path = "/mathvision/task-4/v1/final/final.html";
        String token = "valid-html-token";
        byte[] html = "<!doctype html><html><body>GeoGebra</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        FileObjectResult fileObject = new FileObjectResult();
        fileObject.setName("final.html");
        fileObject.setSize((long) html.length);
        fileObject.setMimeTypeName("text/html");
        fileObject.setContent(new ByteArrayInputStream(html));

        when(valueOperations.get(DOWNLOAD_ID_PREFIX + token))
                .thenReturn(new DownloadDTO(path, "final.html"));
        when(fileStorageService.getFileObject(path)).thenReturn(fileObject);

        mockMvc.perform(get("/api/v1/mathvision/download/download")
                        .param("path", path)
                        .param("mode", "inline")
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().bytes(html));
    }

    @Test
    void nonInlineDownloadsRemainDeniedForFrames() throws Exception {
        mockMvc.perform(get("/api/v1/mathvision/download/download")
                        .param("path", "/mathvision/task-4/v1/final/final.html")
                        .param("mode", "attachment")
                        .param("token", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void mathVisionDownloadTokenCreationStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/mathvision/download/get/downloadId")
                        .param("path", "/mathvision/task-2/v5/final/final.mp4")
                        .param("fileName", "final.mp4"))
                .andExpect(status().isForbidden());
    }
}
