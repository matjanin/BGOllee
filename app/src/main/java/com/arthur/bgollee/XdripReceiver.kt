package com.arthur.bgollee

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class XdripReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_XDRIP_BG = "com.eveningoutpost.dexdrip.BgEstimate"
        private const val ACTION_GDH_BG = "com.eveningoutpost.dexdrip.watch.wearintegration.BROADCAST_SERVICE_RECEIVER"
        private const val XDRIP_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
        private const val XDRIP_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
        private const val XDRIP_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
        private const val GDH_FUNCTION = "FUNCTION"
        private const val GDH_BG = "bg.valueMgdl"
        private const val GDH_DELTA = "bg.deltaValueMgdl"
        private const val GDH_TREND = "bg.deltaName"
        private const val GDH_UPDATE_BG = "update_bg"
        private const val GDH_UPDATE_BG_FORCE = "update_bg_force"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isGdhBroadcast = intent.action == ACTION_GDH_BG
        if (!isGdhBroadcast && intent.action != ACTION_XDRIP_BG) {
            Log.d("BG_RECEIVER", "Ignoring action: ${intent.action}")
            return
        }

        val extras = intent.extras ?: run {
            Log.e("BG_RECEIVER", "Broadcast has no extras")
            return
        }

        if (isGdhBroadcast) {
            val function = extras.getString(GDH_FUNCTION)
            if (function != GDH_UPDATE_BG && function != GDH_UPDATE_BG_FORCE) {
                Log.d("BG_RECEIVER", "Ignoring GDH function: $function")
                return
            }
        }

        val bgKey = if (isGdhBroadcast) GDH_BG else XDRIP_BG
        val bgValue = numberValue(extras.get(bgKey))
        val bg = bgValue?.roundToInt()?.toString() ?: run {
            Log.e("BG_RECEIVER", "No valid glucose value in $bgKey")
            return
        }

        val delta: Int?
        val trend: String?
        if (isGdhBroadcast) {
            delta = numberValue(extras.get(GDH_DELTA))?.roundToInt()
            trend = trendFromName(extras.get(GDH_TREND))
                ?: delta?.let { trendFromRate(it / 5.0) }
        } else {
            val slope = numberValue(extras.get(XDRIP_SLOPE))
            val ratePerMinute = slope?.times(60_000.0)
            delta = ratePerMinute?.let { (it * 5).roundToInt() }
            trend = ratePerMinute?.let(::trendFromRate)
                ?: trendFromName(extras.get(XDRIP_SLOPE_NAME))
        }

        Log.d("BG_RECEIVER", "BG=$bg | delta=$delta | trend=$trend | source=${intent.action}")

        val prefs = context.getSharedPreferences("data", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_bg", bg)
            .putString("last_delta", delta?.toString())
            .putString("last_trend", trend ?: "UNKNOWN")
            .putLong("last_time", System.currentTimeMillis())
            .apply()

        context.sendBroadcast(
            Intent("BG_UPDATED").setPackage(context.packageName)
        )

        val serviceIntent = Intent(context, BleService::class.java).apply {
            putExtra("bg", bg)
            putExtra("trend", trend ?: "UNKNOWN")
            delta?.let { putExtra("delta", it) }
        }

        ContextCompat.startForegroundService(context, serviceIntent)
    }

    private fun numberValue(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        else -> value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun trendFromRate(ratePerMinute: Double): String = when {
        ratePerMinute > 3 -> "UP2"
        ratePerMinute > 1 -> "UP"
        ratePerMinute < -3 -> "DOWN2"
        ratePerMinute < -1 -> "DOWN"
        else -> "FLAT"
    }

    private fun trendFromName(value: Any?): String? = when (value?.toString()) {
        "DoubleUp", "1" -> "UP2"
        "SingleUp", "FortyFiveUp", "2", "3" -> "UP"
        "Flat", "4" -> "FLAT"
        "FortyFiveDown", "SingleDown", "5", "6" -> "DOWN"
        "DoubleDown", "7" -> "DOWN2"
        else -> null
    }
}