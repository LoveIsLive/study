package com.kwang.study.llm.mapper;

import com.kwang.study.llm.pojo.ChatSession;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatSessionMapper {
    @Insert("INSERT INTO chat_sessions(session_id, user_id, title, create_time, update_time) " +
            "VALUES(#{sessionId}, #{userId}, #{title}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ChatSession session);

    @Select("SELECT * FROM chat_sessions WHERE session_id = #{sessionId}")
    ChatSession findBySessionId(String sessionId);

    @Select("SELECT * FROM chat_sessions WHERE user_id = #{userId} ORDER BY update_time DESC")
    List<ChatSession> findByUserId(Long userId);

    @Update("UPDATE chat_sessions SET update_time = NOW() WHERE session_id = #{sessionId}")
    void updateTime(String sessionId);

    // 可以添加修改标题的方法
    @Update("UPDATE chat_sessions SET title = #{title} WHERE session_id = #{sessionId}")
    void updateTitle(String sessionId, String title);

    @Delete("DELETE FROM chat_sessions WHERE session_id = #{sessionId}")
    int deleteBySessionId(String sessionId);
}