package org.projectnia.app.avatar

object MotionBundlePolicy {
    const val MAX_ENTRIES = 256
    const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    const val MAX_BUNDLE_BYTES = 64L * 1024L * 1024L

    private val safeEntry = Regex("[a-z0-9_-]{1,120}\\.(niamotion|niareview)")

    fun isSafeEntryName(name: String): Boolean = safeEntry.matches(name)

    fun validateShape(names: List<String>) {
        require(names.isNotEmpty()) { "Backup is empty" }
        require(names.size <= MAX_ENTRIES && names.distinct().size == names.size) {
            "Backup contains too many or duplicate files"
        }
        require(names.all(::isSafeEntryName)) { "Backup contains an unsupported file" }
        val motions = names.filter { it.endsWith(".niamotion") }.map { it.removeSuffix(".niamotion") }.toSet()
        val reviews = names.filter { it.endsWith(".niareview") }.map { it.removeSuffix(".niareview") }.toSet()
        require(motions.isNotEmpty() && motions == reviews) {
            "Backup must contain matching approved motion and review files"
        }
    }
}
