/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.camera;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceProxy;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Slog;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.os.UEventObserver; /*parksungho, add the switch driver*/

/**
 * CameraService is the system_server analog to the camera service running in mediaserver.
 *
 * @hide
 */
public class CameraService extends SystemService implements Handler.Callback {
    private static final String TAG = "CameraService_proxy";
    /**
     * This must match the ICameraService.aidl definition
     */
    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";
    public static final String CAMERA_SERVICE_PROXY_BINDER_NAME = "media.camera.proxy";
    // Event arguments to use with the camera service notifySystemEvent call:
    public static final int NO_EVENT = 0; // NOOP
    public static final int USER_SWITCHED = 1; // User changed, argument is the new user handle
    // Handler message codes
    private static final int MSG_SWITCH_USER = 1;
    private static final int RETRY_DELAY_TIME = 20; //ms

    /*parksungho, add the switch driver*/
    private static final String OEM_SENSOR_UEVENT = "DEVPATH=/devices/virtual/switch/SensorMount";
    private static final java.lang.String ACTION_OEM_CAMERA_UPDATE = "android.intent.action.OEM_CAMERA_UPDATE";
    private static final java.lang.String ACTION_OEM_FCAMERA_UPDATE = "android.intent.action.OEM_FCAMERA_UPDATE";

    private String mStrInsertCamera;
    //]parksungho
    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;
    private UserManager mUserManager;
    private final Object mLock = new Object();
    private Set<Integer> mEnabledCameraUsers;
    private final ICameraServiceProxy.Stub mCameraServiceProxy = new ICameraServiceProxy.Stub() {
        @Override
        public void pingForUserUpdate() {
            notifySwitchWithRetries(30);
        }
    };
    public CameraService(Context context) {
        super(context);
        mContext = context;
        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_DISPLAY, /*allowTo*/false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), this);

        /*parksungho, add the switch driver*/
        mObserver.startObserving(OEM_SENSOR_UEVENT);
        //]parksungho
    }


    /*parksungho, add the switch driver*/
    private final UEventObserver mObserver = new UEventObserver() {
	@Override
	public void onUEvent(UEventObserver.UEvent event) {
		try {
	               synchronized (mLock) {
		                sendOemCameraIntentLocked(event.get("SWITCH_STATE").trim());
	        	}
	   	} catch (NumberFormatException e) {
	        	Slog.e(TAG, "uevent failed, e="+e.toString());
		}
	}
    };

    private void sendOemCameraIntentLocked(String sensorMountData) {
        final Intent intent = new Intent(ACTION_OEM_SENSOR_MOUNT);

        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_REPLACE_PENDING);

        intent.putExtra("SENSOR_MOUNT", sensorMountData);

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.USER_ALL);
            }
        });
    }
    //]parksungho
    
    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case MSG_SWITCH_USER: {
                notifySwitchWithRetries(msg.arg1);
            } break;
            default: {
                Slog.e(TAG, "CameraService error, invalid message: " + msg.what);
            } break;
        }
        return true;
    }
    @Override
    public void onStart() {
        mUserManager = UserManager.get(mContext);
        if (mUserManager == null) {
            // Should never see this unless someone messes up the SystemServer service boot order.
            throw new IllegalStateException("UserManagerService must start before CameraService!");
        }
        publishBinderService(CAMERA_SERVICE_PROXY_BINDER_NAME, mCameraServiceProxy);
    }
    @Override
    public void onStartUser(int userHandle) {
        synchronized(mLock) {
            if (mEnabledCameraUsers == null) {
                // Initialize mediaserver, or update mediaserver if we are recovering from a crash.
                switchUserLocked(userHandle);
            }
        }
    }
    @Override
    public void onSwitchUser(int userHandle) {
        synchronized(mLock) {
            switchUserLocked(userHandle);
        }
    }
    private void switchUserLocked(int userHandle) {
        Set<Integer> currentUserHandles = getEnabledUserHandles(userHandle);
        if (mEnabledCameraUsers == null || !mEnabledCameraUsers.equals(currentUserHandles)) {
            // Some user handles have been added or removed, update mediaserver.
            mEnabledCameraUsers = currentUserHandles;
            notifyMediaserver(USER_SWITCHED, currentUserHandles);
        }
    }
    private Set<Integer> getEnabledUserHandles(int currentUserHandle) {
        List<UserInfo> userProfiles = mUserManager.getEnabledProfiles(currentUserHandle);
        Set<Integer> handles = new HashSet<>(userProfiles.size());
        for (UserInfo i : userProfiles) {
            handles.add(i.id);
        }
        return handles;
    }
    private void notifySwitchWithRetries(int retries) {
        synchronized(mLock) {
            if (mEnabledCameraUsers == null) {
                return;
            }
            if (notifyMediaserver(USER_SWITCHED, mEnabledCameraUsers)) {
                retries = 0;
            }
        }
        if (retries <= 0) {
            return;
        }
        Slog.i(TAG, "Could not notify camera service of user switch, retrying...");
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SWITCH_USER, retries - 1, 0, null),
                RETRY_DELAY_TIME);
    }
    private boolean notifyMediaserver(int eventType, Set<Integer> updatedUserHandles) {
        // Forward the user switch event to the native camera service running in the mediaserver
        // process.
        IBinder cameraServiceBinder = getBinderService(CAMERA_SERVICE_BINDER_NAME);
        if (cameraServiceBinder == null) {
            Slog.w(TAG, "Could not notify mediaserver, camera service not available.");
            return false; // Camera service not active, cannot evict user clients.
        }
        ICameraService cameraServiceRaw = ICameraService.Stub.asInterface(cameraServiceBinder);
        try {
            cameraServiceRaw.notifySystemEvent(eventType, toArray(updatedUserHandles));
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not notify mediaserver, remote exception: " + e);
            // Not much we can do if camera service is dead.
            return false;
        }
        return true;
    }
    private static int[] toArray(Collection<Integer> c) {
        int len = c.size();
        int[] ret = new int[len];
        int idx = 0;
        for (Integer i : c) {
            ret[idx++] = i;
        }
        return ret;
    }
}
