# KnowledgeGraph 格式

阶段 2 的产物。阶段 3 按 `teaching_order` 为每个节点设计一个场景。

## 格式只有一种

规划图、补全、落盘都用同一种结构:起点字段是 `start_node_id`,`nodes` 是以节点 id 为键的映射。平台的模型输出 schema 与落库格式已对齐,不再有草稿形态。

## 完整示例

```jsonc
{
  "start_node_id": "problem_setup",
  "nodes": {
    "problem_setup": {
      "id": "problem_setup",
      "step": "画出直线 l 和同侧的 A、B 两点,标出要求最小的 AP + PB",
      "reason": "先让学习者看清对象和所求",
      "node_type": "problem",
      "min_depth": 0,
      "equations": ["AP + PB"],
      "definitions": { "P": "直线 l 上的动点", "l": "给定的直线" },
      "interpretation": "P 在 l 上滑动时,两段路程之和在变化,我们要找它最小的位置。",
      "examples": []
    },
    "reflect_a": {
      "id": "reflect_a",
      "step": "作 A 关于 l 的对称点 A′,把 AP 折到 A′P",
      "reason": "反射把折线 APB 拉成可比较的折线 A′PB",
      "node_type": "construction",
      "min_depth": 1,
      "equations": ["AP = A′P"],
      "definitions": { "A′": "A 关于 l 的对称点" },
      "interpretation": "对称保证 AP 与 A′P 等长,所以 AP + PB 等于 A′P + PB。",
      "examples": []
    },
    "straight_line": {
      "id": "straight_line",
      "step": "连接 A′B 交 l 于 P,此时 A′P + PB 最短",
      "reason": "两点之间线段最短",
      "node_type": "conclusion",
      "min_depth": 2,
      "equations": ["A′P + PB \\ge A′B"],
      "definitions": {},
      "interpretation": "A′、P、B 共线时和取最小值。",
      "examples": []
    }
  },
  "next_edges": {
    "problem_setup": ["reflect_a"],
    "reflect_a": ["straight_line"]
  },
  "teaching_order": ["problem_setup", "reflect_a", "straight_line"]
}
```

## 字段说明

### 顶层

| 字段 | 说明 |
| --- | --- |
| `start_node_id` | 起点节点 id。必须是**没有入边**的节点 |
| `nodes` | 映射,键是节点 id,值是节点对象(节点内也有 `id`,二者必须一致)。规划时顺序随意,落盘前按 `min_depth` 再按 `step` 排序 |
| `next_edges` | 映射,键是源节点 id,值是直接后继 id 的数组。方向是"这个节点之后紧接着讲哪些节点" |
| `teaching_order` | 数组,每个节点恰好出现一次,每个节点都在它的所有前置节点之后 |

### 节点

| 字段 | 阶段 | 说明 |
| --- | --- | --- |
| `id` | 探索 | ASCII、小写、不重复 |
| `step` | 探索,补全可修正 | 一个面向展示的教学节拍,中文自然语言 |
| `reason` | 探索,补全可修正 | 这个节拍在讲解流程里为什么重要,简明的中文 |
| `node_type` | 探索 | 概念图:`concept`/`observation`/`construction`/`derivation`/`conclusion`;解题图:把 `concept` 换成 `problem` |
| `min_depth` | 探索 | 从起点出发的最短边数,起点为 0 |
| `equations` | 补全 | LaTeX 字符串数组,不带 `$`,反斜杠按 JSON 转义 |
| `definitions` | 补全 | 键是符号本身,值是中文含义 |
| `interpretation` | 补全 | 简短中文直觉 |
| `examples` | 补全 | 可选 |

`equations` 与 `definitions` 在没有内容时写空数组、空对象。平台以这两个字段是否存在来判断节点是否已补全,所以补全过的节点即使没有内容也建议保留这两个字段。

## 整理规则(落盘前核对)

- id 小写、去首尾空格;重复 id 只留第一个;`step` 为空的节点丢弃。
- `next_edges` 只含存在的节点,无自环,无重复。
- `start_node_id` 对应的节点没有入边;否则换一个没有入边的节点,若图里没有入度为零的节点(有环),新增 `problem_entry` 或 `concept_entry` 作为入口并指向各候选起点。
- `min_depth` 是最短距离;不连通的部分接在后面。
- 图无环。
- `teaching_order` 覆盖全部节点、各一次、尊重依赖。
- 非法的 `node_type` 要改成该图类型下允许的值(概念图默认 `concept`,解题图默认 `derivation`)。

## 常见错误

- 写成 `start_id` 或数组形式的 `nodes`(旧写法,不要用)。
- `teaching_order` 漏了节点,或把后继排在前置节点前面。
- `next_edges` 的方向写反(写成了"依赖于")。
- 图里有环。
- 节点里的 `id` 与映射的键不一致。
- `equations` 里带了 `$`。
- 为了"完整"拆出一堆只是显然事实的节点。
