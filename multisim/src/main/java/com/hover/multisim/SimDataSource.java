package com.hover.multisim;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.sentry.Sentry;

final public class SimDataSource extends DataSource {
	private final static String TAG = "SimDataSource";
	private final String TABLE = SimContract.TABLE_NAME;
	private final String[] COLUMNS = SimContract.allColumns;

	public SimDataSource(Context context) { super(context); }

	@SuppressWarnings("UnusedReturnValue")
	long saveToDb(SimInfo simInfo) {
		ContentValues cv = getContentValues(simInfo);
		open();
		long insertId = database.insert(TABLE, null, cv);
		close();
		Log.d(TAG, "Saved Sim with imsi: " + simInfo.imsi + ", iccid: " + simInfo.iccId + ". Id: " + insertId);
		return insertId;
	}

	public List<SimInfo> getAll() {
		List<SimInfo> infos = new ArrayList<>();
		try {
			open();
			Cursor cursor = database.query(TABLE, COLUMNS, null, null, null, null, null);
			cursor.moveToFirst();
			while (!cursor.isAfterLast()) {
				SimInfo si = cursorToSimInfo(cursor);
				infos.add(si);
				cursor.moveToNext();
			}
			cursor.close();
			close();
		} catch (Exception e) { Sentry.captureException(e); }
		return infos;
	}

	public SimInfo get(int slotIdx) {
		return load(SimContract.COLUMN_SLOT_IDX + " = " + slotIdx);
	}
	@SuppressWarnings("unused")
	public SimInfo loadBy(String iccId) {
		return load(SimContract.COLUMN_ICCID + " = '" + iccId + "'");
	}
	private SimInfo load(String selection) {
		open();
		SimInfo si = null;
		Cursor cursor = database.query(TABLE, COLUMNS, selection, null, null, null, null);
		cursor.moveToFirst();
		if (!cursor.isAfterLast())
			si = cursorToSimInfo(cursor);
		else
			Log.d(TAG, "didn't load cursor...");

		cursor.close();
		close();
		return si;
	}

	@SuppressWarnings("UnnecessaryUnboxing")
	public List<SimInfo> getPresent(String mcc, String mnc) {
		List<SimInfo> matchingSims = new ArrayList<>();
		open();
		Cursor cursor = database.query(TABLE, COLUMNS, SimContract.COLUMN_MCC + " = '" + mcc + "' AND " + SimContract.COLUMN_SLOT_IDX + " != -1", null, null, null, null);
		cursor.moveToFirst();
		int mncInt = Integer.valueOf(mnc).intValue();
		while (!cursor.isAfterLast()) {
			SimInfo si = cursorToSimInfo(cursor);
			if (si.isMncMatch(mncInt))
				matchingSims.add(si);
			cursor.moveToNext();
		}
		cursor.close();
		close();
		return matchingSims;
	}

	void remove(SimInfo si) {
		ContentValues cv = new ContentValues();
		cv.put(SimContract.COLUMN_SLOT_IDX, -1);
		update(cv, si.iccId);
	}

//	void updateSlot(SimInfo si, SimInfo updatedInfo) {
//		ContentValues cv = new ContentValues();
//		if (updatedInfo != null) {
//			cv.put(SimContract.COLUMN_SLOT_IDX, updatedInfo.slotIdx);
//			cv.put(SimContract.COLUMN_SUB_ID, updatedInfo.subscriptionId);
//			cv.put(SimContract.COLUMN_IMEI, updatedInfo.imei);
//			cv.put(SimContract.COLUMN_STATE, updatedInfo.simState);
//		} else
//			cv.put(SimContract.COLUMN_SLOT_IDX, -1);
//
//		update(cv, si.iccId);
//	}

//	void updateNetwork(SimInfo si, String operator, String name, String countryIso, int type, int roaming) {
//		ContentValues cv = new ContentValues();
//		cv.put(SimContract.COLUMN_NETWORK_CODE, operator);
//		cv.put(SimContract.COLUMN_NETWORK_NAME, name);
//		cv.put(SimContract.COLUMN_NETWORK_COUNTRY, countryIso);
//		cv.put(SimContract.COLUMN_NETWORK_TYPE, type);
//		cv.put(SimContract.COLUMN_ROAMING, roaming);
//
//		update(cv, si.iccId);
//	}

	private void update(ContentValues cv, String iccId) {
		open();
		database.update(TABLE, cv, SimContract.COLUMN_ICCID + " = '" + iccId + "'", null);
		close();
	}

	private ContentValues getContentValues(SimInfo simInfo) {
		ContentValues cv = new ContentValues();
		cv.put(SimContract.COLUMN_SLOT_IDX, simInfo.slotIdx);
		cv.put(SimContract.COLUMN_SUB_ID, simInfo.subscriptionId);
		cv.put(SimContract.COLUMN_IMEI, simInfo.imei);
		cv.put(SimContract.COLUMN_STATE, simInfo.simState);

		cv.put(SimContract.COLUMN_IMSI, simInfo.imsi);
		cv.put(SimContract.COLUMN_MCC, simInfo.mcc);
		cv.put(SimContract.COLUMN_MNC, simInfo.mnc);
		cv.put(SimContract.COLUMN_ICCID, simInfo.setStandardIccId(simInfo.iccId));
		cv.put(SimContract.COLUMN_OP, simInfo.hni);
		cv.put(SimContract.COLUMN_OP_NAME, simInfo.operatorName);
		cv.put(SimContract.COLUMN_COUNTRY_ISO, simInfo.countryIso);
		cv.put(SimContract.COLUMN_ROAMING, simInfo.networkRoaming ? 1 : 0);

		cv.put(SimContract.COLUMN_NETWORK_CODE, simInfo.networkOperator);
		cv.put(SimContract.COLUMN_NETWORK_NAME, simInfo.networkOperatorName);
		cv.put(SimContract.COLUMN_NETWORK_COUNTRY, simInfo.networkCountryIso);
		cv.put(SimContract.COLUMN_NETWORK_TYPE, simInfo.networkType);
		return cv;
	}

	private SimInfo cursorToSimInfo(Cursor c) {
		SimInfo simInfo = new SimInfo();
		simInfo.slotIdx = c.getInt(c.getColumnIndex(SimContract.COLUMN_SLOT_IDX));
		simInfo.subscriptionId = c.getInt(c.getColumnIndex(SimContract.COLUMN_SUB_ID));
		simInfo.imei = c.getString(c.getColumnIndex(SimContract.COLUMN_IMEI));
		simInfo.simState = c.getInt(c.getColumnIndex(SimContract.COLUMN_STATE));

		simInfo.imsi = c.getString(c.getColumnIndex(SimContract.COLUMN_IMSI));
		simInfo.mcc = c.getString(c.getColumnIndex(SimContract.COLUMN_MCC));
		simInfo.mnc = c.getString(c.getColumnIndex(SimContract.COLUMN_MNC));
		simInfo.iccId = c.getString(c.getColumnIndex(SimContract.COLUMN_ICCID));
		simInfo.hni = c.getString(c.getColumnIndex(SimContract.COLUMN_OP));
		simInfo.operatorName = c.getString(c.getColumnIndex(SimContract.COLUMN_OP_NAME));
		simInfo.countryIso = c.getString(c.getColumnIndex(SimContract.COLUMN_COUNTRY_ISO));
		simInfo.networkRoaming = c.getInt(c.getColumnIndex(SimContract.COLUMN_ROAMING)) == 1;

		simInfo.networkOperator = c.getString(c.getColumnIndex(SimContract.COLUMN_NETWORK_CODE));
		simInfo.networkOperatorName = c.getString(c.getColumnIndex(SimContract.COLUMN_NETWORK_NAME));
		simInfo.networkCountryIso = c.getString(c.getColumnIndex(SimContract.COLUMN_NETWORK_COUNTRY));
		simInfo.networkType = c.getInt(c.getColumnIndex(SimContract.COLUMN_NETWORK_TYPE));
		return simInfo;
	}
}
