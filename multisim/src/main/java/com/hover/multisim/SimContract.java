package com.hover.multisim;

final public class SimContract {
	public static final String TABLE_NAME = "hsdk_sims";

	public static final String COLUMN_ENTRY_ID = "_id";

	public static final String COLUMN_SLOT_IDX = "slot_idx";
	public static final String COLUMN_SUB_ID = "sub_id";
	public static final String COLUMN_IMEI = "imei";
	public static final String COLUMN_STATE = "state";

	public static final String COLUMN_IMSI = "imsi";
	public static final String COLUMN_MCC = "mcc";
	public static final String COLUMN_MNC = "mnc";
	public static final String COLUMN_ICCID = "iccid";
	public static final String COLUMN_OP = "operator";
	public static final String COLUMN_OP_NAME = "operator_name";
	public static final String COLUMN_COUNTRY_ISO = "country_iso";
	public static final String COLUMN_ROAMING = "is_roaming";

	public static final String COLUMN_NETWORK_CODE = "network_code";
	public static final String COLUMN_NETWORK_NAME = "network_name";
	public static final String COLUMN_NETWORK_COUNTRY = "network_country";
	public static final String COLUMN_NETWORK_TYPE = "network_type";

	public static final String[] allColumns = {
			COLUMN_ENTRY_ID,
			COLUMN_SLOT_IDX,
			COLUMN_SUB_ID,
			COLUMN_IMEI,
			COLUMN_STATE,

			COLUMN_IMSI,
			COLUMN_MCC,
			COLUMN_MNC,
			COLUMN_ICCID,
			COLUMN_OP,
			COLUMN_OP_NAME,
			COLUMN_COUNTRY_ISO,
			COLUMN_ROAMING,

			COLUMN_NETWORK_CODE,
			COLUMN_NETWORK_NAME,
			COLUMN_NETWORK_COUNTRY,
			COLUMN_NETWORK_TYPE
	};
}
