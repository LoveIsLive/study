package com.kwang.study.service.async;

import com.kwang.study.dto.NodeDetailDTO;
import com.kwang.study.service.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AsyncSearchService {
    @Autowired
    private NodeService nodeService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Async
    public void searchAndSendResults(Long startNodeId, String namePattern, String sessionId) {
        String destination = "/queue/search-results";
        log.info("Async search started for user: {}, destination: {}", sessionId, destination);

        try {
            nodeService.searchNodesBFS(
                    startNodeId,
                    namePattern,
                    (NodeDetailDTO foundNode) -> {
                        log.info("Found node for user {}, sending: {}", sessionId, foundNode.getName());
                        messagingTemplate.convertAndSendToUser(sessionId, destination, foundNode);
                    }
            );
            // 搜索完成后可以发送一个结束信号
            messagingTemplate.convertAndSendToUser(sessionId, destination, "SEARCH_COMPLETE");
            log.info("Async search completed for user: {}", sessionId);
        } catch (Exception e) {
            log.error("Error during async search for user: " + sessionId, e);
            messagingTemplate.convertAndSendToUser(sessionId, destination, "SEARCH_ERROR: " + e.getMessage());
        }
    }
}
