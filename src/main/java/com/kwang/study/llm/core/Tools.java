package com.kwang.study.llm.core;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kwang.study.utils.SpringContextUtil;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import lombok.*;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

public class Tools {
    @Data
    public static abstract class Tool {
        @JsonIgnore
        private String toolCallId;
        @JsonIgnore
        public abstract boolean isTerminal();

        // 非终端方法需要重写它
        public Object execute() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @JsonClassDescription("用于向用户回复的工具")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ReplayTool extends Tool {
        @JsonPropertyDescription("回复给用户的具体文本内容")
        public String message;

        @Override
        public boolean isTerminal() {
            return true;
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @JsonClassDescription("此工具用于执行SQL查询语句。如果用户的请求需要分析数据库来完成并且系统提示词里存在可以完成此任务的数据库表schema，你应该调用此工具。" +
            "\n注意：数据库版本是MySQL 8.0；不要执行 DELETE, UPDATE 或 INSERT 操作；最多仅一条sql语句")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SqlExecutorTool extends Tool {

        @JsonPropertyDescription("要执行的SQL查询语句。例如: SELECT `id`, `name` FROM users WHERE `status` = 'active' LIMIT 5" +
                "\n注意：仅包含sql语句不要包含其他不相关内容")
        public String sql;

        @Override
        public boolean isTerminal() {
            // SQL 执行通常是中间步骤，执行完后还需要模型总结数据给用户，所以不是终点
            return false;
        }

        @Override
        public Object execute() {
            // 安全性检查
            if (sql == null || !sql.trim().toUpperCase().startsWith("SELECT")) {
                return "Error: Only SELECT statements are allowed for safety reasons.";
            }

            try {
                // 获取 Spring 上下文中的 JdbcTemplate
                JdbcTemplate jdbcTemplate = SpringContextUtil.getBean(JdbcTemplate.class);

                // 执行查询
                // limit 限制，防止大模型查出全表把内存撑爆
                String finalSql = sql;
                if (!sql.toUpperCase().contains("LIMIT")) {
                    finalSql += " LIMIT 20";
                }

                List<Map<String, Object>> result = jdbcTemplate.queryForList(finalSql);

                if (result.isEmpty()) {
                    return "Query executed successfully but returned no results.";
                }
                return result;

            } catch (BadSqlGrammarException e) {
                return "SQL Syntax Error: " + e.getSQLException().getMessage();
            } catch (Exception e) {
                return "Database Error: " + e.getMessage();
            }
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @JsonClassDescription("此工具用于生成数据可视化图表（Apache ECharts）。" +
            "当用户请求以图表（如柱状图、折线图、饼图、散点图等）形式展示数据时，或者数据非常适合可视化分析时，必须调用此工具。" +
            "你需要根据上下文数据构建标准的 ECharts 'option' 配置对象。")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EChartsTool extends Tool {

        @JsonPropertyDescription("图表的文本解释或总结。用于告诉用户这张图表展示了什么信息。")
        public String explanation;

        @JsonPropertyDescription("Apache ECharts 的标准 option 配置对象的 JSON 字符串。" +
                "由于这是一个字符串，请确保正确转义内部的引号。" +
                "例如: \"{\\\"title\\\": {\\\"text\\\": \\\"Sales\\\"}, ...}\"")
        public String option;

        @Override
        public boolean isTerminal() {
            // 图表通常是最终的展示结果，所以作为终点
            return true;
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @JsonClassDescription("当用户要求生成作业、测验、考试题目时，调用此工具。支持单选、多选、问答题。")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public static class HomeworkGenerationTool extends Tool {
        @JsonPropertyDescription("作业的总标题")
        public Optional<String> title;

        @JsonPropertyDescription("作业的详细说明或导语 (Content)")
        public Optional<String> content;

        @JsonPropertyDescription("生成的题目列表")
        public Optional<List<GeneratedQuestion>> questions;

        @JsonPropertyDescription("需要删除的题目列表")
        public Optional<List<String>> deleteQuestions;

        @Data
        public static class GeneratedQuestion {
            @JsonPropertyDescription("题目ID。如果是修改已有的题目，必须回传原题目id；如果是新增题目，请置为null")
            public Optional<String> id;

            @JsonPropertyDescription("题目类型。必须是以下值之一: 'SINGLE_CHOICE' (单选), 'MULTI_CHOICE' (多选), 'TEXT' (简答/填空)")
            public Optional<String> type;

            @JsonPropertyDescription("题干内容")
            public Optional<String> title;

            @JsonPropertyDescription("该题分值")
            public Optional<Integer> score;

            @JsonPropertyDescription("选项列表。如果是选择题，必须提供。")
            public Optional<List<GeneratedOption>> options;

            @JsonPropertyDescription("正确答案列表。单选存一个标签如['A']；多选存多个标签如['A','B']；简答题存参考答案如['关键点1...']。")
            public Optional<List<String>> correctAnswer;

            @JsonPropertyDescription("题目解析。解释为什么选这个答案。")
            public Optional<String> analysis;

            @JsonPropertyDescription("AI评分标准。仅用于'TEXT'类型，描述得分点。")
            public Optional<String> aiGradingCriteria;
        }

        @Data
        public static class GeneratedOption {
            @JsonPropertyDescription("选项ID。如果是修改已有的选项，必须回传原选项id；如果是新增选项，请置为null")
            public Optional<String> id;
            @JsonPropertyDescription("选项标号，如 A, B, C, D")
            public Optional<String> label;
            @JsonPropertyDescription("选项的具体内容")
            public Optional<String> text;
        }

        @Override
        public boolean isTerminal() {
            return true;
        }
    }

    public static final List<Class<?>> SUPPORTED_TOOLS = List.of(ReplayTool.class,
            SqlExecutorTool.class,
            EChartsTool.class,
            HomeworkGenerationTool.class
    );

    public static List<Tools.Tool> convert(ChatCompletionMessage message, List<Class<?>> toolClasses) {
        Optional<List<ChatCompletionMessageToolCall>> toolCallsTemp = message.toolCalls();
        if (toolCallsTemp.isEmpty()) {
            return List.of();
        }
        List<ChatCompletionMessageToolCall> toolCalls = toolCallsTemp.get();
        HashMap<String, Class<?>> map = new HashMap<>(toolCalls.size());
        toolClasses.forEach(toolCall -> {
            map.put(toolCall.getSimpleName(), toolCall);
        });

        return toolCalls.stream()
                .map(toolCall -> {
                    ChatCompletionMessageFunctionToolCall function = toolCall.asFunction();
                    Class<?> target = map.get(function.function().name());
                    Assert.notNull(target, String.format("模型所选择工具不在传入的toolClasses中, %s, %s", function.function().name(), toolClasses));
                    Tool tool = (Tool) function.function().arguments(target);
                    tool.setToolCallId(function.id());
                    return tool;
                }).collect(Collectors.toList());
    }
}