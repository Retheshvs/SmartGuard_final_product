package com.smartguard.policy

/**
 * Maps human app categories to representative Android package names, used by both the
 * Policy Engine (to translate a natural-language policy into concrete allow/block sets)
 * and the AccessibilityService (to enforce category curfews). Fully static & on-device.
 */
object AppCategories {

    val CATEGORIES: Map<String, List<String>> = mapOf(
        "games" to listOf(
            "com.king.candycrushsaga",
            "com.supercell.clashofclans",
            "com.roblox.client",
            "com.mojang.minecraftpe",
            "com.activision.callofduty.shooter",
            "com.dts.freefireth",
            "com.tencent.ig"
        ),
        "social" to listOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.snapchat.android",
            "com.facebook.katana",
            "com.whatsapp",
            "com.twitter.android",
            "com.reddit.frontpage"
        ),
        "video" to listOf(
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "in.startv.hotstar"
        ),
        "browser" to listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx"
        ),
        "education" to listOf(
            "com.duolingo",
            "org.khanacademy.android",
            "com.google.android.apps.classroom",
            "com.youtube.kids",
            "com.google.android.apps.youtube.kids"
        ),
        "utilities" to listOf(
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.google.android.deskclock",
            "com.android.deskclock"
        )
    )

    fun packagesFor(category: String): List<String> =
        CATEGORIES[category.lowercase()] ?: emptyList()

    fun categoryOf(packageName: String): String? =
        CATEGORIES.entries.firstOrNull { it.value.contains(packageName) }?.key

    val knownCategories: Set<String> get() = CATEGORIES.keys
}
