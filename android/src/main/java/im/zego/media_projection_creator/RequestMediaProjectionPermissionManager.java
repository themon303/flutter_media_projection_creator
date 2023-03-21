package im.zego.media_projection_creator;
//
//  RequestMediaProjectionPermissionManager.java
//  android
//  im.zego.media_projection_creator.internal
//
//  Created by Patrick Fu on 2020/10/27.
//  Copyright © 2020 Zego. All rights reserved.
//

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import im.zego.media_projection_creator.internal.MediaProjectionService;
import im.zego.media_projection_creator.internal.RequestMediaProjectionPermissionActivity;
import io.flutter.Log;
import io.flutter.plugin.common.MethodChannel;

public class RequestMediaProjectionPermissionManager extends BroadcastReceiver {

    public static final int ERROR_CODE_SUCCEED = 0;
    public static final int ERROR_CODE_FAILED_USER_CANCELED = 1;
    public static final int ERROR_CODE_FAILED_SYSTEM_VERSION_TOO_LOW = 2;

    @SuppressLint("StaticFieldLeak")
    private static RequestMediaProjectionPermissionManager instance;

    private MediaProjectionCreatorCallback mediaProjectionCreatorCallback;

    private int foregroundNotificationIcon = 0;

    private String foregroundNotificationText = "";

    private Context context;

    private Intent service;

    private MethodChannel.Result flutterResult;

    public static RequestMediaProjectionPermissionManager getInstance() {
        if (instance == null) {
            synchronized (RequestMediaProjectionPermissionManager.class) {
                if (instance == null) {
                    instance = new RequestMediaProjectionPermissionManager();
                }
            }
        }
        return instance;
    }

    /// Developers need to set callback through manager in their native code to get media projection
    public void setRequestPermissionCallback(MediaProjectionCreatorCallback callback) {
        this.mediaProjectionCreatorCallback = callback;
    }

    /// Developers can set the foreground notification style (available since Android Q)
    public void setForegroundServiceNotificationStyle(int foregroundNotificationIcon, String foregroundNotificationText) {
        this.foregroundNotificationIcon = foregroundNotificationIcon;
        this.foregroundNotificationText = foregroundNotificationText;
    }



    /* ------- Private functions ------- */

    void requestMediaProjectionPermission(Context context, MethodChannel.Result result) {
        this.context = context;
        this.flutterResult = result;
        Intent intent = new Intent(context, RequestMediaProjectionPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    void stopMediaProjectionService(Context context) {
        if (service != null) {
            context.stopService(service);
        }
    }

    void startProjection(MethodChannel.Result result) {
        this.flutterResult = result;
        if (mMediaProjection != null) {
            // display metrics
            mDensity = Resources.getSystem().getDisplayMetrics().densityDpi;
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mDisplay = windowManager.getDefaultDisplay();

            // create virtual display depending on device width / height
            createVirtualDisplay();

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                // register orientation change callback
                mOrientationChangeCallback = new OrientationChangeCallback(this.context);
                if (mOrientationChangeCallback.canDetectOrientation()) {
                    mOrientationChangeCallback.enable();
                }

                // register media projection stop callback
                mMediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    void stopProjection() {
        if (mHandler != null) {
            mHandler.post(() -> {
                if (mMediaProjection != null) {
                    mMediaProjection.stop();
                }
            });
        }
    }

    public void onMediaProjectionCreated(MediaProjection mediaProjection, int errorCode) {
        mMediaProjection = mediaProjection;
        this.invokeCallback(mediaProjection, errorCode);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void createMediaProjection(int resultCode, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service = new Intent(this.context, MediaProjectionService.class);
            service.putExtra("code", resultCode);
            service.putExtra("data", intent);
            service.putExtra("notificationIcon", this.foregroundNotificationIcon);
            service.putExtra("notificationText", this.foregroundNotificationText);
            this.context.startForegroundService(service);
        } else {
            MediaProjectionManager manager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection mediaProjection = manager.getMediaProjection(resultCode, intent);
            this.onMediaProjectionCreated(mediaProjection, ERROR_CODE_SUCCEED);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        switch (action) {
            case "com.media_projection_creator.request_permission_result_succeeded":
                int resultCode = intent.getIntExtra("resultCode", 100);

                // start capture handling thread
                new Thread() {
                    @Override
                    public void run() {
                        Looper.prepare();
                        mHandler = new Handler();
                        Looper.loop();
                    }
                }.start();

                if (Build.VERSION.SDK_INT >= 21) {
                    this.createMediaProjection(resultCode, intent);
                } else {
                    this.onMediaProjectionCreated(null, ERROR_CODE_FAILED_SYSTEM_VERSION_TOO_LOW);
                }
                break;
            case "com.media_projection_creator.request_permission_result_failed_user_canceled":
                invokeCallback(null, ERROR_CODE_FAILED_USER_CANCELED);
                break;
            case "com.media_projection_creator.request_permission_result_failed_system_version_too_low":
                invokeCallback(null, ERROR_CODE_FAILED_SYSTEM_VERSION_TOO_LOW);
                break;
        }
    }

    private void invokeCallback(MediaProjection mediaProjection, int errorCode) {
        if (mediaProjectionCreatorCallback != null) {
            mediaProjectionCreatorCallback.onMediaProjectionCreated(mediaProjection, errorCode);
        }

        Log.d("ZEGO", "[invokeCallback], errorCode " + errorCode);

        if (this.flutterResult != null) {
            Log.d("ZEGO", "[invokeCallback], flutter result, errorCode " + errorCode);
            this.flutterResult.success(errorCode);
        }
    }
    private void createVirtualDisplay() {
        if (mMediaProjection != null) {
            // get width and height
            mWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
            mHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
            mDensity = Resources.getSystem().getDisplayMetrics().densityDpi;

            // start capture reader
            mImageReader = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                mImageReader = ImageReader.newInstance(mWidth, mHeight, ImageFormat.JPEG, 1);
                mVirtualDisplay = mMediaProjection.createVirtualDisplay("SCREEN_CAP", mWidth, mHeight,
                        mDensity, getVirtualDisplayFlags(), mImageReader.getSurface(), null, mHandler);
                mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
            }
        }
    }

    private static int getVirtualDisplayFlags() {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    }

    private ImageReader mImageReader;
//    private String mStoreDir;
    private int mWidth;
    private int mHeight;
    private int mDensity;
    private Handler mHandler;
    private VirtualDisplay mVirtualDisplay;
    private OrientationChangeCallback mOrientationChangeCallback;
    private MediaProjection mMediaProjection;
    private Display mDisplay;
    private int mRotation;

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private class OrientationChangeCallback extends OrientationEventListener {

        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            mHandler.post(() -> {
                if (mVirtualDisplay != null) mVirtualDisplay.release();
                if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);
                if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                mMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
            });
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {

//            FileOutputStream fos = null;
//            Bitmap bitmap = null;
            try (Image image = mImageReader.acquireLatestImage()) {
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.capacity()];
                    if (flutterResult != null) {
                        flutterResult.success(bytes);
                    }
                    stopProjection();

//                    Image.Plane[] planes = image.getPlanes();
//                    ByteBuffer buffer = planes[0].getBuffer();
//                    int pixelStride = planes[0].getPixelStride();
//                    int rowStride = planes[0].getRowStride();
//                    int rowPadding = rowStride - pixelStride * mWidth;
//
//                    // create bitmap
//                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);
//                    bitmap.copyPixelsFromBuffer(buffer);
//
//                    // write bitmap to a file
//                    fos = new FileOutputStream(mStoreDir + "/myscreen_" + ".png");
//                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);

                    Log.d("ZEGO", "captured image");
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
//                if (fos != null) {
//                    try {
//                        fos.close();
//                    } catch (IOException ioe) {
//                        ioe.printStackTrace();
//                    }
//                }
//
//                if (bitmap != null) {
//                    bitmap.recycle();
//                }

            }
        }
    }

}
