/**
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.cyanogenmod.nemesis.pano;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.cyanogenmod.nemesis.CameraActivity;
import org.cyanogenmod.nemesis.R;
import org.cyanogenmod.nemesis.SnapshotManager;
import org.cyanogenmod.nemesis.Storage;
import org.cyanogenmod.nemesis.Util;
import org.cyanogenmod.nemesis.feats.CaptureTransformer;
import org.cyanogenmod.nemesis.ui.ShutterButton;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Nemesis interface to interact with Google's mosaic interface
 * Strongly inspired from AOSP's PanoramaModule
 */
public class MosaicProxy extends CaptureTransformer
        implements SurfaceTexture.OnFrameAvailableListener, TextureView.SurfaceTextureListener {
    private static final String TAG = "CAM PanoModule";

    public static final int DEFAULT_SWEEP_ANGLE = 160;
    public static final int DEFAULT_BLEND_MODE = Mosaic.BLENDTYPE_HORIZONTAL;
    public static final int DEFAULT_CAPTURE_PIXELS = 960 * 720;

    private static final int MSG_LOW_RES_FINAL_MOSAIC_READY = 1;
    private static final int MSG_GENERATE_FINAL_MOSAIC_ERROR = 2;
    private static final int MSG_RESET_TO_PREVIEW = 3;
    private static final int MSG_CLEAR_SCREEN_DELAY = 4;

    private static final int PREVIEW_STOPPED = 0;
    private static final int PREVIEW_ACTIVE = 1;
    private static final int CAPTURE_STATE_VIEWFINDER = 0;
    private static final int CAPTURE_STATE_MOSAIC = 1;
    private final String mPreparePreviewString;
    private final String mDialogTitle;
    private final String mDialogOkString;
    private final String mDialogPanoramaFailedString;
    private final String mDialogWaitingPreviousString;

    private Runnable mOnFrameAvailableRunnable;
    private MosaicFrameProcessor mMosaicFrameProcessor;
    private MosaicPreviewRenderer mMosaicPreviewRenderer;
    private boolean mMosaicFrameProcessorInitialized;
    private float mHorizontalViewAngle;
    private float mVerticalViewAngle;

    private int mCaptureState;
    private FrameLayout mGLRootView;
    private TextureView mGLSurfaceView;
    private CameraActivity mActivity;
    private Handler mMainHandler;
    private SurfaceTexture mCameraTexture;
    private SurfaceTexture mMosaicTexture;
    private boolean mCancelComputation;
    private long mTimeTaken;
    private Matrix mProgressDirectionMatrix = new Matrix();

    private class MosaicJpeg {
        public MosaicJpeg(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.isValid = true;
        }

        public MosaicJpeg() {
            this.data = null;
            this.width = 0;
            this.height = 0;
            this.isValid = false;
        }

        public final byte[] data;
        public final int width;
        public final int height;
        public final boolean isValid;
    }

    public MosaicProxy(CameraActivity activity) {
        super(activity.getCamManager(), activity.getSnapManager());
        mActivity = activity;
        mCaptureState = CAPTURE_STATE_VIEWFINDER;

        mGLRootView = (FrameLayout) mActivity.findViewById(R.id.gl_renderer_container);
        mGLSurfaceView = new TextureView(mActivity);
        mGLRootView.addView(mGLSurfaceView);

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) mGLSurfaceView.getLayoutParams();
        layoutParams.width = FrameLayout.LayoutParams.WRAP_CONTENT;
        layoutParams.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        layoutParams.gravity = Gravity.CENTER;

        mGLSurfaceView.setSurfaceTextureListener(this);

        mMosaicFrameProcessor = MosaicFrameProcessor.getInstance();
        Resources appRes = mActivity.getResources();
        mPreparePreviewString = appRes.getString(R.string.pano_dialog_prepare_preview);
        mDialogTitle = appRes.getString(R.string.pano_dialog_title);
        mDialogOkString = appRes.getString(R.string.OK);
        mDialogPanoramaFailedString = appRes.getString(R.string.pano_dialog_panorama_failed);
        mDialogWaitingPreviousString = appRes.getString(R.string.pano_dialog_waiting_previous);

        // This runs in UI thread.
        mOnFrameAvailableRunnable = new Runnable() {
            @Override
            public void run() {
                // Frames might still be available after the activity is paused.
                // If we call onFrameAvailable after pausing, the GL thread will crash.
                // if (mPaused) return;

                if (mGLRootView.getVisibility() != View.VISIBLE) {
                    mMosaicPreviewRenderer.showPreviewFrameSync();
                    mGLRootView.setVisibility(View.VISIBLE);
                } else {
                    if (mCaptureState == CAPTURE_STATE_VIEWFINDER) {
                        mMosaicPreviewRenderer.showPreviewFrame();
                    } else {
                        mMosaicPreviewRenderer.alignFrameSync();
                        mMosaicFrameProcessor.processFrame();
                    }
                }
            }
        };

        mMainHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_LOW_RES_FINAL_MOSAIC_READY:
                        //onBackgroundThreadFinished();
                        //showFinalMosaic((Bitmap) msg.obj);
                        saveHighResMosaic();
                        break;
                    case MSG_GENERATE_FINAL_MOSAIC_ERROR:
                        /*onBackgroundThreadFinished();
                        if (mPaused) {
                            resetToPreview();
                        } else {
                            mRotateDialog.showAlertDialog(
                                    mDialogTitle, mDialogPanoramaFailedString,
                                    mDialogOkString, new Runnable() {
                                @Override
                                public void run() {
                                    resetToPreview();
                                }},
                                    null, null);
                        }
                        clearMosaicFrameProcessorIfNeeded();*/
                        break;
                    case MSG_RESET_TO_PREVIEW:
                        /*onBackgroundThreadFinished();
                        resetToPreview();
                        clearMosaicFrameProcessorIfNeeded();*/
                        break;
                    case MSG_CLEAR_SCREEN_DELAY:
                        mActivity.getWindow().clearFlags(WindowManager.LayoutParams.
                                FLAG_KEEP_SCREEN_ON);
                        break;
                }
            }
        };

        // Initialization
        Camera.Parameters params = mActivity.getCamManager().getParameters();
        mHorizontalViewAngle = params.getHorizontalViewAngle();
        mVerticalViewAngle = params.getVerticalViewAngle();

    }

    /**
     * Call this when you're done using MosaicProxy, to remove views added and shutdown
     * threads.
     */
    public void tearDown() {
        mGLRootView.removeView(mGLSurfaceView);
    }

    private void configMosaicPreview(int w, int h) {
        Log.d(TAG, "Mosaic Preview: " + w + "x" + h);
        // TODO: Grab the actual REAL landscape mode from orientation sensors
        boolean isLandscape = (mActivity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE);

        mMosaicPreviewRenderer = new MosaicPreviewRenderer(mMosaicTexture, w, h, isLandscape);

        mCameraTexture = mMosaicPreviewRenderer.getInputSurfaceTexture();
        mCameraTexture.setOnFrameAvailableListener(this);
        mActivity.getCamManager().setRenderToTexture(mCameraTexture);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        /* This function may be called by some random thread,
         * so let's be safe and jump back to ui thread.
         * No OpenGL calls can be done here. */
        mActivity.runOnUiThread(mOnFrameAvailableRunnable);
    }

    @Override
    public void onShutterButtonClicked(ShutterButton button) {
        if (mCaptureState == CAPTURE_STATE_MOSAIC) {
            stopCapture(false);
        } else {
            startCapture();
        }
    }

    @Override
    public void onSnapshotShutter(SnapshotManager.SnapshotInfo info) {

    }

    @Override
    public void onSnapshotPreview(SnapshotManager.SnapshotInfo info) {

    }

    @Override
    public void onSnapshotProcessing(SnapshotManager.SnapshotInfo info) {

    }

    @Override
    public void onSnapshotSaved(SnapshotManager.SnapshotInfo info) {

    }

    @Override
    public void onMediaSavingStart() {

    }

    @Override
    public void onMediaSavingDone() {

    }

    @Override
    public void onVideoRecordingStart() {

    }

    @Override
    public void onVideoRecordingStop() {

    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i2) {
        mMosaicTexture = surfaceTexture;

        Camera.Parameters params = mActivity.getCamManager().getParameters();
        int previewWidth = params.getPreviewSize().width;
        int previewHeight = params.getPreviewSize().height;

        PixelFormat pixelInfo = new PixelFormat();
        PixelFormat.getPixelFormatInfo(params.getPreviewFormat(), pixelInfo);
        // TODO: remove this extra 32 byte after the driver bug is fixed.
        int previewBufSize = (previewWidth * previewHeight * pixelInfo.bitsPerPixel / 8) + 32;

        mMosaicFrameProcessor.initialize(previewWidth, previewHeight, previewBufSize);
        mMosaicFrameProcessorInitialized = true;

        configMosaicPreview(previewWidth, previewHeight);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i2) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

    }

    public void startCapture() {
        Log.e(TAG, "Starting Panorama capture");
        // Reset values so we can do this again.
        mCancelComputation = false;
        mTimeTaken = System.currentTimeMillis();
       // mShutterButton.setImageResource(R.drawable.btn_shutter_recording);
        mCaptureState = CAPTURE_STATE_MOSAIC;
        //mCaptureIndicator.setVisibility(View.VISIBLE);
        //showDirectionIndicators(PanoProgressBar.DIRECTION_NONE);

        mMosaicFrameProcessor.setProgressListener(new MosaicFrameProcessor.ProgressListener() {
            @Override
            public void onProgress(boolean isFinished, float panningRateX, float panningRateY,
                                   float progressX, float progressY) {
                float accumulatedHorizontalAngle = progressX * mHorizontalViewAngle;
                float accumulatedVerticalAngle = progressY * mVerticalViewAngle;
                if (isFinished
                        || (Math.abs(accumulatedHorizontalAngle) >= DEFAULT_SWEEP_ANGLE)
                        || (Math.abs(accumulatedVerticalAngle) >= DEFAULT_SWEEP_ANGLE)) {
                    Log.e(TAG, "TODO: STOP CAPTURE"); // TODO
                    //stopCapture(false);
                } else {
                    float panningRateXInDegree = panningRateX * mHorizontalViewAngle;
                    float panningRateYInDegree = panningRateY * mVerticalViewAngle;
                    //updateProgress(panningRateXInDegree, panningRateYInDegree,
                    //        accumulatedHorizontalAngle, accumulatedVerticalAngle);
                }
            }
        });

        //mPanoProgressBar.reset();
        // TODO: calculate the indicator width according to different devices to reflect the actual
        // angle of view of the camera device.
        //mPanoProgressBar.setIndicatorWidth(20);
        //mPanoProgressBar.setMaxProgress(DEFAULT_SWEEP_ANGLE);
        //mPanoProgressBar.setVisibility(View.VISIBLE);
        //mDeviceOrientationAtCapture = mDeviceOrientation;
        //keepScreenOn();
        //mActivity.getOrientationManager().lockOrientation();
        setupProgressDirectionMatrix();
    }

    private void stopCapture(boolean aborted) {
        mCaptureState = CAPTURE_STATE_VIEWFINDER;
        //mCaptureIndicator.setVisibility(View.GONE);
        //hideTooFastIndication();
        //hideDirectionIndicators();

        mMosaicFrameProcessor.setProgressListener(null);
        //stopCameraPreview();

        mCameraTexture.setOnFrameAvailableListener(null);

        if (!aborted /*&& !mThreadRunning*/) {
            //mRotateDialog.showWaitingDialog(mPreparePreviewString);
            // Hide shutter button, shutter icon, etc when waiting for
            // panorama to stitch
            //mActivity.hideUI();
            new Thread() {
                @Override
                public void run() {
                    MosaicJpeg jpeg = generateFinalMosaic(false);

                    if (jpeg != null && jpeg.isValid) {
                        Bitmap bitmap = null;
                        bitmap = BitmapFactory.decodeByteArray(jpeg.data, 0, jpeg.data.length);
                        mMainHandler.sendMessage(mMainHandler.obtainMessage(
                                MSG_LOW_RES_FINAL_MOSAIC_READY, bitmap));
                    } else {
                        mMainHandler.sendMessage(mMainHandler.obtainMessage(
                                MSG_RESET_TO_PREVIEW));
                    }
                }
            }.start();
        }
        //keepScreenOnAwhile();
    }

    /**
     * Generate the final mosaic image.
     *
     * @param highRes flag to indicate whether we want to get a high-res version.
     * @return a MosaicJpeg with its isValid flag set to true if successful; null if the generation
     *         process is cancelled; and a MosaicJpeg with its isValid flag set to false if there
     *         is an error in generating the final mosaic.
     */
    public MosaicJpeg generateFinalMosaic(boolean highRes) {
        int mosaicReturnCode = mMosaicFrameProcessor.createMosaic(highRes);
        if (mosaicReturnCode == Mosaic.MOSAIC_RET_CANCELLED) {
            return null;
        } else if (mosaicReturnCode == Mosaic.MOSAIC_RET_ERROR) {
            return new MosaicJpeg();
        }

        byte[] imageData = mMosaicFrameProcessor.getFinalMosaicNV21();
        if (imageData == null) {
            Log.e(TAG, "getFinalMosaicNV21() returned null.");
            return new MosaicJpeg();
        }

        int len = imageData.length - 8;
        int width = (imageData[len + 0] << 24) + ((imageData[len + 1] & 0xFF) << 16)
                + ((imageData[len + 2] & 0xFF) << 8) + (imageData[len + 3] & 0xFF);
        int height = (imageData[len + 4] << 24) + ((imageData[len + 5] & 0xFF) << 16)
                + ((imageData[len + 6] & 0xFF) << 8) + (imageData[len + 7] & 0xFF);
        Log.v(TAG, "ImLength = " + (len) + ", W = " + width + ", H = " + height);

        if (width <= 0 || height <= 0) {
            // TODO: pop up an error message indicating that the final result is not generated.
            Log.e(TAG, "width|height <= 0!!, len = " + (len) + ", W = " + width + ", H = " +
                    height);
            return new MosaicJpeg();
        }

        YuvImage yuvimage = new YuvImage(imageData, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvimage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
        try {
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "Exception in storing final mosaic", e);
            return new MosaicJpeg();
        }
        return new MosaicJpeg(out.toByteArray(), width, height);
    }

    void setupProgressDirectionMatrix() {
        int degrees = Util.getDisplayRotation(mActivity);
        int cameraId = 0; //CameraHolder.instance().getBackCameraId();
        int orientation = 0; // TODO //Util.getDisplayOrientation(degrees, cameraId);
        mProgressDirectionMatrix.reset();
        mProgressDirectionMatrix.postRotate(orientation);
    }

    public void saveHighResMosaic() {
        new Thread() {
            @Override
            public void run() {
                //mPartialWakeLock.acquire();
                MosaicJpeg jpeg;
                try {
                    jpeg = generateFinalMosaic(true);
                } finally {
                    //mPartialWakeLock.release();
                }

                if (jpeg == null) {  // Cancelled by user.
                    mMainHandler.sendEmptyMessage(MSG_RESET_TO_PREVIEW);
                } else if (!jpeg.isValid) {  // Error when generating mosaic.
                    mMainHandler.sendEmptyMessage(MSG_GENERATE_FINAL_MOSAIC_ERROR);
                } else {
                    int orientation = 0; //getCaptureOrientation();
                    Uri uri = savePanorama(jpeg.data, jpeg.width, jpeg.height, orientation);
                    if (uri != null) {
                        Util.broadcastNewPicture(mActivity, uri);
                    }
                    mMainHandler.sendMessage(
                            mMainHandler.obtainMessage(MSG_RESET_TO_PREVIEW));
                }
            }
        }.start();
        //reportProgress();
    }

    private Uri savePanorama(byte[] jpegData, int width, int height, int orientation) {
        if (jpegData != null) {
            String filename = PanoUtil.createName(
                    mActivity.getResources().getString(R.string.pano_file_name_format), mTimeTaken);
            String filepath = Storage.getStorage().writeFile(filename, jpegData);

            // Add Exif tags.
            try {
                ExifInterface exif = new ExifInterface(filepath);
                /*exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP,
                        mGPSDateStampFormat.format(mTimeTaken));
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP,
                        mGPSTimeStampFormat.format(mTimeTaken));
                exif.setAttribute(ExifInterface.TAG_DATETIME,
                        mDateTimeStampFormat.format(mTimeTaken));
                exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                        getExifOrientation(orientation));*/
                exif.saveAttributes();
            } catch (IOException e) {
                Log.e(TAG, "Cannot set EXIF for " + filepath, e);
            }

            int jpegLength = (int) (new File(filepath).length());
            return Storage.getStorage().addImage(mActivity.getContentResolver(), filename, mTimeTaken,
                    null, orientation, jpegLength, filepath, width, height);
        }
        return null;
    }
}
