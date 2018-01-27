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

#define LOG_TAG "BluetoothIap2ServiceJni"

#define LOG_NDEBUG 0

#define CHECK_CALLBACK_ENV                                                      \
   if (!checkCallbackThread()) {                                                \
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);\
       return;                                                                  \
   }

#include "com_android_bluetooth.h"
#include "hardware/bt_iap2.h"
#include "utils/Log.h"
#include "android_runtime/AndroidRuntime.h"

#include <string.h>

namespace android {

static jmethodID method_onConnectionStateChanged;
static jmethodID method_onServiceStateChanged;
static jmethodID method_onDataRx;
static jmethodID method_onError;

static const btiap2_interface_t *sBluetoothIap2Interface = NULL;
static jobject mCallbacksObj = NULL;
static JNIEnv *sCallbackEnv = NULL;

static bool checkCallbackThread() {
    // Always fetch the latest callbackEnv from AdapterService.
    // Caching this could cause this sCallbackEnv to go out-of-sync
    // with the AdapterService's ENV if an ASSOCIATE/DISASSOCIATE event
    // is received
    //if (sCallbackEnv == NULL) {
    sCallbackEnv = getCallbackEnv();
    //}
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (sCallbackEnv != env || sCallbackEnv == NULL) return false;
    return true;
}

static void connection_state_callback(btiap2_connection_state_t state, bt_bdaddr_t* bd_addr) {
    jbyteArray addr;

    ALOGI("%s", __FUNCTION__);

    CHECK_CALLBACK_ENV
    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (!addr) {
        ALOGE("Fail to new jbyteArray bd addr for connection state");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged,
                                 (jint) state, addr);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static void service_state_callback(btiap2_service_state_t state, bt_bdaddr_t* bd_addr, int fd) {
    jbyteArray addr;
    jobject fileDescriptor = NULL;

    ALOGI("%s", __FUNCTION__);

    CHECK_CALLBACK_ENV
    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (!addr) {
        ALOGE("Fail to new jbyteArray bd addr for connection state");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    if (state == BTIAP2_SERVICE_STATE_CONNECTED) {
        fileDescriptor = jniCreateFileDescriptor(sCallbackEnv, fd);
        if (!fileDescriptor) {
            ALOGE("Failed to convert file descriptor, fd: %d", fd);
            sCallbackEnv->DeleteLocalRef(addr);
            return;
        }
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceStateChanged,
                                 (jint) state, addr, fileDescriptor);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static void data_callback(unsigned int len, unsigned char *data) {
    jbyteArray buf;

    CHECK_CALLBACK_ENV
    buf = sCallbackEnv->NewByteArray(len);
    if (!buf) {
        ALOGE("Fail to new jbyteArray buf for data callback");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(buf, 0, len, (jbyte *) data);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onDataRx, (jint) len, buf);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(buf);
}


static void error_callback(btiap2_error_t error_code, char *error_string) {
    CHECK_CALLBACK_ENV
    jstring js_error_string = sCallbackEnv->NewStringUTF(error_string);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onError,
                                 (jint) error_code,
                                 js_error_string);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(js_error_string);
}

static btiap2_callbacks_t sBluetoothIap2Callbacks = {
    sizeof(sBluetoothIap2Callbacks),
    connection_state_callback,
    service_state_callback,
    data_callback,
    error_callback,
};

static void classInitNative(JNIEnv* env, jclass clazz) {
    int err;

    method_onConnectionStateChanged =
        env->GetMethodID(clazz, "onConnectionStateChanged", "(I[B)V");
    method_onServiceStateChanged =
        env->GetMethodID(clazz, "onServiceStateChanged", "(I[BLjava/io/FileDescriptor;)V");
    method_onDataRx = env->GetMethodID(clazz, "onDataRx", "(I[B)V");
    method_onError = env->GetMethodID(clazz, "onError", "(ILjava/lang/String;)V");

    ALOGI("%s: succeeds", __FUNCTION__);
}

static void initializeNative(JNIEnv *env, jobject object) {
    const bt_interface_t* btInf;
    bt_status_t status;

    if ( (btInf = getBluetoothInterface()) == NULL) {
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    if (sBluetoothIap2Interface !=NULL) {
        ALOGW("Cleaning up Bluetooth IAP2 Interface before initializing...");
        sBluetoothIap2Interface->cleanup();
        sBluetoothIap2Interface = NULL;
    }

    if (mCallbacksObj != NULL) {
        ALOGW("Cleaning up Bluetooth IAP2 callback object");
        env->DeleteGlobalRef(mCallbacksObj);
        mCallbacksObj = NULL;
    }

    if ( (sBluetoothIap2Interface = (btiap2_interface_t *)
          btInf->get_profile_interface(BT_PROFILE_IAP2_ID)) == NULL) {
        ALOGE("Failed to get Bluetooth IAP2 Interface");
        return;
    }

    if ( (status = sBluetoothIap2Interface->init(&sBluetoothIap2Callbacks)) != BT_STATUS_SUCCESS) {
        ALOGE("Failed to initialize Bluetooth IAP2, status: %d", status);
        sBluetoothIap2Interface = NULL;
        return;
    }

    mCallbacksObj = env->NewGlobalRef(object);
}

static void cleanupNative(JNIEnv *env, jobject object) {
    const bt_interface_t* btInf;
    bt_status_t status;

    if ( (btInf = getBluetoothInterface()) == NULL) {
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    if (sBluetoothIap2Interface !=NULL) {
        ALOGW("Cleaning up Bluetooth IAP2 Interface...");
        sBluetoothIap2Interface->cleanup();
        sBluetoothIap2Interface = NULL;
    }

    if (mCallbacksObj != NULL) {
        ALOGW("Cleaning up Bluetooth Handsfree callback object");
        env->DeleteGlobalRef(mCallbacksObj);
        mCallbacksObj = NULL;
    }
}

static jboolean connectIap2Native(JNIEnv *env, jobject object, jbyteArray address) {
    jbyte *addr;
    bt_status_t status;

    ALOGI("%s: sBluetoothIap2Interface: %p", __FUNCTION__, sBluetoothIap2Interface);
    if (!sBluetoothIap2Interface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (!addr) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    if ((status = sBluetoothIap2Interface->connect((bt_bdaddr_t *)addr)) != BT_STATUS_SUCCESS) {
        ALOGE("Failed IAP2 connection, status: %d", status);
    }
    env->ReleaseByteArrayElements(address, addr, 0);
    return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean disconnectIap2Native(JNIEnv *env, jobject object, jbyteArray address) {
    jbyte *addr;
    bt_status_t status;

    if (!sBluetoothIap2Interface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (!addr) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    if ( (status = sBluetoothIap2Interface->disconnect((bt_bdaddr_t *)addr)) != BT_STATUS_SUCCESS) {
        ALOGE("Failed IAP2 disconnection, status: %d", status);
    }
    env->ReleaseByteArrayElements(address, addr, 0);
    return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean sendDataNative(JNIEnv *env, jobject object, jint len, jbyteArray data) {
    jbyte *buf;
    const jchar *name;
    bt_status_t status;

    if (!sBluetoothIap2Interface) return JNI_FALSE;

    buf = env->GetByteArrayElements(data, NULL);
    if (!buf) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    if ( (status = sBluetoothIap2Interface->send_data(len, (unsigned char *)buf)) != BT_STATUS_SUCCESS) {
        ALOGE("Failed IAP2 send data, status: %d", status);
    }
    env->ReleaseByteArrayElements(data, buf, 0);
    return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void *) classInitNative},
    {"initializeNative", "()V", (void *) initializeNative},
    {"cleanupNative", "()V", (void *) cleanupNative},
    {"connectIap2Native", "([B)Z", (void *) connectIap2Native},
    {"disconnectIap2Native", "([B)Z", (void *) disconnectIap2Native},
    {"sendDataNative", "(I[B)Z", (void *) sendDataNative},
};

int register_com_android_bluetooth_iap2(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/bluetooth/iap2/Iap2StateMachine",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
