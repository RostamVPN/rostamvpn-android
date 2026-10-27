package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The perk comes back only when the request names the brand (e-sim.net e828773). */
class CatalogPathsTest {
    private val all = listOf(
        CatalogPaths.destinations("internetpro"),
        CatalogPaths.destination("JP", "internetpro"),
        CatalogPaths.plan("jp-5gb-30d", "internetpro"),
        CatalogPaths.topups("8901260000000000001", "internetpro"),
        CatalogPaths.recommend("JP", 7, "socials", "internetpro"),
    )

    @Test fun everyCatalogAndTopupRequestNamesTheBrand() {
        all.forEach { p ->
            val q = p.substringAfter('?', "").split('&')
            // q is the split query, so check each parameter on its own (the old
            // joined-string check could never match and always failed).
            assertTrue(p, "utm_source=internetpro" in q && "utm_medium=android" in q)
            assertEquals(p, 1, p.count { it == '?' })
        }
    }

    @Test fun exactShapes() {
        assertEquals("/destinations?utm_source=internetpro&utm_medium=android", all[0])
        assertEquals("/destinations/JP?utm_source=internetpro&utm_medium=android", all[1])
        assertEquals("/plans/jp-5gb-30d?utm_source=internetpro&utm_medium=android", all[2])
        assertEquals("/esims/8901260000000000001/topups?utm_source=internetpro&utm_medium=android", all[3])
        assertEquals("/recommend?dest=JP&days=7&usage=socials&utm_source=internetpro&utm_medium=android", all[4])
        assertEquals("/destinations/a%20b?utm_source=x%26y&utm_medium=android", CatalogPaths.destination("a b", "x&y"))
    }

    @Test fun defaultIsThisBuildsCashierBrand() {
        assertTrue(CatalogPaths.destinations().contains("utm_source=" + com.rostamvpn.android.BuildConfig.CASHIER_BRAND))
    }

    @Test fun topupWithThePerkCarriesItsDays() {
        val t = parseTopups("""{"topups":[{"slug":"t1","data_mb":1024,"days":7,"price_cents":450,"perk":{"vpn_days":7}},
            {"slug":"t2","data_mb":2048,"days":7,"price_cents":800}]}""")!!
        assertEquals(listOf(7, null), t.map { it.vpnDays })
    }
}
