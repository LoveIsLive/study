package com.kwang.study.cache;

import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.pojo.Node;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class NodeCache {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    public static final String NODE_KEY_PREFIX = "filesystem:node:";
    public static final String CHILDREN_KEY_PREFIX = "filesystem:children:";

    public static final String ROOT_KEY = "filesystem:ROOT";
    
    public static final int timeout = 10 * 60; // 以秒为单位

    public void setNodeCache(Long id, Node node) {
        String key = NODE_KEY_PREFIX + id;
        redisTemplate.opsForValue().set(key, node, timeout, TimeUnit.SECONDS);
    }

    public Node getNodeCache(Long id) {
        String key = NODE_KEY_PREFIX + id;
        return (Node) redisTemplate.opsForValue().get(key);
    }

    public void setChildrenCache(Long parentId, List<Node> children) {
        String key = CHILDREN_KEY_PREFIX + parentId;
        redisTemplate.opsForValue().set(key, children, timeout, TimeUnit.SECONDS);
    }

    public List<Node> getChildrenCache(Long parentId) {
        String key = CHILDREN_KEY_PREFIX + parentId;
        return (List<Node>) redisTemplate.opsForValue().get(key);
    }

    public void deleteNodeCache(Long id) {
        String key = NODE_KEY_PREFIX + id;
        redisTemplate.delete(key);
    }

    public void deleteChildrenCache(Long parentId) {
        String key = CHILDREN_KEY_PREFIX + parentId;
        redisTemplate.delete(key);
    }

    public void deleteRootChildren() {
        redisTemplate.delete(ROOT_KEY);
    }

    // 设置根目录内容永不超时
    public void setRootChildren(List<Node> children) {
        redisTemplate.opsForValue().set(ROOT_KEY, children);
    }

    public List<Node> getRootChildren() {
        return (List<Node>) redisTemplate.opsForValue().get(ROOT_KEY);
    }

    public void batchDeleteNodeCache(List<Node> nodes) {
        List<String> collect = nodes.stream().map(node -> (Objects.equals(node.getType(), NodeTypeEnum.DIR.getCode()) ?
                NODE_KEY_PREFIX : CHILDREN_KEY_PREFIX) + node.getId()).collect(Collectors.toList());
        redisTemplate.delete(collect);
    }

}
