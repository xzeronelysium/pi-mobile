package dev.pi.mobile.chat

/**
 * A deliberately small syntax highlighter for fenced code in the transcript.
 *
 * It is a tokenizer, not a grammar: keywords, strings, comments, numbers and a few special cases
 * (preprocessor lines, diff marks) are enough to make machine output scannable on a phone. Unknown
 * languages simply produce no tokens, so the renderer shows the code unchanged.
 */
enum class CodeTokenKind { KEYWORD, TYPE, STRING, COMMENT, NUMBER, META, ADDED, REMOVED }

data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

object CodeHighlight {
  fun tokenize(code: String, language: String): List<CodeToken> {
    if (code.isEmpty()) return emptyList()
    val profile = profileFor(language)
    if (profile.diff) return diffTokens(code)
    if (profile.keywords.isEmpty() && profile.lineComments.isEmpty() && profile.blockComments.isEmpty() && profile.quotes.isEmpty()) {
      return emptyList()
    }

    val tokens = ArrayList<CodeToken>()
    var i = 0
    while (i < code.length) {
      val c = code[i]

      val blockComment = profile.blockComments.firstOrNull { (open, _) -> code.startsWith(open, i) }
      if (blockComment != null) {
        val close = code.indexOf(blockComment.second, i + blockComment.first.length)
        val end = if (close < 0) code.length else close + blockComment.second.length
        tokens.add(CodeToken(i, end, CodeTokenKind.COMMENT))
        i = end
        continue
      }

      if (c == '#' && profile.hashDirectives) {
        val end = lineEnd(code, i)
        tokens.add(CodeToken(i, end, CodeTokenKind.META))
        i = end
        continue
      }

      val lineComment = profile.lineComments.firstOrNull { marker ->
        code.startsWith(marker, i) && (marker != "#" || i == 0 || code[i - 1].isWhitespace())
      }
      if (lineComment != null) {
        val end = lineEnd(code, i)
        tokens.add(CodeToken(i, end, CodeTokenKind.COMMENT))
        i = end
        continue
      }

      if (c in profile.quotes) {
        val triple = profile.tripleQuotes && code.startsWith("$c$c$c", i)
        val quote = if (triple) "$c$c$c" else c.toString()
        val end = scanString(code, i, quote, allowNewline = triple || c == '`')
        tokens.add(CodeToken(i, end, CodeTokenKind.STRING))
        i = end
        continue
      }

      if (c.isDigit() && (i == 0 || !isIdentifierPart(code[i - 1]))) {
        val end = scanNumber(code, i)
        if (end > i) {
          tokens.add(CodeToken(i, end, CodeTokenKind.NUMBER))
          i = end
          continue
        }
      }

      if (c == '@' && i + 1 < code.length && (code[i + 1].isLetter() || code[i + 1] == '_')) {
        val end = scanIdentifier(code, i + 1)
        tokens.add(CodeToken(i, end, CodeTokenKind.META))
        i = end
        continue
      }

      if (isIdentifierStart(c)) {
        val end = scanIdentifier(code, i)
        val word = code.substring(i, end)
        val kind = when {
          profile.keywords.contains(word) -> CodeTokenKind.KEYWORD
          TYPE_WORDS.contains(word) -> CodeTokenKind.TYPE
          else -> null
        }
        if (kind != null) tokens.add(CodeToken(i, end, kind))
        i = end
        continue
      }

      i++
    }
    return tokens
  }

  private data class Profile(
    val keywords: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val quotes: Set<Char> = emptySet(),
    val tripleQuotes: Boolean = false,
    val hashDirectives: Boolean = false,
    val diff: Boolean = false,
  )

  private fun profileFor(raw: String): Profile {
    val language = raw.trim().lowercase().removePrefix("language-").removePrefix(".")
    return when (language) {
      "kotlin", "kt", "kts" -> Profile(
        keywords = keywords(
          "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "class",
          "object", "interface", "data", "enum", "sealed", "override", "private", "public",
          "internal", "protected", "import", "package", "try", "catch", "finally", "throw", "is",
          "in", "as", "by", "lazy", "lateinit", "companion", "init", "constructor", "suspend",
          "inline", "noinline", "crossinline", "reified", "where", "typealias", "abstract", "open",
          "const", "this", "super", "null", "true", "false",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\''),
        tripleQuotes = true,
      )
      "java" -> Profile(
        keywords = keywords(
          "public", "private", "protected", "class", "interface", "extends", "implements", "static",
          "final", "void", "if", "else", "for", "while", "do", "switch", "case", "default", "break",
          "continue", "return", "new", "try", "catch", "finally", "throw", "throws", "import",
          "package", "this", "super", "null", "true", "false", "abstract", "synchronized",
          "volatile", "transient", "native", "enum", "record", "var", "instanceof", "yield",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\''),
      )
      "javascript", "js", "jsx", "mjs", "cjs", "typescript", "ts", "tsx" -> Profile(
        keywords = keywords(
          "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
          "switch", "case", "break", "continue", "class", "extends", "new", "this", "super",
          "import", "export", "from", "default", "async", "await", "try", "catch", "finally",
          "throw", "typeof", "instanceof", "in", "of", "null", "true", "false", "undefined",
          "interface", "type", "enum", "public", "private", "protected", "readonly", "implements",
          "namespace", "declare", "abstract", "as", "satisfies", "void", "delete", "yield",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\'', '`'),
      )
      "python", "py" -> Profile(
        keywords = keywords(
          "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or",
          "is", "None", "True", "False", "import", "from", "as", "pass", "break", "continue", "try",
          "except", "finally", "raise", "with", "lambda", "global", "nonlocal", "assert", "yield",
          "async", "await", "del", "self",
        ),
        lineComments = listOf("#"),
        quotes = setOf('"', '\''),
        tripleQuotes = true,
      )
      "shell", "sh", "bash", "zsh", "console", "shellscript" -> Profile(
        keywords = keywords(
          "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
          "function", "in", "select", "until", "return", "break", "continue", "local", "export",
          "readonly", "declare", "unset", "shift", "source", "alias", "echo", "cd", "pwd", "exit",
          "set", "trap",
        ),
        lineComments = listOf("#"),
        quotes = setOf('"', '\''),
      )
      "json", "jsonc" -> Profile(
        keywords = keywords("true", "false", "null"),
        lineComments = if (language == "jsonc") listOf("//") else emptyList(),
        blockComments = if (language == "jsonc") listOf("/*" to "*/") else emptyList(),
        quotes = setOf('"'),
      )
      "sql" -> Profile(
        keywords = keywords(
          "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
          "create", "table", "alter", "drop", "join", "inner", "left", "right", "outer", "on",
          "group", "by", "order", "having", "limit", "offset", "as", "and", "or", "not", "null",
          "is", "in", "like", "distinct", "union", "all", "case", "when", "then", "else", "end",
          "primary", "key", "foreign", "references", "index", "view", "true", "false",
        ),
        lineComments = listOf("--"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('\'', '"'),
      )
      "yaml", "yml" -> Profile(
        keywords = keywords("true", "false", "null", "yes", "no", "on", "off"),
        lineComments = listOf("#"),
        quotes = setOf('"', '\''),
      )
      "html", "xml", "svg", "xhtml" -> Profile(
        blockComments = listOf("<!--" to "-->"),
        quotes = setOf('"', '\''),
      )
      "css", "scss", "less" -> Profile(
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\''),
      )
      "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "objc", "objective-c" -> Profile(
        keywords = keywords(
          "int", "char", "float", "double", "void", "long", "short", "unsigned", "signed", "struct",
          "union", "enum", "typedef", "static", "const", "volatile", "extern", "register", "if",
          "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return",
          "goto", "sizeof", "true", "false", "NULL", "class", "public", "private", "protected",
          "namespace", "template", "typename", "new", "delete", "using", "auto", "nullptr",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\''),
        hashDirectives = true,
      )
      "csharp", "cs", "c#" -> Profile(
        keywords = keywords(
          "using", "namespace", "class", "interface", "struct", "enum", "public", "private",
          "protected", "internal", "static", "readonly", "const", "void", "var", "new", "return",
          "if", "else", "for", "foreach", "while", "do", "switch", "case", "break", "continue",
          "try", "catch", "finally", "throw", "async", "await", "null", "true", "false", "record",
          "get", "set", "value", "out", "ref", "in", "is", "as",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\''),
      )
      "go", "golang" -> Profile(
        keywords = keywords(
          "package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan",
          "go", "defer", "return", "if", "else", "for", "range", "switch", "case", "default",
          "break", "continue", "select", "nil", "true", "false", "fallthrough", "goto",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\'', '`'),
      )
      "rust", "rs" -> Profile(
        keywords = keywords(
          "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "for", "while",
          "loop", "if", "else", "match", "return", "use", "mod", "pub", "crate", "self", "super",
          "where", "async", "await", "move", "ref", "dyn", "box", "true", "false", "Some", "None",
          "Ok", "Err", "unsafe", "extern", "type", "as", "in",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"'),
      )
      "swift" -> Profile(
        keywords = keywords(
          "func", "let", "var", "class", "struct", "enum", "protocol", "extension", "import", "if",
          "else", "for", "while", "repeat", "switch", "case", "default", "return", "guard", "defer",
          "throw", "throws", "try", "catch", "async", "await", "true", "false", "nil", "self",
          "super", "in", "where", "public", "private", "internal", "fileprivate", "static", "mutating",
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"'),
        tripleQuotes = true,
      )
      "ruby", "rb" -> Profile(
        keywords = keywords(
          "def", "class", "module", "end", "if", "elsif", "else", "unless", "while", "until", "for",
          "in", "do", "return", "yield", "begin", "rescue", "ensure", "raise", "puts", "require",
          "attr_accessor", "true", "false", "nil", "self", "and", "or", "not", "then",
        ),
        lineComments = listOf("#"),
        quotes = setOf('"', '\''),
      )
      "php" -> Profile(
        keywords = keywords(
          "function", "class", "interface", "trait", "extends", "implements", "public", "private",
          "protected", "static", "const", "return", "if", "else", "elseif", "foreach", "for",
          "while", "do", "switch", "case", "break", "continue", "echo", "new", "try", "catch",
          "finally", "throw", "null", "true", "false", "use", "namespace", "fn", "match",
        ),
        lineComments = listOf("//", "#"),
        blockComments = listOf("/*" to "*/"),
        quotes = setOf('"', '\''),
      )
      "diff", "patch" -> Profile(diff = true)
      else -> Profile()
    }
  }

  private fun diffTokens(code: String): List<CodeToken> {
    val tokens = ArrayList<CodeToken>()
    var offset = 0
    for (line in code.split('\n')) {
      val kind = when {
        line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") ||
          line.startsWith("diff ") || line.startsWith("index ") ||
          line.startsWith("new file") || line.startsWith("deleted file") -> CodeTokenKind.META
        line.startsWith("+") -> CodeTokenKind.ADDED
        line.startsWith("-") -> CodeTokenKind.REMOVED
        else -> null
      }
      if (kind != null) tokens.add(CodeToken(offset, offset + line.length, kind))
      offset += line.length + 1
    }
    return tokens
  }

  private fun lineEnd(code: String, from: Int): Int {
    val newline = code.indexOf('\n', from)
    return if (newline < 0) code.length else newline
  }

  private fun scanString(code: String, start: Int, quote: String, allowNewline: Boolean): Int {
    var i = start + quote.length
    while (i < code.length) {
      if (code[i] == '\\' && i + 1 < code.length) {
        i += 2
        continue
      }
      if (code.startsWith(quote, i)) return i + quote.length
      if (code[i] == '\n' && !allowNewline) return i
      i++
    }
    return code.length
  }

  private fun scanNumber(code: String, start: Int): Int {
    var i = start
    val radix = code.getOrNull(i + 1)?.lowercaseChar()
    if (code[i] == '0' && (radix == 'x' || radix == 'b' || radix == 'o')) {
      i += 2
      while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
      return i
    }
    while (i < code.length && (code[i].isDigit() || code[i] == '_')) i++
    if (code.getOrNull(i) == '.') {
      i++
      while (i < code.length && (code[i].isDigit() || code[i] == '_')) i++
    }
    if (code.getOrNull(i)?.lowercaseChar() == 'e') {
      i++
      if (code.getOrNull(i) == '+' || code.getOrNull(i) == '-') i++
      while (i < code.length && code[i].isDigit()) i++
    }
    while (i < code.length && code[i] in "fFdDlLuU") i++
    return i
  }

  private fun scanIdentifier(code: String, start: Int): Int {
    var i = start
    while (i < code.length && isIdentifierPart(code[i])) i++
    return i
  }

  private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

  private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

  private fun keywords(vararg words: String): Set<String> = words.toHashSet()

  private val TYPE_WORDS = keywords(
    "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short", "Unit",
    "Any", "Nothing", "List", "Map", "Set", "Array", "Object", "Void", "Integer", "Number",
    "Task", "Result", "Error", "Promise", "Record",
  )
}
