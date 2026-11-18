# ProblemBundle 格式

阶段 1 的产物。下游所有阶段都只读它。字段名使用 snake_case,与平台落库的 JSON 完全一致。

## 完整结构

```jsonc
{
  "id": "shortest_path_reflection",          // ASCII 短标识,小写加下划线
  "title": "Shortest path via reflection",   // 简短标题
  "input_mode": "problem",                   // "concept" | "problem"
  "output_target": "manim",                  // 本 skill 固定为 "manim"
  "scene_mode": "2d",                        // "2d" | "3d"
  "source": {                                // 原始输入的记录
    "source_type": "text",                   // "text" | "image" | "mixed"
    "raw_text": "用户原话,以及文本附件拼接后的内容",
    "assets": [
      { "id": "figure1.png", "type": "image", "path": "input/figure1.png", "mime_type": "image/png" }
    ]
  },
  "statement": "规范化后的完整题干",
  "diagram": {
    "present": false,
    "source_observed": false,
    "diagram_description": {},
    "coordinate_model": {},
    "unknowns": [],
    "ambiguities": [],
    "normalization_notes": []
  }
}
```

## 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | ASCII 标识 |
| `title` | 是 | 标题 |
| `input_mode` | 是 | `concept` 或 `problem` |
| `scene_mode` | 是 | `2d` 或 `3d`,拿不准用 `2d` |
| `statement` | 是 | 独立、完整的题干,保持输入语言 |
| `diagram` | 是 | 至少包含 `present` |
| `output_target` | 平台补全 | 平台由任务设置写入,不是模型产出。本 skill 写 `"manim"` |
| `source` | 平台补全 | 平台由输入记录写入,不是模型产出。自己按上面的形态填,`source_type` 取值依据:有文字无图为 `text`,有图无文字为 `image`,二者皆有为 `mixed`。`assets[].type` 取 `image`、`text` 或 `file` |

平台里,模型只产出 `id`、`title`、`input_mode`、`scene_mode`、`statement`、`diagram` 这六个字段,`output_target` 和 `source` 由系统在落盘时补上。你自己落盘时要把它们补全,这样第二期入库不需要转换。

## `diagram` 详解

### 没有来源图形

```json
{
  "present": false,
  "source_observed": false,
  "diagram_description": {},
  "coordinate_model": {},
  "unknowns": [],
  "ambiguities": [],
  "normalization_notes": []
}
```

### 有来源图形

各字段的内部结构是**自由的原生 JSON**,没有固定 schema,只要求是对象或数组。下面是推荐形态。

```jsonc
{
  "present": true,
  "source_observed": true,
  "diagram_description": {
    "overall_shape": "以 C 为圆心的四分之一圆扇形",
    "points": {
      "C": { "role": "直角顶点与扇形圆心", "position": "左下" },
      "P": { "role": "动点", "position": "在弧 AB 上" }
    },
    "segments": [
      { "name": "AC", "description": "竖直半径,长 4" }
    ],
    "marks": [
      { "type": "right_angle", "vertex": "C", "description": "∠ACB = 90°" }
    ]
  },
  "coordinate_model": {
    "description": "以 C 为原点,CB 为 x 轴正向,CA 为 y 轴正向",
    "coordinates": { "C": [0, 0], "A": [0, 4], "B": [4, 0], "D": [2, 0] },
    "arc_constraint": { "point": "P", "equation": "x_P^2 + y_P^2 = 16", "theta_range": "[0, pi/2]" },
    "triangle_constraint": { "if_P": ["x", "y"], "then_Q": ["2 - y", "x - 2"] }
  },
  "unknowns": [
    { "name": "position of P", "description": "P 在弧 AB 上变化" },
    { "name": "minimum AQ", "description": "所求的值" }
  ],
  "ambiguities": [
    {
      "name": "orientation of triangle DPQ",
      "choices": ["clockwise branch", "counterclockwise branch"],
      "selected_by_source_diagram": "counterclockwise branch",
      "reason": "图中 Q 明显在 BC 上方且在 D 的左上"
    }
  ],
  "normalization_notes": ["坐标公式只建模来源图形所示的分支,没有求解最值"]
}
```

注意:

- **不要**写 `diagram.objects`、`diagram.constraints`、`diagram.construction_notes`。具体的分镜对象和约束由阶段 3 创建。
- `coordinate_model` 里的公式后续会被当作硬约束,写之前先代入验证。

## 最小示例:纯文字题

```json
{
  "id": "shortest_path_reflection",
  "title": "Shortest path via reflection",
  "input_mode": "problem",
  "output_target": "manim",
  "scene_mode": "2d",
  "source": {
    "source_type": "text",
    "raw_text": "直线 l 同侧有两点 A、B,在 l 上求一点 P,使 AP + PB 最小。",
    "assets": []
  },
  "statement": "直线 l 同侧有两点 A、B,在 l 上求一点 P,使 AP + PB 最小。",
  "diagram": {
    "present": false,
    "source_observed": false,
    "diagram_description": {},
    "coordinate_model": {},
    "unknowns": [],
    "ambiguities": [],
    "normalization_notes": []
  }
}
```

## 常见错误

- 纯文字题写了 `diagram.present = true`。
- 把图片里的题目文字当作图形。
- 在 `diagram` 里加了辅助线、对称点、结论标记等原图没有的内容。
- `scene_mode` 选了 `3d`,但题目不需要深度。
- `statement` 里混进了解题思路。
