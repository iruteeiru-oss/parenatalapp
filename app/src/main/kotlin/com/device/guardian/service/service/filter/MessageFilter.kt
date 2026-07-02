package com.device.guardian.service.service.filter

object MessageFilter {

    private val bullyingPatterns = setOf(
        "ugly", "stupid", "idiot", "loser", "fat", "hate you",
        "kill yourself", "kys", "nobody likes you", "die",
        "worthless", "freak", "dumb", "pathetic", "shut up",
        "you're a joke", "no one likes you", "go away"
    )

    private val distressPatterns = setOf(
        "i want to die", "i hate myself", "i give up",
        "nobody cares", "i can't take it", "i'm done",
        "end it all", "i don't want to live"
    )

    data class FilterResult(
        val isFlagged: Boolean,
        val reason: String?
    )

    fun analyze(content: String): FilterResult {
        val lower = content.lowercase().trim()

        distressPatterns.firstOrNull { lower.contains(it) }?.let {
            return FilterResult(true, "Distress signal detected")
        }

        bullyingPatterns.firstOrNull { lower.contains(it) }?.let {
            return FilterResult(true, "Possible bullying detected")
        }

        return FilterResult(false, null)
    }
}
