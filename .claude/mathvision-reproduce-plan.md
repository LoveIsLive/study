# 目标：在 study 中复现 math-vision，不再运行其源码拷贝

## 背景（已核实的现状）
study 现在通过「嵌入整包 + 委托运行」的方式跑 math-vision：
- `study/src/main/java/com/mathvision/**`（96 个 .java，git 未跟踪）是 math-vision 源码的逐字节拷贝。
- 5 个 stage executor 全部委托给 `core/MathVisionCoreWorkflowService`，后者用 `com.mathvision.node.*` + 原版 PocketFlow 跑，AI 调用经 `core/PlatformAiClientAdapter` 桥接。
- 你要求：**删掉这条路径，改用 study 自己复现的实现**（`com.kwang.study.mathvision.workflow.*`，已存在、已是 @Component、依赖 study 自己的 MathVisionAiChatService）。

`com.mathvision` 依赖面被严格限制在 7 个文件：2 个 bridge（core/）+ 5 个 executor + 1 个测试。

## 改动方案

### 1. 重写 5 个 stage executor —— 直接调用移植版节点（不再委托 core）
每个 executor 改为注入对应的 `com.kwang...workflow` 节点（Spring 构造注入），用 study 的 `workflow.model.*` 类型加载/序列化 artifact，节点串联与循环逻辑严格对齐 math-vision 的 WorkflowFlow：

- **ProblemNormalizationStageExecutor** → `ProblemNormalizationNode.run(task, ctx)`
- **ReasoningGraphStageExecutor** → `ExplorationNode.run(...)` 然后 `MathEnrichmentNode.run(...)`（MV：exploration→enrichment）
- **VisualStoryboardStageExecutor** → `VisualDesignNode.run(...)` 然后 `StoryboardValidationNode.run(...)`（MV：visualDesign→validation）
- **CodeGenerationStageExecutor** → `CodeGenerationNode.run(...)` + CodeEval→CodeFix 循环（4 评估/3 修复）
- **RenderResultStageExecutor** → `RenderNode` + `SceneEvaluationNode` + CodeFix，render-fix 与 scene-fix 双独立预算

> 注：本会话早期这些 executor 就是这样直连移植版节点的（含我改的 D2 双预算循环），是 codex 后来改成委托 core 的。此步是恢复已知逻辑，非新造。

### 2. 删除 bridge 层与嵌入拷贝
- 删 `com/kwang/study/mathvision/core/MathVisionCoreWorkflowService.java`
- 删 `com/kwang/study/mathvision/core/PlatformAiClientAdapter.java`
- 删整个 `src/main/java/com/mathvision/`（96 文件）
- 删测试 `src/test/java/com/kwang/study/mathvision/core/MathVisionCoreWiringParityTest.java`（它断言嵌入拷贝在 classpath 上，方向相反）

### 3. 配置与资源
- `llm/*.md`（4 个手册）、`render/mathvision_geometry_export.py`：**保留**——移植版 SystemPrompts / ManimRenderService 从 classpath 同名路径加载，仍需要。
- `workflow-config.json`、`model-config.json`：删除嵌入拷贝后，唯一读它们的 `ConfigLoader`（在 com.mathvision.config）被删除，二者变孤儿。**保留文件但确认无引用**（或后续清理）；不影响运行。

### 4. 重试参数一致性（已确认：对齐 config 值）
math-vision 实际运行时从 workflow-config.json 读重试值：
`visualDesign=3, storyboardValidation=5, placementEnrichment=3, codeGen=2, codeEvaluation=3, render=10, sceneEval=5`。
而移植版节点**硬编码**：`VisualDesign MAX_SCENE_RETRIES=2, StoryboardValidation MAX_VALIDATION_FIX_ATTEMPTS=3, MAX_PLACEMENT_ENRICHMENT_RETRIES=3, Render DEFAULT_MAX_RENDER_RETRIES=4`，executor 侧 `MAX_SCENE_EVALUATION_FIX_ATTEMPTS=2`、`MAX_CODE_EVALUATION_FIX_ATTEMPTS=3`。

为忠实复现 math-vision 的运行行为，把这些硬编码值对齐到 config 的值（visualDesign 2→3、storyboardValidation 3→5、render 4→10、sceneEval 2→5；codeGen/codeEval/placement 已一致）。

## 验证
- `mvnw -q -o compile` 编译通过（删除 96+4 文件后无残留引用）。
- grep 确认全库无 `com.mathvision` 引用残留。
- 确认 5 个 stage 的节点串联/循环次数与 WorkflowFlow + config 逐条对应。
- （无法在此环境实跑渲染）交付后需在装 manim 的环境跑一次端到端。

## 风险
- 这是删 ~100 个文件 + 重写 5 个 executor 的大改。移植版节点已经过本会话多轮审计（逻辑、prompt、schema、归一化、双重试预算均已对齐 MV），是可靠的复现基础。
- 若移植版与 MV 仍有未发现的细节差异，会在此暴露——但这正是"在 study 复现"的正确形态，而非隐藏在嵌入拷贝背后。
