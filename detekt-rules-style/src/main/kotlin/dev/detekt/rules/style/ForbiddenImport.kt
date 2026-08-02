package dev.detekt.rules.style

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import dev.detekt.api.valuesWithReason
import dev.detekt.psi.pathGlobToRegex
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports all imports that are forbidden.
 *
 * This rule allows to set a list of forbidden [forbiddenImports].
 * This can be used to discourage the use of unstable, experimental or deprecated APIs.
 * Imports are configured as glob patterns and may include a reason that is shown in the finding:
 *
 * ```yaml
 * ForbiddenImport:
 *   active: true
 *   forbiddenImports:
 *     - value: 'kotlin.jvm.JvmField'
 *       reason: 'Use explicit backing properties instead.'
 *     - value: 'java.util.*'
 *       reason: 'Use Kotlin standard library APIs instead.'
 *   allowedImports:
 *     - 'java.util.UUID'
 * ```
 *
 * Detekt checks the name written after `import`, with any alias removed. `import java.util.Date` is checked
 * as `java.util.Date`, `import java.util.List as JList` as `java.util.List`, and `import java.util.*` as
 * `java.util.*` — an all-under import keeps its trailing star, so a pattern meant to match it needs one too.
 * Both [forbiddenImports] and [allowedImports] are matched against that name.
 *
 * A pattern reports an import when it matches that name completely:
 *
 * - `*` matches zero or more characters, including the package separator `.`. The pattern `java.util.*`
 *   therefore reports `import java.util.Date`, `import java.util.concurrent.Future` and `import java.util.*`.
 * - `?` matches exactly one character, so `java.util.Dat?` reports `import java.util.Date` but neither
 *   `import java.util.Dat` nor `import java.util.Dates`.
 * - `.` always matches a literal dot; use `?` if you mean "any character". `*`, `?` and `.` cannot be
 *   escaped. Any other regular expression syntax is passed through, so `java.util.(Date|UUID)` is a valid
 *   pattern, while an unbalanced `java.util.[` fails the analysis with a pattern syntax error.
 * - `java.util.Date` reports only `import java.util.Date`. It does not report `import java.util.DateFormat`,
 *   nor `import java.util.*`, which detekt cannot expand without resolving the contents of the package.
 *
 * An import that matches both [forbiddenImports] and [allowedImports] is not reported.
 *
 * <noncompliant>
 * import kotlin.jvm.JvmField
 * import java.util.Date
 * import java.util.*
 * import java.util.UUID.*
 * </noncompliant>
 *
 * <compliant>
 * import java.util.UUID
 * </compliant>
 */
class ForbiddenImport(config: Config) :
    Rule(
        config,
        "Mark forbidden imports. A forbidden import could be an import for an unstable / experimental api " +
            "and hence you might want to mark it as forbidden in order to get warned about the usage."
    ) {

    @Configuration(
        "List of imports, specified as glob patterns, that are forbidden. `*` matches zero or more characters " +
            "including the package separator, `?` matches exactly one character, any other regular expression " +
            "syntax is passed through, and a pattern has to match the whole imported name, including the " +
            "trailing star of an all-under import such as `import java.util.*`. " +
            "It is recommended to also specify a reason."
    )
    private val forbiddenImports: List<Forbidden> by config(valuesWithReason()) { list ->
        list.map { Forbidden(it.value.pathGlobToRegex(), it.reason) }
    }

    @Configuration(
        "List of imports, specified as glob patterns, to explicitly allow. " +
            "Use this to specify exceptions to the forbidden imports. A pattern has to match the whole " +
            "imported name, including the trailing star of an all-under import. " +
            "An import that matches both lists is not reported."
    )
    private val allowedImports: List<Regex> by config(emptyList<String>()) { list ->
        list.map { it.pathGlobToRegex() }
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)

        val importedName = importDirective.importedFqName?.asString() ?: return
        // `importedFqName` omits the trailing star of an all-under import, so `import java.util.*` yields
        // `java.util`. Restore the star so that both lists match the import as it is written in the source.
        val import = if (importDirective.isAllUnder) "$importedName.*" else importedName

        val forbidden = forbiddenImports.find { it.import.matches(import) } ?: return

        if (importIsExplicitlyAllowed(import)) {
            return
        }
        val reason = forbidden.reason?.let { "The import `$import` has been forbidden: ${forbidden.reason}" }
            ?: defaultReason(import)

        report(Finding(Entity.from(importDirective), reason))
    }

    private fun defaultReason(forbiddenImport: String): String =
        "The import `$forbiddenImport` has been forbidden in the detekt config."

    private fun importIsExplicitlyAllowed(import: String): Boolean =
        allowedImports.any { allowed -> allowed.matches(import) }
}

private data class Forbidden(val import: Regex, val reason: String?)
