package com.hover.multisim;

import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

public class DataSource {
	public final static String TAG = "DataSource";

	private Context context;
	protected SQLiteDatabase database;

	protected DataSource(Context context) { context = context.getApplicationContext(); }

	protected void open() throws SQLException {
		database = SimDatabase.getInstance(context).getWritableDatabase();
	}
	@SuppressWarnings("EmptyMethod")
	protected void close() throws SQLException {
		// Apparently don't need to do this? Seems strange but: https://stackoverflow.com/questions/6608498/best-place-to-close-database-connection
	}
}
