package com.hover.multisim.sim

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.telephony.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.hover.multisim.sim.MultiSimWorker
import com.hover.multisim.sim.SlotManager.Companion.addValidReadySlots
import com.hover.multisim.sim.Utils.getPackage
import com.hover.multisim.sim.Utils.hasPhonePerm
import io.sentry.Sentry
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MultiSimWorker(context: Context, params: WorkerParameters) :
    ListenableWorker(context, params) {

    private lateinit var workerFuture: SettableFuture<Result>
    private var result: Result? = null
    private val slotSemaphore = Semaphore(1, true)
    private val simSemaphore = Semaphore(1, true)
    private var simStateReceiver: SimStateReceiver? = null
    private var simStateListener: SimStateListener? = null
    private var validClassNames: ArrayList<String?>? = null
    private val POSS_CLASS_NAMES = arrayOf(
        null,
        "android.telephony.TelephonyManager",
        "android.telephony.MSimTelephonyManager",
        "android.telephony.MultiSimTelephonyService",
        "com.mediatek.telephony.TelephonyManagerEx",
        "com.android.internal.telephony.Phone",
        "com.android.internal.telephony.PhoneFactory"
    )

    @SuppressLint("RestrictedApi")
    override fun startWork(): ListenableFuture<Result> {
        android.util.Log.v(TAG, "Starting new Multi SIM worker")
        workerFuture = SettableFuture.create()
        if (hasPhonePerm(applicationContext)) startListeners() else workerFuture.set(Result.failure())
        return workerFuture
    }

    @SuppressLint("RestrictedApi")
    private fun startListeners() {
        try {
            registerSimStateReceiver()
            if (simStateListener == null) simStateListener = SimStateListener()
            // TelephonyManager.listen() must take place on the main thread
            (applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).listen(
                simStateListener,
                PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
            )
        } catch (e: Exception) {
            android.util.Log.d(TAG, "Failed to start SIM listeners, setting retry", e)
            workerFuture.set(Result.retry())
        }
    }

    @SuppressLint("RestrictedApi")
    @Synchronized
    private fun updateSimInfo() {
        backgroundExecutor.execute {
            result = try {
                simSemaphore.acquire()
                if (hasPhonePerm(applicationContext)) {
                    android.util.Log.v(TAG, "reviewing sim info")
                    val oldList: List<SimInfo>? = saved
                    val newList = findUniqueSimInfo()
                    run {
                        compareNewAndOld(newList, oldList)
                        Result.success()
                    }
                } else Result.failure()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "threw while attempting to update sim list", e)
                Sentry.captureException(e)
                Result.failure()
            } finally {
                simSemaphore.release()
                // Give the listeners a chance to receive a few events - sometimes the first trigger isn't the needed info. 5s is long but this is a background thread anyway and the info has already been updated in the DB
                SystemClock.sleep(5000)
                if (!workerFuture.isDone) {
                    android.util.Log.v(TAG, "Finishing Multi SIM worker")
                    workerFuture.set(result)
                }
            }
        }
    }

    private fun compareNewAndOld(newList: List<SimInfo?>, oldList: List<SimInfo>?) {
        if (oldList == null || oldList.size != newList.size) {
            android.util.Log.v(
                TAG,
                "no old list or sizes differ. Old: " + (oldList?.size
                    ?: "null") + ", new: " + newList.size
            )
            onSimInfoUpdate(newList)
        } else {
            for (i in newList.indices) if (newList[i]!!.isNotContainedInOrHasMoved(oldList)) {
                android.util.Log.v(TAG, "some sim moved")
                onSimInfoUpdate(newList)
                break
            }
        }
    }

    private val saved: ArrayList<SimInfo>?
        get() {
            var oldList: ArrayList<SimInfo>? = null
            for (i in 0 until SLOT_COUNT) {
                val si: SimInfo = SimDataSource(applicationContext).get(i)
                if (oldList == null) oldList = ArrayList()
                oldList.add(si)
            }
            android.util.Log.v(TAG, "Loaded old list from db. Size: " + (oldList?.size ?: "null"))
            return oldList
        }

    private fun onSimInfoUpdate(newList: List<SimInfo?>) {
        updateDb(newList)
        android.util.Log.v(TAG, "Saved. Firing broadcast")
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(
                action(
                    applicationContext
                )
            )
        )
    }

    private fun updateDb(newList: List<SimInfo?>) {
        for (si in newList) {
            android.util.Log.i(TAG, "Saving SIM in slot " + si!!.slotIdx + ": " + si.toString())
            si.save(applicationContext)
        }
        val dbInfos: List<SimInfo> = SimDataSource(applicationContext).getAll()
        for (dbSi in dbInfos) {
            if (!findPhysicalSim(newList, dbSi)) {
                android.util.Log.i(TAG, "Couldn't find SIM: $dbSi, removing")
                dbSi.setSimRemoved(applicationContext)
            }
        }
    }

    private fun findPhysicalSim(newList: List<SimInfo?>, savedSim: SimInfo): Boolean {
        for (si in newList) {
            if (si!!.isSameSim(savedSim)) return true
        }
        return false
    }

    @Synchronized
    private fun findUniqueSimInfo(): List<SimInfo?> {
        var newList: List<SimInfo?> = ArrayList()
        try {
            slotSemaphore.acquire()
            val slotMgrList: List<SlotManager?> = ArrayList()
            val teleMgrInstances = listTeleMgrs(slotMgrList)
            var subInfos: List<SubscriptionInfo>? = ArrayList()
            if (Build.VERSION.SDK_INT >= 22) subInfos =
                getSubscriptions(teleMgrInstances, slotMgrList)
            newList = if (subInfos != null && subInfos.isNotEmpty()) createUniqueSimInfoList(
                subInfos, slotMgrList
            ) else createUniqueSimInfoList(slotMgrList)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Multi-SIM worker caught something", e)
            Sentry.captureException(e)
        } finally {
            slotSemaphore.release()
        }
        return newList
    }

    private fun createUniqueSimInfoList(
        subInfos: List<SubscriptionInfo>, slotMgrList: List<SlotManager?>
    ): List<SimInfo?> {
        val newList = createUniqueSimInfoList(slotMgrList)
        for (subInfo in subInfos) newList.add(SimInfo(subInfo, applicationContext))
        return removeDuplicates(newList)
    }

    private fun createUniqueSimInfoList(slotMgrList: List<SlotManager?>): MutableList<SimInfo?> {
        val newList: MutableList<SimInfo?> = ArrayList()
        for (sm in slotMgrList) newList.add(sm!!.createSimInfo())
        return removeDuplicates(newList)
    }

    private fun removeDuplicates(simInfos: List<SimInfo?>): MutableList<SimInfo?> {
        val uniqueSimInfos: MutableList<SimInfo?> = ArrayList()
        for (simInfo in simInfos) if (simInfo!!.isNotContainedIn(uniqueSimInfos)) uniqueSimInfos.add(
            simInfo
        )
        return uniqueSimInfos
    }

    @TargetApi(22)
    @Throws(Exception::class)
    private fun getSubscriptions(
        teleMgrInstances: List<Any?>?, slotMgrList: List<SlotManager?>
    ): List<SubscriptionInfo>? {
        val subInfos = SubscriptionManager.from(applicationContext).activeSubscriptionInfoList
        if (teleMgrInstances != null) {
            for (teleMgr in teleMgrInstances) {
                if (subInfos != null) {
                    for (subinfo in subInfos) addValidReadySlots(
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

    private fun listTeleMgrs(slotMgrList: List<SlotManager?>): List<Any?> {
        if (validClassNames == null || validClassNames!!.isEmpty()) validClassNames = ArrayList(
            listOf(*POSS_CLASS_NAMES)
        )
        val teleMgrList: MutableList<Any?> = ArrayList()
        for (className in POSS_CLASS_NAMES) {
            if (className == null) continue
            addMgrFromReflection(className, teleMgrList, null, slotMgrList)
            for (i in 0 until SLOT_COUNT) addMgrFromReflection(
                className, teleMgrList, i, slotMgrList
            )
        }
        addMgrFromSystemService(Context.TELEPHONY_SERVICE, teleMgrList, null, slotMgrList)
        addMgrFromSystemService("phone_msim", teleMgrList, null, slotMgrList)
        for (j in 0 until SLOT_COUNT) addMgrFromSystemService(
            "phone$j", teleMgrList, j, slotMgrList
        )
        teleMgrList.add(null)
        return teleMgrList
    }

    private fun addMgrFromReflection(
        className: String,
        teleMgrList: MutableList<Any?>,
        slotIdx: Any?,
        slotMgrList: List<SlotManager?>
    ) {
        val result = runMethodReflect(className, "getDefault", slotIdx?.let { arrayOf(it) })
        if (result != null && !teleMgrList.contains(result)) {
            teleMgrList.add(result)
            Log("Added Mgr using className: $className, method: getDefault, and param: $slotIdx")
            if (Build.VERSION.SDK_INT < 22) addValidReadySlots(
                slotMgrList, slotIdx, result, validClassNames
            )
        }
    }

    private fun addMgrFromSystemService(
        serviceName: String,
        teleMgrList: MutableList<Any?>,
        slotIdx: Any?,
        slotMgrList: List<SlotManager?>
    ) {
        val serv = applicationContext.getSystemService(serviceName)
        if (serv != null && !teleMgrList.contains(serv)) {
            teleMgrList.add(serv)
            Log("Added Mgr using mContext.getSystemService('$serviceName')")
            if (Build.VERSION.SDK_INT < 22) addValidReadySlots(
                slotMgrList, slotIdx, serv, validClassNames
            )
        }
    }

    private fun runMethodReflect(
        className: String, methodName: String, methodParams: Array<Any>?
    ): Any? {
        try {
            return runMethodReflect(null, Class.forName(className), methodName, methodParams)
        } catch (e: ClassNotFoundException) {
            validClassNames!!.remove(className)
        }
        return null
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

    private inner class SimStateListener : PhoneStateListener() {
        override fun onServiceStateChanged(serviceState: ServiceState) {
            updateSimInfo()
        }
    }

    private inner class SimStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent != null) updateSimInfo()
        }
    }

    override fun onStopped() {
        super.onStopped()
        try {
            (applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).listen(
                simStateListener, PhoneStateListener.LISTEN_NONE
            )
            simStateListener = null
            if (simStateReceiver != null) applicationContext.unregisterReceiver(simStateReceiver)
            simStateReceiver = null
        } catch (ignored: Exception) {
        }
    }

    private fun goFish() {
        printAllMethodsAndFields("android.telephony.TelephonyManager", -1) // all methods
        printAllMethodsAndFields("android.telephony.MultiSimTelephonyService", -1) // all methods
        printAllMethodsAndFields("android.telephony.MSimTelephonyManager", -1) // all methods
        printAllMethodsAndFields("com.mediatek.telephony.TelephonyManager", -1) // all methods
        printAllMethodsAndFields("com.mediatek.telephony.TelephonyManagerEx", -1) // all methods
        printAllMethodsAndFields("com.android.internal.telephony.ITelephony", -1) // all methods
        printAllMethodsAndFields(
            "com.android.internal.telephony.ITelephony\$Stub\$Proxy", -1
        ) // all methods
    }

    private fun printAllMethodsAndFields(className: String, paramsCount: Int) {
        Log("====================================================================================")
        Log("Methods of $className")
        try {
            val MultiSimClass = Class.forName(className)
            for (method in MultiSimClass.methods) {
                Log(method.toGenericString())
                try {
                    if (method.parameterTypes.isEmpty()) Log(
                        runMethodReflect(
                            MultiSimClass, method.name, null
                        ) as String?
                    ) else if (method.parameterTypes.size == 1) Log(
                        " " + runMethodReflect(
                            MultiSimClass, method.name, arrayOf(0)
                        )
                    )
                } catch (e: Exception) {
                    Log("Failed. $e")
                }
            }
        } catch (e: Exception) {
            Log("Failed. $e")
        }
    }

    companion object {
        const val TAG = "MultiSimTeleMgr"
        private const val NEW_SIM_INFO = "NEW_SIM_INFO_ACTION"
        const val SLOT_COUNT = 3 // Need to check 0, 1, and 2. Some phones index from 1.
        fun makeToil(): PeriodicWorkRequest {
            return PeriodicWorkRequest.Builder(MultiSimWorker::class.java, 15, TimeUnit.MINUTES)
                .build()
        }

        fun makeWork(): OneTimeWorkRequest {
            return OneTimeWorkRequest.Builder(MultiSimWorker::class.java).build()
        }

        private fun runMethodReflect(
            actualInstance: Any, methodName: String, methodParams: Array<Any>?
        ): Any? {
            return runMethodReflect(
                actualInstance, actualInstance.javaClass, methodName, methodParams
            )
        }

        fun runMethodReflect(
            actualInstance: Any?,
            classInstance: Class<*>,
            methodName: String?,
            methodParams: Array<Any>?
        ): Any? {
            var result: Any? = null
            try {
                val method = methodName?.let {
                    classInstance.getDeclaredMethod(
                        it, *getClassParams(methodParams)
                    )
                }
                val accessible = method?.isAccessible
                if (method != null) {
                    method.isAccessible = true
                }
                if (method != null) {
                    result = method.invoke(actualInstance ?: classInstance, *methodParams)
                }
                if (accessible != null) {
                    method.isAccessible = accessible
                }
            } catch (ignored: Exception) { /* Log("Method not found: " + ignored); */
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
            } catch (ignored: Exception) {
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

        fun action(c: Context?): String {
            return getPackage(c!!) + "." + NEW_SIM_INFO
        }

        private fun log(message: String?) {}
    }
}