package com.opentalk.android.models

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED,
    REJECTED
}

data class CallRecord(
    val id: Long = 0,
    val remoteNumber: String,
    val displayName: String,
    val type: CallType,
    val timestamp: Long,
    val durationSeconds: Int,
    val accountId: String? = null
) {
    val formattedDuration: String
        get() {
            val safeSeconds = maxOf(0, durationSeconds)
            val minutes = safeSeconds / 60
            val seconds = safeSeconds % 60
            val hours = minutes / 60
            return when {
                hours > 0 -> String.format(Locale.US, "%dh %dm %ds", hours, minutes % 60, seconds)
                minutes > 0 -> String.format(Locale.US, "%dm %ds", minutes, seconds)
                else -> "${seconds}s"
            }
        }

    val formattedTime: String
        get() = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))

    val formattedDate: String
        get() = SimpleDateFormat("dd MMM, yyyy", Locale.getDefault()).format(Date(timestamp))

    /**
     * Smart date and time for Recents list:
     * - Today: "Today, 10:32 AM"
     * - Yesterday: "Yesterday, 11:45 PM"
     * - This year: "24 Oct, 10:57 PM"
     * - Older: "24/10/2025, 10:57 PM"
     */
    val formattedDateTime: String
        get() {
            return try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val callDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
                val diffDays = ChronoUnit.DAYS.between(callDate, today)
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))

                when {
                    diffDays == 0L -> "Today, $timeStr"
                    diffDays == 1L -> "Yesterday, $timeStr"
                    today.year == callDate.year -> {
                        val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
                        "$dateStr, $timeStr"
                    }
                    else -> {
                        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
                        "$dateStr, $timeStr"
                    }
                }
            } catch (_: Exception) {
                ""
            }
        }
}
