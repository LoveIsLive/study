package com.kwang.study.cache;

import com.kwang.study.dto.NodeDetailDTO;
import com.kwang.study.pojo.Node;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文件系统节点和目录结构的Redis缓存
 * 缓存策略:
 * - 单个节点信息 (Node): 使用 "filesystem:node:{id}" 作为键。
 * - 目录内容 (List<Node>): 使用 "filesystem:children:{parentId}" 作为键。
 * - 根目录内容 (List<Node>): 使用 "filesystem:ROOT" 作为键。
 */
@Component
public class NodeCache {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public static final String NODE_KEY_PREFIX = "filesystem:node:";
    public static final String CHILDREN_KEY_PREFIX = "filesystem:children:";
    // 根目录内容的缓存key
    public static final String ROOT_DIR_KEY = "null";

    // 缓存过期时间（10分钟）
    public static final long TIMEOUT_SECONDS = 10 * 60;

    /**
     * 缓存单个节点信息
     *
     * @param id   节点ID
     * @param node 节点对象
     */
    public void setNodeCache(Long id, Node node) {
        if (id == null || node == null) return;
        String key = NODE_KEY_PREFIX + id;
        redisTemplate.opsForValue().set(key, node, TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 获取单个节点缓存
     *
     * @param id 节点ID
     * @return 节点对象或null
     */
    public Node getNodeCache(Long id) {
        if (id == null) return null;
        String key = NODE_KEY_PREFIX + id;
        return (Node) redisTemplate.opsForValue().get(key);
    }

    /**
     * 删除单个节点缓存
     *
     * @param id 节点ID
     */
    public void deleteNodeCache(Long id) {
        if (id == null) return;
        String key = NODE_KEY_PREFIX + id;
        redisTemplate.delete(key);
    }

    /**
     * 缓存目录的子节点列表
     *
     * @param parentId 父节点ID
     * @param children 子节点列表
     */
    public void setChildrenCache(Long parentId, List<NodeDetailDTO> children) {
        if (children == null) return;
        String key = CHILDREN_KEY_PREFIX + (parentId == null ? ROOT_DIR_KEY : String.valueOf(parentId));
        // 设置根目录永不超时
        if (parentId == null) {
            redisTemplate.opsForValue().set(key, children);
        } else {
            redisTemplate.opsForValue().set(key, children, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * 获取目录的子节点详细信息缓存
     *
     * @param parentId 父节点ID
     * @return 子节点列表或null
     */
    @SuppressWarnings("unchecked")
    public List<NodeDetailDTO> getChildrenCache(Long parentId) {
        String key = CHILDREN_KEY_PREFIX + (parentId == null ? ROOT_DIR_KEY : String.valueOf(parentId));
        return (List<NodeDetailDTO>) redisTemplate.opsForValue().get(key);
    }

    /**
     * 删除目录的子节点缓存
     *
     * @param parentId 父节点ID
     */
    public void deleteChildrenCache(Long parentId) {
        String key = CHILDREN_KEY_PREFIX + (parentId == null ? ROOT_DIR_KEY : String.valueOf(parentId));
        redisTemplate.delete(key);
    }

    /**
     * 批量删除缓存
     *
     * @param keys 要删除的缓存键列表
     */
    public void batchDeleteCache(List<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}