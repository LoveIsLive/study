package com.kwang.study.service;

import com.kwang.study.mapper.MimeTypeMapper;
import com.kwang.study.pojo.MimeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MimeTypeService {

    @Autowired
    private MimeTypeMapper mimeTypeMapper;

    // 使用一个简单的本地缓存来减少数据库查询
    private final ConcurrentMap<String, Integer> nameToIdCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> idToNameCache = new ConcurrentHashMap<>();

    /**
     * 根据MIME类型名称获取其ID。如果不存在，则创建新的记录。
     * 该方法是线程安全的，并能在事务环境中正确运行。
     * @param name MIME类型名称, e.g., "application/json"
     * @return MIME类型的ID
     */
    @Transactional
    public Integer getOrCreateMimeTypeId(String name) {
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
        } else {
            // 如果不存在，则插入新记录
            MimeType newMimeType = new MimeType();
            newMimeType.setName(name);
            mimeTypeMapper.insertMimeType(newMimeType);
            // 插入后，MyBatis会回填ID
            Integer newId = newMimeType.getId();

            // 存入缓存并返回
            nameToIdCache.put(name, newId);
            idToNameCache.put(newId, name);
            return newId;
        }
    }
}