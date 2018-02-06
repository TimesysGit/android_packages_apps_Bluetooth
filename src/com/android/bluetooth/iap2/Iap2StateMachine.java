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

/**
 * Bluetooth IAP2 StateMachine
 *                      (Disconnected)
 *                           |    ^
 *                   CONNECT |    | DISCONNECTED
 *                           V    |
 *                         (Pending)
 *                           |    ^
 *                 CONNECTED |    | CONNECT
 *                           V    |
 *                        (Connected)
 */
package com.android.bluetooth.iap2;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothIAP2;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ActivityNotFoundException;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.FileDescriptor;

final class Iap2StateMachine extends StateMachine {
    private static final String TAG = "Iap2StateMachine";
    private static final boolean DBG = true;
    //For Debugging only
    private static int sRefCount=0;

    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    static final int SEND_DATA = 3;

    private static final int STACK_EVENT = 101;

    private static final int CONNECT_TIMEOUT = 201;

    private static final ParcelUuid[] IAP2_UUIDS = {
        BluetoothUuid.AppleIAP2,
    };

    private Disconnected mDisconnected;
    private Pending mPending;
    private Connected mConnected;

    private Iap2Service mService;
    private boolean mServiceConnected = false;
    private FileDescriptor mAppFd = null;

    private BluetoothAdapter mAdapter;
    private boolean mNativeAvailable;

    // mCurrentDevice is the device connected before the state changes
    // mTargetDevice is the device to be connected
    // mIncomingDevice is the device connecting to us, valid only in Pending state
    //                when mIncomingDevice is not null, both mCurrentDevice
    //                  and mTargetDevice are null
    //                when either mCurrentDevice or mTargetDevice is not null,
    //                  mIncomingDevice is null
    // Stable states
    //   No connection, Disconnected state
    //                  both mCurrentDevice and mTargetDevice are null
    //   Connected, Connected state
    //              mCurrentDevice is not null, mTargetDevice is null
    // Interim states
    //   Connecting to a device, Pending
    //                           mCurrentDevice is null, mTargetDevice is not null
    //   Disconnecting device, Connecting to new device
    //     Pending
    //     Both mCurrentDevice and mTargetDevice are not null
    //   Disconnecting device Pending
    //                        mCurrentDevice is not null, mTargetDevice is null
    //   Incoming connections Pending
    //                        Both mCurrentDevice and mTargetDevice are null
    private BluetoothDevice mCurrentDevice = null;
    private BluetoothDevice mTargetDevice = null;
    private BluetoothDevice mIncomingDevice = null;

    static {
        classInitNative();
    }

    private Iap2StateMachine(Iap2Service context) {
        super(TAG);
        Log.d(TAG, "constructing IAP2 State Machine");
        mService = context;

        mAdapter = BluetoothAdapter.getDefaultAdapter();

        initializeNative();
        mNativeAvailable=true;

        mDisconnected = new Disconnected();
        mPending = new Pending();
        mConnected = new Connected();

        addState(mDisconnected);
        addState(mPending);
        addState(mConnected);

        setInitialState(mDisconnected);
    }

    static Iap2StateMachine make(Iap2Service context) {
        Log.d(TAG, "make");
        Iap2StateMachine hssm = new Iap2StateMachine(context);
        hssm.start();
        return hssm;
    }


    public void doQuit() {
        quitNow();
    }

    public void cleanup() {
        if (mNativeAvailable) {
            cleanupNative();
            mNativeAvailable = false;
        }
    }

    private class Disconnected extends State {
        @Override
        public void enter() {
            log("Enter Disconnected: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("Disconnected process message: " + message.what);
            if (mCurrentDevice != null || mTargetDevice != null || mIncomingDevice != null) {
                Log.e(TAG, "ERROR: current, target, or mIncomingDevice not null in Disconnected");
                return NOT_HANDLED;
            }

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                   BluetoothProfile.STATE_DISCONNECTED);

                    if (!connectIap2Native(getByteAddress(device)) ) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        break;
                    }

                    synchronized (Iap2StateMachine.this) {
                        mTargetDevice = device;
                        transitionTo(mPending);
                    }
                    // TODO(BT) remove CONNECT_TIMEOUT when the stack
                    //          sends back events consistently
                    sendMessageDelayed(CONNECT_TIMEOUT, 30000);
                    break;
                case DISCONNECT:
                    // ignore
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        log("event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_SERVICE_STATE_CHANGED:
                            processServiceEvent(event.valueInt, event.device, event.valueFd);
                            break;
                        default:
                            Log.e(TAG, "Unexpected stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        @Override
        public void exit() {
            log("Exit Disconnected: " + getCurrentMessage().what);
        }

        // in Disconnected state
        private void processServiceEvent(int state, BluetoothDevice device, FileDescriptor fd) {
            switch(state) {
                case Iap2HalConstants.SERVICE_STATE_DISCONNECTED:
                    Log.w(TAG, "Ignore IAP2 SERVICE DISCONNECTED event, device: " + device);
                    break;
                case Iap2HalConstants.SERVICE_STATE_CONNECTED:
                    Log.e(TAG, "IAP2 SERVICE CONNECTED event while device is not connected, device: " + device);
                    break;
                default:
                    Log.e(TAG, "Incorrect service state: " + state);
                    break;
            }
        }

        // in Disconnected state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
            case Iap2HalConstants.CONNECTION_STATE_DISCONNECTED:
                Log.w(TAG, "Ignore IAP2 DISCONNECTED event, device: " + device);
                break;
            case Iap2HalConstants.CONNECTION_STATE_CONNECTING:
                if (okToConnect(device)){
                    Log.i(TAG,"Incoming Iap2 accepted");
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                             BluetoothProfile.STATE_DISCONNECTED);
                    synchronized (Iap2StateMachine.this) {
                        mIncomingDevice = device;
                        transitionTo(mPending);
                    }
                } else {
                    Log.i(TAG,"Incoming Iap2 rejected. priority=" + mService.getPriority(device)+
                              " bondState=" + device.getBondState());
                    //reject the connection and stay in Disconnected state itself
                    disconnectIap2Native(getByteAddress(device));
                    // the other profile connection should be initiated
                    AdapterService adapterService = AdapterService.getAdapterService();
                    if ( adapterService != null) {
                        adapterService.connectOtherProfile(device,
                                                           AdapterService.PROFILE_CONN_REJECTED);
                    }
                }
                break;
            case Iap2HalConstants.CONNECTION_STATE_CONNECTED:
                Log.w(TAG, "Iap2 Connected from Disconnected state");
                if (okToConnect(device)){
                    Log.i(TAG,"Incoming Iap2 accepted");
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_DISCONNECTED);
                    synchronized (Iap2StateMachine.this) {
                        mCurrentDevice = device;
                        transitionTo(mConnected);
                    }
                } else {
                    //reject the connection and stay in Disconnected state itself
                    Log.i(TAG,"Incoming iDevice rejected. priority=" + mService.getPriority(device) +
                              " bondState=" + device.getBondState());
                    disconnectIap2Native(getByteAddress(device));
                }
                break;
            case Iap2HalConstants.CONNECTION_STATE_DISCONNECTING:
                Log.w(TAG, "Ignore Iap2 DISCONNECTING event, device: " + device);
                break;
            default:
                Log.e(TAG, "Incorrect state: " + state);
                break;
            }
        }
    }

    private class Pending extends State {
        @Override
        public void enter() {
            log("Enter Pending: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("Pending process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                    deferMessage(message);
                    break;
                case CONNECT_TIMEOUT:
                    onConnectionStateChanged(Iap2HalConstants.CONNECTION_STATE_DISCONNECTED,
                                             getByteAddress(mTargetDevice));
                    break;
                case DISCONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mCurrentDevice != null && mTargetDevice != null &&
                        mTargetDevice.equals(device) ) {
                        // cancel connection to the mTargetDevice
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        synchronized (Iap2StateMachine.this) {
                            mTargetDevice = null;
                        }
                    } else {
                        deferMessage(message);
                    }
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        log("event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            removeMessages(CONNECT_TIMEOUT);
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_SERVICE_STATE_CHANGED:
                            processServiceEvent(event.valueInt, event.device, event.valueFd);
                            break;
                        default:
                            Log.e(TAG, "Unexpected event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        // in Pending state
        private void processServiceEvent(int state, BluetoothDevice device, FileDescriptor fd) {
            switch(state) {
                case Iap2HalConstants.SERVICE_STATE_DISCONNECTED:
                    Log.w(TAG, "Ignore IAP2 SERVICE DISCONNECTED event, device: " + device);
                    break;
                case Iap2HalConstants.SERVICE_STATE_CONNECTED:
                    Log.e(TAG, "IAP2 SERVICE CONNECTED event while device is not connected, device: " + device);
                    break;
                default:
                    Log.e(TAG, "Incorrect service state: " + state);
                    break;
            }
        }

        // in Pending state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case Iap2HalConstants.CONNECTION_STATE_DISCONNECTED:
                    if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                        broadcastConnectionState(mCurrentDevice,
                                                 BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_DISCONNECTING);
                        synchronized (Iap2StateMachine.this) {
                            mCurrentDevice = null;
                        }

                        if (mTargetDevice != null) {
                            if (!connectIap2Native(getByteAddress(mTargetDevice))) {
                                broadcastConnectionState(mTargetDevice,
                                                         BluetoothProfile.STATE_DISCONNECTED,
                                                         BluetoothProfile.STATE_CONNECTING);
                                synchronized (Iap2StateMachine.this) {
                                    mTargetDevice = null;
                                    transitionTo(mDisconnected);
                                }
                            }
                        } else {
                            synchronized (Iap2StateMachine.this) {
                                mIncomingDevice = null;
                                transitionTo(mDisconnected);
                            }
                        }
                    } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        // outgoing connection failed
                        broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTING);
                        synchronized (Iap2StateMachine.this) {
                            mTargetDevice = null;
                            transitionTo(mDisconnected);
                        }
                    } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                        broadcastConnectionState(mIncomingDevice,
                                                 BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTING);
                        synchronized (Iap2StateMachine.this) {
                            mIncomingDevice = null;
                            transitionTo(mDisconnected);
                        }
                    } else {
                        Log.e(TAG, "Unknown device Disconnected: " + device);
                    }
                    break;
            case Iap2HalConstants.CONNECTION_STATE_CONNECTED:
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    // disconnection failed
                    broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_DISCONNECTING);
                    if (mTargetDevice != null) {
                        broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTING);
                    }
                    synchronized (Iap2StateMachine.this) {
                        mTargetDevice = null;
                        transitionTo(mConnected);
                    }
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_CONNECTING);
                    synchronized (Iap2StateMachine.this) {
                        mCurrentDevice = mTargetDevice;
                        mTargetDevice = null;
                        transitionTo(mConnected);
                    }
                } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    broadcastConnectionState(mIncomingDevice, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_CONNECTING);
                    synchronized (Iap2StateMachine.this) {
                        mCurrentDevice = mIncomingDevice;
                        mIncomingDevice = null;
                        transitionTo(mConnected);
                    }
                } else {
                    Log.e(TAG, "Unknown device Connected: " + device);
                    // something is wrong here, but sync our state with stack
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_DISCONNECTED);
                    synchronized (Iap2StateMachine.this) {
                        mCurrentDevice = device;
                        mTargetDevice = null;
                        mIncomingDevice = null;
                        transitionTo(mConnected);
                    }
                }
                break;
            case Iap2HalConstants.CONNECTION_STATE_CONNECTING:
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    log("current device tries to connect back");
                    // TODO(BT) ignore or reject
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    // The stack is connecting to target device or
                    // there is an incoming connection from the target device at the same time
                    // we already broadcasted the intent, doing nothing here
                    if (DBG) {
                        log("Stack and target device are connecting");
                    }
                }
                else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    Log.e(TAG, "Another connecting event on the incoming device");
                } else {
                    // We get an incoming connecting request while Pending
                    // TODO(BT) is stack handing this case? let's ignore it for now
                    log("Incoming connection while pending, ignore");
                }
                break;
            case Iap2HalConstants.CONNECTION_STATE_DISCONNECTING:
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    // we already broadcasted the intent, doing nothing here
                    if (DBG) {
                        log("stack is disconnecting mCurrentDevice");
                    }
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    Log.e(TAG, "TargetDevice is getting disconnected");
                } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    Log.e(TAG, "IncomingDevice is getting disconnected");
                } else {
                    Log.e(TAG, "Disconnecting unknow device: " + device);
                }
                break;
            default:
                Log.e(TAG, "Incorrect state: " + state);
                break;
            }
        }

    }

    private class Connected extends State {
        @Override
        public void enter() {
            log("Enter Connected: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("Connected process message: " + message.what);
            if (DBG) {
                if (mCurrentDevice == null) {
                    log("ERROR: mCurrentDevice is null in Connected");
                    return NOT_HANDLED;
                }
            }

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mCurrentDevice.equals(device)) {
                        break;
                    }

                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                   BluetoothProfile.STATE_DISCONNECTED);
                    if (!disconnectIap2Native(getByteAddress(mCurrentDevice))) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        break;
                    }

                    synchronized (Iap2StateMachine.this) {
                        mTargetDevice = device;
                        transitionTo(mPending);
                    }
                }
                    break;
                case DISCONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mCurrentDevice.equals(device)) {
                        break;
                    }

                    // Service-disconnect message not currently sent up, make it up here
                    if (getServiceState(device) == BluetoothIAP2.STATE_SERVICE_CONNECTED) {
                        Log.w(TAG, "synthesizing a service-disconnect broadcast");
                        broadcastServiceState(device, BluetoothIAP2.STATE_SERVICE_DISCONNECTED,
                                              BluetoothIAP2.STATE_SERVICE_CONNECTED);
                    }

                    broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTING,
                                   BluetoothProfile.STATE_CONNECTED);
                    if (!disconnectIap2Native(getByteAddress(device))) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                       BluetoothProfile.STATE_DISCONNECTED);
                        break;
                    }
                    transitionTo(mPending);
                }
                    break;
                case SEND_DATA:
                    byte[] buf = (byte[]) message.obj;
                    processSendDataEvent(buf);
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        log("event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_SERVICE_STATE_CHANGED:
                            processServiceEvent(event.valueInt, event.device, event.valueFd);
                            break;
                        case EVENT_TYPE_DATA:
                            processDataRxEvent(event.valueByteArray);
                            break;
                        default:
                            Log.e(TAG, "Unknown stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        private void processDataRxEvent(byte[] data) {
            Log.d(TAG, "processDataRxEvent called with data len " + data.length);
        }

        // in Connected state
        private void processServiceEvent(int state, BluetoothDevice device, FileDescriptor fd) {
            switch(state) {
                case Iap2HalConstants.SERVICE_STATE_DISCONNECTED:
                    mServiceConnected = false;
                    mAppFd = null;
                    if (mCurrentDevice.equals(device)) {
                        broadcastServiceState(mCurrentDevice, BluetoothIAP2.STATE_SERVICE_DISCONNECTED,
                                                 BluetoothIAP2.STATE_SERVICE_CONNECTED);
                    }
                    break;
                case Iap2HalConstants.SERVICE_STATE_CONNECTED:
                    mServiceConnected = true;
                    mAppFd = fd;
                    if (mCurrentDevice.equals(device)) {
                        broadcastServiceState(mCurrentDevice, BluetoothIAP2.STATE_SERVICE_CONNECTED,
                                                 BluetoothIAP2.STATE_SERVICE_DISCONNECTED);
                    }
                    break;
                default:
                    Log.e(TAG, "Incorrect service state: " + state);
                    break;
            }
        }

        // in Connected state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case Iap2HalConstants.CONNECTION_STATE_DISCONNECTED:
                    if (mCurrentDevice.equals(device)) {
                        broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTED);
                        synchronized (Iap2StateMachine.this) {
                            mCurrentDevice = null;
                            transitionTo(mDisconnected);
                        }
                    } else {
                        Log.e(TAG, "Disconnected from unknown device: " + device);
                    }
                    break;
              default:
                  Log.e(TAG, "Connection State Device: " + device + " bad state: " + state);
                  break;
            }
        }

    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
        }

        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
        }
    };

    FileDescriptor getFd() {
        return mAppFd;
    }

    int getServiceState(BluetoothDevice device) {
        IState currentState = getCurrentState();
        if (currentState == mDisconnected || currentState == mPending) {
            return BluetoothIAP2.STATE_SERVICE_DISCONNECTED;
        }
        if (mCurrentDevice.equals(device)) {
            return mServiceConnected ? BluetoothIAP2.STATE_SERVICE_CONNECTED : BluetoothIAP2.STATE_SERVICE_DISCONNECTED;
        }
        return BluetoothIAP2.STATE_SERVICE_DISCONNECTED;
    }

    // Iap2 Connection state of the device could be changed by the state machine
    // in separate thread while this method is executing.
    int getConnectionState(BluetoothDevice device) {
        if (getCurrentState() == mDisconnected) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }

        synchronized (this) {
            IState currentState = getCurrentState();
            if (currentState == mPending) {
                if ((mTargetDevice != null) && mTargetDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING;
                }
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    return BluetoothProfile.STATE_DISCONNECTING;
                }
                if ((mIncomingDevice != null) && mIncomingDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING; // incoming connection
                }
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            if (currentState == mConnected) {
                if (mCurrentDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTED;
                }
                return BluetoothProfile.STATE_DISCONNECTED;
            } else {
                Log.e(TAG, "Bad currentState: " + currentState);
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
    }

    List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized(this) {
            if (isConnected()) {
                devices.add(mCurrentDevice);
            }
        }
        return devices;
    }

    private void processSendDataEvent(byte[] buf)
    {
        // ALK - TODO implement
        Log.d(TAG, "processSendDataEvent called with len " + buf.length);
        sendDataNative(buf.length, buf);
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        int connectionState;
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                ParcelUuid[] featureUuids = device.getUuids();
                if (!BluetoothUuid.containsAnyUuid(featureUuids, IAP2_UUIDS)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for(int i = 0; i < states.length; i++) {
                    if (connectionState == states[i]) {
                        deviceList.add(device);
                    }
                }
            }
        }
        return deviceList;
    }

    // This method does not check for error conditon (newState == prevState)
    private void broadcastConnectionState(BluetoothDevice device, int newState, int prevState) {
        log("Connection state " + device + ": " + prevState + "->" + newState);
        if(prevState == BluetoothProfile.STATE_CONNECTED) {
        }

        /* Notifying the connection state change of the profile before sending the intent for
           connection state change, as it was causing a race condition, with the UI not being
           updated with the correct connection state. */
        mService.notifyProfileConnectionStateChanged(device, BluetoothProfile.IAP2,
                                                     newState, prevState);
        Intent intent = new Intent(BluetoothIAP2.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(intent, Iap2Service.BLUETOOTH_PERM);
    }

    // This method does not check for error conditon (newState == prevState)
    private void broadcastServiceState(BluetoothDevice device, int newState, int prevState) {
        log("Service state " + device + ": " + prevState + "->" + newState);
        if(prevState == BluetoothIAP2.STATE_SERVICE_CONNECTED) {
        }

        mService.onServiceStateChanged(device, newState, prevState);
        Intent intent = new Intent(BluetoothIAP2.ACTION_SERVICE_STATE_CHANGED);
        intent.putExtra(BluetoothIAP2.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothIAP2.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(intent, Iap2Service.BLUETOOTH_PERM);
    }


    private void onConnectionStateChanged(int state, byte[] address) {
        StackEvent event = new StackEvent(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt = state;
        event.device = getDevice(address);
        sendMessage(STACK_EVENT, event);
    }

    private void onServiceStateChanged(int state, byte[] address, FileDescriptor fd) {
        StackEvent event = new StackEvent(EVENT_TYPE_SERVICE_STATE_CHANGED);
        event.valueInt = state;
        event.valueFd = fd;
        event.device = getDevice(address);
        sendMessage(STACK_EVENT, event);
    }

    private void onDataRx(int len, byte[] data) {
        StackEvent event = new StackEvent(EVENT_TYPE_DATA);
        event.valueInt = len;
        event.valueByteArray = data;
        sendMessage(STACK_EVENT, event);
    }

    private void onError(int code, String s) {
    }

    private String getCurrentDeviceName() {
        String defaultName = "<unknown>";
        if (mCurrentDevice == null) {
            return defaultName;
        }
        String deviceName = mCurrentDevice.getName();
        if (deviceName == null) {
            return defaultName;
        }
        return deviceName;
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    boolean isConnected() {
        IState currentState = getCurrentState();
        return (currentState == mConnected);
    }

    boolean okToConnect(BluetoothDevice device) {
        AdapterService adapterService = AdapterService.getAdapterService();
        int priority = mService.getPriority(device);
        boolean ret = false;
        //check if this is an incoming connection in Quiet mode.
        if((adapterService == null) ||
           ((adapterService.isQuietModeEnabled() == true) &&
           (mTargetDevice == null))){
            ret = false;
        }
        // check priority and accept or reject the connection. if priority is undefined
        // it is likely that our SDP has not completed and peer is initiating the
        // connection. Allow this connection, provided the device is bonded
        else if((BluetoothProfile.PRIORITY_OFF < priority) ||
                ((BluetoothProfile.PRIORITY_UNDEFINED == priority) &&
                (device.getBondState() != BluetoothDevice.BOND_NONE))){
            ret= true;
        }
        return ret;
    }

    @Override
    protected void log(String msg) {
        if (DBG) {
            super.log(msg);
        }
    }

    public void handleAccessPermissionResult(Intent intent) {
        log("handleAccessPermissionResult");
    }


    // Event types for STACK_EVENT message
    final private static int EVENT_TYPE_NONE = 0;
    final private static int EVENT_TYPE_CONNECTION_STATE_CHANGED = 1;
    final private static int EVENT_TYPE_SERVICE_STATE_CHANGED = 2;
    final private static int EVENT_TYPE_DATA = 3;

    private class StackEvent {
        int type = EVENT_TYPE_NONE;
        int valueInt = 0;
        int valueInt2 = 0;
        String valueString = null;
        byte[] valueByteArray = null;
        FileDescriptor valueFd = null;
        BluetoothDevice device = null;

        private StackEvent(int type) {
            this.type = type;
        }
    }

    private native static void classInitNative();
    private native void initializeNative();
    private native void cleanupNative();
    private native boolean connectIap2Native(byte[] address);
    private native boolean disconnectIap2Native(byte[] address);
    private native boolean sendDataNative(int len, byte[] data);
}
