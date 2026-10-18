# Markdown rendering

Assistant answers are rendered by the app, not by the WebView runtime. The renderer is a small
GFM-oriented parser (`MarkdownParser`) plus a Compose renderer (`MarkdownContent`). User messages
stay plain text.

The goal is a phone-readable transcript of what an agent actually writes: nested plans, tables,
fenced code, diffs, links and checklists. It is not a full CommonMark implementation and it never
executes HTML.

## What is supported

| Area | Behaviour |
| --- | --- |
| Blocks | ATX headings (`#`–`######`), setext headings, paragraphs, thematic breaks |
| Lists | Bullet and ordered lists, preserved start numbers, nesting, indented continuation lines, task checkboxes (`- [ ]` / `- [x]`) |
| Quotes | Consecutive `>` lines form one block; quotes contain paragraphs, lists, code and tables |
| Code | Backtick and tilde fences, longer fences that contain shorter ones, indented code, language info strings |
| Tables | GFM tables with `:---`, `:---:` and `---:` alignment; escaped pipes and pipes inside code spans stay in the cell |
| Inline | Bold, italic, bold-italic, strikethrough, inline code, inline links with titles, bare http(s) URLs, `<...>` autolinks, email autolinks, hard breaks, backslash escapes, HTML entities |
| Images | `![alt](url)` becomes `[image: alt]`; nothing is fetched |
| Streaming | An unclosed fence, code span, link or emphasis run stays literal until it closes |

## Rendering choices

- Headings step down by level in the sans family; body copy uses the transcript's monospace voice.
- Inline code is a quiet chip without literal backticks.
- Fenced code has a language label, a copy action, horizontal scrolling for long lines, optional
  syntax colouring, and collapses after 24 lines.
- Tables share one computed width per column across every row, so cells line up, and scroll
  horizontally when they do not fit.
- Nested lists indent under the parent item and use a lighter bullet per level.
- Quotes draw one continuous rule and mute their contents.
- Links are accent-coloured and underlined. Only `http(s)` and plain `mailto:` links can open.
- The whole message participates in text selection.

## Deliberate limits

- Raw HTML is shown as text. Only `<br>` is interpreted (as a line break); `<script>` and other
  tags are never executed.
- Images are never downloaded or displayed.
- Reference-style links (`[text][id]` with `[id]: url`) and footnotes are not resolved.
- Math (`$...$`, `$$...$$`) and diagrams (`mermaid`) are shown as source, not rendered.
- Tables that are not valid GFM (header and separator column counts differ) stay paragraphs.

## Tests

`MarkdownParserTest` covers block and inline parsing, nesting, tables, escapes, streaming
tolerance and URL safety. `CodeHighlightTest` covers the tokenizer's language families and diff
mode. Renderer layout is exercised in the app; unit tests cover parsing and highlighting.
