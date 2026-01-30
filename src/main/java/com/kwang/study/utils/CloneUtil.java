package com.kwang.study.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * 对象深拷贝工具类，通过JSON序列化/反序列化实现
 */
public class CloneUtil {

    // 默认的ObjectMapper，配置了常用模块和特性
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    static {
        // 配置默认ObjectMapper
        DEFAULT_MAPPER.registerModule(new JavaTimeModule());
        DEFAULT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        DEFAULT_MAPPER.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        DEFAULT_MAPPER.setTimeZone(TimeZone.getTimeZone("GMT+8"));

        DEFAULT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        DEFAULT_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        DEFAULT_MAPPER.configure(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL, true);

        DEFAULT_MAPPER.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
                .activateDefaultTyping(LaissezFaireSubTypeValidator.instance ,
                        ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);  // 多态
    }

    /**
     * 使用默认ObjectMapper进行深拷贝
     * @param source 源对象
     * @param clazz 目标类型
     * @param <T> 目标类型
     * @return 深拷贝后的新对象
     * @throws IllegalArgumentException 如果拷贝失败
     */
    public static <T> T clone(Object source, Class<T> clazz) {
        return clone(source, clazz, DEFAULT_MAPPER);
    }

    /**
     * 使用指定的ObjectMapper进行深拷贝
     * @param source 源对象
     * @param clazz 目标类型
     * @param objectMapper 自定义的ObjectMapper
     * @param <T> 目标类型
     * @return 深拷贝后的新对象
     * @throws IllegalArgumentException 如果拷贝失败
     */
    public static <T> T clone(Object source, Class<T> clazz, ObjectMapper objectMapper) {
        if (source == null) {
            return null;
        }

        try {
            // 序列化为JSON字节数组，再反序列化为新对象
            byte[] jsonBytes = objectMapper.writeValueAsBytes(source);
            return objectMapper.readValue(jsonBytes, clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException("对象深拷贝失败: " + source.getClass().getSimpleName() + " -> " + clazz.getSimpleName(), e);
        }
    }

    /**
     * 拷贝到已有对象（不创建新实例）
     * @param source 源对象
     * @param target 目标对象
     * @param <T> 对象类型
     * @return 更新后的目标对象
     * @throws IllegalArgumentException 如果拷贝失败
     */
    public static <T> T cloneInto(Object source, T target) {
        return cloneInto(source, target, DEFAULT_MAPPER);
    }

    /**
     * 使用指定ObjectMapper拷贝到已有对象
     * @param source 源对象
     * @param target 目标对象
     * @param objectMapper 自定义的ObjectMapper
     * @param <T> 对象类型
     * @return 更新后的目标对象
     * @throws IllegalArgumentException 如果拷贝失败
     */
    public static <T> T cloneInto(Object source, T target, ObjectMapper objectMapper) {
        if (source == null || target == null) {
            return target;
        }

        try {
            // 将源对象序列化为JSON，再更新到目标对象
            String json = objectMapper.writeValueAsString(source);
            return objectMapper.readerForUpdating(target).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("对象属性拷贝失败: " + source.getClass().getSimpleName() + " -> " + target.getClass().getSimpleName(), e);
        }
    }

    /**
     * 获取默认配置的ObjectMapper（可用于自定义扩展）
     * @return 默认ObjectMapper实例
     */
    public static ObjectMapper getDefaultObjectMapper() {
        return DEFAULT_MAPPER;
    }
}