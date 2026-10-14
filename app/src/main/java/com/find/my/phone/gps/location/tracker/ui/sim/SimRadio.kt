package com.find.my.phone.gps.location.tracker.ui.sim

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** Small read-only helpers over a SIM's radio (subscription id, network type) for the SIM Tools. */
object SimRadio {

    @SuppressLint("MissingPermission")
    fun subIdForSlot(context: Context, slot1Based: Int): Int? {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
        return runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty()
            .firstOrNull { it.simSlotIndex + 1 == slot1Based }?.subscriptionId
    }

    fun tmFor(context: Context, subId: Int): TelephonyManager =
        (context.getSystemService(TelephonyManager::class.java) as TelephonyManager).createForSubscriptionId(subId)

    fun defaultVoiceSubId(): Int = SubscriptionManager.getDefaultVoiceSubscriptionId()

    /** "5G" / "4G" / "3G" / "2G", or null when unknown. */
    @SuppressLint("MissingPermission")
    fun networkLabel(context: Context, subId: Int): String? {
        val tm = tmFor(context, subId)
        var type = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        if (type == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            type = runCatching { tm.voiceNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        }
        return label(type)
    }

    private fun label(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
        else -> null
    }
}
