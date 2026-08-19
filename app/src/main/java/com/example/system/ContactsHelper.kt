package com.example.system

import android.content.Context
import android.provider.ContactsContract

/**
 * Looks up phone contacts by a spoken/typed name, tolerating mispronunciation
 * or misspelling (e.g. "Ramesh" said as "Rameesh" still matches "Ramesh
 * Kumar"). Requires READ_CONTACTS permission — if not granted, all lookups
 * simply return no match (caller falls back to treating the input as a raw
 * phone number or asks the user to add the number manually).
 */
class ContactsHelper(private val context: Context) {

    data class ContactMatch(
        val name: String,
        val phoneNumber: String,
        val confidence: Double // 0.0 - 1.0, higher is a closer match
    )

    private fun hasPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns the best-matching contact for [spokenName], or null if
     * permission is missing, no contacts exist, or nothing matches closely
     * enough (confidence below 0.5) to avoid messaging/calling the wrong
     * person on a wild guess.
     */
    fun findBestMatch(spokenName: String): ContactMatch? {
        if (!hasPermission()) return null
        val query = spokenName.trim().lowercase()
        if (query.isBlank()) return null

        val candidates = mutableListOf<ContactMatch>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: continue
                val number = it.getString(numberIdx) ?: continue
                val score = similarityScore(query, name.lowercase())
                if (score > 0.0) {
                    candidates.add(ContactMatch(name, number, score))
                }
            }
        }

        return candidates.maxByOrNull { it.confidence }?.takeIf { it.confidence >= 0.5 }
    }

    /**
     * Combines an exact-substring check with normalized edit-distance so
     * "Rameesh" still strongly matches "Ramesh Kumar" (checks each word in
     * the contact's full name separately, since spoken names are usually
     * just the first name).
     */
    private fun similarityScore(query: String, contactNameLower: String): Double {
        if (contactNameLower.contains(query)) return 1.0

        val words = contactNameLower.split(" ", "-").filter { it.isNotBlank() }
        var best = 0.0
        for (word in words) {
            val dist = levenshtein(query, word)
            val maxLen = maxOf(query.length, word.length)
            if (maxLen == 0) continue
            val score = 1.0 - (dist.toDouble() / maxLen)
            if (score > best) best = score
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
