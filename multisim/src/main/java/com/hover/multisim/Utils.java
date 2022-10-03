package com.hover.multisim;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import io.sentry.Sentry;

public class Utils {
	private static final String TAG = "Utils";
	private static final String SHARED_PREFS = "_multisim";

	public static String getPackage(Context c) {
		try {
			return c.getApplicationContext().getPackageName();
		} catch (NullPointerException e) {
			Sentry.capture(e);
			return "fail";
		}
	}

	public static boolean hasPhonePerm(Context c) {
		return Build.VERSION.SDK_INT < 23 || (c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
			                                      c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED);
	}

	public static SharedPreferences getSharedPrefs(Context context) {
		return context.getSharedPreferences(getPackage(context) + SHARED_PREFS, Context.MODE_PRIVATE);
	}
}
