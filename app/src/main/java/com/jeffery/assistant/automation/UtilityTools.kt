package com.jeffery.assistant.automation

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Small, self-contained utility tools — no permissions, no external services, just logic. */
object UtilityTools {

    fun convertUnits(value: Double, from: String, to: String): String? {
        val f = from.trim().lowercase()
        val t = to.trim().lowercase()
        val conversions: Map<Pair<String, String>, (Double) -> Double> = mapOf(
            ("celsius" to "fahrenheit") to { c -> c * 9 / 5 + 32 },
            ("fahrenheit" to "celsius") to { fh -> (fh - 32) * 5 / 9 },
            ("miles" to "km") to { m -> m * 1.60934 },
            ("km" to "miles") to { k -> k / 1.60934 },
            ("kg" to "lbs") to { kg -> kg * 2.20462 },
            ("lbs" to "kg") to { lb -> lb / 2.20462 },
            ("meters" to "feet") to { m -> m * 3.28084 },
            ("feet" to "meters") to { ft -> ft / 3.28084 },
            ("inches" to "cm") to { i -> i * 2.54 },
            ("cm" to "inches") to { c -> c / 2.54 }
        )
        val converter = conversions[f to t] ?: return null
        val result = converter(value)
        return "%.2f %s is %.2f %s".format(value, from, result, to)
    }

    fun generatePassword(length: Int, includeSymbols: Boolean): String {
        val letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val digits = "0123456789"
        val symbols = "!@#$%^&*()-_=+[]{}"
        val pool = letters + digits + if (includeSymbols) symbols else ""
        val random = SecureRandom()
        return (1..length.coerceIn(4, 128)).map { pool[random.nextInt(pool.length)] }.joinToString("")
    }

    /** Parses a date like "2026-08-15" or "August 15 2026" (best-effort) and returns days remaining. */
    fun daysUntil(dateString: String): String {
        val trimmed = dateString.trim()
        val formats = listOf("yyyy-MM-dd", "MM/dd/yyyy", "MMMM d, yyyy", "MMMM d yyyy", "MMM d yyyy")
        for (pattern in formats) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
                val target = LocalDate.parse(trimmed, formatter)
                val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), target)
                return when {
                    days == 0L -> "That's today."
                    days > 0 -> "$days day${if (days != 1L) "s" else ""} away."
                    else -> "That was ${abs(days)} day${if (abs(days) != 1L) "s" else ""} ago."
                }
            } catch (e: Exception) {
                continue
            }
        }
        return "Couldn't parse that date — try a format like 2026-08-15."
    }

    fun rollDice(sides: Int, count: Int): String {
        val random = SecureRandom()
        val safeSides = sides.coerceIn(2, 1000)
        val safeCount = count.coerceIn(1, 20)
        val rolls = (1..safeCount).map { random.nextInt(safeSides) + 1 }
        return if (safeCount == 1) "Rolled a ${rolls.first()}." else "Rolled: ${rolls.joinToString(", ")} (total ${rolls.sum()})."
    }

    fun flipCoin(): String = if (SecureRandom().nextBoolean()) "Heads." else "Tails."

    fun batteryAndSystemStats(context: Context): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

        val stat = StatFs(android.os.Environment.getDataDirectory().path)
        val availableStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
        val totalStorageGb = (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)

        return "Battery: %d%%. RAM: %.1f/%.1f GB free. Storage: %.1f/%.1f GB free."
            .format(batteryPercent, availableRamGb, totalRamGb, availableStorageGb, totalStorageGb)
    }
}
