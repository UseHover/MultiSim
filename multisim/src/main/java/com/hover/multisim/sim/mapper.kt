package com.hover.multisim.sim

import com.hover.multisim.db.model.HSDKSims

fun HSDKSims.toSimInfo() = SimInfo(
    slotIdx = this.slot_idx,
    subscriptionId = this.sub_id,
    imei = this.imei,
    simState = this.state,
    imsi = this.imsi,
    mcc = this.mcc,
    mnc = this.mnc,
    iccId = this.iccid,
    hni = this.operator,
    operatorName = this.operator_name,
    countryIso = this.country_iso,
    networkRoaming = this.is_roaming,
    networkOperator = this.network_code,
    networkOperatorName = this.network_name,
    networkCountryIso = this.network_country,
    networkType = this.network_type
)

fun SimInfo.toSimInfo() = HSDKSims(
    id = 0,
    slot_idx = this.slotIdx,
    sub_id = this.subscriptionId,
    imei = this.imei,
    state = this.simState,
    imsi = this.imsi,
    mcc = this.mcc,
    mnc = this.mnc,
    iccid = this.iccId,
    operator = this.hni,
    operator_name = this.operatorName,
    country_iso = this.countryIso,
    is_roaming = this.networkRoaming,
    network_code = this.networkOperator,
    network_name = this.networkOperatorName,
    network_country = this.networkCountryIso,
    network_type = this.networkType
)
