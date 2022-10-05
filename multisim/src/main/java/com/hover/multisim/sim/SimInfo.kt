package com.hover.multisim.sim;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

import io.sentry.Sentry;

public class SimInfo {
    private final static String TAG = "SimInfo";
    private final static String KEY = "sim_info_";

    /**
     * The slot that the SIM is in, starting from 0. Can be -1 if the SIM has been removed
     */
    public int slotIdx = -1;
    /**
     * The Subscription ID assigned by Android. The same SIM can be assigned a new ID if it is removed and re-inserted. Hover will forget the old ID and update a SIM to the newest
     */
    public int subscriptionId = -1;

    String imei;

    protected String iccId;

    /**
     * The Hardware identifier for the SIM. Hover uses this to track a SIM regardless of whether it is removed or its slot changed
     */
    public String getIccId() {
        return iccId;
    }

    protected String imsi;

    /**
     * The The International Mobile Subscriber Identity used by the network to identify the SIM. The value reported here may be only the begining 5-6 digits or the whole thing.
     * If you are trying to determine which network a SIM is for use this. The first 3 digits will always be the MCC and the following 2 or 3 will be the MNC which you can use to definitively identify which network this SIM is for
     * See https://en.wikipedia.org/wiki/Mobile_country_code
     */
    public String getImsi() {
        return imsi;
    }

    String mcc;
    String mnc;

    int simState = -1;

    protected String hni;

    /**
     * The Home Network Identifier. This is the first 5-6 digits of the IMSI, however, we recomend against using this since some devices may not report it correctly. Use the first 5-6 digits of the imsi using getImsi()
     *
     * @see SimInfo#getImsi()
     */
    public String getOSReportedHni() {
        return hni;
    }

    protected String operatorName;

    /**
     * The name of the operator which provisioned the SIM. May differ from SIM to SIM distributed by the same network provisioner
     */
    public String getOperatorName() {
        return operatorName;
    }

    protected String countryIso;

    /**
     * The country ISO of the operator which provisioned the SIM
     */
    public String getCountryIso() {
        return countryIso;
    }

    String networkOperator;

    /**
     * The network of the operator which the SIM is connected to
     */
    public String getNetworkOperator() {
        return networkOperator;
    }

    String networkOperatorName;

    /**
     * The network name of the operator which the SIM is connected to
     */
    public String getNetworkOperatorName() {
        return networkOperatorName;
    }

    String networkCountryIso;

    /**
     * The country ISO of the operator which the SIM is connected to
     */
    public String getNetworkCountryIso() {
        return networkCountryIso;
    }

    int networkType;
    // careful find networkTypeName because it will be different with networkType on same devices

    protected boolean networkRoaming = false;

    /**
     * Whether the SIM is currently roaming. Not guaranteed to be accurate.
     */
    public boolean isRoaming() {
        return networkRoaming;
    }

    public SimInfo() {
    }

    public SimInfo(SlotManager slotMgr) {
        if (slotMgr.slotIndex != null) slotIdx = slotMgr.slotIndex;
        subscriptionId = slotMgr.subscriptionId;

        imei = slotMgr.imei;
        simState = setSimState(slotMgr.findSimState());
        iccId = setStandardIccId(slotMgr.findIccId());
        imsi = slotMgr.findImsi();
        mcc = setMcc(imsi);

        hni = slotMgr.findOperator();
        operatorName = slotMgr.findOperatorName();
        countryIso = slotMgr.findCountryIso();
        networkOperator = slotMgr.findNetworkOperator();
        networkOperatorName = slotMgr.findNetworkOperatorName();
        networkCountryIso = slotMgr.findNetworkCountryIso();
        networkType = setNetworkType(slotMgr.findNetworkType());
        networkRoaming = setNetworkRoaming(slotMgr.findNetworkRoaming());

//		Log.i(TAG, "Created SIM representation using reflection: " + this.log());
    }

    @TargetApi(22)
    public SimInfo(SubscriptionInfo subInfo, Context c) {
        subscriptionId = subInfo.getSubscriptionId();
        slotIdx = subInfo.getSimSlotIndex();

        imsi = "" + subInfo.getMcc() + subInfo.getMnc();
        hni = "" + subInfo.getMcc() + subInfo.getMnc();
        mcc = "" + subInfo.getMcc();
        mnc = "" + subInfo.getMnc();
        iccId = setStandardIccId(subInfo.getIccId());

        operatorName = (String) subInfo.getCarrierName(); // Is this Network Operator or Sim Operator?
        countryIso = subInfo.getCountryIso();
        networkRoaming = SubscriptionManager.from(c).isNetworkRoaming(subscriptionId);

//		Can't load these until API 24 (Using TelephonyManager), but doesn't really matter:
//		hnis, networkOperator, networkOperatorName, networkCountryIso, networkType
//		These are not useful/inconsistent:
//		Log.i(TAG, "SubInfo Display Name: " + si.getDisplayName());
//		Log.i(TAG, "SubInfo Number: " + si.getNumber());

//		Log.i(TAG, "Created SIM representation using Subscription info: " + this.log());
    }

    public boolean isSameSim(SimInfo simInfo) { // FIXME: change so that if the SimInfo represents the same sim, the one with more/better info (and more accurate slotIdx?) is returned. It currently relies on order to do this, which is fragile
        return simInfo != null && simInfo.iccId != null && iccId != null && iccId.equals(simInfo.iccId);
    }

    private boolean isSameSimInSameSlot(SimInfo simInfo) {
        return isSameSim(simInfo) && simInfo.slotIdx == slotIdx;
    }

    public boolean isNotContainedIn(List<SimInfo> simInfos) {
        if (simInfos == null) return true;
        for (SimInfo simInfo : simInfos)
            if (this.isSameSim(simInfo))
                return false;
        return true;
    }

    public boolean isNotContainedInOrHasMoved(List<SimInfo> simInfos) {
        if (simInfos == null) return true;
        for (SimInfo simInfo : simInfos)
            if (this.isSameSimInSameSlot(simInfo))
                return false;
        return true;
    }

    public void save(Context c) {
        new SimDataSource(c).saveToDb(this);
        updateSubId(subscriptionId, c);
    }

    //	private void updateSlot(Context c) {
//		Log.i(TAG, "Updating sim slot to: " + slotIdx);
//		new SimDataSource(c).updateSlot(this, updatedInfo);
//		updateSubId(updatedInfo.subscriptionId, c);
//	}
    public void setSimRemoved(Context c) {
        Log.i(TAG, "Updating sim slot to: -1");
        new SimDataSource(c).remove(this);
    }

    @SuppressLint("ApplySharedPref")
    private void updateSubId(int subId, Context c) {
        SharedPreferences.Editor editor = Utils.getSharedPrefs(c).edit();
        editor.putInt(KEY + SimContract.COLUMN_SUB_ID + iccId, subId);
        editor.commit();
    }

    public static int getSubId(String iccId, Context c) {
        return Utils.getSharedPrefs(c).getInt(KEY + SimContract.COLUMN_SUB_ID + iccId, -1);
    }

    public static List<SimInfo> loadPresentByHni(JSONArray hniList, Context c) {
        List<SimInfo> simInfos = new ArrayList<>();
        for (int h = 0; h < hniList.length(); h++) {
            try {
                simInfos.addAll(new SimDataSource(c).getPresent(hniList.getString(h).substring(0, 3), hniList.getString(h).substring(3)));
            } catch (JSONException | NullPointerException e) {
                Sentry.captureException(e);
            }
        }
        return simInfos;
    }

    public static SimInfo loadBySlot(int slotIdx, Context c) {
        return new SimDataSource(c).get(slotIdx);
    }

    public static List<SimInfo> loadAll(Context c) {
        return new SimDataSource(c).getAll();
    }

    String setStandardIccId(String iccId) {
        if (iccId != null)
            iccId = iccId.replaceAll("[a-zA-Z]", "");
        return iccId;
    }

    private String setMcc(String imsi) {
        return imsi != null ? imsi.substring(0, 3) : null;
    }

    boolean isMncMatch(int mncInt) {
        return (imsi.length() == 4 && Integer.valueOf(imsi.substring(3)) == mncInt) ||
                (imsi.length() >= 5 && Integer.valueOf(imsi.substring(3, 5)) == mncInt) ||
                (imsi.length() >= 6 && Integer.valueOf(imsi.substring(3, 6)) == mncInt);
    }

    public String getInterpretedHni(JSONArray actionHniList) {
        for (int h = 0; h < actionHniList.length(); h++) {
            int mncInt = Integer.valueOf(actionHniList.optString(h).substring(3));
            if (isMncMatch(mncInt)) {
                return imsi.substring(0, 3) + mncInt;
            }
        }
        return null;
    }

    private int setSimState(Integer simState) {
        if (simState == null) return -1;
        return simState;
    }

    private int setNetworkType(Integer networkType) {
        if (networkType == null) return 0;
        return networkType;
    }

    private boolean setNetworkRoaming(Boolean networkRoaming) {
        return networkRoaming != null && networkRoaming;
    }


    String log() {
        return "slotIdx=[" + slotIdx +
                "] subscriptionId=[" + subscriptionId +
                "] imei=[" + imei +
                "] imsi=[" + imsi +
                "] simState=[" + simState +
                "] simIccId=[" + iccId +
                "] simHni=[" + hni +
                "] simOperatorName=[" + operatorName +
                "] simCountryIso=[" + countryIso +
                "] networkOperator=[" + networkOperator +
                "] networkOperatorName=[" + networkOperatorName +
                "] networkCountryIso=[" + networkCountryIso +
                "] networkType=[" + networkType +
                "] networkRoaming=[" + networkRoaming + "]";
    }

    public String toString() {
        return operatorName + " " + (countryIso != null ? countryIso.toUpperCase() : "") + " (SIM " + (slotIdx + 1) + ")";
    }
}
