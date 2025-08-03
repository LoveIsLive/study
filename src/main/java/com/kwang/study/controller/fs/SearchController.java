package com.kwang.study.controller.fs;

import com.kwang.study.dto.fs.request.SearchRequestDTO;
import com.kwang.study.service.async.AsyncSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.validation.Valid;
import java.security.Principal;

import static com.kwang.study.constant.ApiPrefixConstant.FS_BASE_PREFIX;

@Controller
@Slf4j
@RequestMapping(FS_BASE_PREFIX)
@Validated
public class SearchController {

    @Autowired
    private AsyncSearchService asyncSearchService;

    /**
     * 处理来自客户端的搜索请求
     * @param request 包含搜索起始目录和模式的请求
     * @param principal 代表当前已认证的用户
     */
    @MessageMapping("/search")
    public void handleSearch(@Valid @RequestBody SearchRequestDTO request, Principal principal) {
        String userName  = principal.getName();
        asyncSearchService.searchAndSendResults(request.getStartNodeId(), request.getNamePattern(), userName);
    }
}
