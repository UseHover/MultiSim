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
        return imsi.length == 4 && imsi.substring(3).toInt() == mncInt ||
            imsi.length >= 5 && imsi.substring(3, 5).toInt() == mncInt ||
            imsi.length >= 6 && imsi.substring(3, 6).toInt() == mncInt
    }
}
