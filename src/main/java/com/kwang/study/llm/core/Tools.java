package com.kwang.study.llm.core;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kwang.study.utils.SpringContextUtil;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import lombok.*;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;

import java.sql.SQLException;
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

//    @EqualsAndHashCode(callSuper = true)
//    @JsonClassDescription("此工具用于执行SQL查询语句。如果用户的请求需要分析数据库来完成并且系统提示词里存在可以完成此任务的数据库表schema，你应该调用此工具。" +
//            "\n注意：数据库版本是MySQL 8.0；不要执行 DELETE, UPDATE 或 INSERT 操作；最多仅一条sql语句")
//    @Data
//    @AllArgsConstructor
//    @NoArgsConstructor
//    public static class SqlExecutorTool extends Tool {
//
//        @JsonPropertyDescription("要执行的SQL查询语句。例如: SELECT `id`, `name` FROM users WHERE `status` = 'active' LIMIT 5" +
//                "\n注意：仅包含sql语句不要包含其他不相关内容")
//        public String sql;
//
//        @Override
//        public boolean isTerminal() {
//            // SQL 执行通常是中间步骤，执行完后还需要模型总结数据给用户，所以不是终点
//            return false;
//        }
//
//        @Override
//        public Object execute() {
//            try {
//                String processedSql = processSQL(sql);
//                // 获取 Spring 上下文中的 JdbcTemplate
//                JdbcTemplate jdbcTemplate = SpringContextUtil.getBean(JdbcTemplate.class);
//
//                List<Map<String, Object>> result = jdbcTemplate.queryForList(processedSql);
//
//                if (result.isEmpty()) {
//                    return "Query executed successfully but returned no results.";
//                }
//                return result;
//            } catch (BadSqlGrammarException e) {
//                return "SQL Syntax Error: " + e.getSQLException().getMessage();
//            } catch (Exception e) {
//                return "Database Error: " + e.getMessage();
//            }
//        }
//
//        private String processSQL(String sql) throws JSQLParserException {
//            if (sql == null)
//                throw new IllegalArgumentException("sql is null");
//            // 1. 解析SQL
//            Statement statement = CCJSqlParserUtil.parse(sql);
//            if (!(statement instanceof Select))
//                throw new JSQLParserException("select statement is not Select");
//
//            Select select = (Select) statement;
//
//            Limit limit = new Limit();
//            limit.setRowCount(new LongValue(20));
//            select.setLimit(limit);
//
//            // 5. 重新生成 SQL 并追加分号
//            return statement + ";";
//        }
//    }


    @EqualsAndHashCode(callSuper = true)
    @JsonClassDescription("当用户要求生成作业、测验、考试题目时，调用此工具。支持单选、多选、问答题。")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class HomeworkGenerationTool extends Tool {
        @JsonPropertyDescription("作业的总标题")
        public String title;

        @JsonPropertyDescription("作业的详细说明或导语 (Content)")
        public String content;

        @JsonPropertyDescription("生成的题目列表")
        public List<GeneratedQuestion> questions;

        @JsonPropertyDescription("需要删除的题目列表")
        public List<String> deleteQuestions;

        @Data
        public static class GeneratedQuestion {
            @JsonPropertyDescription("题目ID。如果是修改已有的题目，必须回传原题目id；如果是新增题目，请置为null")
            public String id;

            @JsonPropertyDescription("题目类型。必须是以下值之一: 'SINGLE_CHOICE' (单选), 'MULTI_CHOICE' (多选), 'TEXT' (简答/填空)")
            public String type;

            @JsonPropertyDescription("题干内容")
            public String title;

            @JsonPropertyDescription("该题分值")
            public Integer score;

            @JsonPropertyDescription("选项列表。如果是选择题，必须提供。")
            public List<GeneratedOption> options;

            @JsonPropertyDescription("正确答案列表。单选存一个标签如['A']；多选存多个标签如['A','B']；简答题存参考答案如['关键点1...']。" +
                    "在修改题目时，此值可能是已有的选项id而不是标签。")
            public List<String> correctAnswer;

            @JsonPropertyDescription("题目解析。解释为什么选这个答案。")
            public String analysis;

            @JsonPropertyDescription("AI评分标准。仅用于'TEXT'类型，描述得分点。")
            public String aiGradingCriteria;
        }

        @Data
        public static class GeneratedOption {
            @JsonPropertyDescription("选项ID。如果是修改已有的选项，必须回传原选项id；如果是新增选项，请置为null")
            public String id;
            @JsonPropertyDescription("选项标号，如 A, B, C, D")
            public String label;
            @JsonPropertyDescription("选项的具体内容")
            public String text;
        }

        @Override
        public boolean isTerminal() {
            return true;
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @JsonClassDescription("当系统请求你批改学生的主观题（简答题/填空题）时，调用此工具。你需要根据给定的题目内容、参考答案和评分标准，给出每道题的得分和评语，并给出一个总评。")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class HomeworkGradingTool extends Tool {
        @JsonPropertyDescription("教师对整份作业的总评语（鼓励为主，指出核心优缺点，语气要像一位负责任的老师）")
        public String generalComment;

        @JsonPropertyDescription("每道主观题的批改详情列表。")
        public List<GradingDetail> details;

        @Data
        public static class GradingDetail {
            @JsonPropertyDescription("题目ID (务必与输入数据中的题目ID保持完全一致)")
            public String questionId;

            @JsonPropertyDescription("该题得分 (必须是整数，且绝不能超过该题的满分)")
            public Integer score;

            @JsonPropertyDescription("该题的具体批改评语 (指出得分点或失分原因)")
            public String comment;
        }

        @Override
        public boolean isTerminal() {
            return true;
        }
    }


    public static final List<Class<?>> SUPPORTED_TOOLS = List.of(ReplayTool.class,
//            SqlExecutorTool.class,
            HomeworkGenerationTool.class,
            HomeworkGradingTool.class
    );

    public static List<Tools.Tool> convert(ChatCompletionMessage message, List<Class<?>> toolClasses) {
        Optional<List<ChatCompletionMessageToolCall>> toolCallsTemp = message.toolCalls();
        if (toolCallsTemp.isEmpty()) {
            return List.of();
        }
        // TODO: 没选择工具可以将内容包装成ReplayTool返回
        List<ChatCompletionMessageToolCall> toolCalls = toolCallsTemp.get();
        HashMap<String, Class<?>> map = new HashMap<>(toolCalls.size());
        toolClasses.forEach(toolCall -> {
            map.put(toolCall.getSimpleName(), toolCall);
        });

        return toolCalls.stream()
                .map(toolCall -> {
                    ChatCompletionMessageFunctionToolCall function = toolCall.asFunction();
                    Class<?> target = map.get(function.function().name());
                    Assert.notNull(target, String.format("模型所选择工具不在传入的toolClasses中, %s, %s", function.function().name(), toolClasses));Tool tool = (Tool) function.function().arguments(target);
                    tool.setToolCallId(function.id());
                    return tool;
                }).collect(Collectors.toList());
    }
}