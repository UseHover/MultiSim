package com.hover.multisim.sim

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import io.sentry.Sentry
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MultiSimWorker(
    context: Context,
    params: WorkerParameters,
) : ListenableWorker(context, params) {

    private val NEW_SIM_INFO = "NEW_SIM_INFO_ACTION"
    val SLOT_COUNT = 3 // Need to check 0, 1, and 2. Some phones index from 1.

    private lateinit var workerFuture: SettableFuture<Result>
    private var result: Result? = null

    private val slotSemaphore = Semaphore(1, true)
    private val simSemaphore = Semaphore(1, true)

    private var simStateReceiver: SimStateReceiver? = null
    private var simStateListener: SimStateListener? = null
    private var validClassNames: ArrayList<String>? = null

    private val POSS_CLASS_NAMES = arrayOf(
        null,
        "android.telephony.TelephonyManager",
        "android.telephony.MSimTelephonyManager",
        "android.telephony.MultiSimTelephonyService",
        "com.mediatek.telephony.TelephonyManagerEx",
        "com.android.internal.telephony.Phone",
        "com.android.internal.telephony.PhoneFactory"
    )

    fun makeToil(): PeriodicWorkRequest {
        return PeriodicWorkRequest.Builder(MultiSimWorker::class.java, 15, TimeUnit.MINUTES).build()
    }

    fun makeWork(): OneTimeWorkRequest {
        return OneTimeWorkRequest.Builder(MultiSimWorker::class.java).build()
    }

    @SuppressLint("RestrictedApi")
    override fun startWork(): ListenableFuture<Result> {
        workerFuture = SettableFuture.create()
        if (Utils.hasPhonePerm(applicationContext)) {
            startListeners()
        } else {
            workerFuture.set(Result.failure())
        }
        return workerFuture
    }

    @SuppressLint("RestrictedApi")
    private fun startListeners() {
        try {
            registerSimStateReceiver()
            if (simStateListener == null) simStateListener = SimStateListener()
            // TelephonyManager.listen() must take place on the main thread
            (applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .listen(
                    simStateListener,
                    PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
        } catch (e: java.lang.Exception) {
            workerFuture.set(Result.retry())
        }
    }

    @SuppressLint("RestrictedApi")
    @Synchronized
    private fun updateSimInfo() {
        backgroundExecutor.execute {
            result = try {
                simSemaphore.acquire()
                if (Utils.hasPhonePerm(applicationContext)) {
                    val oldList: List<SimInfo?>? = getSaved()
                    val newList = findUniqueSimInfo()
                    if (newList != null) {
                        compareNewAndOld(newList, oldList)
                        Result.success()
                    } else Result.failure()
                } else Result.failure()
            } catch (e: java.lang.Exception) {
                Sentry.captureException(e)
                Result.failure()
            } finally {
                simSemaphore.release()
                // Give the listeners a chance to receive a few events - sometimes the first trigger isn't the needed info. 5s is long but this is a background thread anyway and the info has already been updated in the DB
                SystemClock.sleep(5000)
                if (!workerFuture.isDone) {
                    workerFuture.set(result)
                }
            }
        }
    }

    private fun compareNewAndOld(newList: List<SimInfo?>, oldList: List<SimInfo?>?) {
        if (oldList == null || oldList.size != newList.size) {
            onSimInfoUpdate(newList)
        } else {
            for (i in newList.indices) if (newList[i].isNotContainedInOrHasMoved(oldList)) {
                onSimInfoUpdate(newList)
                break
            }
        }
    }

    private fun getSaved(): java.util.ArrayList<SimInfo?>? {
        var oldList: java.util.ArrayList<SimInfo?>? = null
        for (i in 0 until SLOT_COUNT) {
            val si: SimInfo = SimDataSource(applicationContext).get(i)
            if (oldList == null) oldList = java.util.ArrayList()
            oldList.add(si)
        }
        return oldList
    }

    private fun onSimInfoUpdate(newList: List<SimInfo>) {
        updateDb(newList)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(
                MultiSimWorker.action(
                    applicationContext
                )
            )
        )
    }

    private fun updateDb(newList: List<SimInfo>) {
        for (si in newList) {
            si.save(applicationContext)
        }
        val dbInfos: List<SimInfo> = SimDataSource(applicationContext).getAll()
        for (dbSi in dbInfos) {
            if (!findPhysicalSim(newList, dbSi)) {
                dbSi.setSimRemoved(applicationContext)
            }
        }
    }

    private fun findPhysicalSim(newList: List<SimInfo>, savedSim: SimInfo): Boolean {
        for (si in newList) {
            if (si.isSameSim(savedSim)) return true
        }
        return false
    }

    @Synchronized
    @SuppressWarnings("MissingPermission")
    private fun findUniqueSimInfo(): List<SimInfo?>? {
        var newList: List<SimInfo?> = java.util.ArrayList()
        try {
            slotSemaphore.acquire()
            val slotMgrList: List<SlotManager> = java.util.ArrayList()
            val teleMgrInstances = listTeleMgrs(slotMgrList)
            var subInfos: List<SubscriptionInfo?>? = java.util.ArrayList()
            if (Build.VERSION.SDK_INT >= 22) subInfos =
                getSubscriptions(teleMgrInstances, slotMgrList)
            newList =
                if (subInfos != null && subInfos.isNotEmpty()) createUniqueSimInfoList(
                    subInfos,
                    slotMgrList
                ) else createUniqueSimInfoList(slotMgrList)
        } catch (e: java.lang.Exception) {
            Sentry.captureException(e)
        } finally {
            slotSemaphore.release()
        }
        return newList
    }

    private fun createUniqueSimInfoList(
        subInfos: List<SubscriptionInfo>,
        slotMgrList: List<SlotManager>
    ): List<SimInfo> {
        val newList = createUniqueSimInfoList(slotMgrList)
        for (subInfo in subInfos) newList.add(SimInfo(subInfo, applicationContext))
        return removeDuplicates(newList)
    }

    private fun createUniqueSimInfoList(slotMgrList: List<SlotManager>): MutableList<SimInfo> {
        val newList: MutableList<SimInfo> = java.util.ArrayList()
        for (sm in slotMgrList) newList.add(sm.createSimInfo())
        return removeDuplicates(newList)
    }

    private fun removeDuplicates(simInfos: List<SimInfo>): MutableList<SimInfo> {
        val uniqueSimInfos: MutableList<SimInfo> = java.util.ArrayList()
        for (simInfo in simInfos) if (simInfo.isNotContainedIn(uniqueSimInfos)) uniqueSimInfos.add(
            simInfo
        )
        return uniqueSimInfos
    }

    @TargetApi(22)
    @SuppressWarnings("MissingPermission")
    private fun getSubscriptions(
        teleMgrInstances: List<Any>?,
        slotMgrList: List<SlotManager>
    ): List<SubscriptionInfo>? {
        val subInfos = SubscriptionManager.from(
            applicationContext
        ).activeSubscriptionInfoList
        if (teleMgrInstances != null) {
            for (teleMgr in teleMgrInstances) {
                if (subInfos != null) {
                    for (subinfo in subInfos) SlotManager.addValidReadySlots(
                        slotMgrList,
                        subinfo.simSlotIndex,
                        subinfo.subscriptionId,
                        teleMgr,
                        validClassNames
                    )
                }
            }
        }
        return subInfos
    }

    private fun listTeleMgrs(slotMgrList: List<SlotManager>): List<Any?> {
        if (validClassNames == null || validClassNames!!.isEmpty()) validClassNames =
            java.util.ArrayList(
                listOf(*POSS_CLASS_NAMES)
            )
        val teleMgrList: MutableList<Any> = java.util.ArrayList()

        for (className in POSS_CLASS_NAMES) {
            if (className == null) continue
            addMgrFromReflection(className, teleMgrList, null, slotMgrList)
            for (i in 0 until SLOT_COUNT) addMgrFromReflection(
                className,
                teleMgrList,
                i,
                slotMgrList
            )
        }
        addMgrFromSystemService(Context.TELEPHONY_SERVICE, teleMgrList, null, slotMgrList)
        addMgrFromSystemService("phone_msim", teleMgrList, null, slotMgrList)
        for (j in 0 until SLOT_COUNT) addMgrFromSystemService(
            "phone$j",
            teleMgrList,
            j,
            slotMgrList
        )
        teleMgrList.add(null)
        return teleMgrList
    }

    private fun addMgrFromReflection(
        className: String,
        teleMgrList: MutableList<Any>,
        slotIdx: Any?,
        slotMgrList: List<SlotManager>
    ) {
        val result = runMethodReflect(className, "getDefault", slotIdx?.let { arrayOf(it) })
        if (result != null && !teleMgrList.contains(result)) {
            teleMgrList.add(result)
            if (Build.VERSION.SDK_INT < 22) SlotManager.addValidReadySlots(
                slotMgrList,
                slotIdx,
                result,
                validClassNames
            )
        }
    }

    private fun addMgrFromSystemService(
        serviceName: String,
        teleMgrList: MutableList<Any>,
        slotIdx: Any,
        slotMgrList: List<SlotManager>
    ) {
        val serv = applicationContext.getSystemService(serviceName)
        if (serv != null && !teleMgrList.contains(serv)) {
            teleMgrList.add(serv)
            if (Build.VERSION.SDK_INT < 22) SlotManager.addValidReadySlots(
                slotMgrList,
                slotIdx,
                serv,
                validClassNames
            )
        }
    }

    private fun runMethodReflect(
        className: String,
        methodName: String,
        methodParams: Array<Any>
    ): Any? {
        try {
            return runMethodReflect(null, Class.forName(className), methodName, methodParams)
        } catch (e: ClassNotFoundException) {
            validClassNames!!.remove(className)
        }
        return null
    }

    private fun runMethodReflect(
        actualInstance: Any,
        methodName: String,
        methodParams: Array<Any>
    ): Any? {
        return runMethodReflect(actualInstance, actualInstance.javaClass, methodName, methodParams)
    }

    private fun runMethodReflect(
        actualInstance: Any?,
        classInstance: Class<*>,
        methodName: String,
        methodParams: Array<Any>
    ): Any? {
        var result: Any? = null
        try {
            val method = classInstance.getDeclaredMethod(
                methodName, *getClassParams(methodParams)
            )
            val accessible = method.isAccessible
            method.isAccessible = true
            result = method.invoke(actualInstance ?: classInstance, *methodParams)
            method.isAccessible = accessible
        } catch (ignored: java.lang.Exception) {
        }
        return result
    }

    private fun runFieldReflect(className: String, field: String): Any? {
        var result: Any? = null
        try {
            val classInstance = Class.forName(className)
            val fieldReflect = classInstance.getField(field)
            val accessible = fieldReflect.isAccessible
            fieldReflect.isAccessible = true
            result = fieldReflect[null]?.toString()
            fieldReflect.isAccessible = accessible
        } catch (ignored: java.lang.Exception) {
        }
        return result
    }

    private fun getClassParams(methodParams: Array<Any>?): Array<Class<*>?>? {
        var classesParams: Array<Class<*>?>? = null
        if (methodParams != null) {
            classesParams = arrayOfNulls<Class<*>?>(methodParams.size)
            for (i in methodParams.indices) {
                if (methodParams[i] is Int) classesParams[i] =
                    Int::class.javaPrimitiveType // logString += methodParams[i] + ",";
                else if (methodParams[i] is String) classesParams[i] =
                    String::class.java // logString += "\"" + methodParams[i] + "\",";
                else if (methodParams[i] is Long) classesParams[i] =
                    Long::class.javaPrimitiveType // logString += methodParams[i] + ",";
                else if (methodParams[i] is Boolean) classesParams[i] =
                    Boolean::class.javaPrimitiveType // logString += methodParams[i] + ",";
                else classesParams[i] =
                    methodParams[i].javaClass // logString += "["+methodParams[i]+"]" + ",";
            }
        }
        return classesParams
    }

    private fun registerSimStateReceiver() {
        if (simStateReceiver == null) {
            simStateReceiver = SimStateReceiver()
            val intentFilter = IntentFilter()
            intentFilter.addAction("android.intent.action.SIM_STATE_CHANGED")
            intentFilter.addAction("android.intent.action.ACTION_SIM_STATE_CHANGED")
            intentFilter.addAction("android.intent.action.PHONE_STATE")
            intentFilter.addAction("vivo.intent.action.ACTION_SIM_STATE_CHANGED")
            applicationContext.registerReceiver(simStateReceiver, intentFilter)
        }
    }

    private class SimStateListener : PhoneStateListener() {
        override fun onServiceStateChanged(serviceState: ServiceState) {
            updateSimInfo()
        }
    }

    private class SimStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateSimInfo()
        }
    }

    override fun onStopped() {
        super.onStopped()
        try {
            (applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .listen(simStateListener, PhoneStateListener.LISTEN_NONE)
            simStateListener = null
            if (simStateReceiver != null) applicationContext.unregisterReceiver(simStateReceiver)
            simStateReceiver = null
        } catch (ignored: Exception) {
        }
    }

}