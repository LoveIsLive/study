package com.kwang.study.service.fs;

import com.kwang.study.mapper.fs.MimeTypeMapper;
import com.kwang.study.pojo.fs.MimeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class MimeTypeService {

    @Autowired
    private MimeTypeMapper mimeTypeMapper;

    // 使用一个简单的本地缓存来减少数据库查询
    private final ConcurrentMap<String, Integer> nameToIdCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> idToNameCache = new ConcurrentHashMap<>();

    /**
     * 根据MIME类型名称获取其ID。不存在返回null
     * @param name MIME类型名称, e.g., "application/json"
     * @return MIME类型的ID
     */
    public Integer getMimeTypeId(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }

        // 先检查本地缓存
        Integer cachedId = nameToIdCache.get(name);
        if (cachedId != null) {
            return cachedId;
        }

        // 查询数据库
        MimeType mimeType = mimeTypeMapper.selectByName(name);

        if (mimeType != null) {
            // 存入缓存并返回
            nameToIdCache.put(name, mimeType.getId());
            idToNameCache.put(mimeType.getId(), name);
            return mimeType.getId();
        }
        return null;
    }

    /**
     * 获取所有MIME类型的名称列表
     * @return MIME类型名称列表
     */
    public List<String> getAllMimeTypeNames() {
        return mimeTypeMapper.selectAll().stream()
                .map(MimeType::getName)
                .collect(Collectors.toList());
    }
}