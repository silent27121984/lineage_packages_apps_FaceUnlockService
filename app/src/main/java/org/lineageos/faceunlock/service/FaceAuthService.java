package org.lineageos.faceunlock.service;

import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_VENDOR;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.internal.util.custom.faceunlock.IFaceServiceReceiver;

import org.lineageos.faceunlock.AppConstants;
import org.lineageos.faceunlock.R;
import org.lineageos.faceunlock.camera.CameraFaceAuthController;
import org.lineageos.faceunlock.camera.CameraFaceEnrollController;
import org.lineageos.faceunlock.camera.CameraUtil;
import org.lineageos.faceunlock.util.NotificationUtils;
import org.lineageos.faceunlock.util.Settings;
import org.lineageos.faceunlock.util.SharedUtil;
import org.lineageos.faceunlock.util.Util;
import org.lineageos.faceunlock.vendor.VendorFaceManager;
import org.lineageos.faceunlock.vendor.impl.VendorFaceManagerImpl;

import java.util.Random;

@SuppressWarnings("SynchronizeOnNonFinalField")
public class FaceAuthService extends Service {
    private static final String ALARM_FAIL_TIMEOUT_LOCKOUT = "org.lineageos.faceunlock.ACTION_LOCKOUT_RESET";
    private static final String ALARM_TIMEOUT_FREEZED = "org.lineageos.faceunlock.freezedtimeout";
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 3600000 * 4; // 4 hours
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30000; // 30 seconds
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 10;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5;
    private static final int MSG_CHALLENGE_TIMEOUT = 100;
    private static final String TAG = FaceAuthService.class.getName();
    private AlarmManager mAlarmManager;
    private Integer mAuthErrorCount = 0;
    private Integer mAuthErrorThrottleCount = 0;
    private CameraFaceAuthController mCameraAuthController;
    private CameraFaceEnrollController mCameraEnrollController;
    private long mChallenge = 0;
    private int mChallengeCount = 0;
    private byte[] mEnrollToken;
    private VendorFaceManager mFaceAuth;
    private PendingIntent mIdleTimeoutIntent;
    private boolean mOnIdleTimer;
    private Integer mLockoutType = LOCKOUT_TYPE_DISABLED;
    private static final int LOCKOUT_TYPE_DISABLED = 0;
    private static final int LOCKOUT_TYPE_TIMED = 1;
    private static final int LOCKOUT_TYPE_PERMANENT = 2;
    private static final int LOCKOUT_TYPE_IDLE = 3;
    private PendingIntent mLockoutTimeoutIntent;
    private IFaceServiceReceiver mFaceReceiver;
    private boolean mOnLockoutTimer = false;
    private FaceAuthServiceWrapper mService;
    private SharedUtil mShareUtil;
    private boolean mUserUnlocked = false;
    private boolean mIsAuthenticated = false;
    private int mFrontCamId = 0;
    private CameraManager mCameraManager;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "OnReceive intent = " + intent);
            }
            if (action.equals(FaceAuthService.ALARM_TIMEOUT_FREEZED)) {
                Log.d(FaceAuthService.TAG, "ALARM_TIMEOUT_FREEZED");
                synchronized (mLockoutType) {
                    mLockoutType = LOCKOUT_TYPE_IDLE;
                }
            } else if (action.equals(FaceAuthService.ALARM_FAIL_TIMEOUT_LOCKOUT)) {
                Log.d(FaceAuthService.TAG, "ALARM_FAIL_TIMEOUT_LOCKOUT");
                cancelLockoutTimer();
                synchronized (mLockoutType) {
                    mLockoutType = LOCKOUT_TYPE_DISABLED;
                }
                synchronized (mAuthErrorCount) {
                    mAuthErrorCount = 0;
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF) || action.equals(Intent.ACTION_USER_PRESENT)) {
                mUserUnlocked = action.equals(Intent.ACTION_USER_PRESENT);
                updateTimersAndLockout();
            }
        }
    };
    private int mUserId;

    private CameraManager.AvailabilityCallback mCameraAvailabilityCallback =
        new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(@NonNull String cameraId) {
                super.onCameraAvailable(cameraId);
                if (mFrontCamId == Integer.parseInt(cameraId) && mIsAuthenticated) {
                    mCameraManager.unregisterAvailabilityCallback(this);
                    mIsAuthenticated = false;
                    onAuthenticated();
                }
            }

            @Override
            public void onCameraUnavailable(@NonNull String cameraId) {
                super.onCameraUnavailable(cameraId);
            }
    };

    private final CameraFaceAuthController.ServiceCallback mCameraAuthControllerCallback = new CameraFaceAuthController.ServiceCallback() {

        @Override
        public int handlePreviewData(byte[] bArr, int i, int i2) {
            int[] iArr = new int[20];
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "handleData start");
            }
            int compare = mFaceAuth.compare(bArr, i, i2, 0, true, true, iArr);
            if (Util.DEBUG) {
                Log.d(TAG, "handleData result = " + compare + " run: fake = " + iArr[0] + ", low = " + iArr[1] + ", compare score:" + iArr[2] + " live score:" + (((double) iArr[3]) / 100.0d));
            }
            synchronized (this) {
                if (mCameraAuthController == null) {
                    return -1;
                }
                if (compare == 0) {
                    mIsAuthenticated = true;
                    mCameraAuthController.stop();
                }
            }
            return compare;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void setDetectArea(Camera.Size size) {
            mFaceAuth.setDetectArea(0, 0, size.height, size.width);
        }

        @Override
        public void onTimeout(boolean withFace) {
            if (Util.DEBUG){
                Log.d(TAG, "onTimeout, withFace=" + withFace);
            }
            try {
                if (withFace) {
                    increaseAndCheckLockout();
                }
                if (mLockoutType != LOCKOUT_TYPE_DISABLED) {
                    sendLockoutError();
                }else{
                    mFaceReceiver.onAuthenticated(0, -1, mShareUtil.getByteArrayValueByKey(AppConstants.SHARED_KEY_ENROLL_TOKEN));
                }
                stopAuthrate();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            stopAuthrate();
        }

        @Override
        public void onCameraError() {
            try {
                mFaceReceiver.onError(5, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            stopAuthrate();
        }
    };
    private final CameraFaceEnrollController.CameraCallback mCameraEnrollServiceCallback = new CameraFaceEnrollController.CameraCallback() {
        static final int FEATURE_SIZE = 10000;
        final byte[] mImage = new byte[40000];
        final byte[] mSavedFeature = new byte[FEATURE_SIZE];

        @Override
        public void handleSaveFeatureResult(int i) {
        }

        @Override
        public void onFaceDetected() {
        }

        @Override
        public int handleSaveFeature(byte[] bArr, int i, int i2, int i3) {
            int[] iArr = new int[1];
            int saveFeature = mFaceAuth.saveFeature(bArr, i, i2, i3, true, mSavedFeature, mImage, iArr);
            synchronized (this) {
                if (mCameraEnrollController == null) {
                    return -1;
                }
                try {
                    int i4 = iArr[0] + 1;
                    if (saveFeature == 0) {
                        int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
                        if (intValue > 0) {
                            mFaceAuth.deleteFeature(intValue);
                        }
                        if (i4 > 0) {
                            mShareUtil.saveIntValue(AppConstants.SHARED_KEY_FACE_ID, i4);
                            mShareUtil.saveByteArrayValue(AppConstants.SHARED_KEY_ENROLL_TOKEN, mEnrollToken);
                            Util.setFaceUnlockAvailable(getApplicationContext());
                            NotificationUtils.checkAndShowNotification(getApplicationContext());
                        }
                        stopEnroll();
                        mFaceReceiver.onEnrollResult(i4, mUserId, 0);
                    } else if (saveFeature == 19) {
                        mFaceReceiver.onEnrollResult(i4, mUserId, 1);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                return saveFeature;
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void setDetectArea(Camera.Size size) {
            mFaceAuth.setDetectArea(0, 0, size.height, size.width);
        }

        @Override
        public void onTimeout() {
            try {
                stopEnroll();
                mFaceReceiver.onError(3, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCameraError() {
            try {
                stopEnroll();
                mFaceReceiver.onError(5, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    };
    private FaceHandler mWorkHandler;

    @Override
    public int onStartCommand(Intent intent, int i, int i2) {
        return START_REDELIVER_INTENT;
    }

    @Override
    @Nullable
    public IBinder onBind(Intent intent) {
        if (Util.DEBUG) {
            Log.i(TAG, "onBind");
        }
        return mService;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Util.DEBUG) {
            Log.i(TAG, "onCreate start");
        }
        mCameraManager = getSystemService(CameraManager.class);
        mFrontCamId = CameraUtil.getFrontFacingCameraId(this);
        mService = new FaceAuthServiceWrapper();
        HandlerThread handlerThread = new HandlerThread(TAG, -2);
        handlerThread.start();
        mWorkHandler = new FaceHandler(handlerThread.getLooper());
        mShareUtil = new SharedUtil(this);
        mFaceAuth = new VendorFaceManagerImpl(this, getResources().getBoolean(R.bool.use_alternative_vendor_impl));
        mUserId = Util.getUserId(this);
        if (!Util.isFaceUnlockDisabledByDPM(this) && Util.isFaceUnlockEnrolled(this)) {
            mWorkHandler.post(() -> mFaceAuth.init());
        }
        mAlarmManager = getSystemService(AlarmManager.class);
        mIdleTimeoutIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(ALARM_TIMEOUT_FREEZED), PendingIntent.FLAG_MUTABLE);
        mLockoutTimeoutIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(ALARM_FAIL_TIMEOUT_LOCKOUT), PendingIntent.FLAG_MUTABLE);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ALARM_TIMEOUT_FREEZED);
        intentFilter.addAction(ALARM_FAIL_TIMEOUT_LOCKOUT);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(mReceiver, intentFilter);
        if (Util.DEBUG) {
            Log.d(TAG, "OnCreate end");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Util.DEBUG) {
            Log.d(TAG, "onDestroy");
        }
        mFaceAuth.release();
        unregisterReceiver(mReceiver);
    }

    private void onAuthenticated(){
        try {
            mFaceReceiver.onAuthenticated(mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID), mUserId, mShareUtil.getByteArrayValueByKey(AppConstants.SHARED_KEY_ENROLL_TOKEN));
            resetLockoutCount();
            stopAuthrate();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void stopEnroll() {
        if (mCameraEnrollController != null) {
            mCameraEnrollController.stop(mCameraEnrollServiceCallback);
        }
        mCameraEnrollController = null;
        mEnrollToken = null;
        mFaceAuth.saveFeatureStop();
    }

    private void stopAuthrate() {
        synchronized (this) {
            if (mCameraAuthController != null) {
                mCameraAuthController.stop();
            }
            mCameraAuthController = null;
        }
        mFaceAuth.compareStop();
    }

    private void stopCurrentWork() {
        if (mCameraAuthController != null) {
            try {
                mFaceReceiver.onError(10, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            stopAuthrate();
        }
        if (mCameraEnrollController != null) {
            try {
                mFaceReceiver.onError(10, 0);
            } catch (RemoteException e2) {
                e2.printStackTrace();
            }
            stopEnroll();
        }
    }

    private void startIdleTimer() {
        mOnIdleTimer = true;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + DEFAULT_IDLE_TIMEOUT_MS, mIdleTimeoutIntent);
    }

    private void cancelIdleTimer() {
        mOnIdleTimer = false;
        mAlarmManager.cancel(mIdleTimeoutIntent);
    }

    private void startLockoutTimer() {
        long elapsedRealtime = SystemClock.elapsedRealtime() + FAIL_LOCKOUT_TIMEOUT_MS;
        mOnLockoutTimer = true;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, elapsedRealtime, mLockoutTimeoutIntent);
    }

    private void cancelLockoutTimer() {
        if (mOnLockoutTimer) {
            mAlarmManager.cancel(mLockoutTimeoutIntent);
            mOnLockoutTimer = false;
        }
    }

    private void increaseAndCheckLockout() {
        if (mOnLockoutTimer || mLockoutType != LOCKOUT_TYPE_DISABLED){
            return;
        }
        synchronized (mAuthErrorCount) {
            mAuthErrorCount += 1;
            mAuthErrorThrottleCount += 1;
            Log.d(TAG, "increaseAndCheckLockout, mAuthErrorCount=" + mAuthErrorCount + ", mAuthErrorThrottleCount=" + mAuthErrorThrottleCount);
            Log.d(TAG, "mUserUnlocked=" + mUserUnlocked);

            if (mUserUnlocked && mAuthErrorCount == MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED){
                Log.d(TAG, "Too many attempts, lockout permanent because device is unlocked");
                mLockoutType = LOCKOUT_TYPE_PERMANENT;
                cancelLockoutTimer();
            } else if (mAuthErrorThrottleCount == MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT) {
                synchronized (mLockoutType) {
                    Log.d(TAG, "Too many attempts, lockout permanent");
                    mLockoutType = LOCKOUT_TYPE_PERMANENT;
                    cancelLockoutTimer();
                }
            } else if (mAuthErrorCount == MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED) {
                synchronized (mLockoutType) {
                    Log.d(TAG, "Too many attempts, lockout for 30s");
                    mLockoutType = LOCKOUT_TYPE_TIMED;
                }
                mAuthErrorCount = 0;
                startLockoutTimer();
            }
        }
    }

    private void updateTimersAndLockout(){
        if (mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID) > -1 && !mUserUnlocked) {
            if (!mOnIdleTimer){
                cancelIdleTimer();
                startIdleTimer();
            }
        }else{
            cancelIdleTimer();
            resetLockoutCount();
        }
    }

    private void resetLockoutCount() {
        synchronized (mAuthErrorCount) {
            mAuthErrorCount = 0;
            mAuthErrorThrottleCount = 0;
            mLockoutType = LOCKOUT_TYPE_DISABLED;
        }
        cancelLockoutTimer();
    }

    private final class FaceAuthServiceWrapper extends IFaceService.Stub {
        private FaceAuthServiceWrapper() {
        }

        @Override
        public boolean getFeature(int i, int i2) {
            return false;
        }

        @Override
        public void setFeature(int i, boolean z, byte[] bArr, int i2) {
        }

        @Override
        public void setCallback(IFaceServiceReceiver faceServiceReceiver) {
            mFaceReceiver = faceServiceReceiver;
        }

        @Override
        public void enroll(byte[] bArr, int i, int[] iArr) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "enroll");
            }
            boolean z = true;
            if (Util.isFaceUnlockDisabledByDPM(FaceAuthService.this) || mChallenge == 0 || bArr == null) {
                StringBuilder sb = new StringBuilder();
                sb.append("enroll error ! hasChallenge = ");
                sb.append(mChallenge != 0);
                sb.append(" hasCryptoToken = ");
                if (bArr == null) {
                    z = false;
                }
                sb.append(z);
                Log.e(TAG, sb.toString());
                try {
                    mFaceReceiver.onError(3, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                mEnrollToken = bArr;
                int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
                if (intValue > 0) {
                    mFaceAuth.deleteFeature(intValue - 1);
                    mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_FACE_ID);
                    mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_ENROLL_TOKEN);
                }
                resetLockoutCount();
                mWorkHandler.post(() -> {
                    mFaceAuth.saveFeatureStart();
                    synchronized (this) {
                        if (mCameraEnrollController == null) {
                            mCameraEnrollController = CameraFaceEnrollController.getInstance();
                        }
                        mCameraEnrollController.start(mCameraEnrollServiceCallback, 0);
                    }
                });
            }
        }

        @Override
        public void cancel() {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "cancel");
            }
            mWorkHandler.post(() -> {
                if (mCameraAuthController != null) {
                    stopAuthrate();
                }
                if (mCameraEnrollController != null) {
                    stopEnroll();
                }
                try {
                    mFaceReceiver.onError(5, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public void authenticate(long j) {
            mCameraManager.registerAvailabilityCallback(mCameraAvailabilityCallback, mWorkHandler);
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "authenticate");
            }
            if (!Settings.isFaceUnlockAvailable(FaceAuthService.this) ||
                    ContextCompat.checkSelfPermission(FaceAuthService.this.getApplicationContext(), "android.permission.CAMERA") != 0) {
                try {
                    mFaceReceiver.onError(5, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }else if (Util.isFaceUnlockDisabledByDPM(FaceAuthService.this)) {
                try {
                    mFaceReceiver.onError(5, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else if (mLockoutType != LOCKOUT_TYPE_DISABLED) {
                sendLockoutError();
            } else {
                mWorkHandler.post(() -> {
                    mFaceAuth.compareStart();
                    synchronized (this) {
                        if (mCameraAuthController == null) {
                            mCameraAuthController = new CameraFaceAuthController(FaceAuthService.this, mCameraAuthControllerCallback);
                        } else {
                            mCameraAuthController.stop();
                        }
                        mCameraAuthController.start();
                    }
                });
            }
        }

        @Override
        public void remove(final int i) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "remove");
            }
            mWorkHandler.post(() -> {
                int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
                if (!(i == 0 || intValue == i)) {
                    Log.e(TAG, "Remove unsaved feature! " + i);
                }
                mFaceAuth.deleteFeature(intValue - 1);
                mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_FACE_ID);
                mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_ENROLL_TOKEN);
                Util.setFaceUnlockAvailable(getApplicationContext());
                try {
                    if (i == 0) {
                        mFaceReceiver.onRemoved(new int[]{intValue}, mUserId);
                    } else {
                        mFaceReceiver.onRemoved(new int[]{i}, mUserId);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public int enumerate() {
            int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
            final int[] iArr = intValue > -1 ? new int[]{intValue} : new int[0];
            mWorkHandler.post(() -> {
                try {
                    if (Util.DEBUG) {
                        Log.d(TAG, "enumerate mFaceReceiver = " + mFaceReceiver);
                    }
                    if (mFaceReceiver == null) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if (mFaceReceiver != null) {
                        mFaceReceiver.onEnumerate(iArr, mUserId);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
            return 0;
        }

        @Override
        public int getFeatureCount() {
            return mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID) > -1 ? 1 : 0;
        }

        @Override
        public long generateChallenge(int i) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "generateChallenge + " + i);
            }
            if (mChallengeCount <= 0 || mChallenge == 0) {
                mChallenge = new Random().nextLong();
            }
            mChallengeCount += 1;
            mWorkHandler.removeMessages(MSG_CHALLENGE_TIMEOUT);
            mWorkHandler.sendEmptyMessageDelayed(MSG_CHALLENGE_TIMEOUT, i * 1000);
            return mChallenge;
        }

        @Override
        public int revokeChallenge() {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "revokeChallenge");
            }
            mChallengeCount -= 1;
            if (mChallengeCount <= 0 && mChallenge != 0) {
                mChallenge = 0;
                mChallengeCount = 0;
                mWorkHandler.removeMessages(MSG_CHALLENGE_TIMEOUT);
                stopCurrentWork();
            }
            return 0;
        }

        @Override
        public int getAuthenticatorId() {
            return -1;
        }

        @Override
        public void resetLockout(byte[] bArr) {
            resetLockoutCount();
        }
    }

    private void sendLockoutError(){
        int errorCode = 0;
        switch (mLockoutType){
            case LOCKOUT_TYPE_PERMANENT:
                errorCode = BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                break;
            case LOCKOUT_TYPE_TIMED:
                errorCode = BIOMETRIC_ERROR_LOCKOUT;
                break;
            case LOCKOUT_TYPE_IDLE:
                errorCode = BIOMETRIC_ERROR_VENDOR;
                break;
        }
        try {
            mFaceReceiver.onError(errorCode, 0);
        } catch (RemoteException e2) {
            e2.printStackTrace();
        }
    }

    private class FaceHandler extends Handler {
        public FaceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            if (message.what == MSG_CHALLENGE_TIMEOUT) {
                mChallenge = 0;
                mChallengeCount = 0;
                stopCurrentWork();
            }
        }
    }
}
