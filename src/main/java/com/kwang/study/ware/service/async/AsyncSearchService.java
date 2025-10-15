package com.kwang.study.ware.service.async;

import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.SearchNodeResult;
import com.kwang.study.ware.service.WareService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AsyncSearchService {
    @Autowired
    private WareService wareService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Async
    public void searchAndSendResults(String path, String namePattern, String user, SecurityContext securityContext) {
        // 设置当前认证身份
        SecurityContextHolder.setContext(securityContext);

        String destination = "/queue/search-results";
        log.info("Async search started for user: {}, destination: {}", user, destination);

        try {
            wareService.searchNodesBFS(
                    path,
                    namePattern,
                    (SearchNodeResult foundNode) -> {
                        log.info("Found node for user {}, sending: {}", user, foundNode.getName());
                        foundNode.setFullPath(foundNode.getFullPath()
                                .substring(FileStorageModuleNameEnum.WARE_NAME.getModuleName().length()));
                        messagingTemplate.convertAndSendToUser(user, destination, foundNode);
                    }
            );
            // 搜索完成后可以发送一个结束信号
            messagingTemplate.convertAndSendToUser(user, destination, "SEARCH_COMPLETE");
            log.info("Async search completed for user: {}", user);
        } catch (Exception e) {
            log.error("Error during async search for user: " + user, e);
            messagingTemplate.convertAndSendToUser(user, destination, "SEARCH_ERROR: " + e.getMessage());
        }
    }
}
