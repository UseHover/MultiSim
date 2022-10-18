package com.hover.multisim.sim

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import io.sentry.Sentry

object Utils {

    private const val SHARED_PREFS = "_multisim"

    @JvmStatic
    fun getPackage(context: Context): String? {
        return try {
            context.applicationContext.packageName
        } catch (e: NullPointerException) {
            Sentry.captureException(e)
            null
        }
    }

    /* ktlint-disable max-line-length */
    @JvmStatic
    fun hasPhonePerm(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getSharedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(
            getPackage(context) + SHARED_PREFS, Context.MODE_PRIVATE
        )
    }
}
