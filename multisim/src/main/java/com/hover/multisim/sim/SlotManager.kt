package com.hover.multisim.sim;

import static android.telephony.TelephonyManager.SIM_STATE_READY;
import static android.telephony.TelephonyManager.SIM_STATE_UNKNOWN;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.sentry.Sentry;

final class SlotManager {
	public final static String TAG = "SlotManager";
	final Integer slotIndex;
	final Integer subscriptionId;
	final String imei;
	private final Object teleMgr;
	private final Class<?> teleClass;

	private final static String[] METHOD_SUFFIXES = new String[] {
			"",
			"Gemini",
			"Ext",
			"Ds",
			"ForSubscription",
			"ForPhone"
	};

	@SuppressWarnings("unused")
	private SlotManager(int slotIdx, int subscriptionId, Object teleMgr, Class<?> teleClass, Integer simState, String imei, String iccId) {
//		Log.i(TAG, "Creating slotMgr. SlotIdx: " + slotIdx + " Mgr: " + teleMgr + " Class + " + teleClass + " IMEI: " + imei + " ICCID: " + iccId);
		slotIndex = slotIdx;
		this.subscriptionId = subscriptionId;
		this.imei = imei;
		this.teleMgr = teleMgr;
		this.teleClass = teleClass;
	}

	SimInfo createSimInfo() {
		return new SimInfo(this);
	}

	Object getValue(String methodName, Object subscriptionId) {
		return getValue(methodName, (int) subscriptionId, teleMgr, teleClass);
	}

	Integer findSimState() {	return (Integer) getValue("getSimState", slotIndex); }
	String findIccId() {	return (String) getValue("getSimSerialNumber", subscriptionId); }
	String findImsi() {	return (String) getValue("getSubscriberId", subscriptionId); }
	String findOperator() {	return (String) getValue("getSimOperator", subscriptionId); }
	String findOperatorName() {	return (String) getValue("getSimOperatorName", subscriptionId); }
	String findCountryIso() {	return (String) getValue("getSimCountryIso", subscriptionId); }
	String findNetworkOperator() {	return (String) getValue("getNetworkOperator", subscriptionId); }
	String findNetworkOperatorName() {	return (String) getValue("getNetworkOperatorName", subscriptionId); }
	String findNetworkCountryIso() {	return (String) getValue("getNetworkCountryIso", subscriptionId); }
	Integer findNetworkType() {	return (Integer) getValue("getNetworkType", subscriptionId); }
	boolean findNetworkRoaming() {	return (boolean) getValue("isNetworkRoaming", subscriptionId); }

	private boolean isUnique(List<SlotManager> slotMgrList) {
		for (SlotManager mgr: slotMgrList) {
			try {
				if (mgr != null && imei.equals(mgr.imei) && teleMgr == mgr.teleMgr && teleClass == mgr.teleClass)
					return false;
			} catch (NullPointerException e) { Log.w(TAG, "something was null that shouldn't be", e); Sentry.captureException(e); }
		}
//		Log.i(TAG, "Slot Manager was unique with Imei: " + imei + ", teleMgr: " + teleMgr + ", and teleClass: " + teleClass);
		return true;
	}

	public static void addValidReadySlots(List<SlotManager> slotMgrList, Object slotIdx, Object teleMgrInstance, ArrayList<String> validClassNames) {
		if (slotIdx == null)
			for (int i = 0; i < MultiSimWorker.SLOT_COUNT - 1; i++)
				addValidReadySlots(slotMgrList, i, i, teleMgrInstance, validClassNames);
		else
			addValidReadySlots(slotMgrList, (int) slotIdx, (int) slotIdx, teleMgrInstance, validClassNames);
	}
	public static void addValidReadySlots(List<SlotManager> slotMgrList, int slotIdx, int subscriptionId, Object teleMgrInstance, ArrayList<String> validClassNames) {
		if (validClassNames == null || validClassNames.size() <= 0) { return; }
		for (String className : validClassNames)
			if (teleMgrInstance != null || className != null) {
				SlotManager sm = findValidReadySlot(slotIdx, subscriptionId, teleMgrInstance, className);
				if (sm != null && (slotMgrList.size() == 0 || sm.isUnique(slotMgrList))) {
					slotMgrList.add(sm);
//					Log.e(TAG, "Added slotMgr. SlotIdx: " + slotIdx + " subId: " + subscriptionId + " Mgr: " + teleMgrInstance + " Class + " + sm.teleClass + " IMEI: " + sm.imei); // + " ICCID: " + getSimIccId(subscriptionId, teleMgr, sm.teleClass));
				}
			}
	}
	private static SlotManager findValidReadySlot(int slotIdx, int subscriptionId, Object teleMgr, String className) {
		Class<?> teleClass = getTeleClass(teleMgr, className);
		Integer simState = getSimState(slotIdx, teleMgr, teleClass);
		String imei = getDeviceId(slotIdx, teleMgr, teleClass);
		String iccId = getSimIccId(subscriptionId, teleMgr, teleClass);
//		Log.i(TAG, "Got slot mgr with slotIdx: " + slotIdx + " subId: " + subscriptionId + " teleClass: " + teleClass + " simState: " + simState + " IMEI: " + imei + " ICCID: " + iccId + " IMSI: " + getSimImsi(subscriptionId, teleMgr, teleClass));
		if (simState != null && simState == SIM_STATE_READY && imei != null && iccId != null)
			return new SlotManager(slotIdx, subscriptionId, teleMgr, teleClass, simState, imei, iccId);
		return null;
	}

	private static Integer getSimState(int slotIndex, Object teleMgr, Class<?> teleClass) {
		try {
			return (Integer) getValue("getSimState", slotIndex, teleMgr, teleClass);
		} catch (Exception e) { Log.d(TAG, "Couldn't get sim state"); return SIM_STATE_UNKNOWN; }
	}
	private static String getDeviceId(int slotIndex, Object teleMgr, Class<?> teleClass) {
		String imei = (String) getValue("getDeviceId", slotIndex, teleMgr, teleClass);
		if (imei == null) imei = (String) getValue("getImei", slotIndex, teleMgr, teleClass);
		return imei;
	}
	private static String getSimIccId(int subscriptionId, Object teleMgr, Class<?> teleClass) {
		return (String) getValue("getSimSerialNumber", subscriptionId, teleMgr, teleClass);
	}
	static String getSimImsi(int subscriptionId, Object teleMgr, Class<?> teleClass) {
		return (String) getValue("getSubscriberId", subscriptionId, teleMgr, teleClass);
	}

	private static Class<?> getTeleClass(Object teleMgr, String className) {
		try {
			if (className != null) return Class.forName(className);
		} catch (ClassNotFoundException ignored) { }
		if (teleMgr != null) return teleMgr.getClass();
		return null;
	}

	private static Object getValue(String methodName, int slotIndex, Object teleMgr, Class<?> teleClass) {
		Object result = spamTeleMgr(methodName, teleMgr, teleClass, slotIndex);
		if (result == null) result = spamTeleMgr(methodName, teleMgr, teleClass, null);
		if (result == null) return null;
		return result;
	}
	private static Object spamTeleMgr(String methodName, Object teleMgr, Class<?> teleClass, Object subscriptionId) {
		if (methodName == null || methodName.length() <= 0)  return null;
		Object result;
		for (String methodSuffix : METHOD_SUFFIXES) {
			result = MultiSimWorker.runMethodReflect(teleMgr, teleClass, methodName + methodSuffix, subscriptionId == null ? null : new Object[]{ subscriptionId });
			if (result != null) {
//				Log.i(TAG, "Got result for method: " + methodName + methodSuffix + ". Result: " + result.toString());
				return result;
			}
		}
		return null;
	}
}