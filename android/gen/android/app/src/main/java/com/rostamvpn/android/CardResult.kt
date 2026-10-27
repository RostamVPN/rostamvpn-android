package com.rostamvpn.android

/** How the card sheet (src/cardSheet or its stub) ended. */
sealed class CardResult {
    data object Completed : CardResult()
    data object Cancelled : CardResult()
    data class Failed(val message: String?) : CardResult()
}
