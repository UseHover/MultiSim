package com.hover.multisim;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class SimDatabase extends SQLiteOpenHelper {
	private static final int DATABASE_VERSION = 1;
	private static final String DATABASE_NAME = "multisim.db";

	private static SimDatabase instance = null;

	public static synchronized SimDatabase getInstance(Context ctx) {
		if (instance == null)
			instance = new SimDatabase(ctx.getApplicationContext());
		return instance;
	}

	private SimDatabase(Context ctx) {
		super(ctx, DATABASE_NAME, null, DATABASE_VERSION);
	}

	private static final String SIM_TABLE_CREATE = "create table "
		+ SimContract.TABLE_NAME + "("
		+ SimContract.COLUMN_ENTRY_ID + " integer primary key autoincrement, "
		+ SimContract.COLUMN_SLOT_IDX + " integer not null, "
		+ SimContract.COLUMN_SUB_ID + " integer not null, "
		+ SimContract.COLUMN_IMEI + " text, "
		+ SimContract.COLUMN_STATE + " integer default -1, "
		+ SimContract.COLUMN_IMSI + " text not null, "
		+ SimContract.COLUMN_MCC + " text not null, "
		+ SimContract.COLUMN_MNC + " text, "
		+ SimContract.COLUMN_ICCID + " text not null, "
		+ SimContract.COLUMN_OP + " text, "
		+ SimContract.COLUMN_OP_NAME + " text, "
		+ SimContract.COLUMN_COUNTRY_ISO + " text, "
		+ SimContract.COLUMN_ROAMING + " integer default 0 not null, "
		+ SimContract.COLUMN_NETWORK_CODE + " text, "
		+ SimContract.COLUMN_NETWORK_NAME + " text, "
		+ SimContract.COLUMN_NETWORK_COUNTRY + " text, "
		+ SimContract.COLUMN_NETWORK_TYPE + " integer, "
		+ "UNIQUE (" + SimContract.COLUMN_ICCID + ") ON CONFLICT REPLACE"
		+ ")";

	public void onCreate(SQLiteDatabase db) {
		db.execSQL(SIM_TABLE_CREATE);
	}

	private static final String SQL_DELETE_SIMS = "DROP TABLE IF EXISTS " + SimContract.TABLE_NAME;

	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		db.execSQL(SQL_DELETE_SIMS);
		onCreate(db);
	}
	public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		onUpgrade(db, oldVersion, newVersion);
	}
}
