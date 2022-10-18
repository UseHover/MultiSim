package com.hover.multisim.sim

data class SimInfo(
    val slotIdx: Int,
    val subscriptionId: Int,
    val imei: String?,
    val simState: Int,
    val imsi: String,
    val mcc: String,
    val mnc: String?,
    val iccId: String,
    val hni: String?,
    val operatorName: String?,
    val countryIso: String?,
    val networkRoaming: Boolean,
    val networkOperator: String?,
    val networkOperatorName: String?,
    val networkCountryIso: String?,
    val networkType: Int?
) {

    fun isMncMatch(mncInt: Int): Boolean {
        return imsi.length == 4 && imsi.substring(3)
            .toInt() == mncInt || imsi.length >= 5 && imsi.substring(3, 5)
            .toInt() == mncInt || imsi.length >= 6 && imsi.substring(3, 6).toInt() == mncInt
    }

    fun isNotContainedInOrHasMoved(simInfos: List<SimInfo>?): Boolean {
        if (simInfos == null) return true
        for (simInfo in simInfos) if (simInfo.let { this.isSameSimInSameSlot(it) }) return false
        return true
    }

    private fun isSameSimInSameSlot(simInfo: SimInfo): Boolean =
        isSameSim(simInfo) && simInfo.slotIdx == slotIdx

    // FIXME: change so that if the SimInfo represents the same sim, the one with more/better info (and more accurate slotIdx?) is returned. It currently relies on order to do this, which is fragile
    fun isSameSim(simInfo: SimInfo?): Boolean = simInfo?.iccId != null && iccId == simInfo.iccId

    fun isNotContainedIn(simInfos: List<SimInfo?>?): Boolean {
        if (simInfos == null) return true
        for (simInfo in simInfos) if (isSameSim(simInfo)) return false
        return true
    }
}
