package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewPurchaseTest {
    private fun e(iccid: String, order: String = "") = Esim(iccid, "", "", "LPA:1\$x\$y", "", "", null, null, "", orderId = order)

    @Test fun byOrderIdFirst() {
        val l = listOf(e("new2", "o2"), e("new1", "o1"), e("old", "o0"))
        assertEquals("new1", newPurchase(l, Awaiting("o1", setOf("old")))!!.iccid)
    }

    @Test fun elseTheIccidThatWasNotThere() {
        val l = listOf(e("new"), e("old"))
        assertEquals("new", newPurchase(l, Awaiting(null, setOf("old")))!!.iccid)
        assertEquals("new", newPurchase(l, Awaiting("unknown-order", setOf("old")))!!.iccid)
    }

    @Test fun nothingYet() {
        assertNull(newPurchase(listOf(e("old")), Awaiting(null, setOf("old"))))
        assertNull(newPurchase(listOf(e("x")), Awaiting(null, null)))   // no snapshot, no order: never guess
    }
}

class AttachedNowTest {
    private fun e(iccid: String, status: String) = Esim(iccid, "", status, "", "", "", null, null, if (status == "active") "ACTIVE" else "NOT_ACTIVE")

    @Test fun onlyAWaitingEsimThatTurnedActive() {
        val l = listOf(e("a", "active"), e("b", "active"), e("c", "ready"))
        org.junit.Assert.assertEquals(listOf("a"), attachedNow(l, waiting = setOf("a", "c"), attached = emptySet()).map { it.iccid })
        // b was never seen waiting (restored already active): not an attach
        org.junit.Assert.assertTrue(attachedNow(l, waiting = setOf("c"), attached = emptySet()).isEmpty())
        // a already counted
        org.junit.Assert.assertTrue(attachedNow(l, waiting = setOf("a"), attached = setOf("a")).isEmpty())
    }
}
