/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.bluetooth.iap2;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothIAP2;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothIAP2;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Provides Bluetooth IAP2 profile, as a service in
 * the Bluetooth application.
 * @hide
 */
public class Iap2Service extends ProfileService {
    private static final boolean DBG = true;
    private static final String TAG = "Iap2Service";

    private Iap2StateMachine mStateMachine;
    private static Iap2Service sIap2Service;

    private ParcelFileDescriptor mPfd;

    protected String getName() {
        return TAG;
    }

    public IProfileServiceBinder initBinder() {
        return new BluetoothIap2Binder(this);
    }

    protected boolean start() {
        mStateMachine = Iap2StateMachine.make(this);
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        try {
            registerReceiver(mIap2Receiver, filter);
        } catch (Exception e) {
            Log.w(TAG,"Unable to register iap2 receiver",e);
        }
        setIap2Service(this);
        return true;
    }

    protected boolean stop() {
        try {
            unregisterReceiver(mIap2Receiver);
        } catch (Exception e) {
            Log.w(TAG,"Unable to unregister iap2 receiver",e);
        }
        mStateMachine.doQuit();
        return true;
    }

    protected boolean cleanup() {
        if (mStateMachine != null) {
            mStateMachine.cleanup();
        }
        clearIap2Service();
        return true;
    }

    private final BroadcastReceiver mIap2Receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // process broadcasts here
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private static class BluetoothIap2Binder extends IBluetoothIAP2.Stub implements IProfileServiceBinder {
        private Iap2Service mService;

        public BluetoothIap2Binder(Iap2Service svc) {
            mService = svc;
        }
        public boolean cleanup() {
            mService = null;
            return true;
        }

        private Iap2Service getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"Iap2 call not allowed for non-active user");
                return null;
            }

            if (mService  != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        public boolean connect(BluetoothDevice device) {
            Iap2Service service = getService();
            if (service == null) return false;
            return service.connect(device);
        }

        public boolean disconnect(BluetoothDevice device) {
            Iap2Service service = getService();
            if (service == null) return false;
            return service.disconnect(device);
        }

        public List<BluetoothDevice> getConnectedDevices() {
            Iap2Service service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getConnectedDevices();
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            Iap2Service service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getDevicesMatchingConnectionStates(states);
        }

        public int getConnectionState(BluetoothDevice device) {
			if (DBG) Log.d(TAG, "Binder getConnectionState() called");
            Iap2Service service = getService();
            if (service == null) {
				if (DBG) Log.d(TAG, "Binder getConnectionState() service is null");
				return BluetoothProfile.STATE_DISCONNECTED;
			}
            return service.getConnectionState(device);
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            Iap2Service service = getService();
            if (service == null) return false;
            return service.setPriority(device, priority);
        }

        public int getPriority(BluetoothDevice device) {
            Iap2Service service = getService();
            if (service == null) return BluetoothProfile.PRIORITY_UNDEFINED;
            return service.getPriority(device);
        }

        public boolean sendData(BluetoothDevice device, int len, byte[] data) {
            Iap2Service service = getService();
            if (service == null) return false;
            if (service.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) return false;
            return service.sendData(device, len, data);
        }

        public ParcelFileDescriptor getSocket(BluetoothDevice device) {
			if (DBG) Log.d(TAG, "BluetoothIap2Binder getSocket() called");
            Iap2Service service = getService();
            if (service == null) {
				if (DBG) Log.d(TAG, "getSocket(): service is NULL");
				return null;
			}
            if (service.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
				if (DBG) Log.d(TAG, "getSocket(): device not connected");
				return null;
			}
            return service.getSocket(device);
        }

        public boolean acceptIncomingConnect(BluetoothDevice device) {
            Iap2Service service = getService();
            if (service == null) return false;
            return service.acceptIncomingConnect(device);
        }

        public boolean rejectIncomingConnect(BluetoothDevice device) {
            Iap2Service service = getService();
            if (service == null) return false;
            return service.rejectIncomingConnect(device);
        }

    };

    //API methods
    public static synchronized Iap2Service getIap2Service(){
        if (sIap2Service != null && sIap2Service.isAvailable()) {
            if (DBG) Log.d(TAG, "getIap2Service(): returning " + sIap2Service);
            return sIap2Service;
        }
        if (DBG)  {
            if (sIap2Service == null) {
                Log.d(TAG, "getIap2Service(): service is NULL");
            } else if (!(sIap2Service.isAvailable())) {
                Log.d(TAG,"getIap2Service(): service is not available");
            }
        }
        return null;
    }

    private static synchronized void setIap2Service(Iap2Service instance) {
        if (instance != null && instance.isAvailable()) {
            if (DBG) Log.d(TAG, "setIap2Service(): set to: " + sIap2Service);
            sIap2Service = instance;
        } else {
            if (DBG)  {
                if (sIap2Service == null) {
                    Log.d(TAG, "setIap2Service(): service not available");
                } else if (!sIap2Service.isAvailable()) {
                    Log.d(TAG,"setIap2Service(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearIap2Service() {
        sIap2Service = null;
    }

    public boolean connect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH ADMIN permission");

        if (getPriority(device) == BluetoothProfile.PRIORITY_OFF) {
            return false;
        }

        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState == BluetoothProfile.STATE_CONNECTED ||
            connectionState == BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        mStateMachine.sendMessage(Iap2StateMachine.CONNECT, device);
        return true;
    }

    boolean disconnect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH ADMIN permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
            connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        mStateMachine.sendMessage(Iap2StateMachine.DISCONNECT, device);
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getConnectedDevices();
    }

    private List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getDevicesMatchingConnectionStates(states);
    }

    int getConnectionState(BluetoothDevice device) {
		if (DBG) Log.d(TAG, "getConnectionState() called");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
		if (DBG) Log.d(TAG, "getConnectionState() returning " + mStateMachine.getConnectionState(device));
        return mStateMachine.getConnectionState(device);
    }

    public boolean setPriority(BluetoothDevice device, int priority) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH_ADMIN permission");
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH_ADMIN permission");
        int priority = BluetoothProfile.PRIORITY_UNDEFINED;
        return priority;
    }

    boolean sendData(BluetoothDevice device, int len, byte[] data) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        mStateMachine.sendMessage(Iap2StateMachine.SEND_DATA, data);
        return true;
    }

    ParcelFileDescriptor getSocket(BluetoothDevice device) {
		if (DBG) Log.d(TAG, "iap2 service getSocket() called");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
			if (DBG) Log.d(TAG, "iap2 service getSocket: device not connected");
            return null;
        }
        int serviceState = mStateMachine.getServiceState(device);
        if (serviceState != BluetoothIAP2.STATE_SERVICE_CONNECTED) {
			if (DBG) Log.d(TAG, "iap2 service getSocket: service not connected");
            return null;
        }

        FileDescriptor fd = mStateMachine.getFd();
        if (fd == null) {
			if (DBG) Log.d(TAG, "unable to get file descriptor");
            return null;
		}
        ParcelFileDescriptor pfd = null;
        try {
            // create a dup of the original file descriptor
            pfd = ParcelFileDescriptor.dup(fd);
            if (pfd == null) {
                Log.e(TAG, "unable to dup file descriptor");
            }
        } catch (IOException e) {
			Log.e(TAG, "excepion on dup file descriptor " + e.getMessage());
            return null;
        }
        return pfd;
    }

    void onServiceStateChanged(BluetoothDevice device, int newState, int prevState) {
        if (newState == BluetoothIAP2.STATE_SERVICE_CONNECTED) {
            /* UNCOMMENT THIS TO SEND 256 BYTES UPON EA SESSION CONNECT
            // ALK - autonomous test
            Log.d(TAG, "BluetoothIAP2.STATE_SERVICE_CONNECTED");
            ParcelFileDescriptor pfd = getSocket(device);
            if (pfd == null) {
                Log.e(TAG, "unable to get Parcel FD");
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            //FileDescriptor fd = mStateMachine.getFd();
            if (fd == null) {
                Log.d(TAG, "unable to get file descriptor");
            }
            if (!fd.valid()) {
                Log.d(TAG, "file descriptor is invalid");
            }
            FileOutputStream os = new FileOutputStream(fd);
            byte[] test_data = new byte[256];
            int i;
            for (i = 0; i < 256; i++)
                test_data[i] = (byte)(i & 0xFF);
            try {
                os.write(test_data);
            } catch (IOException e) {
                Log.e(TAG, "unable to write data");
            }
            */
        }
    }

    boolean acceptIncomingConnect(BluetoothDevice device) {
        // TODO(BT) remove it if stack does access control
        return false;
    }

    boolean rejectIncomingConnect(BluetoothDevice device) {
        // TODO(BT) remove it if stack does access control
        return false;
    }
}
