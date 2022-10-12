package com.hover.multisim.sim

import android.telephony.TelephonyManager
import android.util.Log
import io.sentry.Sentry

internal class SlotManager  //		Log.i(TAG, "Creating slotMgr. SlotIdx: " + slotIdx + " Mgr: " + teleMgr + " Class + " + teleClass + " IMEI: " + imei + " ICCID: " + iccId);
private constructor(
    val slotIndex: Int,
    val subscriptionId: Int,
    private val teleMgr: Any?,
    private val teleClass: Class<*>?,
    simState: Int,
    val imei: String,
    iccId: String
) {
    fun createSimInfo(): SimInfo {
        return SimInfo(this)
    }

    operator fun getValue(methodName: String, subscriptionId: Any): Any? {
        return getValue(methodName, subscriptionId as Int, teleMgr, teleClass)
    }

    fun findSimState(): Int {
        return getValue("getSimState", slotIndex) as Int
    }

    fun findIccId(): String? {
        return getValue("getSimSerialNumber", subscriptionId) as String?
    }

    fun findImsi(): String? {
        return getValue("getSubscriberId", subscriptionId) as String?
    }

    fun findOperator(): String? {
        return getValue("getSimOperator", subscriptionId) as String?
    }

    fun findOperatorName(): String? {
        return getValue("getSimOperatorName", subscriptionId) as String?
    }

    fun findCountryIso(): String? {
        return getValue("getSimCountryIso", subscriptionId) as String?
    }

    fun findNetworkOperator(): String? {
        return getValue("getNetworkOperator", subscriptionId) as String?
    }

    fun findNetworkOperatorName(): String? {
        return getValue("getNetworkOperatorName", subscriptionId) as String?
    }

    fun findNetworkCountryIso(): String? {
        return getValue("getNetworkCountryIso", subscriptionId) as String?
    }

    fun findNetworkType(): Int{
        return getValue("getNetworkType", subscriptionId) as Int
    }

    fun findNetworkRoaming(): Boolean {
        return getValue("isNetworkRoaming", subscriptionId) as Boolean
    }

    private fun isUnique(slotMgrList: List<SlotManager?>): Boolean {
        for (mgr in slotMgrList) {
            try {
                if (mgr != null && imei == mgr.imei && teleMgr === mgr.teleMgr && teleClass == mgr.teleClass) return false
            } catch (e: NullPointerException) {
                Log.w(TAG, "something was null that shouldn't be", e)
                Sentry.captureException(e)
            }
        }
        return true
    }

    companion object {
        const val TAG = "SlotManager"
        private val METHOD_SUFFIXES = arrayOf(
            "", "Gemini", "Ext", "Ds", "ForSubscription", "ForPhone"
        )

        fun addValidReadySlots(
            slotMgrList: MutableList<SlotManager?>,
            slotIdx: Any?,
            teleMgrInstance: Any?,
            validClassNames: ArrayList<String?>?
        ) {
            if (slotIdx == null) for (i in 0 until MultiSimWorker.SLOT_COUNT - 1) addValidReadySlots(
                slotMgrList, i, i, teleMgrInstance, validClassNames
            ) else addValidReadySlots(
                slotMgrList, slotIdx as Int, slotIdx, teleMgrInstance, validClassNames
            )
        }

        @JvmStatic
        fun addValidReadySlots(
            slotMgrList: MutableList<SlotManager?>,
            slotIdx: Int,
            subscriptionId: Int,
            teleMgrInstance: Any?,
            validClassNames: ArrayList<String?>?
        ) {
            if (validClassNames == null || validClassNames.size <= 0) {
                return
            }
            for (className in validClassNames) if (teleMgrInstance != null || className != null) {
                val sm = findValidReadySlot(slotIdx, subscriptionId, teleMgrInstance, className)
                if (sm != null && (slotMgrList.size == 0 || sm.isUnique(slotMgrList))) {
                    slotMgrList.add(sm)
                }
            }
        }

        private fun findValidReadySlot(
            slotIdx: Int, subscriptionId: Int, teleMgr: Any?, className: String?
        ): SlotManager? {
            val teleClass = getTeleClass(teleMgr, className)
            val simState = getSimState(slotIdx, teleMgr, teleClass)
            val imei = getDeviceId(slotIdx, teleMgr, teleClass)
            val iccId = getSimIccId(subscriptionId, teleMgr, teleClass)
            return if (simState != null && simState == TelephonyManager.SIM_STATE_READY && imei != null && iccId != null) SlotManager(
                slotIdx, subscriptionId, teleMgr, teleClass, simState, imei, iccId
            ) else null
        }

        private fun getSimState(slotIndex: Int, teleMgr: Any?, teleClass: Class<*>?): Int? {
            return try {
                getValue("getSimState", slotIndex, teleMgr, teleClass) as Int?
            } catch (e: Exception) {
                Log.d(TAG, "Couldn't get sim state")
                TelephonyManager.SIM_STATE_UNKNOWN
            }
        }

        private fun getDeviceId(slotIndex: Int, teleMgr: Any?, teleClass: Class<*>?): String? {
            var imei = getValue("getDeviceId", slotIndex, teleMgr, teleClass) as String?
            if (imei == null) imei = getValue("getImei", slotIndex, teleMgr, teleClass) as String?
            return imei
        }

        private fun getSimIccId(subscriptionId: Int, teleMgr: Any?, teleClass: Class<*>?): String? {
            return getValue("getSimSerialNumber", subscriptionId, teleMgr, teleClass) as String?
        }

        fun getSimImsi(subscriptionId: Int, teleMgr: Any?, teleClass: Class<*>?): String? {
            return getValue("getSubscriberId", subscriptionId, teleMgr, teleClass) as String?
        }

        private fun getTeleClass(teleMgr: Any?, className: String?): Class<*>? {
            try {
                if (className != null) return Class.forName(className)
            } catch (ignored: ClassNotFoundException) {
            }
            return teleMgr?.javaClass
        }

        private fun getValue(
            methodName: String, slotIndex: Int, teleMgr: Any?, teleClass: Class<*>?
        ): Any? {
            var result = spamTeleMgr(methodName, teleMgr, teleClass, slotIndex)
            if (result == null) result = spamTeleMgr(methodName, teleMgr, teleClass, null)
            return result
        }

        private fun spamTeleMgr(
            methodName: String?, teleMgr: Any?, teleClass: Class<*>?, subscriptionId: Any?
        ): Any? {
            if (methodName == null || methodName.isEmpty()) return null
            var result: Any?
            for (methodSuffix in METHOD_SUFFIXES) {
                result = MultiSimWorker.runMethodReflect(teleMgr,
                    teleClass,
                    methodName + methodSuffix,
                    subscriptionId?.let { arrayOf(it) })
                if (result != null) {
                    return result
                }
            }
            return null
        }
    }
}