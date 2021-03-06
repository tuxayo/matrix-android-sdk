/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.crypto.internal;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.jetbrains.annotations.NotNull;
import org.matrix.androidsdk.HomeServerConnectionConfig;
import org.matrix.androidsdk.core.JsonUtility;
import org.matrix.androidsdk.core.Log;
import org.matrix.androidsdk.core.StringUtilsKt;
import org.matrix.androidsdk.core.callback.ApiCallback;
import org.matrix.androidsdk.core.callback.SimpleApiCallback;
import org.matrix.androidsdk.core.listeners.IMXNetworkEventListener;
import org.matrix.androidsdk.core.listeners.ProgressListener;
import org.matrix.androidsdk.core.model.MatrixError;
import org.matrix.androidsdk.crypto.CryptoConstantsKt;
import org.matrix.androidsdk.crypto.IncomingRoomKeyRequest;
import org.matrix.androidsdk.crypto.IncomingRoomKeyRequestCancellation;
import org.matrix.androidsdk.crypto.MXCrypto;
import org.matrix.androidsdk.crypto.MXCryptoAlgorithms;
import org.matrix.androidsdk.crypto.MXCryptoConfig;
import org.matrix.androidsdk.crypto.MXCryptoError;
import org.matrix.androidsdk.crypto.MXDecryptionException;
import org.matrix.androidsdk.crypto.MXDeviceList;
import org.matrix.androidsdk.crypto.MXEventDecryptionResult;
import org.matrix.androidsdk.crypto.MXMegolmExportEncryption;
import org.matrix.androidsdk.crypto.MXOlmDevice;
import org.matrix.androidsdk.crypto.MXOutgoingRoomKeyRequestManager;
import org.matrix.androidsdk.crypto.MegolmSessionData;
import org.matrix.androidsdk.crypto.RoomKeysRequestListener;
import org.matrix.androidsdk.crypto.algorithms.IMXDecrypting;
import org.matrix.androidsdk.crypto.algorithms.IMXEncrypting;
import org.matrix.androidsdk.crypto.cryptostore.IMXCryptoStore;
import org.matrix.androidsdk.crypto.data.ImportRoomKeysResult;
import org.matrix.androidsdk.crypto.data.MXDeviceInfo;
import org.matrix.androidsdk.crypto.data.MXEncryptEventContentResult;
import org.matrix.androidsdk.crypto.data.MXKey;
import org.matrix.androidsdk.crypto.data.MXOlmInboundGroupSession2;
import org.matrix.androidsdk.crypto.data.MXOlmSessionResult;
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap;
import org.matrix.androidsdk.crypto.interfaces.CryptoEvent;
import org.matrix.androidsdk.crypto.interfaces.CryptoEventContent;
import org.matrix.androidsdk.crypto.interfaces.CryptoEventListener;
import org.matrix.androidsdk.crypto.interfaces.CryptoRoom;
import org.matrix.androidsdk.crypto.interfaces.CryptoRoomMember;
import org.matrix.androidsdk.crypto.interfaces.CryptoRoomState;
import org.matrix.androidsdk.crypto.interfaces.CryptoSession;
import org.matrix.androidsdk.crypto.interfaces.CryptoSyncResponse;
import org.matrix.androidsdk.crypto.keysbackup.KeysBackup;
import org.matrix.androidsdk.crypto.model.crypto.KeysUploadResponse;
import org.matrix.androidsdk.crypto.model.crypto.RoomKeyContent;
import org.matrix.androidsdk.crypto.model.crypto.RoomKeyRequestBody;
import org.matrix.androidsdk.crypto.rest.CryptoRestClient;
import org.matrix.androidsdk.crypto.rest.model.crypto.EncryptedMessage;
import org.matrix.androidsdk.crypto.rest.model.crypto.RoomKeyShare;
import org.matrix.androidsdk.crypto.verification.VerificationManager;
import org.matrix.androidsdk.network.NetworkConnectivityReceiver;
import org.matrix.olm.OlmAccount;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * A `MXCrypto` class instance manages the end-to-end crypto for a MXSession instance.
 * <p>
 * Messages posted by the user are automatically redirected to MXCrypto in order to be encrypted
 * before sending.
 * In the other hand, received events goes through MXCrypto for decrypting.
 * MXCrypto maintains all necessary keys and their sharing with other devices required for the crypto.
 * Specially, it tracks all room membership changes events in order to do keys updates.
 */
public class MXCryptoImpl implements MXCrypto {
    private static final String LOG_TAG = MXCryptoImpl.class.getSimpleName();

    // max number of keys to upload at once
    // Creating keys can be an expensive operation so we limit the
    // number we generate in one go to avoid blocking the application
    // for too long.
    private static final int ONE_TIME_KEY_GENERATION_MAX_NUMBER = 5;

    // frequency with which to check & upload one-time keys
    private static final long ONE_TIME_KEY_UPLOAD_PERIOD = 60 * 1000; // one minute

    // The Matrix session.
    private final CryptoSession mSession;

    // the crypto store
    public IMXCryptoStore mCryptoStore;

    // MXEncrypting instance for each room.
    private final Map<String, IMXEncrypting> mRoomEncryptors;

    // A map from algorithm to MXDecrypting instance, for each room
    private final Map<String, /* room id */
            Map<String /* algorithm */, IMXDecrypting>> mRoomDecryptors;

    // Our device keys
    private MXDeviceInfo mMyDevice;

    // The libolm wrapper. Null if the Crypto has been closed
    @Nullable
    private MXOlmDevice mOlmDevice;

    private Map<String, Map<String, String>> mLastPublishedOneTimeKeys;

    // the encryption is starting
    private boolean mIsStarting;

    // tell if the crypto is started
    private boolean mIsStarted;

    // the crypto background threads
    private HandlerThread mEncryptingHandlerThread;
    private Handler mEncryptingHandler = null;

    private HandlerThread mDecryptingHandlerThread;
    private Handler mDecryptingHandler = null;

    // the UI thread
    private Handler mUIHandler;

    private NetworkConnectivityReceiver mNetworkConnectivityReceiver;

    private Integer mOneTimeKeyCount;

    private final MXDeviceList mDevicesList;

    private final CryptoRestClient mCryptoRestClient;

    private final MXOutgoingRoomKeyRequestManager mOutgoingRoomKeyRequestManager;

    private final IMXNetworkEventListener mNetworkListener = new IMXNetworkEventListener() {
        @Override
        public void onNetworkConnectionUpdate(boolean isConnected) {
            if (isConnected && !isStarted()) {
                Log.d(LOG_TAG, "Start MXCrypto because a network connection has been retrieved ");
                start(false, null);
            }
        }
    };

    private final CryptoEventListener mEventListener = new CryptoEventListener() {
        /*
         * Warning, if a method is added here, the corresponding call has to be also added in MxEventDispatcher
         */

        @Override
        public void onToDeviceEvent(CryptoEvent event) {
            MXCryptoImpl.this.onToDeviceEvent(event);
        }

        @Override
        public void onLiveEvent(CryptoEvent event, CryptoRoomState roomState) {
            if (TextUtils.equals(event.getType(), CryptoEvent.EVENT_TYPE_MESSAGE_ENCRYPTION)) {
                onCryptoEvent(event);
            } else if (TextUtils.equals(event.getType(), CryptoEvent.EVENT_TYPE_STATE_ROOM_MEMBER)) {
                onRoomMembership(event);
            }
        }
    };

    // initialization callbacks
    private final List<ApiCallback<Void>> mInitializationCallbacks = new ArrayList<>();

    // Warn the user if some new devices are detected while encrypting a message.
    private boolean mWarnOnUnknownDevices = true;

    // tell if there is a OTK check in progress
    private boolean mOneTimeKeyCheckInProgress = false;

    // last OTK check timestamp
    private long mLastOneTimeKeyCheck = 0;

    // list of IncomingRoomKeyRequests/IncomingRoomKeyRequestCancellations
    // we received in the current sync.
    private final List<IncomingRoomKeyRequest> mReceivedRoomKeyRequests = new ArrayList<>();
    private final List<IncomingRoomKeyRequest> mReceivedRoomKeyRequestCancellations = new ArrayList<>();

    // Set of parameters used to configure/customize the end-to-end crypto.
    private MXCryptoConfig mCryptoConfig;

    // The key backup manager.
    private final KeysBackup mKeysBackup;

    // The SAS verification manager.
    private final VerificationManager mShortCodeVerificationManager;

    /**
     * Constructor
     *
     * @param matrixSession the session
     * @param cryptoStore   the crypto store
     * @param cryptoConfig  the optional set of parameters used to configure the e2e encryption.
     */
    public MXCryptoImpl(@NonNull CryptoSession matrixSession,
                        @NonNull IMXCryptoStore cryptoStore,
                        @Nullable MXCryptoConfig cryptoConfig,
                        HomeServerConnectionConfig homeServerConnectionConfig) {
        mSession = matrixSession;
        mCryptoStore = cryptoStore;

        mCryptoRestClient = new CryptoRestClient(homeServerConnectionConfig);

        if (null != cryptoConfig) {
            mCryptoConfig = cryptoConfig;
        } else {
            // Consider the default configuration value
            mCryptoConfig = new MXCryptoConfig();
        }

        mOlmDevice = new MXOlmDevice(mCryptoStore);
        mRoomEncryptors = new HashMap<>();
        mRoomDecryptors = new HashMap<>();

        String deviceId = mSession.getDeviceId();
        // deviceId should always be defined
        boolean refreshDevicesList = !TextUtils.isEmpty(deviceId);

        if (TextUtils.isEmpty(deviceId)) {
            // use the stored one
            deviceId = mCryptoStore.getDeviceId();
            mSession.setDeviceId(deviceId);
        }

        if (TextUtils.isEmpty(deviceId)) {
            deviceId = UUID.randomUUID().toString();
            mSession.setDeviceId(deviceId);
            Log.d(LOG_TAG, "Warning: No device id in MXCredentials. An id was created. Think of storing it");
            mCryptoStore.storeDeviceId(deviceId);
        }

        mMyDevice = new MXDeviceInfo(deviceId);
        mMyDevice.userId = mSession.getMyUserId();

        mDevicesList = new MXDeviceList(matrixSession, this);

        Map<String, String> keys = new HashMap<>();

        if (!TextUtils.isEmpty(mOlmDevice.getDeviceEd25519Key())) {
            keys.put("ed25519:" + mSession.getDeviceId(), mOlmDevice.getDeviceEd25519Key());
        }

        if (!TextUtils.isEmpty(mOlmDevice.getDeviceCurve25519Key())) {
            keys.put("curve25519:" + mSession.getDeviceId(), mOlmDevice.getDeviceCurve25519Key());
        }

        mMyDevice.keys = keys;

        mMyDevice.algorithms = MXCryptoAlgorithms.sharedAlgorithms().supportedAlgorithms();
        mMyDevice.mVerified = MXDeviceInfo.DEVICE_VERIFICATION_VERIFIED;

        // Add our own deviceinfo to the store
        Map<String, MXDeviceInfo> endToEndDevicesForUser = mCryptoStore.getUserDevices(mSession.getMyUserId());

        Map<String, MXDeviceInfo> myDevices;

        if (null != endToEndDevicesForUser) {
            myDevices = new HashMap<>(endToEndDevicesForUser);
        } else {
            myDevices = new HashMap<>();
        }

        myDevices.put(mMyDevice.deviceId, mMyDevice);

        mCryptoStore.storeUserDevices(mSession.getMyUserId(), myDevices);
        mSession.getDataHandler().setCryptoEventsListener(mEventListener);

        mEncryptingHandlerThread = new HandlerThread("MXCrypto_encrypting_" + mSession.getMyUserId(), Thread.MIN_PRIORITY);
        mEncryptingHandlerThread.start();

        mDecryptingHandlerThread = new HandlerThread("MXCrypto_decrypting_" + mSession.getMyUserId(), Thread.MIN_PRIORITY);
        mDecryptingHandlerThread.start();

        mUIHandler = new Handler(Looper.getMainLooper());

        if (refreshDevicesList) {
            // ensure to have the up-to-date devices list
            // got some issues when upgrading from Riot < 0.6.4
            mDevicesList.handleDeviceListsChanges(Arrays.asList(mSession.getMyUserId()), null);
        }

        mOutgoingRoomKeyRequestManager = new MXOutgoingRoomKeyRequestManager(this);

        mReceivedRoomKeyRequests.addAll(mCryptoStore.getPendingIncomingRoomKeyRequests());

        mKeysBackup = new KeysBackup(this, homeServerConnectionConfig);
        mShortCodeVerificationManager = new VerificationManager(mSession);
    }

    /**
     * @return the encrypting thread handler
     */
    public Handler getEncryptingThreadHandler() {
        // mEncryptingHandlerThread was not yet ready
        if (null == mEncryptingHandler) {
            mEncryptingHandler = new Handler(mEncryptingHandlerThread.getLooper());
        }

        // fail to get the handler
        // might happen if the thread is not yet ready
        if (null == mEncryptingHandler) {
            return mUIHandler;
        }

        return mEncryptingHandler;
    }

    /**
     * @return the decrypting thread handler
     */
    @Override
    public Handler getDecryptingThreadHandler() {
        // mDecryptingHandlerThread was not yet ready
        if (null == mDecryptingHandler) {
            mDecryptingHandler = new Handler(mDecryptingHandlerThread.getLooper());
        }

        // fail to get the handler
        // might happen if the thread is not yet ready
        if (null == mDecryptingHandler) {
            return mUIHandler;
        }

        return mDecryptingHandler;
    }

    /**
     * @return the UI thread handler
     */
    @Override
    public Handler getUIHandler() {
        return mUIHandler;
    }

    @Override
    public void setNetworkConnectivityReceiver(NetworkConnectivityReceiver networkConnectivityReceiver) {
        mNetworkConnectivityReceiver = networkConnectivityReceiver;
    }

    /**
     * @return true if some saved data is corrupted
     */
    @Override
    public boolean isCorrupted() {
        return (null != mCryptoStore) && mCryptoStore.isCorrupted();
    }

    /**
     * @return true if this instance has been released
     */
    public boolean hasBeenReleased() {
        return (null == mOlmDevice);
    }

    /**
     * @return my device info
     */
    @Override
    public MXDeviceInfo getMyDevice() {
        return mMyDevice;
    }

    /**
     * @return the crypto store
     */
    @Override
    public IMXCryptoStore getCryptoStore() {
        return mCryptoStore;
    }

    /**
     * @return the deviceList
     */
    @Override
    public MXDeviceList getDeviceList() {
        return mDevicesList;
    }

    /**
     * Provides the tracking status
     *
     * @param userId the user id
     * @return the tracking status
     */
    @Override
    public int getDeviceTrackingStatus(String userId) {
        return mCryptoStore.getDeviceTrackingStatus(userId, MXDeviceList.TRACKING_STATUS_NOT_TRACKED);
    }

    /**
     * Tell if the MXCrypto is started
     *
     * @return true if the crypto is started
     */
    @Override
    public boolean isStarted() {
        return mIsStarted;
    }

    /**
     * Tells if the MXCrypto is starting.
     *
     * @return true if the crypto is starting
     */
    @Override
    public boolean isStarting() {
        return mIsStarting;
    }

    /**
     * Start the crypto module.
     * Device keys will be uploaded, then one time keys if there are not enough on the homeserver
     * and, then, if this is the first time, this new device will be announced to all other users
     * devices.
     *
     * @param isInitialSync true if it starts from an initial sync
     * @param aCallback     the asynchronous callback
     */
    @Override
    public void start(final boolean isInitialSync, final ApiCallback<Void> aCallback) {
        synchronized (mInitializationCallbacks) {
            if ((null != aCallback) && (mInitializationCallbacks.indexOf(aCallback) < 0)) {
                mInitializationCallbacks.add(aCallback);
            }
        }

        if (mIsStarting) {
            return;
        }

        // do not start if there is not network connection
        if ((null != mNetworkConnectivityReceiver) && !mNetworkConnectivityReceiver.isConnected()) {
            // wait that a valid network connection is retrieved
            mNetworkConnectivityReceiver.removeEventListener(mNetworkListener);
            mNetworkConnectivityReceiver.addEventListener(mNetworkListener);
            return;
        }

        mIsStarting = true;

        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                uploadDeviceKeys(new ApiCallback<KeysUploadResponse>() {
                    private void onError() {
                        getUIHandler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isStarted()) {
                                    mIsStarting = false;
                                    start(isInitialSync, null);
                                }
                            }
                        }, 1000);
                    }

                    @Override
                    public void onSuccess(KeysUploadResponse info) {
                        getEncryptingThreadHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                if (!hasBeenReleased()) {
                                    Log.d(LOG_TAG, "###########################################################");
                                    Log.d(LOG_TAG, "uploadDeviceKeys done for " + mSession.getMyUserId());
                                    Log.d(LOG_TAG, "  - device id  : " + mSession.getDeviceId());
                                    Log.d(LOG_TAG, "  - ed25519    : " + mOlmDevice.getDeviceEd25519Key());
                                    Log.d(LOG_TAG, "  - curve25519 : " + mOlmDevice.getDeviceCurve25519Key());
                                    Log.d(LOG_TAG, "  - oneTimeKeys: " + mLastPublishedOneTimeKeys);     // They are
                                    Log.d(LOG_TAG, "");

                                    getEncryptingThreadHandler().post(new Runnable() {
                                        @Override
                                        public void run() {
                                            maybeUploadOneTimeKeys(new ApiCallback<Void>() {
                                                @Override
                                                public void onSuccess(Void info) {
                                                    getEncryptingThreadHandler().post(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (null != mNetworkConnectivityReceiver) {
                                                                mNetworkConnectivityReceiver.removeEventListener(mNetworkListener);
                                                            }

                                                            mIsStarting = false;
                                                            mIsStarted = true;

                                                            mOutgoingRoomKeyRequestManager.start();

                                                            mKeysBackup.checkAndStartKeysBackup();

                                                            synchronized (mInitializationCallbacks) {
                                                                for (ApiCallback<Void> callback : mInitializationCallbacks) {
                                                                    final ApiCallback<Void> fCallback = callback;
                                                                    getUIHandler().post(new Runnable() {
                                                                        @Override
                                                                        public void run() {
                                                                            fCallback.onSuccess(null);
                                                                        }
                                                                    });
                                                                }
                                                                mInitializationCallbacks.clear();
                                                            }

                                                            if (isInitialSync) {
                                                                getEncryptingThreadHandler().post(new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        // refresh the devices list for each known room members
                                                                        getDeviceList().invalidateAllDeviceLists();
                                                                        mDevicesList.refreshOutdatedDeviceLists();
                                                                    }
                                                                });
                                                            } else {
                                                                getEncryptingThreadHandler().post(new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        processReceivedRoomKeyRequests();
                                                                    }
                                                                });
                                                            }
                                                        }
                                                    });
                                                }

                                                @Override
                                                public void onNetworkError(Exception e) {
                                                    Log.e(LOG_TAG, "## start failed : " + e.getMessage(), e);
                                                    onError();
                                                }

                                                @Override
                                                public void onMatrixError(MatrixError e) {
                                                    Log.e(LOG_TAG, "## start failed : " + e.getMessage());
                                                    onError();
                                                }

                                                @Override
                                                public void onUnexpectedError(Exception e) {
                                                    Log.e(LOG_TAG, "## start failed : " + e.getMessage(), e);
                                                    onError();
                                                }
                                            });
                                        }
                                    });
                                }
                            }
                        });
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        Log.e(LOG_TAG, "## start failed : " + e.getMessage(), e);
                        onError();
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        Log.e(LOG_TAG, "## start failed : " + e.getMessage());
                        onError();
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        Log.e(LOG_TAG, "## start failed : " + e.getMessage(), e);
                        onError();
                    }
                });
            }
        });
    }

    /**
     * Close the crypto
     */
    @Override
    public void close() {
        if (null != mEncryptingHandlerThread) {
            mSession.getDataHandler().setCryptoEventsListener(null);
            getEncryptingThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    if (null != mOlmDevice) {
                        mOlmDevice.release();
                        mOlmDevice = null;
                    }

                    // Do not reset My Device
                    // mMyDevice = null;

                    mCryptoStore.close();
                    // Do not reset Crypto store
                    // mCryptoStore = null;

                    if (null != mEncryptingHandlerThread) {
                        mEncryptingHandlerThread.quit();
                        mEncryptingHandlerThread = null;
                    }

                    mOutgoingRoomKeyRequestManager.stop();
                }
            });

            getDecryptingThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    if (null != mDecryptingHandlerThread) {
                        mDecryptingHandlerThread.quit();
                        mDecryptingHandlerThread = null;
                    }
                }
            });
        }
    }

    /**
     * @return the olmdevice instance, or null if the Crypto is closed.
     */
    @Nullable
    @Override
    public MXOlmDevice getOlmDevice() {
        return mOlmDevice;
    }

    /**
     * @return the KeysBackup instance
     */
    @Override
    public KeysBackup getKeysBackup() {
        return mKeysBackup;
    }

    /**
     * @return the VerificationManager instance
     */
    @Override
    public VerificationManager getShortCodeVerificationManager() {
        return mShortCodeVerificationManager;
    }

    /**
     * A sync response has been received
     *
     * @param syncResponse the syncResponse
     * @param fromToken    the start sync token
     * @param isCatchingUp true if there is a catch-up in progress.
     */
    @Override
    public void onSyncCompleted(final CryptoSyncResponse syncResponse, final String fromToken, final boolean isCatchingUp) {
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                if (null != syncResponse.getDeviceLists()) {
                    getDeviceList().handleDeviceListsChanges(syncResponse.getDeviceLists().getChanged(), syncResponse.getDeviceLists().getLeft());
                }

                if (null != syncResponse.getDeviceOneTimeKeysCount()) {
                    int currentCount = (null != syncResponse.getDeviceOneTimeKeysCount().getSignedCurve25519()) ?
                            syncResponse.getDeviceOneTimeKeysCount().getSignedCurve25519() : 0;
                    updateOneTimeKeyCount(currentCount);
                }

                if (isStarted()) {
                    // Make sure we process to-device messages before generating new one-time-keys #2782
                    mDevicesList.refreshOutdatedDeviceLists();
                }

                if (!isCatchingUp && isStarted()) {
                    maybeUploadOneTimeKeys();

                    processReceivedRoomKeyRequests();
                }
            }
        });
    }

    /**
     * Get the stored device keys for a user.
     *
     * @param userId   the user to list keys for.
     * @param callback the asynchronous callback
     */
    public void getUserDevices(final String userId, final ApiCallback<List<MXDeviceInfo>> callback) {
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                final List<MXDeviceInfo> list = getUserDevices(userId);

                if (null != callback) {
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(list);
                        }
                    });
                }
            }
        });
    }

    /**
     * Stores the current one_time_key count which will be handled later (in a call of
     * _onSyncCompleted). The count is e.g. coming from a /sync response.
     *
     * @param currentCount the new count
     */
    private void updateOneTimeKeyCount(int currentCount) {
        mOneTimeKeyCount = currentCount;
    }

    /**
     * Find a device by curve25519 identity key
     *
     * @param senderKey the curve25519 key to match.
     * @param algorithm the encryption algorithm.
     * @return the device info, or null if not found / unsupported algorithm / crypto released
     */
    @Override
    @Nullable
    public MXDeviceInfo deviceWithIdentityKey(final String senderKey, final String algorithm) {
        if (!hasBeenReleased()) {
            if (!TextUtils.equals(algorithm, CryptoConstantsKt.MXCRYPTO_ALGORITHM_MEGOLM)
                    && !TextUtils.equals(algorithm, CryptoConstantsKt.MXCRYPTO_ALGORITHM_OLM)) {
                // We only deal in olm keys
                return null;
            }

            // Find in the crypto store
            return getCryptoStore().deviceWithIdentityKey(senderKey);
        }

        // The store is released
        return null;
    }

    /**
     * Provides the device information for a device id and an user Id
     *
     * @param userId   the user id
     * @param deviceId the device id
     * @param callback the asynchronous callback
     */
    @Override
    public void getDeviceInfo(final String userId, final String deviceId, final ApiCallback<MXDeviceInfo> callback) {
        getDecryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                final MXDeviceInfo di;

                if (!TextUtils.isEmpty(userId) && !TextUtils.isEmpty(deviceId)) {
                    di = mCryptoStore.getUserDevice(deviceId, userId);
                } else {
                    di = null;
                }

                if (null != callback) {
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(di);
                        }
                    });
                }
            }
        });
    }

    /**
     * Set the devices as known
     *
     * @param devices  the devices. Note that the mVerified member of the devices in this list will not be updated by this method.
     * @param callback the asynchronous callback
     */
    @Override
    public void setDevicesKnown(@NotNull List<? extends MXDeviceInfo> devices, @Nullable ApiCallback<Void> callback) {
        if (hasBeenReleased()) {
            return;
        }
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                // build a devices map
                Map<String, List<String>> devicesIdListByUserId = new HashMap<>();

                for (MXDeviceInfo di : devices) {
                    List<String> deviceIdsList = devicesIdListByUserId.get(di.userId);

                    if (null == deviceIdsList) {
                        deviceIdsList = new ArrayList<>();
                        devicesIdListByUserId.put(di.userId, deviceIdsList);
                    }
                    deviceIdsList.add(di.deviceId);
                }

                Set<String> userIds = devicesIdListByUserId.keySet();

                for (String userId : userIds) {
                    Map<String, MXDeviceInfo> storedDeviceIDs = mCryptoStore.getUserDevices(userId);

                    // sanity checks
                    if (null != storedDeviceIDs) {
                        boolean isUpdated = false;
                        List<String> deviceIds = devicesIdListByUserId.get(userId);

                        for (String deviceId : deviceIds) {
                            MXDeviceInfo device = storedDeviceIDs.get(deviceId);

                            // assume if the device is either verified or blocked
                            // it means that the device is known
                            if ((null != device) && device.isUnknown()) {
                                device.mVerified = MXDeviceInfo.DEVICE_VERIFICATION_UNVERIFIED;
                                isUpdated = true;
                            }
                        }

                        if (isUpdated) {
                            mCryptoStore.storeUserDevices(userId, storedDeviceIDs);
                        }
                    }
                }

                if (null != callback) {
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(null);
                        }
                    });
                }
            }
        });
    }

    /**
     * Update the blocked/verified state of the given device.
     *
     * @param verificationStatus the new verification status
     * @param deviceId           the unique identifier for the device.
     * @param userId             the owner of the device
     * @param callback           the asynchronous callback
     */
    @Override
    public void setDeviceVerification(final int verificationStatus, final String deviceId, final String userId, final ApiCallback<Void> callback) {
        if (hasBeenReleased()) {
            return;
        }

        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                MXDeviceInfo device = mCryptoStore.getUserDevice(deviceId, userId);

                // Sanity check
                if (null == device) {
                    Log.e(LOG_TAG, "## setDeviceVerification() : Unknown device " + userId + ":" + deviceId);
                    if (null != callback) {
                        getUIHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(null);
                            }
                        });
                    }
                    return;
                }

                if (device.mVerified != verificationStatus) {
                    device.mVerified = verificationStatus;
                    mCryptoStore.storeUserDevice(userId, device);

                    if (userId.equals(mSession.getMyUserId())) {
                        // If one of the user's own devices is being marked as verified / unverified,
                        // check the key backup status, since whether or not we use this depends on
                        // whether it has a signature from a verified device
                        mKeysBackup.checkAndStartKeysBackup();
                    }
                }

                if (null != callback) {
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(null);
                        }
                    });
                }
            }
        });
    }

    /**
     * Configure a room to use encryption.
     * This method must be called in getEncryptingThreadHandler
     *
     * @param roomId             the room id to enable encryption in.
     * @param algorithm          the encryption config for the room.
     * @param inhibitDeviceQuery true to suppress device list query for users in the room (for now)
     * @param members            list of members to start tracking their devices
     * @return true if the operation succeeds.
     */
    private boolean setEncryptionInRoom(String roomId, String algorithm, boolean inhibitDeviceQuery, List<CryptoRoomMember> members) {
        if (hasBeenReleased()) {
            return false;
        }

        // If we already have encryption in this room, we should ignore this event
        // (for now at least. Maybe we should alert the user somehow?)
        String existingAlgorithm = mCryptoStore.getRoomAlgorithm(roomId);

        if (!TextUtils.isEmpty(existingAlgorithm) && !TextUtils.equals(existingAlgorithm, algorithm)) {
            Log.e(LOG_TAG, "## setEncryptionInRoom() : Ignoring m.room.encryption event which requests a change of config in " + roomId);
            return false;
        }

        Class<IMXEncrypting> encryptingClass = MXCryptoAlgorithms.sharedAlgorithms().encryptorClassForAlgorithm(algorithm);

        if (null == encryptingClass) {
            Log.e(LOG_TAG, "## setEncryptionInRoom() : Unable to encrypt with " + algorithm);
            return false;
        }

        mCryptoStore.storeRoomAlgorithm(roomId, algorithm);

        IMXEncrypting alg;

        try {
            Constructor<?> ctor = encryptingClass.getConstructors()[0];
            alg = (IMXEncrypting) ctor.newInstance();
        } catch (Exception e) {
            Log.e(LOG_TAG, "## setEncryptionInRoom() : fail to load the class", e);
            return false;
        }

        alg.initWithMatrixSession(mSession, this, roomId);

        synchronized (mRoomEncryptors) {
            mRoomEncryptors.put(roomId, alg);
        }

        // if encryption was not previously enabled in this room, we will have been
        // ignoring new device events for these users so far. We may well have
        // up-to-date lists for some users, for instance if we were sharing other
        // e2e rooms with them, so there is room for optimisation here, but for now
        // we just invalidate everyone in the room.
        if (null == existingAlgorithm) {
            Log.d(LOG_TAG, "Enabling encryption in " + roomId + " for the first time; invalidating device lists for all users therein");

            List<String> userIds = new ArrayList<>();

            for (CryptoRoomMember m : members) {
                userIds.add(m.getUserId());
            }

            getDeviceList().startTrackingDeviceList(userIds);

            if (!inhibitDeviceQuery) {
                getDeviceList().refreshOutdatedDeviceLists();
            }
        }

        return true;
    }

    /**
     * Tells if a room is encrypted
     *
     * @param roomId the room id
     * @return true if the room is encrypted
     */
    public boolean isRoomEncrypted(String roomId) {
        boolean res = false;

        if (null != roomId) {
            synchronized (mRoomEncryptors) {
                res = mRoomEncryptors.containsKey(roomId);

                if (!res) {
                    CryptoRoom room = mSession.getDataHandler().getRoom(roomId);

                    if (null != room) {
                        res = room.getState().isEncrypted();
                    }
                }
            }
        }

        return res;
    }

    /**
     * @return the stored device keys for a user.
     */
    @Override
    public List<MXDeviceInfo> getUserDevices(final String userId) {
        Map<String, MXDeviceInfo> map = getCryptoStore().getUserDevices(userId);
        return (null != map) ? new ArrayList<>(map.values()) : new ArrayList<MXDeviceInfo>();
    }

    /**
     * Try to make sure we have established olm sessions for the given users.
     * It must be called in getEncryptingThreadHandler() thread.
     * The callback is called in the UI thread.
     *
     * @param users    a list of user ids.
     * @param callback the asynchronous callback
     */
    @Override
    public void ensureOlmSessionsForUsers(List<String> users, final ApiCallback<MXUsersDevicesMap<MXOlmSessionResult>> callback) {
        Log.d(LOG_TAG, "## ensureOlmSessionsForUsers() : ensureOlmSessionsForUsers " + users);

        Map<String /* userId */, List<MXDeviceInfo>> devicesByUser = new HashMap<>();

        for (String userId : users) {
            devicesByUser.put(userId, new ArrayList<MXDeviceInfo>());

            List<MXDeviceInfo> devices = getUserDevices(userId);

            for (MXDeviceInfo device : devices) {
                String key = device.identityKey();

                if (TextUtils.equals(key, mOlmDevice.getDeviceCurve25519Key())) {
                    // Don't bother setting up session to ourself
                    continue;
                }

                if (device.isVerified()) {
                    // Don't bother setting up sessions with blocked users
                    continue;
                }

                devicesByUser.get(userId).add(device);
            }
        }

        ensureOlmSessionsForDevices(devicesByUser, callback);
    }

    /**
     * Try to make sure we have established olm sessions for the given devices.
     * It must be called in getCryptoHandler() thread.
     * The callback is called in the UI thread.
     *
     * @param devicesByUser a map from userid to list of devices.
     * @param callback      the asynchronous callback
     */
    public void ensureOlmSessionsForDevices(final Map<String, List<MXDeviceInfo>> devicesByUser,
                                            final ApiCallback<MXUsersDevicesMap<MXOlmSessionResult>> callback) {
        List<MXDeviceInfo> devicesWithoutSession = new ArrayList<>();

        final MXUsersDevicesMap<MXOlmSessionResult> results = new MXUsersDevicesMap<>();

        Set<String> userIds = devicesByUser.keySet();

        for (String userId : userIds) {
            List<MXDeviceInfo> deviceInfos = devicesByUser.get(userId);

            for (MXDeviceInfo deviceInfo : deviceInfos) {
                String deviceId = deviceInfo.deviceId;
                String key = deviceInfo.identityKey();

                String sessionId = mOlmDevice.getSessionId(key);

                if (TextUtils.isEmpty(sessionId)) {
                    devicesWithoutSession.add(deviceInfo);
                }

                MXOlmSessionResult olmSessionResult = new MXOlmSessionResult(deviceInfo, sessionId);
                results.setObject(olmSessionResult, userId, deviceId);
            }
        }

        if (devicesWithoutSession.size() == 0) {
            if (null != callback) {
                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(results);
                    }
                });
            }
            return;
        }

        // Prepare the request for claiming one-time keys
        MXUsersDevicesMap<String> usersDevicesToClaim = new MXUsersDevicesMap<>();

        final String oneTimeKeyAlgorithm = MXKey.KEY_SIGNED_CURVE_25519_TYPE;

        for (MXDeviceInfo device : devicesWithoutSession) {
            usersDevicesToClaim.setObject(oneTimeKeyAlgorithm, device.userId, device.deviceId);
        }

        // TODO: this has a race condition - if we try to send another message
        // while we are claiming a key, we will end up claiming two and setting up
        // two sessions.
        //
        // That should eventually resolve itself, but it's poor form.

        Log.d(LOG_TAG, "## claimOneTimeKeysForUsersDevices() : " + usersDevicesToClaim);

        mCryptoRestClient.claimOneTimeKeysForUsersDevices(usersDevicesToClaim, new ApiCallback<MXUsersDevicesMap<MXKey>>() {
            @Override
            public void onSuccess(final MXUsersDevicesMap<MXKey> oneTimeKeys) {
                getEncryptingThreadHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Log.d(LOG_TAG, "## claimOneTimeKeysForUsersDevices() : keysClaimResponse.oneTimeKeys: " + oneTimeKeys);

                            Set<String> userIds = devicesByUser.keySet();

                            for (String userId : userIds) {
                                List<MXDeviceInfo> deviceInfos = devicesByUser.get(userId);

                                for (MXDeviceInfo deviceInfo : deviceInfos) {

                                    MXKey oneTimeKey = null;

                                    List<String> deviceIds = oneTimeKeys.getUserDeviceIds(userId);

                                    if (null != deviceIds) {
                                        for (String deviceId : deviceIds) {
                                            MXOlmSessionResult olmSessionResult = results.getObject(deviceId, userId);

                                            if (null != olmSessionResult.mSessionId) {
                                                // We already have a result for this device
                                                continue;
                                            }

                                            MXKey key = oneTimeKeys.getObject(deviceId, userId);

                                            if (TextUtils.equals(key.type, oneTimeKeyAlgorithm)) {
                                                oneTimeKey = key;
                                            }

                                            if (null == oneTimeKey) {
                                                Log.d(LOG_TAG, "## ensureOlmSessionsForDevices() : No one-time keys " + oneTimeKeyAlgorithm
                                                        + " for device " + userId + " : " + deviceId);
                                                continue;
                                            }

                                            // Update the result for this device in results
                                            olmSessionResult.mSessionId = verifyKeyAndStartSession(oneTimeKey, userId, deviceInfo);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## ensureOlmSessionsForDevices() " + e.getMessage(), e);
                        }

                        if (!hasBeenReleased()) {
                            if (null != callback) {
                                getUIHandler().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        callback.onSuccess(results);
                                    }
                                });
                            }
                        }
                    }
                });
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.e(LOG_TAG, "## ensureOlmSessionsForUsers(): claimOneTimeKeysForUsersDevices request failed" + e.getMessage(), e);

                if (null != callback) {
                    callback.onNetworkError(e);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.e(LOG_TAG, "## ensureOlmSessionsForUsers(): claimOneTimeKeysForUsersDevices request failed" + e.getMessage());

                if (null != callback) {
                    callback.onMatrixError(e);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.e(LOG_TAG, "## ensureOlmSessionsForUsers(): claimOneTimeKeysForUsersDevices request failed" + e.getMessage(), e);

                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
            }
        });
    }

    private String verifyKeyAndStartSession(MXKey oneTimeKey, String userId, MXDeviceInfo deviceInfo) {
        String sessionId = null;

        String deviceId = deviceInfo.deviceId;
        String signKeyId = "ed25519:" + deviceId;
        String signature = oneTimeKey.signatureForUserId(userId, signKeyId);

        if (!TextUtils.isEmpty(signature) && !TextUtils.isEmpty(deviceInfo.fingerprint())) {
            boolean isVerified = false;
            String errorMessage = null;

            try {
                mOlmDevice.verifySignature(deviceInfo.fingerprint(), oneTimeKey.signalableJSONDictionary(), signature);
                isVerified = true;
            } catch (Exception e) {
                errorMessage = e.getMessage();
            }

            // Check one-time key signature
            if (isVerified) {
                sessionId = getOlmDevice().createOutboundSession(deviceInfo.identityKey(), oneTimeKey.value);

                if (!TextUtils.isEmpty(sessionId)) {
                    Log.d(LOG_TAG, "## verifyKeyAndStartSession() : Started new sessionid " + sessionId
                            + " for device " + deviceInfo + "(theirOneTimeKey: " + oneTimeKey.value + ")");
                } else {
                    // Possibly a bad key
                    Log.e(LOG_TAG, "## verifyKeyAndStartSession() : Error starting session with device " + userId + ":" + deviceId);
                }
            } else {
                Log.e(LOG_TAG, "## verifyKeyAndStartSession() : Unable to verify signature on one-time key for device " + userId
                        + ":" + deviceId + " Error " + errorMessage);
            }
        }

        return sessionId;
    }


    /**
     * Encrypt an event content according to the configuration of the room.
     *
     * @param eventContent the content of the event.
     * @param eventType    the type of the event.
     * @param room         the room the event will be sent.
     * @param callback     the asynchronous callback
     */
    @Override
    public void encryptEventContent(final JsonElement eventContent,
                                    final String eventType,
                                    final CryptoRoom room,
                                    final ApiCallback<MXEncryptEventContentResult> callback) {
        // wait that the crypto is really started
        if (!isStarted()) {
            Log.d(LOG_TAG, "## encryptEventContent() : wait after e2e init");

            start(false, new ApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    encryptEventContent(eventContent, eventType, room, callback);
                }

                @Override
                public void onNetworkError(Exception e) {
                    Log.e(LOG_TAG, "## encryptEventContent() : onNetworkError while waiting to start e2e : " + e.getMessage(), e);

                    if (null != callback) {
                        callback.onNetworkError(e);
                    }
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    Log.e(LOG_TAG, "## encryptEventContent() : onMatrixError while waiting to start e2e : " + e.getMessage());

                    if (null != callback) {
                        callback.onMatrixError(e);
                    }
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    Log.e(LOG_TAG, "## encryptEventContent() : onUnexpectedError while waiting to start e2e : " + e.getMessage(), e);

                    if (null != callback) {
                        callback.onUnexpectedError(e);
                    }
                }
            });

            return;
        }

        final ApiCallback<List<CryptoRoomMember>> apiCallback = new SimpleApiCallback<List<CryptoRoomMember>>(callback) {
            @Override
            public void onSuccess(final List<CryptoRoomMember> members) {
                // just as you are sending a secret message?
                final List<String> userIds = new ArrayList<>();

                for (CryptoRoomMember m : members) {
                    userIds.add(m.getUserId());
                }

                getEncryptingThreadHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        IMXEncrypting alg;

                        synchronized (mRoomEncryptors) {
                            alg = mRoomEncryptors.get(room.getRoomId());
                        }

                        if (null == alg) {
                            String algorithm = room.getState().encryptionAlgorithm();

                            if (null != algorithm) {
                                if (setEncryptionInRoom(room.getRoomId(), algorithm, false, members)) {
                                    synchronized (mRoomEncryptors) {
                                        alg = mRoomEncryptors.get(room.getRoomId());
                                    }
                                }
                            }
                        }

                        if (null != alg) {
                            final long t0 = System.currentTimeMillis();
                            Log.d(LOG_TAG, "## encryptEventContent() starts");

                            alg.encryptEventContent(eventContent, eventType, userIds, new ApiCallback<JsonElement>() {
                                @Override
                                public void onSuccess(final JsonElement encryptedContent) {
                                    Log.d(LOG_TAG, "## encryptEventContent() : succeeds after " + (System.currentTimeMillis() - t0) + " ms");

                                    if (null != callback) {
                                        callback.onSuccess(new MXEncryptEventContentResult(encryptedContent, CryptoEvent.EVENT_TYPE_MESSAGE_ENCRYPTED));
                                    }
                                }

                                @Override
                                public void onNetworkError(final Exception e) {
                                    Log.e(LOG_TAG, "## encryptEventContent() : onNetworkError " + e.getMessage(), e);

                                    if (null != callback) {
                                        callback.onNetworkError(e);
                                    }
                                }

                                @Override
                                public void onMatrixError(final MatrixError e) {
                                    Log.e(LOG_TAG, "## encryptEventContent() : onMatrixError " + e.getMessage());

                                    if (null != callback) {
                                        callback.onMatrixError(e);
                                    }
                                }

                                @Override
                                public void onUnexpectedError(final Exception e) {
                                    Log.e(LOG_TAG, "## encryptEventContent() : onUnexpectedError " + e.getMessage(), e);

                                    if (null != callback) {
                                        callback.onUnexpectedError(e);
                                    }
                                }
                            });
                        } else {
                            final String algorithm = room.getState().encryptionAlgorithm();
                            final String reason = String.format(MXCryptoError.UNABLE_TO_ENCRYPT_REASON,
                                    (null == algorithm) ? MXCryptoError.NO_MORE_ALGORITHM_REASON : algorithm);
                            Log.e(LOG_TAG, "## encryptEventContent() : " + reason);

                            if (null != callback) {
                                getUIHandler().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        callback.onMatrixError(new MXCryptoError(MXCryptoError.UNABLE_TO_ENCRYPT_ERROR_CODE,
                                                MXCryptoError.UNABLE_TO_ENCRYPT, reason));
                                    }
                                });
                            }
                        }
                    }
                });
            }
        };

        // Check whether the event content must be encrypted for the invited members.
        boolean encryptForInvitedMembers = mCryptoConfig.mEnableEncryptionForInvitedMembers
                && room.shouldEncryptForInvitedMembers();

        if (encryptForInvitedMembers) {
            room.getActiveMembersAsyncCrypto(apiCallback);
        } else {
            room.getJoinedMembersAsyncCrypto(apiCallback);
        }
    }

    /**
     * Decrypt an event
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return the MXEventDecryptionResult data, or null in case of error
     */
    @Override
    @Nullable
    public MXEventDecryptionResult decryptEvent(final CryptoEvent event, final String timeline) throws MXDecryptionException {
        if (null == event) {
            Log.e(LOG_TAG, "## decryptEvent : null event");
            return null;
        }

        final CryptoEventContent eventContent = event.getWireEventContent();

        if (null == eventContent) {
            Log.e(LOG_TAG, "## decryptEvent : empty event content");
            return null;
        }

        final List<MXEventDecryptionResult> results = new ArrayList<>();
        final CountDownLatch lock = new CountDownLatch(1);
        final List<MXDecryptionException> exceptions = new ArrayList<>();

        getDecryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                MXEventDecryptionResult result = null;
                IMXDecrypting alg = getRoomDecryptor(event.getRoomId(), eventContent.getAlgorithm());

                if (null == alg) {
                    String reason = String.format(MXCryptoError.UNABLE_TO_DECRYPT_REASON, event.getEventId(), eventContent.getAlgorithm());
                    Log.e(LOG_TAG, "## decryptEvent() : " + reason);
                    exceptions.add(new MXDecryptionException(new MXCryptoError(MXCryptoError.UNABLE_TO_DECRYPT_ERROR_CODE,
                            MXCryptoError.UNABLE_TO_DECRYPT, reason)));
                } else {
                    try {
                        result = alg.decryptEvent(event, timeline);
                    } catch (MXDecryptionException decryptionException) {
                        exceptions.add(decryptionException);
                    }

                    if (null != result) {
                        results.add(result);
                    }
                }
                lock.countDown();
            }
        });

        try {
            lock.await();
        } catch (Exception e) {
            Log.e(LOG_TAG, "## decryptEvent() : failed " + e.getMessage(), e);
        }

        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }

        if (!results.isEmpty()) {
            return results.get(0);
        }

        return null;
    }

    /**
     * Reset replay attack data for the given timeline.
     *
     * @param timelineId the timeline id
     */
    @Override
    public void resetReplayAttackCheckInTimeline(final String timelineId) {
        if ((null != timelineId) && (null != getOlmDevice())) {
            getDecryptingThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    getOlmDevice().resetReplayAttackCheckInTimeline(timelineId);
                }
            });
        }
    }

    /**
     * Encrypt an event payload for a list of devices.
     * This method must be called from the getCryptoHandler() thread.
     *
     * @param payloadFields fields to include in the encrypted payload.
     * @param deviceInfos   list of device infos to encrypt for.
     * @return the content for an m.room.encrypted event.
     */
    public EncryptedMessage encryptMessage(Map<String, Object> payloadFields, List<MXDeviceInfo> deviceInfos) {
        if (hasBeenReleased()) {
            // Empty object
            return new EncryptedMessage();
        }

        Map<String, MXDeviceInfo> deviceInfoParticipantKey = new HashMap<>();
        List<String> participantKeys = new ArrayList<>();

        for (MXDeviceInfo di : deviceInfos) {
            participantKeys.add(di.identityKey());
            deviceInfoParticipantKey.put(di.identityKey(), di);
        }

        Map<String, Object> payloadJson = new HashMap<>(payloadFields);

        payloadJson.put("sender", mSession.getMyUserId());
        payloadJson.put("sender_device", mSession.getDeviceId());

        // Include the Ed25519 key so that the recipient knows what
        // device this message came from.
        // We don't need to include the curve25519 key since the
        // recipient will already know this from the olm headers.
        // When combined with the device keys retrieved from the
        // homeserver signed by the ed25519 key this proves that
        // the curve25519 key and the ed25519 key are owned by
        // the same device.
        Map<String, String> keysMap = new HashMap<>();
        keysMap.put("ed25519", mOlmDevice.getDeviceEd25519Key());
        payloadJson.put("keys", keysMap);

        Map<String, Object> ciphertext = new HashMap<>();

        for (String deviceKey : participantKeys) {
            String sessionId = mOlmDevice.getSessionId(deviceKey);

            if (!TextUtils.isEmpty(sessionId)) {
                Log.d(LOG_TAG, "Using sessionid " + sessionId + " for device " + deviceKey);
                MXDeviceInfo deviceInfo = deviceInfoParticipantKey.get(deviceKey);

                payloadJson.put("recipient", deviceInfo.userId);

                Map<String, String> recipientsKeysMap = new HashMap<>();
                recipientsKeysMap.put("ed25519", deviceInfo.fingerprint());
                payloadJson.put("recipient_keys", recipientsKeysMap);


                String payloadString = StringUtilsKt.convertToUTF8(JsonUtility.canonicalize(JsonUtility.getGson(false).toJsonTree(payloadJson)).toString());
                ciphertext.put(deviceKey, mOlmDevice.encryptMessage(deviceKey, sessionId, payloadString));
            }
        }

        EncryptedMessage res = new EncryptedMessage();

        res.algorithm = CryptoConstantsKt.MXCRYPTO_ALGORITHM_OLM;
        res.senderKey = mOlmDevice.getDeviceCurve25519Key();
        res.cipherText = ciphertext;

        return res;
    }

    /**
     * Sign Object
     * <p>
     * Example:
     * <pre>
     *     {
     *         "[MY_USER_ID]": {
     *             "ed25519:[MY_DEVICE_ID]": "sign(str)"
     *         }
     *     }
     * </pre>
     *
     * @param strToSign the String to sign and to include in the Map
     * @return a Map (see example)
     */
    public Map<String, Map<String, String>> signObject(String strToSign) {
        Map<String, Map<String, String>> result = new HashMap<>();

        Map<String, String> content = new HashMap<>();

        content.put("ed25519:" + mMyDevice.deviceId, mOlmDevice.signMessage(strToSign));

        result.put(mMyDevice.userId, content);

        return result;
    }

    /**
     * Handle the 'toDevice' event
     *
     * @param event the event
     */
    private void onToDeviceEvent(final CryptoEvent event) {
        mShortCodeVerificationManager.onToDeviceEvent(event);

        if (TextUtils.equals(event.getType(), CryptoEvent.EVENT_TYPE_ROOM_KEY)
                || TextUtils.equals(event.getType(), CryptoEvent.EVENT_TYPE_FORWARDED_ROOM_KEY)) {
            getDecryptingThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    onRoomKeyEvent(event);
                }
            });
        } else if (TextUtils.equals(event.getType(), CryptoEvent.EVENT_TYPE_ROOM_KEY_REQUEST)) {
            getEncryptingThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    onRoomKeyRequestEvent(event);
                }
            });
        }
    }

    /**
     * Handle a key event.
     * This method must be called on getDecryptingThreadHandler() thread.
     *
     * @param event the key event.
     */
    private void onRoomKeyEvent(CryptoEvent event) {
        // sanity check
        if (null == event) {
            Log.e(LOG_TAG, "## onRoomKeyEvent() : null event");
            return;
        }

        RoomKeyContent roomKeyContent = event.toRoomKeyContent();

        String roomId = roomKeyContent.room_id;
        String algorithm = roomKeyContent.algorithm;

        if (TextUtils.isEmpty(roomId) || TextUtils.isEmpty(algorithm)) {
            Log.e(LOG_TAG, "## onRoomKeyEvent() : missing fields");
            return;
        }

        IMXDecrypting alg = getRoomDecryptor(roomId, algorithm);

        if (null == alg) {
            Log.e(LOG_TAG, "## onRoomKeyEvent() : Unable to handle keys for " + algorithm);
            return;
        }

        alg.onRoomKeyEvent(event);
    }

    /**
     * Called when we get an m.room_key_request event
     * This method must be called on getEncryptingThreadHandler() thread.
     *
     * @param event the announcement event.
     */
    private void onRoomKeyRequestEvent(final CryptoEvent event) {
        RoomKeyShare roomKeyShare = event.toRoomKeyShare();

        if (null != roomKeyShare.action) {
            switch (roomKeyShare.action) {
                case RoomKeyShare.ACTION_SHARE_REQUEST:
                    synchronized (mReceivedRoomKeyRequests) {
                        mReceivedRoomKeyRequests.add(new IncomingRoomKeyRequest(event));
                    }
                    break;
                case RoomKeyShare.ACTION_SHARE_CANCELLATION:
                    synchronized (mReceivedRoomKeyRequestCancellations) {
                        mReceivedRoomKeyRequestCancellations.add(new IncomingRoomKeyRequestCancellation(event));
                    }
                    break;
                default:
                    Log.e(LOG_TAG, "## onRoomKeyRequestEvent() : unsupported action " + roomKeyShare.action);
                    break;
            }
        }
    }

    /**
     * Process any m.room_key_request events which were queued up during the
     * current sync.
     */
    private void processReceivedRoomKeyRequests() {
        List<IncomingRoomKeyRequest> receivedRoomKeyRequests = null;

        synchronized (mReceivedRoomKeyRequests) {
            if (!mReceivedRoomKeyRequests.isEmpty()) {
                receivedRoomKeyRequests = new ArrayList(mReceivedRoomKeyRequests);
                mReceivedRoomKeyRequests.clear();
            }
        }

        if (null != receivedRoomKeyRequests) {
            for (final IncomingRoomKeyRequest request : receivedRoomKeyRequests) {
                String userId = request.mUserId;
                String deviceId = request.mDeviceId;
                RoomKeyRequestBody body = request.mRequestBody;
                String roomId = body.roomId;
                String alg = body.algorithm;

                Log.d(LOG_TAG, "m.room_key_request from " + userId + ":" + deviceId + " for " + roomId + " / " + body.sessionId + " id " + request.mRequestId);

                if (!TextUtils.equals(mSession.getMyUserId(), userId)) {
                    // TODO: determine if we sent this device the keys already: in
                    Log.e(LOG_TAG, "## processReceivedRoomKeyRequests() : Ignoring room key request from other user for now");
                    return;
                }

                // todo: should we queue up requests we don't yet have keys for,
                // in case they turn up later?

                // if we don't have a decryptor for this room/alg, we don't have
                // the keys for the requested events, and can drop the requests.

                final IMXDecrypting decryptor = getRoomDecryptor(roomId, alg);

                if (null == decryptor) {
                    Log.e(LOG_TAG, "## processReceivedRoomKeyRequests() : room key request for unknown " + alg + " in room " + roomId);
                    continue;
                }

                if (!decryptor.hasKeysForKeyRequest(request)) {
                    Log.e(LOG_TAG, "## processReceivedRoomKeyRequests() : room key request for unknown session " + body.sessionId);
                    mCryptoStore.deleteIncomingRoomKeyRequest(request);
                    continue;
                }

                if (TextUtils.equals(deviceId, getMyDevice().deviceId) && TextUtils.equals(mSession.getMyUserId(), userId)) {
                    Log.d(LOG_TAG, "## processReceivedRoomKeyRequests() : oneself device - ignored");
                    mCryptoStore.deleteIncomingRoomKeyRequest(request);
                    continue;
                }

                request.mShare = new Runnable() {
                    @Override
                    public void run() {
                        getEncryptingThreadHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                decryptor.shareKeysWithDevice(request);
                                mCryptoStore.deleteIncomingRoomKeyRequest(request);
                            }
                        });
                    }
                };

                request.mIgnore = new Runnable() {
                    @Override
                    public void run() {
                        getEncryptingThreadHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                mCryptoStore.deleteIncomingRoomKeyRequest(request);
                            }
                        });
                    }
                };

                // if the device is verified already, share the keys
                MXDeviceInfo device = mCryptoStore.getUserDevice(deviceId, userId);

                if (null != device) {
                    if (device.isVerified()) {
                        Log.d(LOG_TAG, "## processReceivedRoomKeyRequests() : device is already verified: sharing keys");
                        mCryptoStore.deleteIncomingRoomKeyRequest(request);
                        request.mShare.run();
                        continue;
                    }

                    if (device.isBlocked()) {
                        Log.d(LOG_TAG, "## processReceivedRoomKeyRequests() : device is blocked -> ignored");
                        mCryptoStore.deleteIncomingRoomKeyRequest(request);
                        continue;
                    }
                }

                mCryptoStore.storeIncomingRoomKeyRequest(request);
                onRoomKeyRequest(request);
            }
        }

        List<IncomingRoomKeyRequestCancellation> receivedRoomKeyRequestCancellations = null;

        synchronized (mReceivedRoomKeyRequestCancellations) {
            if (!mReceivedRoomKeyRequestCancellations.isEmpty()) {
                receivedRoomKeyRequestCancellations = new ArrayList(mReceivedRoomKeyRequestCancellations);
                mReceivedRoomKeyRequestCancellations.clear();
            }
        }

        if (null != receivedRoomKeyRequestCancellations) {
            for (IncomingRoomKeyRequestCancellation request : receivedRoomKeyRequestCancellations) {
                Log.d(LOG_TAG, "## ## processReceivedRoomKeyRequests() : m.room_key_request cancellation for " + request.mUserId
                        + ":" + request.mDeviceId + " id " + request.mRequestId);

                // we should probably only notify the app of cancellations we told it
                // about, but we don't currently have a record of that, so we just pass
                // everything through.
                onRoomKeyRequestCancellation(request);
                mCryptoStore.deleteIncomingRoomKeyRequest(request);
            }
        }
    }

    /**
     * Handle an m.room.encryption event.
     *
     * @param event the encryption event.
     */
    private void onCryptoEvent(final CryptoEvent event) {
        final CryptoEventContent eventContent = event.getWireEventContent();

        final CryptoRoom room = mSession.getDataHandler().getRoom(event.getRoomId());

        // Check whether the event content must be encrypted for the invited members.
        boolean encryptForInvitedMembers = mCryptoConfig.mEnableEncryptionForInvitedMembers
                && room.shouldEncryptForInvitedMembers();

        ApiCallback<List<CryptoRoomMember>> callback = new ApiCallback<List<CryptoRoomMember>>() {
            @Override
            public void onSuccess(final List<CryptoRoomMember> info) {
                getEncryptingThreadHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        setEncryptionInRoom(event.getRoomId(), eventContent.getAlgorithm(), true, info);
                    }
                });
            }

            private void onError() {
                // Ensure setEncryption in room is done, even if there is a failure to fetch the room members
                getEncryptingThreadHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        setEncryptionInRoom(event.getRoomId(), eventContent.getAlgorithm(), true, room.getState().getLoadedMembersCrypto());
                    }
                });
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.w(LOG_TAG, "[MXCrypto] onCryptoEvent: Warning: Unable to get all members from the HS. Fallback by using lazy-loaded members", e);

                onError();
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.w(LOG_TAG, "[MXCrypto] onCryptoEvent: Warning: Unable to get all members from the HS. Fallback by using lazy-loaded members");

                onError();
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.w(LOG_TAG, "[MXCrypto] onCryptoEvent: Warning: Unable to get all members from the HS. Fallback by using lazy-loaded members", e);

                onError();
            }
        };

        if (encryptForInvitedMembers) {
            room.getActiveMembersAsyncCrypto(callback);
        } else {
            room.getJoinedMembersAsyncCrypto(callback);
        }
    }

    /**
     * Handle a change in the membership state of a member of a room.
     *
     * @param event the membership event causing the change
     */
    private void onRoomMembership(final CryptoEvent event) {
        final IMXEncrypting alg;

        synchronized (mRoomEncryptors) {
            alg = mRoomEncryptors.get(event.getRoomId());
        }

        if (null == alg) {
            // No encrypting in this room
            return;
        }

        final String userId = event.getStateKey();
        final CryptoRoom room = mSession.getDataHandler().getRoom(event.getRoomId());

        CryptoRoomMember roomMember = room.getState().getMember(userId);

        if (null != roomMember) {
            final String membership = roomMember.getMembership();

            getEncryptingThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    if (TextUtils.equals(membership, CryptoRoomMember.MEMBERSHIP_JOIN)) {
                        // make sure we are tracking the deviceList for this user.
                        getDeviceList().startTrackingDeviceList(Arrays.asList(userId));
                    } else if (TextUtils.equals(membership, CryptoRoomMember.MEMBERSHIP_INVITE)
                            && room.shouldEncryptForInvitedMembers()
                            && mCryptoConfig.mEnableEncryptionForInvitedMembers) {
                        // track the deviceList for this invited user.
                        // Caution: there's a big edge case here in that federated servers do not
                        // know what other servers are in the room at the time they've been invited.
                        // They therefore will not send device updates if a user logs in whilst
                        // their state is invite.
                        getDeviceList().startTrackingDeviceList(Arrays.asList(userId));
                    }
                }
            });
        }
    }

    /**
     * Upload my user's device keys.
     * This method must called on getEncryptingThreadHandler() thread.
     * The callback will called on UI thread.
     *
     * @param callback the asynchronous callback
     */
    private void uploadDeviceKeys(ApiCallback<KeysUploadResponse> callback) {
        // Prepare the device keys data to send
        // Sign it
        mMyDevice.signatures = signObject(JsonUtility.getCanonicalizedJsonString(mMyDevice.signalableJSONDictionary()));

        // For now, we set the device id explicitly, as we may not be using the
        // same one as used in login.
        mCryptoRestClient.uploadKeys(mMyDevice.JSONDictionary(), null, mMyDevice.deviceId, callback);
    }

    /**
     * OTK upload loop
     *
     * @param keyCount the number of key to generate
     * @param keyLimit the limit
     * @param callback the asynchronous callback
     */
    private void uploadLoop(final int keyCount, final int keyLimit, final ApiCallback<Void> callback) {
        if (keyLimit <= keyCount) {
            // If we don't need to generate any more keys then we are done.
            if (null != callback) {
                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(null);
                    }
                });
            }
            return;
        }

        final int keysThisLoop = Math.min(keyLimit - keyCount, ONE_TIME_KEY_GENERATION_MAX_NUMBER);

        getOlmDevice().generateOneTimeKeys(keysThisLoop);

        uploadOneTimeKeys(new SimpleApiCallback<KeysUploadResponse>(callback) {
            @Override
            public void onSuccess(final KeysUploadResponse response) {
                getEncryptingThreadHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (response.hasOneTimeKeyCountsForAlgorithm("signed_curve25519")) {
                            uploadLoop(response.oneTimeKeyCountsForAlgorithm("signed_curve25519"), keyLimit, callback);
                        } else {
                            Log.e(LOG_TAG, "## uploadLoop() : response for uploading keys does not contain one_time_key_counts.signed_curve25519");
                            getUIHandler().post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onUnexpectedError(
                                            new Exception("response for uploading keys does not contain one_time_key_counts.signed_curve25519"));
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    /**
     * Check if the OTK must be uploaded.
     */
    private void maybeUploadOneTimeKeys() {
        maybeUploadOneTimeKeys(null);
    }

    /**
     * Check if the OTK must be uploaded.
     *
     * @param callback the asynchronous callback
     */
    private void maybeUploadOneTimeKeys(final ApiCallback<Void> callback) {
        if (mOneTimeKeyCheckInProgress) {
            getUIHandler().post(new Runnable() {
                @Override
                public void run() {
                    if (null != callback) {
                        callback.onSuccess(null);
                    }
                }
            });
            return;
        }

        if ((System.currentTimeMillis() - mLastOneTimeKeyCheck) < ONE_TIME_KEY_UPLOAD_PERIOD) {
            // we've done a key upload recently.
            getUIHandler().post(new Runnable() {
                @Override
                public void run() {
                    if (null != callback) {
                        callback.onSuccess(null);
                    }
                }
            });
            return;
        }

        mLastOneTimeKeyCheck = System.currentTimeMillis();

        mOneTimeKeyCheckInProgress = true;

        // We then check how many keys we can store in the Account object.
        final long maxOneTimeKeys = getOlmDevice().getMaxNumberOfOneTimeKeys();

        // Try to keep at most half that number on the server. This leaves the
        // rest of the slots free to hold keys that have been claimed from the
        // server but we haven't recevied a message for.
        // If we run out of slots when generating new keys then olm will
        // discard the oldest private keys first. This will eventually clean
        // out stale private keys that won't receive a message.
        final int keyLimit = (int) Math.floor(maxOneTimeKeys / 2.0);

        if (null != mOneTimeKeyCount) {
            uploadOTK(mOneTimeKeyCount, keyLimit, callback);
        } else {
            // ask the server how many keys we have
            mCryptoRestClient.uploadKeys(null, null, mMyDevice.deviceId, new ApiCallback<KeysUploadResponse>() {
                private void onFailed(String errorMessage) {
                    if (null != errorMessage) {
                        Log.e(LOG_TAG, "## uploadKeys() : failed " + errorMessage);
                    }
                    mOneTimeKeyCount = null;
                    mOneTimeKeyCheckInProgress = false;
                }

                @Override
                public void onSuccess(final KeysUploadResponse keysUploadResponse) {
                    getEncryptingThreadHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (!hasBeenReleased()) {
                                // We need to keep a pool of one time public keys on the server so that
                                // other devices can start conversations with us. But we can only store
                                // a finite number of private keys in the olm Account object.
                                // To complicate things further then can be a delay between a device
                                // claiming a public one time key from the server and it sending us a
                                // message. We need to keep the corresponding private key locally until
                                // we receive the message.
                                // But that message might never arrive leaving us stuck with duff
                                // private keys clogging up our local storage.
                                // So we need some kind of enginering compromise to balance all of
                                // these factors.
                                int keyCount = keysUploadResponse.oneTimeKeyCountsForAlgorithm("signed_curve25519");
                                uploadOTK(keyCount, keyLimit, callback);
                            }
                        }
                    });
                }

                @Override
                public void onNetworkError(final Exception e) {
                    onFailed(e.getMessage());

                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (null != callback) {
                                callback.onNetworkError(e);
                            }
                        }
                    });
                }

                @Override
                public void onMatrixError(final MatrixError e) {
                    onFailed(e.getMessage());
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (null != callback) {
                                callback.onMatrixError(e);
                            }
                        }
                    });
                }

                @Override
                public void onUnexpectedError(final Exception e) {
                    onFailed(e.getMessage());
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (null != callback) {
                                callback.onUnexpectedError(e);
                            }
                        }
                    });
                }
            });
        }
    }

    /**
     * Upload some the OTKs.
     *
     * @param keyCount the key count
     * @param keyLimit the limit
     * @param callback the asynchronous callback
     */
    private void uploadOTK(int keyCount, int keyLimit, final ApiCallback<Void> callback) {
        uploadLoop(keyCount, keyLimit, new ApiCallback<Void>() {
            private void uploadKeysDone(String errorMessage) {
                if (null != errorMessage) {
                    Log.e(LOG_TAG, "## maybeUploadOneTimeKeys() : failed " + errorMessage);
                }
                mOneTimeKeyCount = null;
                mOneTimeKeyCheckInProgress = false;
            }

            @Override
            public void onSuccess(Void info) {
                Log.d(LOG_TAG, "## maybeUploadOneTimeKeys() : succeeded");
                uploadKeysDone(null);

                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (null != callback) {
                            callback.onSuccess(null);
                        }
                    }
                });
            }

            @Override
            public void onNetworkError(final Exception e) {
                uploadKeysDone(e.getMessage());

                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (null != callback) {
                            callback.onNetworkError(e);
                        }
                    }
                });
            }

            @Override
            public void onMatrixError(final MatrixError e) {
                uploadKeysDone(e.getMessage());

                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (null != callback) {
                            callback.onMatrixError(e);
                        }
                    }
                });
            }

            @Override
            public void onUnexpectedError(final Exception e) {
                uploadKeysDone(e.getMessage());
                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (null != callback) {
                            callback.onUnexpectedError(e);
                        }
                    }
                });
            }
        });

    }

    /**
     * Upload my user's one time keys.
     * This method must called on getEncryptingThreadHandler() thread.
     * The callback will called on UI thread.
     *
     * @param callback the asynchronous callback
     */
    private void uploadOneTimeKeys(final ApiCallback<KeysUploadResponse> callback) {
        final Map<String, Map<String, String>> oneTimeKeys = mOlmDevice.getOneTimeKeys();
        Map<String, Object> oneTimeJson = new HashMap<>();

        Map<String, String> curve25519Map = oneTimeKeys.get(OlmAccount.JSON_KEY_ONE_TIME_KEY);

        if (null != curve25519Map) {
            for (String key_id : curve25519Map.keySet()) {
                Map<String, Object> k = new HashMap<>();
                k.put("key", curve25519Map.get(key_id));

                // the key is also signed
                k.put("signatures", signObject(JsonUtility.getCanonicalizedJsonString(k)));

                oneTimeJson.put("signed_curve25519:" + key_id, k);
            }
        }

        // For now, we set the device id explicitly, as we may not be using the
        // same one as used in login.
        mCryptoRestClient.uploadKeys(null, oneTimeJson, mMyDevice.deviceId, new SimpleApiCallback<KeysUploadResponse>(callback) {
            @Override
            public void onSuccess(final KeysUploadResponse info) {
                getEncryptingThreadHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (!hasBeenReleased()) {
                            mLastPublishedOneTimeKeys = oneTimeKeys;
                            mOlmDevice.markKeysAsPublished();

                            if (null != callback) {
                                getUIHandler().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        callback.onSuccess(info);
                                    }
                                });
                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * Get a decryptor for a given room and algorithm.
     * If we already have a decryptor for the given room and algorithm, return
     * it. Otherwise try to instantiate it.
     *
     * @param roomId    the room id
     * @param algorithm the crypto algorithm
     * @return the decryptor
     */
    private IMXDecrypting getRoomDecryptor(String roomId, String algorithm) {
        // sanity check
        if (TextUtils.isEmpty(algorithm)) {
            Log.e(LOG_TAG, "## getRoomDecryptor() : null algorithm");
            return null;
        }

        if (null == mRoomDecryptors) {
            Log.e(LOG_TAG, "## getRoomDecryptor() : null mRoomDecryptors");
            return null;
        }

        IMXDecrypting alg = null;

        if (!TextUtils.isEmpty(roomId)) {
            synchronized (mRoomDecryptors) {
                if (!mRoomDecryptors.containsKey(roomId)) {
                    mRoomDecryptors.put(roomId, new HashMap<String, IMXDecrypting>());
                }

                alg = mRoomDecryptors.get(roomId).get(algorithm);
            }

            if (null != alg) {
                return alg;
            }
        }

        Class<IMXDecrypting> decryptingClass = MXCryptoAlgorithms.sharedAlgorithms().decryptorClassForAlgorithm(algorithm);

        if (null != decryptingClass) {
            try {
                Constructor<?> ctor = decryptingClass.getConstructors()[0];
                alg = (IMXDecrypting) ctor.newInstance();

                if (null != alg) {
                    alg.initWithMatrixSession(mSession, this);

                    if (!TextUtils.isEmpty(roomId)) {
                        synchronized (mRoomDecryptors) {
                            mRoomDecryptors.get(roomId).put(algorithm, alg);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "## getRoomDecryptor() : fail to load the class", e);
                return null;
            }
        }

        return alg;
    }

    /**
     * Export the crypto keys
     *
     * @param password the password
     * @param callback the exported keys
     */
    @Override
    public void exportRoomKeys(final String password, final ApiCallback<byte[]> callback) {
        exportRoomKeys(password, MXMegolmExportEncryption.DEFAULT_ITERATION_COUNT, callback);
    }

    /**
     * Export the crypto keys
     *
     * @param password         the password
     * @param anIterationCount the encryption iteration count (0 means no encryption)
     * @param callback         the exported keys
     */
    public void exportRoomKeys(final String password, int anIterationCount, final ApiCallback<byte[]> callback) {
        final int iterationCount = Math.max(0, anIterationCount);

        getDecryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                if (null == mCryptoStore) {
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(new byte[0]);
                        }
                    });
                    return;
                }

                List<MegolmSessionData> exportedSessions = new ArrayList<>();

                List<MXOlmInboundGroupSession2> inboundGroupSessions = mCryptoStore.getInboundGroupSessions();

                for (MXOlmInboundGroupSession2 session : inboundGroupSessions) {
                    MegolmSessionData megolmSessionData = session.exportKeys();

                    if (null != megolmSessionData) {
                        exportedSessions.add(megolmSessionData);
                    }
                }

                final byte[] encryptedRoomKeys;

                try {
                    encryptedRoomKeys = MXMegolmExportEncryption
                            .encryptMegolmKeyFile(JsonUtility.getGson(false).toJsonTree(exportedSessions).toString(), password, iterationCount);
                } catch (Exception e) {
                    callback.onUnexpectedError(e);
                    return;
                }

                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(encryptedRoomKeys);
                    }
                });
            }
        });
    }

    /**
     * Import the room keys
     *
     * @param roomKeysAsArray  the room keys as array.
     * @param password         the password
     * @param progressListener the progress listener
     * @param callback         the asynchronous callback.
     */
    @Override
    public void importRoomKeys(final byte[] roomKeysAsArray,
                               final String password,
                               @Nullable final ProgressListener progressListener,
                               final ApiCallback<ImportRoomKeysResult> callback) {
        getDecryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                Log.d(LOG_TAG, "## importRoomKeys starts");

                long t0 = System.currentTimeMillis();
                String roomKeys;

                try {
                    roomKeys = MXMegolmExportEncryption.decryptMegolmKeyFile(roomKeysAsArray, password);
                } catch (final Exception e) {
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onUnexpectedError(e);
                        }
                    });
                    return;
                }

                List<MegolmSessionData> importedSessions;

                long t1 = System.currentTimeMillis();

                Log.d(LOG_TAG, "## importRoomKeys : decryptMegolmKeyFile done in " + (t1 - t0) + " ms");

                try {
                    importedSessions = JsonUtility.getGson(false).fromJson(roomKeys, new TypeToken<List<MegolmSessionData>>() {
                    }.getType());
                } catch (final Exception e) {
                    Log.e(LOG_TAG, "## importRoomKeys failed " + e.getMessage(), e);
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onUnexpectedError(e);
                        }
                    });
                    return;
                }

                long t2 = System.currentTimeMillis();

                Log.d(LOG_TAG, "## importRoomKeys : JSON parsing " + (t2 - t1) + " ms");

                importMegolmSessionsData(importedSessions, true, progressListener, callback);
            }
        });
    }

    /**
     * Import a list of megolm session keys.
     *
     * @param megolmSessionsData megolm sessions.
     * @param backUpKeys         true to back up them to the homeserver.
     * @param progressListener   the progress listener
     * @param callback
     */
    public void importMegolmSessionsData(final List<MegolmSessionData> megolmSessionsData,
                                         final boolean backUpKeys,
                                         @Nullable final ProgressListener progressListener,
                                         final ApiCallback<ImportRoomKeysResult> callback) {
        getDecryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();

                final int totalNumbersOfKeys = megolmSessionsData.size();
                int cpt = 0;
                int lastProgress = 0;
                int totalNumbersOfImportedKeys = 0;

                if (progressListener != null) {
                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            progressListener.onProgress(0, 100);
                        }
                    });
                }

                List<MXOlmInboundGroupSession2> sessions = mOlmDevice.importInboundGroupSessions(megolmSessionsData);

                for (MegolmSessionData megolmSessionData : megolmSessionsData) {
                    cpt++;

                    if ((null != megolmSessionData) && mRoomDecryptors.containsKey(megolmSessionData.roomId)) {
                        IMXDecrypting decrypting = mRoomDecryptors.get(megolmSessionData.roomId).get(megolmSessionData.algorithm);

                        if (null != decrypting) {
                            try {
                                String sessionId = megolmSessionData.sessionId;
                                Log.d(LOG_TAG, "## importRoomKeys retrieve mSenderKey " + megolmSessionData.senderKey + " sessionId " + sessionId);

                                totalNumbersOfImportedKeys++;

                                // cancel any outstanding room key requests for this session
                                RoomKeyRequestBody roomKeyRequestBody = new RoomKeyRequestBody();

                                roomKeyRequestBody.algorithm = megolmSessionData.algorithm;
                                roomKeyRequestBody.roomId = megolmSessionData.roomId;
                                roomKeyRequestBody.senderKey = megolmSessionData.senderKey;
                                roomKeyRequestBody.sessionId = megolmSessionData.sessionId;

                                cancelRoomKeyRequest(roomKeyRequestBody);

                                // Have another go at decrypting events sent with this session
                                decrypting.onNewSession(megolmSessionData.senderKey, sessionId);
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "## importRoomKeys() : onNewSession failed " + e.getMessage(), e);
                            }
                        }
                    }

                    if (progressListener != null) {
                        final int progress = 100 * cpt / totalNumbersOfKeys;

                        if (lastProgress != progress) {
                            lastProgress = progress;

                            getUIHandler().post(new Runnable() {
                                @Override
                                public void run() {
                                    progressListener.onProgress(progress, 100);
                                }
                            });
                        }
                    }
                }

                // Do not back up the key if it comes from a backup recovery
                if (backUpKeys) {
                    mKeysBackup.maybeBackupKeys();
                } else {
                    mCryptoStore.markBackupDoneForInboundGroupSessions(sessions);
                }

                long t1 = System.currentTimeMillis();

                Log.d(LOG_TAG, "## importMegolmSessionsData : sessions import " + (t1 - t0) + " ms (" + megolmSessionsData.size() + " sessions)");

                final int finalTotalNumbersOfImportedKeys = totalNumbersOfImportedKeys;

                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(new ImportRoomKeysResult(totalNumbersOfKeys, finalTotalNumbersOfImportedKeys));
                    }
                });
            }
        });
    }

    /**
     * Tells if the encryption must fail if some unknown devices are detected.
     *
     * @return true to warn when some unknown devices are detected.
     */
    @Override
    public boolean warnOnUnknownDevices() {
        return mWarnOnUnknownDevices;
    }

    /**
     * Update the warn status when some unknown devices are detected.
     *
     * @param warn true to warn when some unknown devices are detected.
     */
    @Override
    public void setWarnOnUnknownDevices(boolean warn) {
        mWarnOnUnknownDevices = warn;
    }

    /**
     * Provides the list of unknown devices
     *
     * @param devicesInRoom the devices map
     * @return the unknown devices map
     */
    public static MXUsersDevicesMap<MXDeviceInfo> getUnknownDevices(MXUsersDevicesMap<MXDeviceInfo> devicesInRoom) {
        MXUsersDevicesMap<MXDeviceInfo> unknownDevices = new MXUsersDevicesMap<>();

        List<String> userIds = devicesInRoom.getUserIds();
        for (String userId : userIds) {
            List<String> deviceIds = devicesInRoom.getUserDeviceIds(userId);
            for (String deviceId : deviceIds) {
                MXDeviceInfo deviceInfo = devicesInRoom.getObject(deviceId, userId);

                if (deviceInfo.isUnknown()) {
                    unknownDevices.setObject(deviceInfo, userId, deviceId);
                }
            }
        }

        return unknownDevices;
    }

    /**
     * Check if the user ids list have some unknown devices.
     * A success means there is no unknown devices.
     * If there are some unknown devices, a MXCryptoError.UNKNOWN_DEVICES_CODE exception is triggered.
     *
     * @param userIds  the user ids list
     * @param callback the asynchronous callback.
     */
    @Override
    public void checkUnknownDevices(List<String> userIds, final ApiCallback<Void> callback) {
        // force the refresh to ensure that the devices list is up-to-date
        mDevicesList.downloadKeys(userIds, true, new SimpleApiCallback<MXUsersDevicesMap<MXDeviceInfo>>(callback) {
            @Override
            public void onSuccess(MXUsersDevicesMap<MXDeviceInfo> devicesMap) {
                MXUsersDevicesMap<MXDeviceInfo> unknownDevices = MXCryptoImpl.getUnknownDevices(devicesMap);

                if (unknownDevices.getMap().size() == 0) {
                    callback.onSuccess(null);
                } else {
                    // trigger an an unknown devices exception
                    callback.onMatrixError(new MXCryptoError(MXCryptoError.UNKNOWN_DEVICES_CODE,
                            MXCryptoError.UNABLE_TO_ENCRYPT, MXCryptoError.UNKNOWN_DEVICES_REASON, unknownDevices));
                }
            }
        });
    }

    /**
     * Set the global override for whether the client should ever send encrypted
     * messages to unverified devices.
     * If false, it can still be overridden per-room.
     * If true, it overrides the per-room settings.
     *
     * @param block    true to unilaterally blacklist all
     * @param callback the asynchronous callback.
     */
    @Override
    public void setGlobalBlacklistUnverifiedDevices(final boolean block, final ApiCallback<Void> callback) {
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                mCryptoStore.setGlobalBlacklistUnverifiedDevices(block);
                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (null != callback) {
                            callback.onSuccess(null);
                        }
                    }
                });
            }
        });
    }

    /**
     * Tells whether the client should ever send encrypted messages to unverified devices.
     * The default value is false.
     * This function must be called in the getEncryptingThreadHandler() thread.
     *
     * @return true to unilaterally blacklist all unverified devices.
     */
    public boolean getGlobalBlacklistUnverifiedDevices() {
        return mCryptoStore.getGlobalBlacklistUnverifiedDevices();
    }

    /**
     * Tells whether the client should ever send encrypted messages to unverified devices.
     * The default value is false.
     * messages to unverified devices.
     *
     * @param callback the asynchronous callback
     */
    @Override
    public void getGlobalBlacklistUnverifiedDevices(final ApiCallback<Boolean> callback) {
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                if (null != callback) {
                    final boolean status = getGlobalBlacklistUnverifiedDevices();

                    getUIHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(status);
                        }
                    });
                }
            }
        });
    }

    /**
     * Tells whether the client should encrypt messages only for the verified devices
     * in this room.
     * The default value is false.
     * This function must be called in the getEncryptingThreadHandler() thread.
     *
     * @param roomId the room id
     * @return true if the client should encrypt messages only for the verified devices.
     */
    public boolean isRoomBlacklistUnverifiedDevices(String roomId) {
        if (null != roomId) {
            return mCryptoStore.getRoomsListBlacklistUnverifiedDevices().contains(roomId);
        } else {
            return false;
        }
    }

    /**
     * Tells whether the client should encrypt messages only for the verified devices
     * in this room.
     * The default value is false.
     * This function must be called in the getEncryptingThreadHandler() thread.
     *
     * @param roomId   the room id
     * @param callback the asynchronous callback
     */
    @Override
    public void isRoomBlacklistUnverifiedDevices(final String roomId, final ApiCallback<Boolean> callback) {
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                final boolean status = isRoomBlacklistUnverifiedDevices(roomId);

                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (null != callback) {
                            callback.onSuccess(status);
                        }
                    }
                });
            }
        });
    }

    /**
     * Manages the room black-listing for unverified devices.
     *
     * @param roomId   the room id
     * @param add      true to add the room id to the list, false to remove it.
     * @param callback the asynchronous callback
     */
    private void setRoomBlacklistUnverifiedDevices(final String roomId, final boolean add, final ApiCallback<Void> callback) {
        final CryptoRoom room = mSession.getDataHandler().getRoom(roomId);

        // sanity check
        if (null == room) {
            getUIHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess(null);
                }
            });

            return;
        }

        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                List<String> roomIds = mCryptoStore.getRoomsListBlacklistUnverifiedDevices();

                if (add) {
                    if (!roomIds.contains(roomId)) {
                        roomIds.add(roomId);
                    }
                } else {
                    roomIds.remove(roomId);
                }

                mCryptoStore.setRoomsListBlacklistUnverifiedDevices(roomIds);

                getUIHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (null != callback) {
                            callback.onSuccess(null);
                        }
                    }
                });
            }
        });
    }


    /**
     * Add this room to the ones which don't encrypt messages to unverified devices.
     *
     * @param roomId   the room id
     * @param callback the asynchronous callback
     */
    @Override
    public void setRoomBlacklistUnverifiedDevices(final String roomId, final ApiCallback<Void> callback) {
        setRoomBlacklistUnverifiedDevices(roomId, true, callback);
    }

    /**
     * Remove this room to the ones which don't encrypt messages to unverified devices.
     *
     * @param roomId   the room id
     * @param callback the asynchronous callback
     */
    @Override
    public void setRoomUnBlacklistUnverifiedDevices(final String roomId, final ApiCallback<Void> callback) {
        setRoomBlacklistUnverifiedDevices(roomId, false, callback);
    }

    /**
     * Send a request for some room keys, if we have not already done so.
     *
     * @param requestBody requestBody
     * @param recipients  recipients
     */
    public void requestRoomKey(final RoomKeyRequestBody requestBody, final List<Map<String, String>> recipients) {
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                mOutgoingRoomKeyRequestManager.sendRoomKeyRequest(requestBody, recipients);
            }
        });
    }

    /**
     * Cancel any earlier room key request
     *
     * @param requestBody requestBody
     */
    @Override
    public void cancelRoomKeyRequest(final RoomKeyRequestBody requestBody) {
        getEncryptingThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                mOutgoingRoomKeyRequestManager.cancelRoomKeyRequest(requestBody);
            }
        });
    }

    /**
     * Re request the encryption keys required to decrypt an event.
     *
     * @param event the event to decrypt again.
     */
    @Override
    public void reRequestRoomKeyForEvent(@NonNull final CryptoEvent event) {
        if (event.getWireContent().isJsonObject()) {
            JsonObject wireContent = event.getWireContent().getAsJsonObject();

            final String algorithm = wireContent.get("algorithm").getAsString();
            final String senderKey = wireContent.get("sender_key").getAsString();
            final String sessionId = wireContent.get("session_id").getAsString();

            getEncryptingThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    RoomKeyRequestBody requestBody = new RoomKeyRequestBody();

                    requestBody.roomId = event.getRoomId();
                    requestBody.algorithm = algorithm;
                    requestBody.senderKey = senderKey;
                    requestBody.sessionId = sessionId;

                    mOutgoingRoomKeyRequestManager.resendRoomKeyRequest(requestBody);
                }
            });
        }
    }

    @Override
    public CryptoRestClient getCryptoRestClient() {
        return mCryptoRestClient;
    }

    // the listeners
    public final Set<RoomKeysRequestListener> mRoomKeysRequestListeners = new HashSet<>();

    /**
     * Add a RoomKeysRequestListener listener.
     *
     * @param listener listener
     */
    @Override
    public void addRoomKeysRequestListener(RoomKeysRequestListener listener) {
        synchronized (mRoomKeysRequestListeners) {
            mRoomKeysRequestListeners.add(listener);
        }
    }

    /**
     * Add a RoomKeysRequestListener listener.
     *
     * @param listener listener
     */
    public void removeRoomKeysRequestListener(RoomKeysRequestListener listener) {
        synchronized (mRoomKeysRequestListeners) {
            mRoomKeysRequestListeners.remove(listener);
        }
    }

    /**
     * Dispatch onRoomKeyRequest
     *
     * @param request the request
     */
    private void onRoomKeyRequest(IncomingRoomKeyRequest request) {
        synchronized (mRoomKeysRequestListeners) {
            for (RoomKeysRequestListener listener : mRoomKeysRequestListeners) {
                try {
                    listener.onRoomKeyRequest(request);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "## onRoomKeyRequest() failed " + e.getMessage(), e);
                }
            }
        }
    }


    /**
     * A room key request cancellation has been received.
     *
     * @param request the cancellation request
     */
    private void onRoomKeyRequestCancellation(IncomingRoomKeyRequestCancellation request) {
        synchronized (mRoomKeysRequestListeners) {
            for (RoomKeysRequestListener listener : mRoomKeysRequestListeners) {
                try {
                    listener.onRoomKeyRequestCancellation(request);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "## onRoomKeyRequestCancellation() failed " + e.getMessage(), e);
                }
            }
        }
    }

    /* ==========================================================================================
     * DEBUG INFO
     * ========================================================================================== */

    @Override
    public String toString() {
        if (mMyDevice != null) {
            return mMyDevice.userId + " (" + mMyDevice.deviceId + ")";
        }

        return super.toString();
    }
}