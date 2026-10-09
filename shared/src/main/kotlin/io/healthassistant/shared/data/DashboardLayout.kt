package io.healthassistant.shared.data

/** How the Home dashboard arranges its metric cards (R-post-8 feature). */
enum class HomeViewStyle {
    GRID,
    LIST,
    SIMPLE,
}

/** Pure-Kotlin dashboard layout logic: applies the user's show-set and custom
 *  order to the readable biomarker list. JVM-testable. */
object DashboardLayout {

    /** The default "basic" cards a fresh user starts with — the everyday Health
     *  Connect metrics (heart rate, steps, weight, SpO₂, sleep). The catalog
     *  lets the user add the rest. */
    fun basicCodes(): Set<String> =
        setOf(
            "8867-4", // Heart rate
            "55423-8", // Steps
            "29463-7", // Weight
            "59408-5", // Oxygen saturation
            "sleep-duration", // Sleep
        )

    /** Filter the readings to the shown [showCodes] and sort by [order]
     *  (unknown codes sink to the end in their original relative order).
     *  Empty showCodes = show everything. Empty order = keep as-is. */
    fun filterAndOrder(
        readings: List<BiomarkerReading>,
        showCodes: Set<String>,
        order: List<String>,
    ): List<BiomarkerReading> {
        val visible =
            if (showCodes.isEmpty()) {
                readings
            } else {
                readings.filter { it.code in showCodes }
            }
        if (order.isEmpty()) return visible
        val rank = order.withIndex().associate { (index, code) -> code to index }
        return visible.sortedBy { rank[it.code] ?: Int.MAX_VALUE }
    }

    /** Move a code one step (delta = ±1) within [order]; returns the new order
     *  (or the same list when the move is not possible). */
    fun moveCode(
        order: List<String>,
        code: String,
        delta: Int,
    ): List<String> {
        val index = order.indexOf(code)
        val target = index + delta
        if (index < 0 || target !in order.indices) return order
        val mutable = order.toMutableList()
        mutable.add(target, mutable.removeAt(index))
        return mutable
    }
}
