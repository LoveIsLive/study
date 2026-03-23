package com.kwang.study.ware.controller;

import com.kwang.study.ware.dto.request.SearchRequestDTO;
import com.kwang.study.ware.service.async.AsyncSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.CustomAutowireConfigurer;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.validation.Valid;
import java.security.Principal;

import static com.kwang.study.constant.ApiPrefixConstant.WARE_BASE_PREFIX;

@Controller
@Slf4j
@Validated
public class SearchController {

    @Autowired
    private AsyncSearchService asyncSearchService;

    /**
     * 处理来自客户端的搜索请求
     * @param request 包含搜索起始目录和模式的请求
     * @param principal 代表当前已认证的用户
     */
    @MessageMapping("/ware/search")
    public void handleSearch(@Valid @RequestBody SearchRequestDTO request, Principal principal) {
        request.check();

        Long classId = request.getActiveClassId();
        Long schoolId = request.getActiveSchoolId();

        String userName  = principal.getName();
        asyncSearchService.searchAndSendResults(
                request.getPath(),
                request.getNamePattern(),
                userName,
                SecurityContextHolder.getContext(),
                classId,
                schoolId
        );
    }
}
