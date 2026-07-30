package com.kwang.study.llm.mapper;

import com.kwang.study.llm.pojo.ChatSession;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatSessionMapper {
    @Insert("INSERT INTO chat_sessions(session_id, user_id, title, purpose, create_time, update_time) " +
            "VALUES(#{sessionId}, #{userId}, #{title}, #{purpose}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ChatSession session);

    @Select("SELECT * FROM chat_sessions WHERE session_id = #{sessionId}")
    ChatSession findBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM chat_sessions WHERE user_id = #{userId} ORDER BY update_time DESC")
    List<ChatSession> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM chat_sessions WHERE user_id = #{userId} AND purpose = #{purpose} ORDER BY update_time DESC")
    List<ChatSession> findByUserIdAndPurpose(@Param("userId") Long userId, @Param("purpose") String purpose);

    @Update("UPDATE chat_sessions SET update_time = NOW() WHERE session_id = #{sessionId}")
    void updateTime(@Param("sessionId") String sessionId);

    @Update("UPDATE chat_sessions SET title = #{title}, update_time = NOW() WHERE session_id = #{sessionId}")
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    @Delete("DELETE FROM chat_sessions WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
