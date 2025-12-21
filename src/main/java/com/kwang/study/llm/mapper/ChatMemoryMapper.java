package com.kwang.study.llm.mapper;

import com.kwang.study.llm.pojo.ChatMemory;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatMemoryMapper {
    @Insert("INSERT INTO chat_memory(session_id, user_id, role, type, content, created_at) " +
            "VALUES(#{sessionId}, #{userId}, #{role}, #{type}, #{content}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ChatMemory memory);

    @Select("SELECT * FROM chat_memory WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<ChatMemory> findBySessionId(String sessionId);

    @Delete("DELETE FROM chat_memory WHERE session_id = #{sessionId}")
    void deleteBySessionId(String sessionId);
}