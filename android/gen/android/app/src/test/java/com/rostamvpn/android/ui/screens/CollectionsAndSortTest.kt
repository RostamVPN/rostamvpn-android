package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsAndSortTest {
    // Shape of api.py _collection_out for content/collections.json "winter-markets".
    private val list = """{"collections":[{"id":"winter-markets","title":"Christmas markets",
        "subtitle":"One eSIM for Europe's winter cities. Online the moment you land.","starts":"2026-11-20","ends":"2027-01-08",
        "image":"https://e-sim.net/static/img/europe.jpg","url":"https://e-sim.net/c/winter-markets"},
        {"id":"","title":"broken"}]}"""
    private val one = """{"id":"winter-markets","title":"Weihnachtsmärkte","subtitle":"x","starts":"2026-11-20","ends":"2027-01-08",
        "destinations":[{"id":"europe","kind":"region","name":"Europe","flag":"","plan_count":9,"from_cents":450},
                        {"id":"DE","kind":"country","name":"Germany","flag":"🇩🇪","plan_count":7,"from_cents":350}]}"""

    @Test fun parsesTheList() {
        val c = parseCollections(list)
        assertEquals(1, c.size)
        assertEquals("winter-markets", c[0].id)
        assertEquals("Christmas markets", c[0].title)
        assertTrue(parseCollections("""{"collections":[]}""").isEmpty())
    }

    @Test fun parsesOneWithDestinations() {
        val c = parseCollection(one)
        assertEquals(listOf("europe", "DE"), c.destinations.map { it.id })
        assertEquals(350L, c.destinations[1].fromCents)
    }

    private fun p(slug: String, mb: Long, days: Int, cents: Long, unl: Boolean = false) = PassPlan(slug, "JP", mb, unl, days, cents, 0, 0)
    private val plans = listOf(p("1g7", 1024, 7, 450), p("5g30", 5120, 30, 1300), p("3g15", 3072, 15, 900), p("u7", 0, 7, 2000, true))

    @Test fun sorts() {
        assertEquals(listOf("1g7", "3g15", "5g30", "u7"), sortPlans(plans, PlanSort.TOTAL).map { it.slug })
        // per GB: 260, 300, 450; unlimited last
        assertEquals(listOf("5g30", "3g15", "1g7", "u7"), sortPlans(plans, PlanSort.PER_GB).map { it.slug })
        // per day: 43.3, 60, 64.3, 285.7
        assertEquals(listOf("5g30", "3g15", "1g7", "u7"), sortPlans(plans, PlanSort.PER_DAY).map { it.slug })
    }

    @Test fun bestValueIsLowestPerGbWithACap() {
        assertEquals("5g30", bestValue(plans)!!.slug)
        assertEquals(null, bestValue(listOf(p("u", 0, 7, 100, true))))
    }
}
