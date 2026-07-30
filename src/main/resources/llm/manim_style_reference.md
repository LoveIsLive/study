# Manim Style Reference

Use this reference only for storyboard-level style planning. This stage does
not write code; all style language must stay compatible with later Manim
generation.

## Rules

* Manim uses a black background by default (`#000000`).
* Write every storyboard color as a 6-digit hex string in `#RRGGBB` format.
* Do not use Manim named color constants, CSS color names, 8-digit hex values,
  RGB strings, gradients, shadows, blur, or unsupported rendering terms.
* Put transparency in a separate `opacity`, `fill_opacity`, or
  `stroke_opacity` field. Do not encode alpha in the color string.
* Use concise, backend-friendly style properties.
* Prefer structured properties over vague prose.
* Treat style as part of teaching, not decoration alone.
* Assign colors to concepts, not random mobjects.
* Keep one clear visual focus per scene.
* Preserve intentional empty space; do not saturate the whole frame.

## 1. Color Format And Contrast

Use hex colors in `color_scheme`, `color_palette`, object `style`, and scene
notes.

Valid examples:

* `#3498DB`
* `#FFFFFF`
* `#1A1A1A`

Invalid examples:

* `BLUE`
* `WHITE`
* `BLACK`
* `#CC3498DB`
* `rgba(52, 152, 219, 0.8)`

Contrast requirements:

* Ordinary non-text strokes, arrows, geometry, markers, and decorative elements
  must contrast against the default black background `#000000` at ratio >= 3.0.
* Text, titles, formulas, labels, badges, and callouts must contrast at ratio
  >= 4.5 against their own background color.
* If a text element has no explicit background color, compare its text color
  against `#000000`.

## 2. Safe Style Properties

Use concise key-value properties that can later map to Manim styling code.

```text
color            // 6-digit hex primary color, #RRGGBB (text color for text/equation; dot color for point; stroke fallback for lines)
fill_color       // 6-digit hex fill color, #RRGGBB (shape interior fill; text_card background box fill)
fill_opacity     // 0..1 (shape fill transparency; text_card background box fill transparency)
stroke_color     // 6-digit hex stroke/border color, #RRGGBB (line color; shape border; text_card background box border)
stroke_width     // numeric stroke width
stroke_opacity   // 0..1 (line transparency; text_card background box border transparency)
highlight_color  // 6-digit hex emphasis color, #RRGGBB
opacity          // overall opacity, 0..1
font_size        // text size when needed
font_family      // text font family
font_weight      // normal | bold
font_style       // normal | italic
line_style       // solid | dashed | dotted | dash_dot
point_size       // point or dot size
radius           // circle/dot/arc radius when needed
marker_size      // angle/tick/marker size
z_index          // rendering order
padding          // text-card inner padding
corner_radius    // text-card corner radius
point_style      // GeoGebra: SetPointStyle code
decoration       // GeoGebra: SetDecoration code
label_visible    // true | false
```

## 3. Style Planning Rules

These rules are for storyboard-level style planning and should remain compatible
with later Manim code generation.

### Semantic Palette

* `color_scheme` should map hex colors to roles or concepts, for example
  "moving point = #22C55E, fixed anchors = #F87171/#60A5FA, result highlight =
  #FACC15".
* Keep the same concept in the same color across scenes.
* Prefer a small consistent palette over many unrelated colors.

### Opacity Hierarchy

Use opacity to direct attention:

* Primary focus: `opacity = 1.0`
* Context that should remain visible but secondary: around `0.35` to `0.5`
* Structural elements such as axes, helper grids, or reference baselines:
  around `0.12` to `0.2`

Do not keep every object at full opacity.

### Empty Space And Visual Weight

* Leave meaningful breathing room around the main focus.
* Keep at least a modest empty region for overlays, captions, or later reveals.
* Do not cluster all heavy content on one side unless comparison is the goal.
* Large opaque cards should sit in a clearly reserved area, not on top of the
  active geometry.

### Typography And Readability

* Prefer monospace fonts for storyboard text planning, for example `Menlo`,
  `JetBrains Mono`, `DejaVu Sans Mono`, or `Courier New`.
* Keep `font_size >= 18` for readable supporting text.
* Use larger sizes for scene titles, section headers, and key conclusions.
* If text must overlap busy geometry, prefer adjusting placement or using
  opacity to separate layers. Only add a background box when the text truly
  cannot be read without one; ensure the text/background contrast is >= 4.5.

### Layout And Motion Vocabulary

* Prefer spatial consistency: the same concept should stay in the same screen
  region across scenes unless the move itself teaches something.
* Prefer progressive disclosure: show the simple state first, then add one new
  idea.
* Prefer transform- or restyle-based continuity over replacing everything.
* Use motion only when it clarifies change, causality, or comparison.

## 4. Storyboard Output Guidance

When writing storyboard JSON:

* `color_palette` must contain only 6-digit hex color strings.
* `color_scheme` should describe semantic mapping, not implementation details or
  raw code.
* Object `style` must be a single typed object, not an array and not a free-form
  map.
* Use only the allowed style keys such as `color`, `fill_color`,
  `stroke_color`, `opacity`, `fill_opacity`,
  `stroke_width`, and `stroke_opacity`.
* Create separate storyboard objects for labels, badges, helper outlines,
  cards, or callouts instead of encoding sidecar layers inside another
  object's style.
* Use `opacity`, `fill_opacity`, and `stroke_opacity` deliberately to encode
  hierarchy.
* Use `font_size` to separate title, body, label, and caption roles.
* `notes_for_codegen` is a hard downstream constraint field; use it only for
  palette consistency, emphasis hierarchy, overlay safety, text readability, or
  similar instructions that code generation and repair must preserve.
