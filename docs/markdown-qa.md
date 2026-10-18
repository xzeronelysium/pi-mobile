# Markdown QA corpus

Use this message as an assistant response when checking transcript rendering on a phone. Check at
normal and enlarged system font sizes. This is a manual acceptance fixture, not a claim that these
checks have been run.

## Text and links

Plain text, **strong**, *emphasis*, ***both***, ~~removed~~, and `inline_code()`.
Unicode: café, 中文, 日本語, العربية, 👩🏽‍💻.

[Safe web link](https://example.com), <dev@example.com>, and a bare URL: https://example.com/path.
An unsafe destination must never launch: [blocked](javascript:alert%281%29).
An image must not fetch anything: ![descriptive alternative](https://example.com/image.png).
Raw HTML must not execute: <script>alert('no')</script>.

## Lists and quotations

3. An ordered list starts at three.
4. Another item with **bold text**.
   - A nested bullet.
     - A deeper bullet.
5. A final item.

- [ ] Incomplete task
- [x] Completed task

> A quotation with *emphasis*.
>
> - A quoted list item.
> - Another item.

## Tables

| Feature | State | Notes |
| :--- | :---: | ---: |
| **Formatting** | ready | `a \| b` |
| [A link](https://example.com) | checked | 中文 |
| Long content wraps inside its column rather than overlapping its neighbour | good | 123 |

## Code

```kotlin
// Copy must preserve whitespace and the full code, not the visible preview.
fun greeting(name: String): String {
    return "Hello, $name"
}
val veryLongLine = "Horizontal scrolling should not move the whole transcript or truncate this deliberately long line of code."
```

```diff
 context
-old value
+new value
```

## Interaction checklist

- Select and copy formatted text; scrolling and selection must not accidentally open a link.
- Activate links by touch and accessibility navigation; only safe schemes may launch.
- Tap blank space after a link; it must not open the closest link.
- Scroll a wide table and code block horizontally without moving the entire screen sideways.
- Paste a fence containing more than 24 lines: expand, collapse, and copy the full original code.
- While a long code fence streams, expansion must not reset with each new token.
- During streaming, unfinished delimiters must not crash or hide response text.
- Headings and task completion states should be available to accessibility services.
- Repeat with enlarged font size and check that controls remain reachable.
