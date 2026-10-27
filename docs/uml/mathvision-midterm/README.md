# MathVision 中期检查 UML 源码

使用 PlantUML 编写，与 20 页、10 分钟版 PPT 大纲对应。每张图使用独立源码，共用 style.iuml；不需要在线上传项目代码。

五张图已按 UML 2.5.1 的关系、控制节点、部署和交互记法完成第一轮修正；规范依据与逐图说明见 [UML 绘图规范调研](../uml-notation-guide.md)。

| PPT 页码 | 图 | 源码 | 导出图片 |
| --- | --- | --- | --- |
| 第 4 页 | 组件图 | [01-components.puml](01-components.puml) | [SVG](rendered/01-components.svg) / [PNG](rendered/01-components.png) |
| 第 5 页 | Agent 协同活动图 | [02-agent-activity.puml](02-agent-activity.puml) | [SVG](rendered/02-agent-activity.svg) / [PNG](rendered/02-agent-activity.png) |
| 第 7 页 | 版本模型类图 | [03-version-model.puml](03-version-model.puml) | [SVG](rendered/03-version-model.svg) / [PNG](rendered/03-version-model.png) |
| 第 9 页 | 部署图 | [04-deployment.puml](04-deployment.puml) | [SVG](rendered/04-deployment.svg) / [PNG](rendered/04-deployment.png) |
| 第 10 页 | 故障接管时序图 | [05-failover-sequence.puml](05-failover-sequence.puml) | [SVG](rendered/05-failover-sequence.svg) / [PNG](rendered/05-failover-sequence.png) |

## 编辑与重新导出

本次使用 PlantUML 1.2026.9、Java 11 和 Graphviz/Dot 布局渲染。Windows 下本次使用 PlantUML 自动解出的 Dot；其他环境若提示找不到 Dot，安装 Graphviz 并将 dot 加入 PATH，或设置 GRAPHVIZ_DOT。字体使用 Microsoft YaHei，可在 style.iuml 中替换成已安装的中文字体。

先从 [PlantUML 官方发布页](https://github.com/plantuml/plantuml/releases/tag/v1.2026.9) 下载 plantuml-1.2026.9.jar，或使用同版本的 [Maven Central 构件](https://repo.maven.apache.org/maven2/net/sourceforge/plantuml/plantuml/1.2026.9/plantuml-1.2026.9.jar)。JAR 保存在工具目录，不作为项目源文件提交。

PowerShell 中执行：

```powershell
.\render.ps1 -PlantUmlJar '你的工具目录\plantuml-1.2026.9.jar'
```

也可以单独导出 SVG：

```powershell
.\render.ps1 -PlantUmlJar '你的工具目录\plantuml-1.2026.9.jar' -Format svg
```

手动导出单张图：

```powershell
java '-Dfile.encoding=UTF-8' -jar '你的工具目录\plantuml-1.2026.9.jar' -charset UTF-8 -tsvg -o rendered 01-components.puml
```

SVG 适合在支持 SVG 的 PowerPoint 中缩放；PNG 可用于其他展示环境。图内保留中文角色名与核心关系，PPT 的标题和结论放在页面中，避免在图中重复。

## 图示与实现对应

- 组件图表示逻辑依赖；主服务写入任务，Worker 从数据库领取，图中不画主服务向 Worker 直接派发 RPC。
- 活动图中的 Agent 动作对应题目理解、推理规划、知识增强、可视化设计、分镜审校、代码生成、代码审查、程序修复、渲染执行和视觉质量评估等角色。分镜审校、代码审查和视觉质量评估由用户选择执行；执行失败触发的渲染修复保留原行为。检查后仍由用户确认是否继续。
- 类图表达逻辑引用。任务版本使用 pnVersion、rgVersion、vsVersion、cgVersion、rrVersion 选择阶段版本，不是每个任务版本独占五份产物。阶段结果相对产物为可选的一对一关系。
- 部署图中的多个 Worker 是执行进程；Agent 为逻辑角色，任务领取单位是阶段，共享基础服务为逻辑分组，不假定全部部署在一台主机。浏览器和外部模型已在组件图中说明，部署图集中展示主服务、Worker 和共享基础设施。
- 时序图表示 A 暂时失联后 B 接管的场景。令牌与租约决定提交资格；模型、渲染及文件上传不持有任务行的长事务。外部调用可能重复执行。

对应代码：MathVisionTaskScheduler、MathVisionStageRunner、MathVisionStageCommitService、五个 StageExecutor、MathVisionTransferServer，以及 MathVisionTask／MathVisionVersion／MathVisionArtifact／MathVisionStageResult。

绘图语法参考：[PlantUML 官方文档](https://plantuml.com/)、[命令行导出](https://plantuml.com/command-line)、[Graphviz 配置](https://plantuml.com/graphviz-dot)。
