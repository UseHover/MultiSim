package com.hover.multisim.sim;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.futures.SettableFuture;

import com.google.common.util.concurrent.ListenableFuture;
import com.hover.multisim.SlotManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.sentry.Sentry;

final public class MultiSimWorker extends ListenableWorker {
    public static final String TAG = "MultiSimTeleMgr";
    private static final String NEW_SIM_INFO = "NEW_SIM_INFO_ACTION";
    static final int SLOT_COUNT = 3; // Need to check 0, 1, and 2. Some phones index from 1.

    private SettableFuture<Result> workerFuture;
    private Result result = null;

    private final Semaphore slotSemaphore = new Semaphore(1, true);
    private final Semaphore simSemaphore = new Semaphore(1, true);

    private SimStateReceiver simStateReceiver;
    private SimStateListener simStateListener;
    private ArrayList<String> validClassNames;

    private final String[] POSS_CLASS_NAMES = new String[]{null, "android.telephony.TelephonyManager", "android.telephony.MSimTelephonyManager", "android.telephony.MultiSimTelephonyService", "com.mediatek.telephony.TelephonyManagerEx", "com.android.internal.telephony.Phone", "com.android.internal.telephony.PhoneFactory"};

    public MultiSimWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static PeriodicWorkRequest makeToil() {
        return new PeriodicWorkRequest.Builder(MultiSimWorker.class, 15, TimeUnit.MINUTES).build();
    }

    public static OneTimeWorkRequest makeWork() {
        return new OneTimeWorkRequest.Builder(MultiSimWorker.class).build();
    }

    @Override
    @SuppressLint("RestrictedApi")
    public @NonNull
    ListenableFuture<Result> startWork() {
        Log.v(TAG, "Starting new Multi SIM worker");
        workerFuture = SettableFuture.create();

        if (Utils.hasPhonePerm(getApplicationContext())) startListeners();
        else workerFuture.set(Result.failure());
        return workerFuture;
    }

    @SuppressLint("RestrictedApi")
    private void startListeners() {
        try {
            registerSimStateReceiver();
            if (simStateListener == null) simStateListener = new SimStateListener();
            // TelephonyManager.listen() must take place on the main thread
            ((TelephonyManager) getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE)).listen(simStateListener, PhoneStateListener.LISTEN_SERVICE_STATE | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (Exception e) {
            Log.d(TAG, "Failed to start SIM listeners, setting retry", e);
            workerFuture.set(Result.retry());
        }
    }

    @SuppressLint("RestrictedApi")
    synchronized private void updateSimInfo() {
        getBackgroundExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    simSemaphore.acquire();
                    if (Utils.hasPhonePerm(getApplicationContext())) {
                        Log.v(TAG, "reviewing sim info");
                        List<SimInfo> oldList = getSaved();
                        List<SimInfo> newList = findUniqueSimInfo();

                        if (newList != null) {
                            compareNewAndOld(newList, oldList);
                            result = Result.success();
                        } else result = Result.failure();
                    } else result = Result.failure();
                } catch (Exception e) {
                    Log.w(TAG, "threw while attempting to update sim list", e);
                    Sentry.captureException(e);
                    result = Result.failure();
                } finally {
                    simSemaphore.release();
// Give the listeners a chance to receive a few events - sometimes the first trigger isn't the needed info. 5s is long but this is a background thread anyway and the info has already been updated in the DB
                    SystemClock.sleep(5000);
                    if (!workerFuture.isDone()) {
                        Log.v(TAG, "Finishing Multi SIM worker");
                        workerFuture.set(result);
                    }
                }
            }
        });
    }

    private void compareNewAndOld(List<SimInfo> newList, List<SimInfo> oldList) {
        if (oldList == null || oldList.size() != newList.size()) {
            Log.v(TAG, "no old list or sizes differ. Old: " + (oldList != null ? oldList.size() : "null") + ", new: " + newList.size());
            onSimInfoUpdate(newList);
        } else {
            for (int i = 0; i < newList.size(); i++)
                if (newList.get(i).isNotContainedInOrHasMoved(oldList)) {
                    Log.v(TAG, "some sim moved");
                    onSimInfoUpdate(newList);
                    break;
                }
        }
    }

    private ArrayList<SimInfo> getSaved() {
        ArrayList<SimInfo> oldList = null;
        for (int i = 0; i < SLOT_COUNT; i++) {
            SimInfo si = new SimDataSource(getApplicationContext()).get(i);
            if (si != null) {
                if (oldList == null) oldList = new ArrayList<>();
                oldList.add(si);
            }
        }
        Log.v(TAG, "Loaded old list from db. Size: " + (oldList != null ? oldList.size() : "null"));
        return oldList;
    }

    private void onSimInfoUpdate(List<SimInfo> newList) {
        updateDb(newList);
        Log.v(TAG, "Saved. Firing broadcast");
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(action(getApplicationContext())));
    }

    private void updateDb(List<SimInfo> newList) {
        for (SimInfo si : newList) {
            Log.i(TAG, "Saving SIM in slot " + si.slotIdx + ": " + si.toString());
            si.save(getApplicationContext());
        }

        List<SimInfo> dbInfos = new SimDataSource(getApplicationContext()).getAll();
        for (SimInfo dbSi : dbInfos) {
            if (!findPhysicalSim(newList, dbSi)) {
                Log.i(TAG, "Couldn't find SIM: " + dbSi.toString() + ", removing");
                dbSi.setSimRemoved(getApplicationContext());
            }
        }
    }

    private boolean findPhysicalSim(List<SimInfo> newList, SimInfo savedSim) {
        for (SimInfo si : newList) {
            if (si.isSameSim(savedSim)) return true;
        }
        return false;
    }

    @SuppressWarnings({"MissingPermission"})
    synchronized private List<SimInfo> findUniqueSimInfo() {
        List<SimInfo> newList = new ArrayList<>();
        try {
            slotSemaphore.acquire();
            List<SlotManager> slotMgrList = new ArrayList<>();
            List<Object> teleMgrInstances = listTeleMgrs(slotMgrList);

            List<SubscriptionInfo> subInfos = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 22)
                subInfos = getSubscriptions(teleMgrInstances, slotMgrList);

            if (subInfos != null && subInfos.size() > 0)
                newList = createUniqueSimInfoList(subInfos, slotMgrList);
            else newList = createUniqueSimInfoList(slotMgrList);
        } catch (Exception e) {
            Log.w(TAG, "Multi-SIM worker caught something", e);
            Sentry.captureException(e);
        } finally {
            slotSemaphore.release();
        }
        return newList;
    }

    private List<SimInfo> createUniqueSimInfoList(List<SubscriptionInfo> subInfos, List<SlotManager> slotMgrList) {
        List<SimInfo> newList = createUniqueSimInfoList(slotMgrList);
        for (SubscriptionInfo subInfo : subInfos)
            newList.add(new SimInfo(subInfo, getApplicationContext()));
        return removeDuplicates(newList);
    }

    private List<SimInfo> createUniqueSimInfoList(List<SlotManager> slotMgrList) {
        List<SimInfo> newList = new ArrayList<>();
        for (SlotManager sm : slotMgrList)
            newList.add(sm.createSimInfo());
        return removeDuplicates(newList);
    }

    private List<SimInfo> removeDuplicates(List<SimInfo> simInfos) {
        List<SimInfo> uniqueSimInfos = new ArrayList<>();
        for (SimInfo simInfo : simInfos)
            if (simInfo.isNotContainedIn(uniqueSimInfos)) uniqueSimInfos.add(simInfo);
        return uniqueSimInfos;
    }

    @TargetApi(22)
    @SuppressWarnings({"MissingPermission"})
    private List<SubscriptionInfo> getSubscriptions(List<Object> teleMgrInstances, List<SlotManager> slotMgrList) throws Exception {
        List<SubscriptionInfo> subInfos = SubscriptionManager.from(getApplicationContext()).getActiveSubscriptionInfoList();
        if (teleMgrInstances != null) {
            for (Object teleMgr : teleMgrInstances) {
                if (subInfos != null) {
                    for (SubscriptionInfo subinfo : subInfos)
                        SlotManager.addValidReadySlots(slotMgrList, subinfo.getSimSlotIndex(), subinfo.getSubscriptionId(), teleMgr, validClassNames);
                }
            }
        }
        return subInfos;
    }

    @SuppressWarnings("ResourceType")
    private List<Object> listTeleMgrs(List<SlotManager> slotMgrList) {
        if (validClassNames == null || validClassNames.isEmpty())
            validClassNames = new ArrayList<>(Arrays.asList(POSS_CLASS_NAMES));
        List<Object> teleMgrList = new ArrayList<>();

//		Log("Creating TeleMgrs List..............................................................");
        for (String className : POSS_CLASS_NAMES) {
            if (className == null) continue;
            addMgrFromReflection(className, teleMgrList, null, slotMgrList);
            for (int i = 0; i < SLOT_COUNT; i++)
                addMgrFromReflection(className, teleMgrList, i, slotMgrList);
        }

        addMgrFromSystemService(Context.TELEPHONY_SERVICE, teleMgrList, null, slotMgrList);
        addMgrFromSystemService("phone_msim", teleMgrList, null, slotMgrList);
        for (int j = 0; j < SLOT_COUNT; j++)
            addMgrFromSystemService("phone" + j, teleMgrList, j, slotMgrList);
        teleMgrList.add(null);

//		Log("Total TeleMgrInstances length including null entry: " + teleMgrList.size());
//		Log("Valid class names were: " + validClassNames.toString());
//		Log("....................................................................................");
        return teleMgrList;
    }

    private void addMgrFromReflection(String className, List<Object> teleMgrList, Object slotIdx, List<SlotManager> slotMgrList) {
        Object result = runMethodReflect(className, "getDefault", slotIdx == null ? null : new Object[]{slotIdx});
        if (result != null && !teleMgrList.contains(result)) {
            teleMgrList.add(result);
            Log("Added Mgr using className: " + className + ", method: getDefault, and param: " + slotIdx);
            if (Build.VERSION.SDK_INT < 22)
                SlotManager.addValidReadySlots(slotMgrList, slotIdx, result, validClassNames);
        }
    }

    private void addMgrFromSystemService(String serviceName, List<Object> teleMgrList, Object slotIdx, List<SlotManager> slotMgrList) {
        Object serv = getApplicationContext().getSystemService(serviceName);
        if (serv != null && !teleMgrList.contains(serv)) {
            teleMgrList.add(serv);
            Log("Added Mgr using mContext.getSystemService('" + serviceName + "')");
            if (Build.VERSION.SDK_INT < 22)
                SlotManager.addValidReadySlots(slotMgrList, slotIdx, serv, validClassNames);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private Object runMethodReflect(String className, String methodName, Object[] methodParams) {
        try {
            return runMethodReflect(null, Class.forName(className), methodName, methodParams);
        } catch (ClassNotFoundException e) {
            validClassNames.remove(className);
//			Log("Class not found, removing: " + e);
        }
        return null;
    }

    private static Object runMethodReflect(Object actualInstance, String methodName, Object[] methodParams) {
        return runMethodReflect(actualInstance, actualInstance.getClass(), methodName, methodParams);
    }

    public static Object runMethodReflect(Object actualInstance, Class<?> classInstance, String methodName, Object[] methodParams) {
        Object result = null;
        try {
            Method method = classInstance.getDeclaredMethod(methodName, getClassParams(methodParams));
            boolean accessible = method.isAccessible();
            method.setAccessible(true);
            result = method.invoke(actualInstance != null ? actualInstance : classInstance, methodParams);
            method.setAccessible(accessible);
        } catch (Exception ignored) { /* Log("Method not found: " + ignored); */ }
        return result;
    }

    @SuppressWarnings("unused")
    private static Object runFieldReflect(String className, String field) {
        Object result = null;
        try {
            Class<?> classInstance = Class.forName(className);
            Field fieldReflect = classInstance.getField(field);
            boolean accessible = fieldReflect.isAccessible();
            fieldReflect.setAccessible(true);
            result = fieldReflect.get(null).toString();
            fieldReflect.setAccessible(accessible);
        } catch (Exception ignored) {
//			Log("Error accessing reflected class: " + ignored);
        }
        return result;
    }

    private static Class[] getClassParams(Object[] methodParams) {
        Class[] classesParams = null;
        if (methodParams != null) {
            classesParams = new Class[methodParams.length];
            for (int i = 0; i < methodParams.length; i++) {
                if (methodParams[i] instanceof Integer)
                    classesParams[i] = int.class; // logString += methodParams[i] + ",";
                else if (methodParams[i] instanceof String)
                    classesParams[i] = String.class; // logString += "\"" + methodParams[i] + "\",";
                else if (methodParams[i] instanceof Long)
                    classesParams[i] = long.class; // logString += methodParams[i] + ",";
                else if (methodParams[i] instanceof Boolean)
                    classesParams[i] = boolean.class; // logString += methodParams[i] + ",";
                else
                    classesParams[i] = methodParams[i].getClass(); // logString += "["+methodParams[i]+"]" + ",";
            }
        }
        return classesParams;
    }

    private void registerSimStateReceiver() {
        if (simStateReceiver == null) {
            simStateReceiver = new SimStateReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.intent.action.SIM_STATE_CHANGED");
            intentFilter.addAction("android.intent.action.ACTION_SIM_STATE_CHANGED");
            intentFilter.addAction("android.intent.action.PHONE_STATE");
            intentFilter.addAction("vivo.intent.action.ACTION_SIM_STATE_CHANGED");
            getApplicationContext().registerReceiver(simStateReceiver, intentFilter);
        }
    }

    private class SimStateListener extends PhoneStateListener {
        public void onServiceStateChanged(ServiceState serviceState) {
            updateSimInfo();
        }
    }

    private class SimStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent != null) updateSimInfo();
        }
    }

    public static String action(Context c) {
        return Utils.getPackage(c) + "." + NEW_SIM_INFO;
    }

    @Override
    public void onStopped() {
        super.onStopped();
        try {
            ((TelephonyManager) getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE)).listen(simStateListener, PhoneStateListener.LISTEN_NONE);
            simStateListener = null;
            if (simStateReceiver != null)
                getApplicationContext().unregisterReceiver(simStateReceiver);
            simStateReceiver = null;
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unused")
    private void goFish() {
        printAllMethodsAndFields("android.telephony.TelephonyManager", -1); // all methods
        printAllMethodsAndFields("android.telephony.MultiSimTelephonyService", -1); // all methods
//		 printAllMethodsAndFields("android.telephony.MultiSimTelephonyService", 0); // methods with 0 params
//		 printAllMethodsAndFields("android.telephony.MultiSimTelephonyService", 1); // methods with 1 params
//		 printAllMethodsAndFields("android.telephony.MultiSimTelephonyService", 2); // methods with 2 params
        printAllMethodsAndFields("android.telephony.MSimTelephonyManager", -1); // all methods
        printAllMethodsAndFields("com.mediatek.telephony.TelephonyManager", -1); // all methods
        printAllMethodsAndFields("com.mediatek.telephony.TelephonyManagerEx", -1); // all methods
        printAllMethodsAndFields("com.android.internal.telephony.ITelephony", -1); // all methods
        printAllMethodsAndFields("com.android.internal.telephony.ITelephony$Stub$Proxy", -1); // all methods
    }

    @SuppressWarnings("unused")
    private void printAllMethodsAndFields(String className, int paramsCount) {
        Log("====================================================================================");
        Log("Methods of " + className);
        try {
            Class<?> MultiSimClass = Class.forName(className);
            for (Method method : MultiSimClass.getMethods()) {
//				if (method.toGenericString().toLowerCase().contains("ussd")) {
                Log(method.toGenericString());
                try {
                    if (method.getParameterTypes().length == 0)
                        Log((String) runMethodReflect(MultiSimClass, method.getName(), null));
                    else if (method.getParameterTypes().length == 1)
                        Log(" " + runMethodReflect(MultiSimClass, method.getName(), new Object[]{0}));
                } catch (Exception e) {
                    Log("Failed. " + e);
                }
//				}
            }
        } catch (Exception e) {
            Log("Failed. " + e);
        }
//				  for( Field field : MultiSimClass.getFields()) {
//					  field.setAccessible(true);
//					  Log.i(LOG, " f2 " + field.getName() + " " + field.getType() + " " + field.load(inst));
//				  }
    }

    @SuppressWarnings({"EmptyMethod", "unused"})
    private static void Log(String message) {
//		Log.e(TAG, message);
    }
}
