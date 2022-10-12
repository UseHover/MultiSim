package com.hover.multisim.sim

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import com.hover.multisim.db.dao.SimDao
import io.sentry.Sentry
import org.json.JSONArray
import org.json.JSONException
import java.util.*
import javax.inject.Inject

open class SimInfo @Inject constructor(
    val simDao: SimDao
) {
    /**
     * The slot that the SIM is in, starting from 0. Can be -1 if the SIM has been removed
     */
    @JvmField
    var slotIdx = -1

    /**
     * The Subscription ID assigned by Android. The same SIM can be assigned a new ID if it is removed and re-inserted. Hover will forget the old ID and update a SIM to the newest
     */
    var subscriptionId = -1
    var imei: String? = null

    /**
     * The Hardware identifier for the SIM. Hover uses this to track a SIM regardless of whether it is removed or its slot changed
     */
    var iccId: String? = null
        protected set

    /**
     * The The International Mobile Subscriber Identity used by the network to identify the SIM. The value reported here may be only the begining 5-6 digits or the whole thing.
     * If you are trying to determine which network a SIM is for use this. The first 3 digits will always be the MCC and the following 2 or 3 will be the MNC which you can use to definitively identify which network this SIM is for
     * See https://en.wikipedia.org/wiki/Mobile_country_code
     */
    var imsi: String? = null
        protected set
    var mcc: String? = null
    var mnc: String? = null
    var simState = -1

    /**
     * The Home Network Identifier. This is the first 5-6 digits of the IMSI, however, we recomend against using this since some devices may not report it correctly. Use the first 5-6 digits of the imsi using getImsi()
     *
     * @see SimInfo.getImsi
     */
    var oSReportedHni: String? = null
        protected set

    /**
     * The name of the operator which provisioned the SIM. May differ from SIM to SIM distributed by the same network provisioner
     */
    var operatorName: String? = null
        protected set

    /**
     * The country ISO of the operator which provisioned the SIM
     */
    var countryIso: String? = null
        protected set

    /**
     * The network of the operator which the SIM is connected to
     */
    var networkOperator: String? = null

    /**
     * The network name of the operator which the SIM is connected to
     */
    var networkOperatorName: String? = null

    /**
     * The country ISO of the operator which the SIM is connected to
     */
    var networkCountryIso: String? = null
    var networkType = 0

    /**
     * Whether the SIM is currently roaming. Not guaranteed to be accurate.
     */
    // careful find networkTypeName because it will be different with networkType on same devices
    var isRoaming = false
        protected set

    private constructor(slotMgr: SlotManager) {
        slotIdx = slotMgr.slotIndex
        subscriptionId = slotMgr.subscriptionId
        imei = slotMgr.imei
        simState = setSimState(slotMgr.findSimState())
        iccId = setStandardIccId(slotMgr.findIccId())
        imsi = slotMgr.findImsi()
        mcc = setMcc(imsi)
        oSReportedHni = slotMgr.findOperator()
        operatorName = slotMgr.findOperatorName()
        countryIso = slotMgr.findCountryIso()
        networkOperator = slotMgr.findNetworkOperator()
        networkOperatorName = slotMgr.findNetworkOperatorName()
        networkCountryIso = slotMgr.findNetworkCountryIso()
        networkType = setNetworkType(slotMgr.findNetworkType())
        isRoaming = setNetworkRoaming(slotMgr.findNetworkRoaming())
    }

    @TargetApi(22)
    constructor(subInfo: SubscriptionInfo, c: Context?) {
        subscriptionId = subInfo.subscriptionId
        slotIdx = subInfo.simSlotIndex
        imsi = "" + subInfo.mcc + subInfo.mnc
        oSReportedHni = "" + subInfo.mcc + subInfo.mnc
        mcc = "" + subInfo.mcc
        mnc = "" + subInfo.mnc
        iccId = setStandardIccId(subInfo.iccId)
        operatorName = subInfo.carrierName as String // Is this Network Operator or Sim Operator?
        countryIso = subInfo.countryIso
        isRoaming = SubscriptionManager.from(c).isNetworkRoaming(subscriptionId)

//		Can't load these until API 24 (Using TelephonyManager), but doesn't really matter:
//		hnis, networkOperator, networkOperatorName, networkCountryIso, networkType
//		These are not useful/inconsistent:
//		Log.i(TAG, "SubInfo Display Name: " + si.getDisplayName());
//		Log.i(TAG, "SubInfo Number: " + si.getNumber());

//		Log.i(TAG, "Created SIM representation using Subscription info: " + this.log());
    }

    fun isSameSim(simInfo: SimInfo?): Boolean { // FIXME: change so that if the SimInfo represents the same sim, the one with more/better info (and more accurate slotIdx?) is returned. It currently relies on order to do this, which is fragile
        return simInfo?.iccId != null && iccId != null && iccId == simInfo.iccId
    }

    private fun isSameSimInSameSlot(simInfo: SimInfo): Boolean {
        return isSameSim(simInfo) && simInfo.slotIdx == slotIdx
    }

    fun isNotContainedIn(simInfos: List<SimInfo?>?): Boolean {
        if (simInfos == null) return true
        for (simInfo in simInfos) if (isSameSim(simInfo)) return false
        return true
    }

    fun isNotContainedInOrHasMoved(simInfos: List<SimInfo>?): Boolean {
        if (simInfos == null) return true
        for (simInfo in simInfos) if (isSameSimInSameSlot(simInfo)) return false
        return true
    }

    fun save(c: Context) {
        simDao.insert(this)
        updateSubId(subscriptionId, c)
    }

    fun setSimRemoved(c: Context?) {
        Log.i(TAG, "Updating sim slot to: -1")
        simDao.delete(this)
    }

    @SuppressLint("ApplySharedPref")
    private fun updateSubId(subId: Int, c: Context) {
        val editor = Utils.getSharedPrefs(c).edit()
        editor.putInt(KEY + SimContract.COLUMN_SUB_ID.toString() + iccId, subId)
        editor.commit()
    }

    fun setStandardIccId(iccId: String?): String? {
        var iccId = iccId
        if (iccId != null) iccId = iccId.replace("[a-zA-Z]".toRegex(), "")
        return iccId
    }

    private fun setMcc(imsi: String?): String? {
        return imsi?.substring(0, 3)
    }

    fun isMncMatch(mncInt: Int): Boolean {
        return imsi!!.length == 4 && Integer.valueOf(imsi!!.substring(3)) == mncInt || imsi!!.length >= 5 && Integer.valueOf(
            imsi!!.substring(3, 5)
        ) == mncInt || imsi!!.length >= 6 && Integer.valueOf(imsi!!.substring(3, 6)) == mncInt
    }

    fun getInterpretedHni(actionHniList: JSONArray): String? {
        for (h in 0 until actionHniList.length()) {
            val mncInt = Integer.valueOf(actionHniList.optString(h).substring(3))
            if (isMncMatch(mncInt)) {
                return imsi!!.substring(0, 3) + mncInt
            }
        }
        return null
    }

    private fun setSimState(simState: Int): Int {
        return simState
    }

    private fun setNetworkType(networkType: Int): Int {
        return networkType
    }

    private fun setNetworkRoaming(networkRoaming: Boolean?): Boolean {
        return networkRoaming != null && networkRoaming
    }

    fun log(): String {
        return "slotIdx=[$slotIdx] subscriptionId=[$subscriptionId] imei=[$imei] imsi=[$imsi] simState=[$simState] simIccId=[$iccId] simHni=[$oSReportedHni] simOperatorName=[$operatorName] simCountryIso=[$countryIso] networkOperator=[$networkOperator] networkOperatorName=[$networkOperatorName] networkCountryIso=[$networkCountryIso] networkType=[$networkType] networkRoaming=[$isRoaming]"
    }

    override fun toString(): String {
        return operatorName + " " + (if (countryIso != null) countryIso!!.uppercase(Locale.getDefault()) else "") + " (SIM " + (slotIdx + 1) + ")"
    }

    companion object {
        private const val TAG = "SimInfo"
        private const val KEY = "sim_info_"
        fun getSubId(iccId: String, c: Context?): Int {
            return Utils.getSharedPrefs(c)
                .getInt(KEY + SimContract.COLUMN_SUB_ID.toString() + iccId, -1)
        }

        fun loadPresentByHni(hniList: JSONArray, c: Context?): List<SimInfo> {
            val simInfos: MutableList<SimInfo> = ArrayList()
            for (h in 0 until hniList.length()) {
                try {
                    simInfos.addAll(
                        SimDataSource(c).getPresent(
                            hniList.getString(h).substring(0, 3), hniList.getString(h).substring(3)
                        )
                    )
                } catch (e: JSONException) {
                    Sentry.captureException(e)
                } catch (e: NullPointerException) {
                    Sentry.captureException(e)
                }
            }
            return simInfos
        }

        fun loadBySlot(slotIdx: Int, c: Context?): SimInfo {
            return SimDataSource(c).get(slotIdx)
        }

        fun loadAll(c: Context?): List<SimInfo> {
            return SimDataSource(c).getAll()
        }
    }
}