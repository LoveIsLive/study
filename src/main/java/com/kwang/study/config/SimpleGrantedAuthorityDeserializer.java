package com.kwang.study.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;

public class SimpleGrantedAuthorityDeserializer extends JsonDeserializer<SimpleGrantedAuthority> {

    @Override
    public SimpleGrantedAuthority deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        JsonNode node = jp.getCodec().readTree(jp);
        // 从 JSON 对象中获取 "authority" 字段的值
        String authority = node.get("authority").asText();
        return new SimpleGrantedAuthority(authority);
    }
}
