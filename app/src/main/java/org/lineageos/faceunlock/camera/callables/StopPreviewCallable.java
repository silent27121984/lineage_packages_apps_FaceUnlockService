package org.lineageos.faceunlock.camera.callables;

import android.hardware.Camera;

import org.lineageos.faceunlock.camera.listeners.CameraListener;

public class StopPreviewCallable extends CameraCallable {
    private static final String TAG = StopPreviewCallable.class.getSimpleName();

    public StopPreviewCallable(CameraListener cameraListener) {
        super(cameraListener);
    }

    @SuppressWarnings("deprecation")
    @Override
    public CallableReturn call() {
        Camera camera = getCameraData().mCamera;
        if (camera == null) {
            return new CallableReturn(new Exception("Camera isn't opened"));
        }
        camera.stopPreview();
        return new CallableReturn(null);
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
