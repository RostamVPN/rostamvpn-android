package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Numbers are never permanent (ESIM-INTEGRATION-2026-10-15 §2 rule 5): no
 * eSIM-tab copy may say permanent, forever, keep (it) for good, unlimited time
 * or auto-renew. Scans the English source of every tabs_* string, so a new
 * string that breaks the rule fails the build's unit tests. "Unlimited" on its
 * own is a data allowance (tabs_unlimited) and is allowed; "unlimited time" is not.
 */
class NumberCopyTest {
    private val banned = listOf(
        Regex("""\bpermanent(ly)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bforever\b""", RegexOption.IGNORE_CASE),
        Regex("""\bkeep (it |this number |the number )?for good\b""", RegexOption.IGNORE_CASE),
        Regex("""\bunlimited time\b""", RegexOption.IGNORE_CASE),
        Regex("""\bauto[- ]?renew""", RegexOption.IGNORE_CASE),
    )

    @Test fun eSimTabCopyNeverPromisesPermanence() {
        val f = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.exists() }
        val strings = Regex("""<string name="(tabs_[a-z0-9_]+)"[^>]*>([^<]*)</string>""").findAll(f.readText())
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("no tabs_* strings found in $f", strings.size > 50)
        val hits = strings.flatMap { (k, v) -> banned.filter { it.containsMatchIn(v) }.map { "$k: \"$v\" (${it.pattern})" } }
        assertTrue("copy promises permanence:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun theScannerCatchesEachBannedPhrase() {
        listOf("Your number is permanent", "Keep it forever", "keep it for good", "Unlimited time", "Auto-renews monthly")
            .forEach { s -> assertTrue(s, banned.any { it.containsMatchIn(s) }) }
        listOf("Unlimited", "Reload to keep this number", "Renews monthly. Cancel anytime.")
            .forEach { s -> assertTrue(s, banned.none { it.containsMatchIn(s) }) }
    }
}
