-- =============================================================
-- MathVision 教学动画/交互图生成模块 - 数据库 Schema
-- MySQL 8.0+ / InnoDB / utf8mb4
--
-- 复用已有表: chat_sessions (purpose='mathvision') / chat_memory  -- 本文件不重建
-- 新增/改造表:
--   1) llm_model_configs        LLM 模型 / API Key 配置 (每用户每厂家)
--   2) mathvision_tasks         任务主表 (与 chat_sessions 1:1)
--   3) mathvision_artifacts     阶段核心产物 (按 task+stage 独立版本, 产物列合一)
--   4) mathvision_stage_results 阶段校验/执行结果 (同 grain, 结果列合一)
--   5) mathvision_versions      任务版本组合表 (UX 的 V1/V2/V3)
--   6) mathvision_square_posts  广场发布记录 (绑定任务的指定版本)
-- =============================================================

-- -------------------------------------------------------------
-- 1) LLM 模型 / API Key 配置
-- -------------------------------------------------------------
-- 供应商与模型目录改由 Nacos (dataId: math-vision) 声明, 本表只存每用户每厂家的 API Key 凭据。
CREATE TABLE `llm_model_configs` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `owner_user_id`      BIGINT       NOT NULL                COMMENT '配置所属用户ID',
  `provider`           VARCHAR(32)  NOT NULL                COMMENT 'openai/anthropic/google/moonshot/zhipu',
  `api_key_encrypted`  TEXT         NULL                    COMMENT '应用层加密后的 API Key',
  `api_key_masked`     VARCHAR(64)  NULL                    COMMENT '脱敏展示值, 如 sk-****abcd',
  `status`             VARCHAR(16)  NOT NULL DEFAULT 'enabled' COMMENT 'enabled/disabled/invalid/not_configured',
  `last_test_time`     DATETIME     NULL                    COMMENT '最近一次测试时间',
  `last_test_result`   VARCHAR(255) NULL                    COMMENT '最近一次测试结果摘要',
  `temperature`        DOUBLE       NULL                    COMMENT '模型温度参数',
  `enable_thinking`    TINYINT(1)   NULL DEFAULT 0          COMMENT '是否启用 thinking',
  `top_p`              DOUBLE       NULL                    COMMENT '默认 top_p 参数',
  `extra_headers_json` JSON         NULL                    COMMENT '供应商要求的额外 Header',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owner_provider` (`owner_user_id`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM API Key 配置 (每用户每厂家)';


-- -------------------------------------------------------------
-- 2) 任务主表 (与 chat_sessions 1:1)
-- -------------------------------------------------------------
CREATE TABLE `mathvision_tasks` (
  `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
  `session_id`               VARCHAR(64)  NOT NULL                COMMENT '对应 chat_sessions.session_id',
  `user_id`                  BIGINT       NOT NULL,
  `input_text`               TEXT         NULL                    COMMENT '文本输入; 纯图片时可为空或存补充说明',
  `input_source_type`        VARCHAR(16)  NULL                    COMMENT 'text/markdown/image/mixed',
  `input_assets_json`        JSON         NULL                    COMMENT '资产: fileName/filePath/mimeTypeName/fileSize/source',
  `mode`                     VARCHAR(16)  NOT NULL                COMMENT 'manual/auto',
  `output_target`            VARCHAR(16)  NOT NULL                COMMENT 'manim/geogebra',
  `status`                   VARCHAR(20)  NOT NULL DEFAULT 'created'
                             COMMENT 'created/queued/running/waiting_confirm/failed/completed/canceled',
  `current_stage`            VARCHAR(40)  NULL                    COMMENT 'problem_normalization/reasoning_graph/visual_storyboard/code_generation/render_result/completed',
  `failed_stage`             VARCHAR(40)  NULL                    COMMENT '失败发生的阶段',
  `error_type`               VARCHAR(32)  NULL                    COMMENT 'input_error/credential_error/model_error/workflow_error/validation_error/code_error/render_error/storage_error/permission_error',
  `error_message`            TEXT         NULL                    COMMENT '失败原因摘要',
  `selected_model_config_id` BIGINT       NULL                    COMMENT '对应 llm_model_configs.id',
  `provider_code`            VARCHAR(32)  NULL                    COMMENT '冗余, 便于展示/校验',
  `model_name`               VARCHAR(128) NULL                    COMMENT '本次实际使用的模型名称',
  `current_version`          INT          NOT NULL DEFAULT 1      COMMENT '当前激活/工作版本, 指向 mathvision_versions.version',
  `last_confirmed_stage`     VARCHAR(40)  NULL                    COMMENT '手动模式最近确认到的阶段',
  `auto_fix_count`           INT          NOT NULL DEFAULT 0      COMMENT '自动修复累计次数 (限流)',
  `cancel_requested`         TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '运行中取消请求标记',
  `final_artifact_path`      VARCHAR(512) NULL                    COMMENT '当前版本最终产物路径',
  `final_artifact_type`      VARCHAR(16)  NULL                    COMMENT 'mp4/html',
  `request_id`               VARCHAR(64)  NULL                    COMMENT '创建幂等键',
  `deleted`                  TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '软删除标记',
  `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session` (`session_id`),
  UNIQUE KEY `uk_request` (`request_id`),
  KEY `idx_user_list` (`user_id`, `deleted`, `update_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MathVision 生成任务主表';


-- -------------------------------------------------------------
-- 3) 阶段核心产物 (按 task+stage 独立版本; 产物列合一为 artifact_json)
--    artifact_json 形状随 stage:
--      problem_normalization -> ProblemBundle
--      reasoning_graph       -> KnowledgeGraph (dag_graph)
--      visual_storyboard     -> Narrative
--      code_generation       -> { "format":"python|geogebra_commands", "text":"..." }
--      render_result         -> { "artifactPath":"...", "artifactType":"mp4|html" }
-- -------------------------------------------------------------
CREATE TABLE `mathvision_artifacts` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `task_id`        BIGINT       NOT NULL,
  `session_id`     VARCHAR(64)  NOT NULL                COMMENT '冗余, 便于按会话查询',
  `user_id`        BIGINT       NOT NULL                COMMENT '冗余, 便于权限校验',
  `stage`          VARCHAR(40)  NOT NULL                COMMENT 'problem_normalization/reasoning_graph/visual_storyboard/code_generation/render_result',
  `version`        INT          NOT NULL                COMMENT '该阶段独立版本号 (在 task_id+stage 内自增)',
  `base_version`   INT          NULL                    COMMENT '本阶段来源版本',
  `artifact_json`  JSON         NULL                    COMMENT '统一产物列, 形状随 stage',
  `change_source`  VARCHAR(32)  NOT NULL DEFAULT 'initial_generation'
                   COMMENT 'initial_generation/user_revision/manual_edit/regenerate/auto_fix/retry',
  `change_summary` VARCHAR(512) NULL                    COMMENT '相对上一版本的变更摘要',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_stage_version` (`task_id`, `stage`, `version`),
  KEY `idx_task_stage` (`task_id`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阶段核心产物 (阶段级独立版本)';


-- -------------------------------------------------------------
-- 4) 阶段校验/执行结果 (同 grain, 结果列合一为 result_json)
--    result_json 形状随 stage:
--      visual_storyboard -> { "storyboardValidation": {...} }
--      code_generation   -> { "codeEvaluation": {...}, "codeFixTrace": [...],
--                             "geogebraReviewedText": "...", "geogebraValidation": {...} }
--      render_result     -> { "renderResult": {...}, "sceneEvaluation": {...} }
-- -------------------------------------------------------------
CREATE TABLE `mathvision_stage_results` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `task_id`     BIGINT      NOT NULL,
  `artifact_id` BIGINT      NOT NULL                COMMENT '对应 mathvision_artifacts.id (同 stage 同 version)',
  `session_id`  VARCHAR(64) NOT NULL                COMMENT '冗余, 便于按会话查询',
  `user_id`     BIGINT      NOT NULL                COMMENT '冗余, 便于权限校验',
  `stage`       VARCHAR(40) NOT NULL                COMMENT '同 mathvision_artifacts.stage',
  `version`     INT         NOT NULL                COMMENT '阶段独立版本号, 与 artifacts 一致',
  `result_json` JSON        NULL                    COMMENT '统一结果列, 形状随 stage',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_stage_version` (`task_id`, `stage`, `version`),
  UNIQUE KEY `uk_artifact` (`artifact_id`),
  KEY `idx_task_stage` (`task_id`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阶段校验/执行结果 (阶段级独立版本)';


-- -------------------------------------------------------------
-- 5) 任务版本组合表 (UX 的 V1/V2/V3)
--    每行 = 对五阶段各选一个"阶段版本号"的指针组合;
--    上游未改的阶段, 多个任务版本填同一个版本号, 底层产物只存一份。
-- -------------------------------------------------------------
CREATE TABLE `mathvision_versions` (
  `id`                    BIGINT      NOT NULL AUTO_INCREMENT,
  `task_id`               BIGINT      NOT NULL,
  `version`               INT         NOT NULL                COMMENT '任务级版本号 (UX 的 V1/V2/V3)',
  `base_version`          INT         NULL                    COMMENT '来源任务版本',
  `pn_version`            INT         NULL                    COMMENT 'problem_normalization 选用的阶段版本号',
  `rg_version`            INT         NULL                    COMMENT 'reasoning_graph 阶段版本号',
  `vs_version`            INT         NULL                    COMMENT 'visual_storyboard 阶段版本号',
  `cg_version`            INT         NULL                    COMMENT 'code_generation 阶段版本号',
  `rr_version`            INT         NULL                    COMMENT 'render_result 阶段版本号 (NULL=尚未生成)',
  `branch_stage`          VARCHAR(40) NULL                    COMMENT '本版本从哪个阶段分叉 (决定下游清空范围)',
  `change_source`         VARCHAR(32) NOT NULL DEFAULT 'initial_generation'
                          COMMENT 'initial_generation/user_revision/manual_edit/regenerate/auto_fix/retry',
  `change_summary`        VARCHAR(512) NULL                   COMMENT '版本变更摘要',
  `change_instruction`    TEXT        NULL                    COMMENT '用户提交的完整变更指令 (仅 user_revision 使用)',
  `workflow_summary_json` JSON        NULL                    COMMENT '整次 workflow 执行摘要 (运行级)',
  `is_current`            TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '是否当前版本',
  `create_time`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_version` (`task_id`, `version`),
  KEY `idx_task_current` (`task_id`, `is_current`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务版本组合表';


-- -------------------------------------------------------------
-- 6) 广场发布记录
--    每条记录固定指向发布时的任务版本；任务软删除后不再展示，永久删除时级联清理。
-- -------------------------------------------------------------
CREATE TABLE `mathvision_square_posts` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `task_id`     BIGINT   NOT NULL                COMMENT '来源任务ID',
  `version`     INT      NOT NULL                COMMENT '分享的任务版本号',
  `load_count`  INT      NOT NULL DEFAULT 0      COMMENT '被加载到工作台的次数',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_version` (`task_id`, `version`),
  KEY `idx_square_create_time` (`create_time`),
  CONSTRAINT `fk_square_task` FOREIGN KEY (`task_id`)
      REFERENCES `mathvision_tasks` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MathVision 广场发布记录';
