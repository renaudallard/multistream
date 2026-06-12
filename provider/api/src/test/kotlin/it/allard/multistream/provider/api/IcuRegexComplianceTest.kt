package it.allard.multistream.provider.api

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's ICU regex engine (API 34+) rejects literal braces that the JVM engine accepts, so a
 * pattern that passes every JVM unit test can still crash on device. This scans every Regex string
 * literal in the project's main sources and fails on an unescaped brace that is not a quantifier
 * or a \p{...}/\x{...} construct.
 */
class IcuRegexComplianceTest {

    @Test fun regexLiteralsContainNoBareBraces() {
        val violations = mutableListOf<String>()
        for (file in mainSourceFiles()) {
            val text = file.readText()
            for (pattern in regexLiterals(text)) {
                if (hasBareBrace(pattern)) violations.add("${file.path}: Regex(\"$pattern\")")
            }
        }
        assertTrue(
            "Escape literal braces as \\{ and \\}; ICU rejects bare ones:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun mainSourceFiles(): List<File> {
        // Tests run with the module directory as the working directory; walk up to the repo root.
        var root = File("").absoluteFile
        while (!File(root, "settings.gradle.kts").exists()) {
            root = root.parentFile ?: error("repo root not found")
        }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") }
            .toList()
    }

    /** The string contents of Regex("...") and Regex(""\"...""\") literals in [text]. */
    private fun regexLiterals(text: String): List<String> {
        val plain = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val raw = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        // A plain literal's backslashes are Kotlin-escaped; undo one level to get the pattern.
        return raw.findAll(text).map { it.groupValues[1] }.toList() +
            plain.findAll(text).map { unescapeKotlin(it.groupValues[1]) }
    }

    private fun unescapeKotlin(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun hasBareBrace(pattern: String): Boolean {
        // Strip the brace uses that are valid in both engines, then look for what is left over.
        val stripped = pattern
            .replace(Regex("""\\[pPx]\{[^}]*\}"""), "") // \p{Mn}, \x{2019} style constructs
            .replace(Regex("""\\[{}]"""), "") // explicitly escaped braces
            .replace(Regex("""\{\d+(,\d*)?\}"""), "") // quantifiers {2}, {1,}, {1,3}
        return '{' in stripped || '}' in stripped
    }
}
