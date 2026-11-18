# MathVision Skill 设计文档(v2:agent 自主,最小工具)

状态:草案,待评审。本文只描述设计,不含实现。取代此前"统一入口脚本加状态机"的 v1 方案。

## 1. 目标与设计原则

### 1.1 目标

把 study 中 MathVision 的生成方法打包成 skill。用户把它装进个人 agent(Claude Code、Codex、DeepSeek 系 harness 等)后,通过对话就能得到和项目流程同类的中间产物与最终动画,并且每一步的产物都在对话里展示给用户。

### 1.2 原则:skill 提供知识,agent 做决策

平台里的流程是写死的:固定节点顺序、固定重试预算、固定的"生成再检查"两段式。那是因为平台里的模型只是被节点调用的函数。个人 agent 本身能规划、能判断、能自己检查,所以 skill 不应该复刻这套编排,而应该:

- **交付知识**:五个阶段各自要解决什么问题、产物长什么样、好的产物满足哪些标准、常见的坑、system prompt 中沉淀下来的规则。
- **交付最小的、agent 自己做不到的能力**:检测环境、带几何导出地渲染 Manim、(可选)结构校验。
- **不规定怎么走**:阶段顺序是推荐路径而不是门控。agent 可以跳过、回退、合并、拆细,可以自己决定逐场景还是整体生成、何时自检、修几轮、何时向用户提问。
- **不做状态机**:没有 `submit`、`scene-commit`、预算计数。文件就是状态,agent 读文件就能恢复进度。

### 1.3 已确认的决策

| 决策点 | 结论 |
| --- | --- |
| 谁执行生成 | 用户 agent 自己的模型。不调用平台 Worker |
| 渲染位置 | 第一版只做本机渲染。回退平台渲染依赖对外接口,放第二期 |
| 平台对接 | 第一版不做。但产物格式与平台一致,为第二期入库留好口子 |
| 脚本语言 | Python,且数量压到最少 |
| harness 适配 | 先做通用格式(Markdown 加少量脚本) |
| GeoGebra | 后续再做,第一版只支持 Manim |
| 语音旁白 | 由 agent 在开始生成前询问用户是否需要,不默认开启 |
| `mv_validate.py` | 第一版不做,先靠 checklist,看试跑结果再决定 |

### 1.4 非目标

- 不写数据库,不上传,不接前端。
- 不在 skill 内调用任何 LLM API。
- 不复刻平台的租约、调度、重试预算、版本指针维护。
- 不把 Java 里一万行的校验、评估逻辑整体移植。

## 2. skill 的构成

```
mathvision-skill/
├── SKILL.md                      入口:目标、推荐路径、协作方式、展示约定。保持短
├── playbooks/                    五个阶段的"作战手册",由现有 system prompt 提炼
│   ├── 01-problem-normalization.md
│   ├── 02-reasoning-graph.md
│   ├── 03-visual-storyboard.md
│   ├── 04-code-generation.md
│   └── 05-render-and-review.md
├── formats/                      产物格式说明,以带注释的示例为主
│   ├── problem-bundle.md
│   ├── knowledge-graph.md
│   ├── narrative.md              分镜,最复杂
│   ├── code-result.md
│   └── render-result.md
├── references/                   按需加载的长手册
│   ├── manim-syntax-manual.md    现有约 1570 行
│   ├── manim-style-reference.md  约 150 行
│   ├── geogebra-*.md             第二档
│   └── constraint-catalog.md     约束的域、关系、参数,由 Java 目录导出
├── checklists/                   各阶段的质量检查清单,agent 自检时对照
└── scripts/
    ├── mv_env.py                 检测渲染环境
    ├── mv_render.py              渲染 Manim,注入几何导出,汇总错误
    ├── mathvision_geometry_export.py     原样复用
    └── mathvision_edge_tts.py            原样复用
```

按需加载:`SKILL.md` 只写总览。进入某阶段时,agent 才读对应 playbook;写 Manim 代码时才读语法手册。这样常驻上下文很小。

## 3. SKILL.md 应该说什么

按"给一个聪明同事交接工作"的口吻写,而不是写成指令表:

1. **这个 skill 是干什么的**:把数学题或概念做成教学动画。
2. **推荐路径**:五个阶段的名称、各自产出什么、前一阶段产物如何喂给后一阶段。
3. **你拥有的自由**:可以合并或拆分阶段,可以回退修改上游产物,可以自己决定粒度;阶段之间的依赖由你判断,例如分镜改了,代码就要跟着重做。
4. **与用户的协作约定**(见第 6 节)。
5. **产物放哪里、叫什么**(见第 5 节)。
6. **有哪几个工具、什么时候值得用**。

不写的东西:固定的重试次数、必须按序执行、必须通过某个门才能继续。

## 4. Playbook 的内容与来源

每个 playbook 回答同一组问题:

- 这个阶段的目的是什么,下游怎样使用它的产物。
- 需要哪些输入,产出什么(链接到对应 format 文档)。
- 推荐做法,以及平台为什么这样做。
- 好产物的标准(链接 checklist)。
- 常见错误与自检办法。
- 可以怎样偏离推荐做法,偏离的代价是什么。

### 4.1 来源

playbook 由现有提示词提炼,不是照抄。平台提示词是为"无状态的节点调用"写的,里面混着调用格式、工具调用约束、角色设定,需要剥离。保留的是领域规则。

| playbook | 主要来源(`workflow/prompt`、`workflow/node`) |
| --- | --- |
| 01 规范化 | `ProblemNormalizationPrompts`(272 行) |
| 02 推理图 | `ExplorationPrompts`(176)、`EnrichmentPrompts`(93) |
| 03 分镜 | `VisualDesignPrompts`(332)、`NarrativePrompts`(338)、`StoryboardSchemaPrompts`(498),以及 `SystemPrompts` 中的相关规则 |
| 04 代码 | `CodeGenerationPrompts`(407)、`CodeEvaluationPrompts`(306)、`CodeFixPrompts`(166),以及 Manim 手册 |
| 05 渲染与复核 | `RenderFixPrompts`(216)、`SceneEvaluationPrompts`(159) |

这些提示词我目前还没有逐字读过,只核对了文件规模。提炼前需要先通读,并区分三类内容:领域规则(保留)、格式与工具调用约束(改写进 format 文档)、纯编排话术(丢弃)。

### 4.2 动态变体的处理

平台提示词会随 `outputTarget`(manim/geogebra)和 `sceneMode`(2d/3d)变化。playbook 不做模板渲染,而是用清晰的条件小节,例如"如果是 3D 场景"。第一版只写 manim,GeoGebra 小节留到第二档。

## 5. 工作区约定

约定而非强制:

```
mathvision-runs/<题目简称>/
├── 01-problem-bundle.json
├── 02-knowledge-graph.json
├── 03-narrative.json
├── 04-code-result.json         含完整 generated_code;同时导出 scene.py 便于直接运行
├── 05-render-result.json
├── scene.py
└── final/                      最终 mp4,及抽取的关键帧
```

- 修改产物就生成新文件(例如 `03-narrative.v2.json`),不覆盖,方便回退和对比。命名由 agent 决定,不做强制。
- 产物 JSON 的字段名与平台 Java 模型的序列化结果保持一致。平台所有落库产物(`artifact_json`)与阶段结果(`result_json`)的键统一为 snake_case,例如 `input_mode`、`start_node_id`、`generated_code`、`artifact_path`。历史数据已由一次性迁移脚本转换,skill 只需要写 snake_case。
- 进度恢复靠读目录:哪个文件存在、哪个更新,就知道做到哪一步。

关于格式依据:`mathvision_schema.sql` 对 `code_generation` 产物的注释是 `{format, text}`,但执行器实际写入的是 `CodeResult` 的序列化(含 `generatedCode`、`headerCode`、`sceneName`、`artifactName`、`sceneEntries` 等)。格式文档以 Java 模型为准,不以 SQL 注释为准。

## 6. 与用户的协作约定

这里是 agent 自主决策最多的地方。skill 只给出默认习惯,不写死:

- **每完成一个有意义的产物,就在对话里展示**,而不是等全部做完。展示什么、展示多详细,由 agent 按用户关注点调整。
- **默认问一次偏好**:是想"每阶段停下来确认",还是"一路做完再看"。对应平台的 manual 与 auto。用户随时可以改。
- **用户一旦提出修改意见**,agent 自行判断影响到哪些下游产物,并告知用户它打算重做哪些。
- **自检结果如实说**:有未解决的问题就说,不假装通过;用了多少轮修复也说。
- **环境不具备时**:告诉用户缺什么、怎么装,并问是否先停在"代码已生成"。

展示建议(非强制):

| 阶段 | 建议展示 |
| --- | --- |
| 1 | 题目陈述与对图形的理解 |
| 2 | 推理图(Mermaid 或缩进列表)与教学顺序 |
| 3 | 分镜表:场景标题、目标、旁白、出场与退场对象 |
| 4 | 文件路径、场景方法列表、关键片段;全文按需展开 |
| 5 | 视频路径、时长、关键帧截图、剩余警告 |

视频能否内联播放因 harness 而异,所以始终给出本地路径和关键帧。

## 7. 最小工具集

只提供 agent 自己做不到、或自己做容易出错的能力。

### 7.1 `mv_env.py`:环境检测

检测 Python 版本、`manim`、FFmpeg、SoX、LaTeX 与 `dvisvgm`、中文字体、Edge TTS 的网络可达性,逐项给出缺失项与安装指引。agent 在第一次渲染前调用,或渲染失败怀疑环境问题时调用。

### 7.2 `mv_render.py`:渲染

输入一个 Manim 脚本,完成这些事:

- 按平台的方式注入几何导出(`patch_scene_for_geometry_export`),设置 `MATHVISION_GEOMETRY_PATH`。
- 启动渲染子进程,按代码规模估算超时,检测致命 stderr。
- 收集产物路径与几何导出文件路径。
- 把冗长的 traceback 压缩成摘要,并标注"疑似环境问题"还是"疑似代码问题"。这一点对 agent 决定"是修代码还是告诉用户装环境"很关键。

这是最该做成工具的一块:子进程管理、超时、注入、错误分类,agent 自己写既啰嗦又容易错。平台里对应 `ManimRenderService` 与 `ErrorSummarizer` 的核心部分。

### 7.3 暂不提供:`mv_validate.py`

第一版不做结构校验脚本。产物格式靠 format 文档和 checklist 约束,agent 自己对照检查。试跑后如果发现某类错误反复出现(例如分镜对象 id 重复、推理图边指向不存在的节点),再针对性补一个只报告问题、不拦截的校验脚本。

### 7.4 同样不提供的东西

- 几何评估:先给 checklist 和几何导出文件的字段说明,让 agent 自己读几何数据判断重叠与越界。如果实践中发现 agent 判断不稳,再把 `SceneEvaluationNode` 的核心规则抽成一个报告脚本,作为第二档。
- 状态机、预算、版本指针、提示词渲染器。

## 8. 与平台的关系

### 8.1 为什么不会漂移得太厉害

v1 方案要把 Java 逻辑整体复刻成 Python,两份实现必然漂移。v2 里:

- 复刻的代码量小(三个脚本)。
- 知识部分是文档,不会"行为不一致",只会"内容过时"。

### 8.2 防止内容过时

1. 对每份 playbook 和 format 文档,头部注明"提炼自哪些 Java 类、哪个提交"。
2. 提供一个检查脚本(开发期使用,不随 skill 分发):比较这些 Java 文件当前的修改时间与文档记录的来源提交,过期就提醒。
3. `references/constraint-catalog.md` 和 format 文档里的枚举,优先用小工具从 Java 导出生成,而不是手写。

### 8.3 一个需要诚实面对的取舍

平台的刚性流程本身是质量保障:强制校验加有限重试,弱模型也能产出合格结果。放开之后,质量更依赖 agent 自身能力。缓解办法是 checklist 写得足够具体、format 文档给出完整示例。第一版不提供校验脚本,所以更要靠试跑暴露问题。但不能保证弱模型的产出和平台持平。第一版需要用不同能力的模型实测,记录差距。

## 9. 测试与验证

1. **格式一致性**:用平台 Java 模型反序列化 skill 试跑产出的各阶段 JSON,应当成功;并与平台生成的真实产物逐字段对照结构。这条保证第二期入库可行。
2. **渲染工具**:对一组已知正常、已知报错、已知超时的 Manim 脚本,检查产物、摘要、错误分类是否准确。
3. **端到端试跑**:选几道代表性题目(带图题、概念题、3D),分别用至少两个能力不同的模型,在真实 harness 里完整跑一遍。观察三件事:是否按需读取 playbook、是否会自主回退修正、产物是否能渲染成功。这条依赖模型,发版前手动跑,结果写入文档。
4. **环境矩阵**:Windows、macOS、Linux 各验证一次 `mv_env.py` 与 `mv_render.py`。

## 10. 风险与开放问题

### 10.1 风险

| 风险 | 缓解 |
| --- | --- |
| 渲染环境重(LaTeX、FFmpeg、SoX、Cairo/Pango、字体) | `mv_env.py` 逐项检测并指引;缺失时停在"代码已生成",如实告知 |
| 运行模型生成的 Python | 渲染前静态检查拦截明显危险调用;首次渲染前提示风险并征得用户同意;只在工作区内运行 |
| 弱模型自主性不足 | 见 8.3 |
| 提炼质量决定成败 | playbook 是核心资产,需要逐阶段评审,并用端到端试跑反馈迭代 |
| 分镜格式复杂 | `formats/narrative.md` 用带注释的完整示例加字段说明,必要时给"最小可用分镜"和"完整分镜"两个示例 |

### 10.2 开放问题

1. **旁白的询问时机**:建议在阶段 3(分镜)开始前问,因为旁白文字在分镜的 `voiceover_text` 里生成。若用户选择无旁白,分镜里就不写 `voiceover_text`,代码阶段也不使用 `VoiceoverScene`。
2. **展示与交互的 harness 差异**:是否需要为不支持 Mermaid 或图片的 harness 单独写展示建议?
3. **校验脚本何时补**:以试跑中出现的高频错误为准。

## 11. 交付分档

**第一档**

- `SKILL.md`、五份 playbook、五份 format 文档、Manim 手册、checklist。
- `mv_env.py`、`mv_render.py`。
- 旁白开关(询问用户,选择后影响分镜与代码两阶段的 playbook)。
- Manim 目标的端到端试跑。

**第二档**

- 视试跑结果补充的校验脚本。
- GeoGebra 小节与渲染工具。
- 几何评估报告脚本(视试跑结果)。
- 针对具体 harness 的薄适配。

**第三档(第二期):平台对接**

平台需要新增面向用户的入口,方向如下,实施前须先读用户侧 Controller 与鉴权代码核实:

- 用户级访问令牌,可撤销,绑定用户。
- 导入任务:一次请求创建任务、版本与各阶段产物,`change_source` 标记为外部导入。
- 产物上传:复用现有分片上传与 sha256 校验,鉴权改用用户令牌。
- skill 侧增加 `mv_upload.py`,把工作区映射为一次导入请求。因为产物格式已与平台一致,这一步基本不用转换。
- 本机渲染失败时回退平台渲染。
- 未决:外部导入的任务,前端是否允许"重新生成"?若允许,需要用户为该任务配置模型凭据。
