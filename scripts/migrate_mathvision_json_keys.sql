-- =====================================================================================
-- MathVision: 落库 JSON 的键由 camelCase 统一为 snake_case (一次性迁移)
--
-- 涉及: mathvision_artifacts.artifact_json   (仅 code_generation / render_result 阶段)
--       mathvision_stage_results.result_json (全部阶段)
--       mathvision_versions.workflow_summary_json
--
-- 本文件由 migrate_mathvision_json_keys.py 的映射表生成, 两者改名规则一致。
-- 需要 MySQL 8.0+。可重复执行: 已是新格式的行不会再变化。
-- 执行前必须: 1) 停掉所有 Worker 和主服务  2) 备份三张表
-- 用法: 整个文件可以一次执行; 它会先建临时函数, 打印"预演"统计, 再迁移, 最后打印校验并清理函数。
--       如果想先只看预演, 执行到 "预演" 段落为止再决定是否继续。
-- =====================================================================================


DROP FUNCTION IF EXISTS mv_snake;
DROP FUNCTION IF EXISTS mv_rename;
DROP FUNCTION IF EXISTS mv_fix_artifact;
DROP FUNCTION IF EXISTS mv_fix_result;
DROP FUNCTION IF EXISTS mv_fix_summary;
DROP FUNCTION IF EXISTS mv_fix_CodeFixResult;
DROP FUNCTION IF EXISTS mv_fix_ElementRef;
DROP FUNCTION IF EXISTS mv_fix_RenderResult;
DROP FUNCTION IF EXISTS mv_fix_RuleCheck;
DROP FUNCTION IF EXISTS mv_fix_SceneCodeEntry;
DROP FUNCTION IF EXISTS mv_fix_StaticFinding;
DROP FUNCTION IF EXISTS mv_fix_StoryboardValidationTraceEntry;
DROP FUNCTION IF EXISTS mv_fix_CodeResult;
DROP FUNCTION IF EXISTS mv_fix_LayoutIssue;
DROP FUNCTION IF EXISTS mv_fix_ReviewSnapshot;
DROP FUNCTION IF EXISTS mv_fix_StaticAnalysis;
DROP FUNCTION IF EXISTS mv_fix_StoryboardValidationReport;
DROP FUNCTION IF EXISTS mv_fix_EvaluationAttempt;
DROP FUNCTION IF EXISTS mv_fix_SampleEvaluation;
DROP FUNCTION IF EXISTS mv_fix_CodeEvaluationResult;
DROP FUNCTION IF EXISTS mv_fix_SceneEvaluationResult;
DROP FUNCTION IF EXISTS mv_list_CodeEvaluationResult;
DROP FUNCTION IF EXISTS mv_list_CodeFixResult;
DROP FUNCTION IF EXISTS mv_list_EvaluationAttempt;
DROP FUNCTION IF EXISTS mv_list_LayoutIssue;
DROP FUNCTION IF EXISTS mv_list_RuleCheck;
DROP FUNCTION IF EXISTS mv_list_SampleEvaluation;
DROP FUNCTION IF EXISTS mv_list_SceneCodeEntry;
DROP FUNCTION IF EXISTS mv_list_StaticFinding;
DROP FUNCTION IF EXISTS mv_list_StoryboardValidationTraceEntry;

DELIMITER $$

CREATE FUNCTION mv_snake(k VARCHAR(255)) RETURNS VARCHAR(255) DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE c CHAR(1);
  DECLARE r VARCHAR(255) DEFAULT '';
  WHILE i <= CHAR_LENGTH(k) DO
    SET c = SUBSTRING(k, i, 1);
    IF i > 1 AND ASCII(c) BETWEEN 65 AND 90
       AND (ASCII(SUBSTRING(k, i - 1, 1)) BETWEEN 97 AND 122 OR ASCII(SUBSTRING(k, i - 1, 1)) BETWEEN 48 AND 57) THEN
      SET r = CONCAT(r, '_', LOWER(c));
    ELSE
      SET r = CONCAT(r, LOWER(c));
    END IF;
    SET i = i + 1;
  END WHILE;
  RETURN r;
END$$

-- 改名 doc 的键。mode='table' 按映射表 m; mode='snake' 对所有键转下划线。
-- 新键已存在时保留新键, 丢弃旧键。
CREATE FUNCTION mv_rename(doc JSON, m JSON, mode VARCHAR(8)) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE keys_ JSON;
  DECLARE n INT;
  DECLARE i INT DEFAULT 0;
  DECLARE k VARCHAR(255);
  DECLARE nk VARCHAR(255);
  DECLARE res JSON DEFAULT JSON_OBJECT();
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET keys_ = JSON_KEYS(doc);
  SET n = JSON_LENGTH(keys_);
  WHILE i < n DO
    SET k = JSON_UNQUOTE(JSON_EXTRACT(keys_, CONCAT('$[', i, ']')));
    IF mode = 'snake' THEN SET nk = mv_snake(k);
    ELSEIF JSON_CONTAINS_PATH(m, 'one', CONCAT('$."', k, '"')) THEN SET nk = JSON_UNQUOTE(JSON_EXTRACT(m, CONCAT('$."', k, '"')));
    ELSE SET nk = k; END IF;
    IF nk = k THEN SET res = JSON_SET(res, CONCAT('$."', k, '"'), JSON_EXTRACT(doc, CONCAT('$."', k, '"'))); END IF;
    SET i = i + 1;
  END WHILE;
  SET i = 0;
  WHILE i < n DO
    SET k = JSON_UNQUOTE(JSON_EXTRACT(keys_, CONCAT('$[', i, ']')));
    IF mode = 'snake' THEN SET nk = mv_snake(k);
    ELSEIF JSON_CONTAINS_PATH(m, 'one', CONCAT('$."', k, '"')) THEN SET nk = JSON_UNQUOTE(JSON_EXTRACT(m, CONCAT('$."', k, '"')));
    ELSE SET nk = k; END IF;
    IF nk <> k AND NOT JSON_CONTAINS_PATH(res, 'one', CONCAT('$."', nk, '"')) THEN
      SET res = JSON_SET(res, CONCAT('$."', nk, '"'), JSON_EXTRACT(doc, CONCAT('$."', k, '"')));
    END IF;
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_CodeFixResult(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('acceptanceIssues','acceptance_issues','acceptanceWarnings','acceptance_warnings','currentRequestPrompt','current_request_prompt','errorReason','error_reason','executionTimeSeconds','execution_time_seconds','failureReason','failure_reason','fixedContextPrompt','fixed_context_prompt','fixedGeneratedCode','fixed_generated_code','originalGeneratedCode','original_generated_code','postFixStaticAuditIssueCount','post_fix_static_audit_issue_count','postFixStaticAuditSummary','post_fix_static_audit_summary','rulesPrompt','rules_prompt','toolCalls','tool_calls'), 'table');
  RETURN d;
END$$

CREATE FUNCTION mv_list_CodeFixResult(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_CodeFixResult(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_ElementRef(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('className','class_name','sampleRole','sample_role','semanticName','semantic_name','stableId','stable_id'), 'table');
  RETURN d;
END$$

CREATE FUNCTION mv_fix_RenderResult(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('artifactFileName','artifact_file_name','artifactMimeType','artifact_mime_type','artifactPath','artifact_path','artifactReceipt','artifact_receipt','artifactType','artifact_type','executionTimeSeconds','execution_time_seconds','finalGeneratedCode','final_generated_code','geometryPath','geometry_path','lastError','last_error','localArtifactPath','local_artifact_path','outputTarget','output_target','sceneName','scene_name','storageArtifactPath','storage_artifact_path','toolCalls','tool_calls','videoPath','video_path'), 'table');
  RETURN d;
END$$

CREATE FUNCTION mv_fix_RuleCheck(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('ruleId','rule_id'), 'table');
  RETURN d;
END$$

CREATE FUNCTION mv_list_RuleCheck(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_RuleCheck(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_SceneCodeEntry(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT(), 'table');
  RETURN d;
END$$

CREATE FUNCTION mv_list_SceneCodeEntry(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_SceneCodeEntry(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_StaticFinding(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('ruleId','rule_id'), 'table');
  RETURN d;
END$$

CREATE FUNCTION mv_list_StaticFinding(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_StaticFinding(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_StoryboardValidationTraceEntry(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('cleanupAttempt','cleanup_attempt','executionTimeSeconds','execution_time_seconds','fixApplied','fix_applied','fixAttempted','fix_attempted','issueCount','issue_count','sceneCount','scene_count','toolCalls','tool_calls'), 'table');
  RETURN d;
END$$

CREATE FUNCTION mv_list_StoryboardValidationTraceEntry(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_StoryboardValidationTraceEntry(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_CodeResult(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('artifactFormat','artifact_format','artifactName','artifact_name','executionTimeSeconds','execution_time_seconds','generatedCode','generated_code','headerCode','header_code','outputTarget','output_target','sceneEntries','scene_entries','sceneName','scene_name','targetConcept','target_concept','targetDescription','target_description','toolCalls','tool_calls'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.scene_entries')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.scene_entries', mv_list_SceneCodeEntry(JSON_EXTRACT(d, '$.scene_entries'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_fix_LayoutIssue(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('intersectionArea','intersection_area','intersectionBounds','intersection_bounds','likelyFalsePositive','likely_false_positive','primaryElement','primary_element','reasonCode','reason_code','recommendedAction','recommended_action','secondaryElement','secondary_element'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.primary_element')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.primary_element', mv_fix_ElementRef(JSON_EXTRACT(d, '$.primary_element'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.secondary_element')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.secondary_element', mv_fix_ElementRef(JSON_EXTRACT(d, '$.secondary_element'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_list_LayoutIssue(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_LayoutIssue(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_ReviewSnapshot(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('approvedForRender','approved_for_render','blockingIssues','blocking_issues','revisionDirectives','revision_directives','ruleChecks','rule_checks'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.rule_checks')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.rule_checks', mv_list_RuleCheck(JSON_EXTRACT(d, '$.rule_checks'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_fix_StaticAnalysis(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('arrangeCount','arrange_count','className','class_name','codeLines','code_lines','fadeInCount','fade_in_count','fadeOutCount','fade_out_count','fadeTransformCount','fade_transform_count','hasConstruct','has_construct','largeShiftCount','large_shift_count','mathTexCount','math_tex_count','maxEnteringObjects','max_entering_objects','maxNarrationWordsPerSecond','max_narration_words_per_second','maxVisibleObjects','max_visible_objects','maxVisibleTextualObjects','max_visible_textual_objects','minNarrationWordsPerSecond','min_narration_words_per_second','nextToCount','next_to_count','replacementTransformCount','replacement_transform_count','sceneCount','scene_count','shiftCount','shift_count','textCount','text_count','threeDObjectCount','three_dobject_count','threeDScene','three_dscene','threeDStoryboardSceneCount','three_dstoryboard_scene_count','toEdgeCount','to_edge_count','transformCount','transform_count','usesManimImport','uses_manim_import'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.findings')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.findings', mv_list_StaticFinding(JSON_EXTRACT(d, '$.findings'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_fix_StoryboardValidationReport(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('finalIssueCount','final_issue_count','finalIssues','final_issues','fixApplied','fix_applied','fixAttempted','fix_attempted','initialIssueCount','initial_issue_count','initialIssues','initial_issues','outputTarget','output_target','resolvedIssueCount','resolved_issue_count','sceneCount','scene_count','totalValidationEvents','total_validation_events'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.entries')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.entries', mv_list_StoryboardValidationTraceEntry(JSON_EXTRACT(d, '$.entries'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_fix_EvaluationAttempt(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('approvedForRender','approved_for_render','gateReason','gate_reason','sceneName','scene_name','staticAnalysis','static_analysis'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.review')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.review', mv_fix_ReviewSnapshot(JSON_EXTRACT(d, '$.review'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.static_analysis')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.static_analysis', mv_fix_StaticAnalysis(JSON_EXTRACT(d, '$.static_analysis'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_list_EvaluationAttempt(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_EvaluationAttempt(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_SampleEvaluation(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('blockingIssueCount','blocking_issue_count','elementCount','element_count','hasIssues','has_issues','issueCount','issue_count','offscreenIssueCount','offscreen_issue_count','overlapIssueCount','overlap_issue_count','playIndex','play_index','sampleId','sample_id','sampleRole','sample_role','sceneMethod','scene_method','sourceCode','source_code'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.issues')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.issues', mv_list_LayoutIssue(JSON_EXTRACT(d, '$.issues'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_list_SampleEvaluation(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_SampleEvaluation(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_CodeEvaluationResult(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('approvedForRender','approved_for_render','executionTimeSeconds','execution_time_seconds','finalReview','final_review','finalStaticAnalysis','final_static_analysis','gateReason','gate_reason','initialReview','initial_review','initialStaticAnalysis','initial_static_analysis','revisedCodeApplied','revised_code_applied','revisionAttempts','revision_attempts','revisionTriggered','revision_triggered','sceneName','scene_name','toolCalls','tool_calls','totalEvaluations','total_evaluations'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.attempts')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.attempts', mv_list_EvaluationAttempt(JSON_EXTRACT(d, '$.attempts'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.final_review')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.final_review', mv_fix_ReviewSnapshot(JSON_EXTRACT(d, '$.final_review'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.final_static_analysis')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.final_static_analysis', mv_fix_StaticAnalysis(JSON_EXTRACT(d, '$.final_static_analysis'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.initial_review')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.initial_review', mv_fix_ReviewSnapshot(JSON_EXTRACT(d, '$.initial_review'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.initial_static_analysis')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.initial_static_analysis', mv_fix_StaticAnalysis(JSON_EXTRACT(d, '$.initial_static_analysis'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_list_CodeEvaluationResult(arr JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE res JSON DEFAULT JSON_ARRAY();
  IF arr IS NULL OR JSON_TYPE(arr) <> 'ARRAY' THEN RETURN arr; END IF;
  WHILE i < JSON_LENGTH(arr) DO
    SET res = JSON_ARRAY_APPEND(res, '$', mv_fix_CodeEvaluationResult(JSON_EXTRACT(arr, CONCAT('$[', i, ']'))));
    SET i = i + 1;
  END WHILE;
  RETURN res;
END$$

CREATE FUNCTION mv_fix_SceneEvaluationResult(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT('blockingIssueCount','blocking_issue_count','executionTimeSeconds','execution_time_seconds','gateReason','gate_reason','geometryPath','geometry_path','issueSampleCount','issue_sample_count','offscreenIssueCount','offscreen_issue_count','overlapIssueCount','overlap_issue_count','renderSuccess','render_success','revisionAttempts','revision_attempts','revisionTriggered','revision_triggered','sampleCount','sample_count','sceneName','scene_name','toolCalls','tool_calls','totalIssueCount','total_issue_count'), 'table');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.samples')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.samples', mv_list_SampleEvaluation(JSON_EXTRACT(d, '$.samples'))); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_fix_artifact(stage VARCHAR(40), doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  IF stage = 'code_generation' THEN RETURN mv_fix_CodeResult(doc); END IF;
  IF stage = 'render_result' THEN RETURN mv_fix_RenderResult(doc); END IF;
  RETURN doc;
END$$

CREATE FUNCTION mv_fix_result(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT(), 'snake');
  IF JSON_TYPE(JSON_EXTRACT(d, '$.code_evaluation')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.code_evaluation', mv_fix_CodeEvaluationResult(JSON_EXTRACT(d, '$.code_evaluation'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.code_evaluation_attempts')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.code_evaluation_attempts', mv_list_CodeEvaluationResult(JSON_EXTRACT(d, '$.code_evaluation_attempts'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.code_fix_trace')) = 'ARRAY' THEN SET d = JSON_SET(d, '$.code_fix_trace', mv_list_CodeFixResult(JSON_EXTRACT(d, '$.code_fix_trace'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.last_render_result')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.last_render_result', mv_fix_RenderResult(JSON_EXTRACT(d, '$.last_render_result'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.render_result')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.render_result', mv_fix_RenderResult(JSON_EXTRACT(d, '$.render_result'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.scene_evaluation')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.scene_evaluation', mv_fix_SceneEvaluationResult(JSON_EXTRACT(d, '$.scene_evaluation'))); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.validation_report')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.validation_report', mv_fix_StoryboardValidationReport(JSON_EXTRACT(d, '$.validation_report'))); END IF;
  -- quality_review 内部键; internal_checkpoints 只改检查点名称, 其中的文档本身不动
  IF JSON_TYPE(JSON_EXTRACT(d, '$.quality_review')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.quality_review', mv_rename(JSON_EXTRACT(d, '$.quality_review'), JSON_OBJECT(), 'snake')); END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.internal_checkpoints')) = 'OBJECT' THEN SET d = JSON_SET(d, '$.internal_checkpoints', mv_rename(JSON_EXTRACT(d, '$.internal_checkpoints'), JSON_OBJECT('enrichedGraph','enriched_graph','explorationGraph','exploration_graph','geometryReport','geometry_report','validatedStoryboard','validated_storyboard','visualDesign','visual_design'), 'table')); END IF;
  RETURN d;
END$$

CREATE FUNCTION mv_fix_summary(doc JSON) RETURNS JSON DETERMINISTIC
BEGIN
  DECLARE d JSON;
  DECLARE stages JSON;
  DECLARE keys_ JSON;
  DECLARE i INT DEFAULT 0;
  DECLARE s VARCHAR(255);
  DECLARE sd JSON;
  IF doc IS NULL OR JSON_TYPE(doc) <> 'OBJECT' THEN RETURN doc; END IF;
  SET d = mv_rename(doc, JSON_OBJECT(), 'snake');
  -- execution_commits 的键是锁令牌, 值必须原样保留 (上一步只改了它自己的键名)
  IF JSON_TYPE(JSON_EXTRACT(doc, '$.executionCommits')) = 'OBJECT' AND NOT JSON_CONTAINS_PATH(doc, 'one', '$.execution_commits') THEN
    SET d = JSON_SET(d, '$.execution_commits', JSON_EXTRACT(doc, '$.executionCommits'));
  END IF;
  IF JSON_TYPE(JSON_EXTRACT(d, '$.stages')) = 'OBJECT' THEN
    SET stages = JSON_EXTRACT(d, '$.stages');
    SET keys_ = JSON_KEYS(stages);
    WHILE i < JSON_LENGTH(keys_) DO
      SET s = JSON_UNQUOTE(JSON_EXTRACT(keys_, CONCAT('$[', i, ']')));
      SET sd = mv_rename(JSON_EXTRACT(stages, CONCAT('$."', s, '"')), JSON_OBJECT(), 'snake');
      IF JSON_TYPE(JSON_EXTRACT(sd, '$.result')) = 'OBJECT' THEN SET sd = JSON_SET(sd, '$.result', mv_fix_result(JSON_EXTRACT(sd, '$.result'))); END IF;
      SET stages = JSON_SET(stages, CONCAT('$."', s, '"'), sd);
      SET i = i + 1;
    END WHILE;
    SET d = JSON_SET(d, '$.stages', stages);
  END IF;
  RETURN d;
END$$

DELIMITER ;

-- =====================================================================================
-- 预演: 只统计将要改动的行数, 不写入。
-- =====================================================================================
SELECT 'mathvision_artifacts' AS tbl, COUNT(*) AS rows_to_change
  FROM mathvision_artifacts
 WHERE artifact_json IS NOT NULL AND stage IN ('code_generation', 'render_result')
   AND CAST(mv_fix_artifact(stage, artifact_json) AS CHAR) <> CAST(artifact_json AS CHAR)
UNION ALL
SELECT 'mathvision_stage_results', COUNT(*) FROM mathvision_stage_results
 WHERE result_json IS NOT NULL AND CAST(mv_fix_result(result_json) AS CHAR) <> CAST(result_json AS CHAR)
UNION ALL
SELECT 'mathvision_versions', COUNT(*) FROM mathvision_versions
 WHERE workflow_summary_json IS NOT NULL AND CAST(mv_fix_summary(workflow_summary_json) AS CHAR) <> CAST(workflow_summary_json AS CHAR);

-- =====================================================================================
-- 正式迁移。update_time = update_time 保证更新时间不被改动。
-- =====================================================================================
START TRANSACTION;

UPDATE mathvision_artifacts
   SET artifact_json = mv_fix_artifact(stage, artifact_json), update_time = update_time
 WHERE artifact_json IS NOT NULL AND stage IN ('code_generation', 'render_result')
   AND CAST(mv_fix_artifact(stage, artifact_json) AS CHAR) <> CAST(artifact_json AS CHAR);

UPDATE mathvision_stage_results
   SET result_json = mv_fix_result(result_json), update_time = update_time
 WHERE result_json IS NOT NULL AND CAST(mv_fix_result(result_json) AS CHAR) <> CAST(result_json AS CHAR);

UPDATE mathvision_versions
   SET workflow_summary_json = mv_fix_summary(workflow_summary_json), update_time = update_time
 WHERE workflow_summary_json IS NOT NULL AND CAST(mv_fix_summary(workflow_summary_json) AS CHAR) <> CAST(workflow_summary_json AS CHAR);

-- 校验: 三个 cnt 都应为 0。若不是 0, 不要提交, 改为执行 ROLLBACK; 并联系开发者。
SELECT 'artifacts 仍有旧键' AS item, COUNT(*) AS cnt FROM mathvision_artifacts
 WHERE (stage = 'code_generation' AND JSON_CONTAINS_PATH(artifact_json, 'one', '$.generatedCode', '$.sceneName', '$.outputTarget', '$.artifactFormat'))
    OR (stage = 'render_result' AND JSON_CONTAINS_PATH(artifact_json, 'one', '$.artifactPath', '$.artifactType', '$.finalGeneratedCode', '$.videoPath'))
UNION ALL
SELECT 'stage_results 仍有旧键', COUNT(*) FROM mathvision_stage_results
 WHERE JSON_CONTAINS_PATH(result_json, 'one', '$.apiCalls', '$.qualityReview', '$.renderResult', '$.sceneEvaluation', '$.codeEvaluation', '$.internalCheckpoints', '$.validationReport')
UNION ALL
SELECT 'versions 仍有旧键', COUNT(*) FROM mathvision_versions
 WHERE JSON_CONTAINS_PATH(workflow_summary_json, 'one', '$.totalApiCalls', '$.executionCommits', '$.taskId');

COMMIT;

-- 清理临时函数
DROP FUNCTION IF EXISTS mv_snake;
DROP FUNCTION IF EXISTS mv_rename;
DROP FUNCTION IF EXISTS mv_fix_artifact;
DROP FUNCTION IF EXISTS mv_fix_result;
DROP FUNCTION IF EXISTS mv_fix_summary;
DROP FUNCTION IF EXISTS mv_fix_CodeFixResult;
DROP FUNCTION IF EXISTS mv_fix_ElementRef;
DROP FUNCTION IF EXISTS mv_fix_RenderResult;
DROP FUNCTION IF EXISTS mv_fix_RuleCheck;
DROP FUNCTION IF EXISTS mv_fix_SceneCodeEntry;
DROP FUNCTION IF EXISTS mv_fix_StaticFinding;
DROP FUNCTION IF EXISTS mv_fix_StoryboardValidationTraceEntry;
DROP FUNCTION IF EXISTS mv_fix_CodeResult;
DROP FUNCTION IF EXISTS mv_fix_LayoutIssue;
DROP FUNCTION IF EXISTS mv_fix_ReviewSnapshot;
DROP FUNCTION IF EXISTS mv_fix_StaticAnalysis;
DROP FUNCTION IF EXISTS mv_fix_StoryboardValidationReport;
DROP FUNCTION IF EXISTS mv_fix_EvaluationAttempt;
DROP FUNCTION IF EXISTS mv_fix_SampleEvaluation;
DROP FUNCTION IF EXISTS mv_fix_CodeEvaluationResult;
DROP FUNCTION IF EXISTS mv_fix_SceneEvaluationResult;
DROP FUNCTION IF EXISTS mv_list_CodeEvaluationResult;
DROP FUNCTION IF EXISTS mv_list_CodeFixResult;
DROP FUNCTION IF EXISTS mv_list_EvaluationAttempt;
DROP FUNCTION IF EXISTS mv_list_LayoutIssue;
DROP FUNCTION IF EXISTS mv_list_RuleCheck;
DROP FUNCTION IF EXISTS mv_list_SampleEvaluation;
DROP FUNCTION IF EXISTS mv_list_SceneCodeEntry;
DROP FUNCTION IF EXISTS mv_list_StaticFinding;
DROP FUNCTION IF EXISTS mv_list_StoryboardValidationTraceEntry;
