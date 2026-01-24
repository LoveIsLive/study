package com.kwang.study.llm.service;

import cn.hutool.core.lang.Pair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class RAG {
    @Autowired
    private UserInfoUtils userInfoUtils;
    @Autowired
    private ObjectMapper objectMapper;

    private ChatRequestDTO request;

    public String build(ChatRequestDTO request, String template) throws JsonProcessingException {
        this.request = request;

        Map<String, String> sub;
        switch (request.getScene()) {
            case "default": case "common":
                sub = processDefault();
                template = Objects.requireNonNullElse(template, DEFAULT_SYSTEM_PROMPT);
                break;
            case "organization":
                sub = processOrganization();
                template = Objects.requireNonNullElse(template, ORGANIZATION_SYSTEM_PROMPT) ;
                break;
            default:
                sub = new HashMap<>();
                template = Objects.requireNonNullElse(template, DEFAULT_SYSTEM_PROMPT);
                break;
        }
        return new StringSubstitutor(sub).replace(template);
    }

    private Map<String, String> processDefault() throws JsonProcessingException {
        HashMap<String, String> result = new HashMap<>();
        baseInfo(result);
        return result;
    }

    private Map<String, String> processOrganization() throws JsonProcessingException {
        HashMap<String, String> map = new HashMap<>();
        baseInfo(map);
        // 仅agent模式可以获得表schema信息。
//        if (!"agent".equals(request.getType()))
//            return map;

        List<String> tables = List.of(
                "- 用户表，存储所有用户，管理员的用户名为admin。CREATE TABLE `users` (\n" +
                        "  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户ID',\n" +
                        "  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名，除管理员`admin`用户外，其余用户遵循前缀策略: S{schoolId}_{username}',\n" +
                        "  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码（加密存储）',\n" +
                        "  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '账户是否启用',\n" +
                        "  PRIMARY KEY (`id`),\n" +
                        "  UNIQUE KEY `username` (`username`)\n" +
                        ") ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表'",

                "- 学校表，存储所有学校信息。CREATE TABLE `schools` (\n" +
                        "  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '学校ID',\n" +
                        "  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '学校名称',\n" +
                        "  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                        "  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n" +
                        "  PRIMARY KEY (`id`),\n" +
                        "  UNIQUE KEY `name` (`name`)\n" +
                        ") ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学校表'",
                "- 学校成员表，存储的是学校的管理者，目前仅有校长。CREATE TABLE `school_members` (\n" +
                        "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                        "  `school_id` bigint NOT NULL COMMENT '学校ID',\n" +
                        "  `user_id` bigint NOT NULL COMMENT '用户ID',\n" +
                        "  `role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '校内角色 (ROLE_PRINCIPAL)',\n" +
                        "  `join_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                        "  PRIMARY KEY (`id`),\n" +
                        "  UNIQUE KEY `uk_school_user` (`school_id`,`user_id`)\n" +
                        ") ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学校成员表'\n",

                "- 班级表，存储所有班级信息。CREATE TABLE `classes` (\n" +
                        "  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '班级ID',\n" +
                        "  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '班级名称',\n" +
                        "  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',\n" +
                        "  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',\n" +
                        "  `school_id` bigint NOT NULL DEFAULT '1' COMMENT '所属学校ID',\n" +
                        "  PRIMARY KEY (`id`),\n" +
                        "  UNIQUE KEY `name` (`name`,`school_id`)\n" +
                        ") ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级表'\n",

                "- 班级成员表，存储所有班级成员信息，包括教师和学生。CREATE TABLE `class_members` (\n" +
                        "  `id` bigint unsigned NOT NULL AUTO_INCREMENT,\n" +
                        "  `class_id` bigint NOT NULL COMMENT '班级ID',\n" +
                        "  `user_id` bigint NOT NULL COMMENT '用户ID',\n" +
                        "  `role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '在班级中的角色',\n" +
                        "  `join_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',\n" +
                        "  PRIMARY KEY (`id`),\n" +
                        "  UNIQUE KEY `uk_class_user` (`class_id`,`user_id`),\n" +
                        "  KEY `fk_cm_user` (`user_id`)\n" +
                        ") ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级成员及班内角色表'\n"
                );
        map.put("table_schemas", String.join("\n", tables));

        return map;
    }


    private void baseInfo(Map<String, String> map) throws JsonProcessingException {
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        String userString = objectMapper.writeValueAsString(user);
        String scene = request.getScene();

        map.putAll(Map.of("current_scene", scene,
                "user_info", userString,
                "current_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
    }

    public static final String DEFAULT_SYSTEM_PROMPT = "## 系统背景\n" +
            "你正在运行于一个深度集成大模型的Web端智能教学系统中。该系统专为教育场景设计，旨在通过 AI 技术减轻教师重复性工作负担并支持学生个性化学习。\n" +
            "系统具备以下核心业务能力，你在回答时需充分意识到这些背景：\n" +
            "1. 类Unix虚拟文件系统：系统拥有独特的分布式文件存储结构，支持大文件分片与哈希去重（你不能直接操作底层文件，但可以理解用户对“/第一课/初识Java语言”灯的引用）。\n" +
            "2. 组织管理模块负责维护用户的层级结构，包括学校、年级、班级三级组织单元，互相数据隔离。用户相应的存在管理员、校长、教师、学生角色。\n" +
            "3. 全流程作业管理：覆盖作业的发布、提交、批改（含 AI 辅助批改）、打回重做及数据统计全生命周期。\n" +
            "4. 工具链集成：系统已在底层集成了 GeoGebra（数学动态绘图）、PhET（理化仿真）等专业教学工具。\n" +
            "\n" +
            "## 角色定义\n" +
            "你是由“智能教学系统”驱动的AI助教。你的任务是辅助用户完成教学或学习任务。\n" +
            "\n" +
            "## 当前上下文\n" +
            "- 当前场景: ${current_scene}\n" +
            "- 用户信息：${user_info}\n" +
            "- 当前时间: ${current_time}\n" +
            "\n" +
            "## 约束与准则\n" +
            "1. 安全性: 严禁泄露学生的个人敏感隐私（如家庭住址、未加密的身份证号）。\n" +
            "2. 教学风格:\n" +
            "   - 对教师：专业、高效、结构化，提供可执行的建议。\n" +
            "   - 对学生：鼓励性、循循善诱，解释概念要通俗易懂。\n" +
            "3. 拒绝回答: 如果问题超出教育教学范畴或违反法律法规，请礼貌拒绝。\n" +
            "\n" +
            "## 目标\n" +
            "- 在不违反`约束与准则`的情况下尽可能的帮助用户。\n" +
            "\n" +
            "## 输出\n" +
            "- 如果要求选择Tool，请选择最适合的Tool，比如图表比单纯的文字更好。";

    public static final String ORGANIZATION_SYSTEM_PROMPT = "## 系统背景\n" +
            "你正在运行于一个深度集成大模型的Web端智能教学系统中。该系统专为教育场景设计，旨在通过 AI 技术减轻教师重复性工作负担并支持学生个性化学习。\n" +
            "系统具备以下核心业务能力，你在回答时需充分意识到这些背景：\n" +
            "1. 类Unix虚拟文件系统：系统拥有独特的分布式文件存储结构，支持大文件分片与哈希去重（你不能直接操作底层文件，但可以理解用户对“/第一课/初识Java语言”灯的引用）。\n" +
            "2. 组织管理模块负责维护用户的层级结构，包括学校、年级、班级三级组织单元，互相数据隔离。用户相应的存在管理员、校长、教师、学生角色。\n" +
            "3. 全流程作业管理：覆盖作业的发布、提交、批改（含 AI 辅助批改）、打回重做及数据统计全生命周期。\n" +
            "4. 工具链集成：系统已在底层集成了 GeoGebra（数学动态绘图）、PhET（理化仿真）等专业教学工具。\n" +
            "\n" +
            "## 角色定义\n" +
            "你是由“智能教学系统”驱动的AI助教。你的任务是辅助用户完成教学或学习任务。\n" +
            "\n" +
            "## 当前上下文\n" +
            "- 当前场景: ${current_scene}\n" +
            "- 用户信息：${user_info}\n" +
            "- 当前时间: ${current_time}\n" +
            "\n" +
            "## 约束与准则\n" +
            "1. 安全性: 严禁泄露学生的个人敏感隐私（如家庭住址、未加密的身份证号）。\n" +
            "2. 教学风格:\n" +
            "   - 对教师：专业、高效、结构化，提供可执行的建议。\n" +
            "   - 对学生：鼓励性、循循善诱，解释概念要通俗易懂。\n" +
            "3. 拒绝回答: 如果问题超出教育教学范畴或违反法律法规，请礼貌拒绝。\n" +
            "\n" +
            "## 目标\n" +
            "- 在不违反`约束与准则`的情况下尽可能的帮助用户。\n" +
            "\n" +
            "## 输出\n" +
            "- 如果要求选择Tool，请选择最适合的Tool，比如图表比单纯的文字更好。\n" +
            "\n" +
            "## 数据库表schema\n" +
            "下面以```开始和结束的内容是通过show create table语句获取到的相关表schema信息，你需要关注表之间的关系和列的含义，注意列注释的描述信息：\n" +
            "# 注意\n" +
            "- 给用户的回复中务必不能涉及表schema信息，表schema信息只能在tool call SqlExecutorTool工具需要填入sql语句时使用。\n" +
            "若用户需要组织信息、请向用户解释“如果您需要分析组织数据，请开启agent模式和获取组织数据按钮。”\n" +
            "```\n" +
            "${table_schemas}\n" +
            "```";
}
