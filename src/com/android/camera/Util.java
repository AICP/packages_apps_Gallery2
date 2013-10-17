/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.FloatMath;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import com.android.gallery3d.R;
import com.android.gallery3d.common.ApiHelper;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

/**
 * Collection of utility functions used in this package.
 */
public class Util {
    private static final String TAG = "Util";

    // Orientation hysteresis amount used in rounding, in degrees
    public static final int ORIENTATION_HYSTERESIS = 5;

    public static final String REVIEW_ACTION = "com.android.camera.action.REVIEW";
    // See android.hardware.Camera.ACTION_NEW_PICTURE.
    public static final String ACTION_NEW_PICTURE = "android.hardware.action.NEW_PICTURE";
    // See android.hardware.Camera.ACTION_NEW_VIDEO.
    public static final String ACTION_NEW_VIDEO = "android.hardware.action.NEW_VIDEO";

    // Fields from android.hardware.Camera.Parameters
    public static final String FOCUS_MODE_CONTINUOUS_PICTURE = "continuous-picture";
    public static final String RECORDING_HINT = "recording-hint";
    private static final String AUTO_EXPOSURE_LOCK_SUPPORTED = "auto-exposure-lock-supported";
    private static final String AUTO_WHITE_BALANCE_LOCK_SUPPORTED = "auto-whitebalance-lock-supported";
    private static final String VIDEO_SNAPSHOT_SUPPORTED = "video-snapshot-supported";
    public static final String SCENE_MODE_HDR = "hdr";
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    private static final String VIDEO_HFR_VALUES = "video-hfr-values";
    public static final String OFF = "off";
    private static final String VIDEO_HDR_VALUES = "video-hdr-values";
    public static final String VIDEO_HDR = "video-hdr";
    public static final String VIDEO_HFR = "video-hfr";
    public static final String VIDEO_HFR_SIZE = "720x480";
    private static final String REDEYE_VALUES = "redeye-reduction-values";
                        
    public static boolean isSupported(String value, List<String> supported) {
        return supported == null ? false : supported.indexOf(value) >= 0;
    }

    public static boolean isAutoExposureLockSupported(Parameters params) {
        return TRUE.equals(params.get(AUTO_EXPOSURE_LOCK_SUPPORTED));
    }

    public static boolean isAutoWhiteBalanceLockSupported(Parameters params) {
        return TRUE.equals(params.get(AUTO_WHITE_BALANCE_LOCK_SUPPORTED));
    }

    public static boolean isVideoSnapshotSupported(Parameters params) {
        return TRUE.equals(params.get(VIDEO_SNAPSHOT_SUPPORTED));
    }

    public static boolean isCameraHdrSupported(Parameters params) {
        List<String> supported = params.getSupportedSceneModes();
        return (supported != null) && supported.contains(SCENE_MODE_HDR);
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    public static boolean isMeteringAreaSupported(Parameters params) {
        if (ApiHelper.HAS_CAMERA_METERING_AREA) {
            return params.getMaxNumMeteringAreas() > 0;
        }
        return false;
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    public static boolean isFocusAreaSupported(Parameters params) {
        if (ApiHelper.HAS_CAMERA_FOCUS_AREA) {
            return (params.getMaxNumFocusAreas() > 0
                    && isSupported(Parameters.FOCUS_MODE_AUTO,
                            params.getSupportedFocusModes()));
        }
        return false;
    }

    public static boolean isVideoHfrSupported(Parameters params) {
        String value = params.get(VIDEO_HFR_VALUES);
        return value != null && !value.equals(OFF);
    }

    public static boolean isVideoHdrSupported(Parameters params) {
        String value = params.get(VIDEO_HDR_VALUES);
        return value != null;
    }

    public static boolean hasRedeyeFlashSupport(Parameters params) {
        String value = params.get(REDEYE_VALUES);
        return value != null;
    }

    // Private intent extras. Test only.
    private static final String EXTRAS_CAMERA_FACING =
            "android.intent.extras.CAMERA_FACING";

    private static float sPixelDensity = 1;
    private static ImageFileNamer sImageFileNamer;


    // Samsung ZSL mode
    private static boolean sEnableZSL;

    // Workaround for QC cameras with broken face detection on front camera
    private static boolean sNoFaceDetectOnFrontCamera;

    // Use samsung HDR format
    private static boolean sSamsungHDRFormat;

    // Send magic command to hardware for Samsung ZSL
    private static boolean sSendMagicSamsungZSLCommand;

    // For setting video size before recording starts
    private static boolean sEarlyVideoSize;

    // Samsung camcorder mode
    private static boolean sSamsungCamMode;

    // HTC camcorder mode
    private static boolean sHTCCamMode;

    // Do not change the focus mode when TTF is used
    private static boolean sNoFocusModeChangeForTouch;

    private Util() {
    }

    public static void initialize(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);
        sPixelDensity = metrics.density;
        sImageFileNamer = new ImageFileNamer(
                context.getString(R.string.image_file_name_format));

        // These come from the config, but are needed before parameters are set.
        sEnableZSL = context.getResources().getBoolean(R.bool.enableZSL);
        sNoFaceDetectOnFrontCamera = context.getResources().getBoolean(
                R.bool.noFaceDetectOnFrontCamera);

        sSamsungHDRFormat = context.getResources().getBoolean(R.bool.needsSamsungHDRFormat);

        sSendMagicSamsungZSLCommand = context.getResources().getBoolean(
                R.bool.sendMagicSamsungZSLCommand);

        sEarlyVideoSize = context.getResources().getBoolean(R.bool.needsEarlyVideoSize);
        sSamsungCamMode = context.getResources().getBoolean(R.bool.needsSamsungCamMode);
        sHTCCamMode = context.getResources().getBoolean(R.bool.needsHTCCamMode);

        sNoFocusModeChangeForTouch = context.getResources().getBoolean(
                R.bool.useContinuosFocusForTouch);

    }

    public static int dpToPixel(int dp) {
        return Math.round(sPixelDensity * dp);
    }

    public static boolean isZSLEnabled() {
        return sEnableZSL;
    }

    public static boolean needSamsungHDRFormat() {
        return sSamsungHDRFormat;
    }
    public static boolean noFaceDetectOnFrontCamera() {
        return sNoFaceDetectOnFrontCamera;
    }

    public static boolean sendMagicSamsungZSLCommand() {
        return sSendMagicSamsungZSLCommand;
    }

    public static boolean needsEarlyVideoSize() {
        return sEarlyVideoSize;
    }

    public static boolean useHTCCamMode() {
        return sHTCCamMode;
    }

    public static boolean useSamsungCamMode() {
        return sSamsungCamMode;
    }

    public static boolean noFocusModeChangeForTouch() {
        return sNoFocusModeChangeForTouch;
    }

    // Rotates the bitmap by the specified degree.
    // If a new bitmap is created, the original bitmap is recycled.
    public static Bitmap rotate(Bitmap b, int degrees) {
        return rotateAndMirror(b, degrees, false);
    }

    // Rotates and/or mirrors the bitmap. If a new bitmap is created, the
    // original bitmap is recycled.
    public static Bitmap rotateAndMirror(Bitmap b, int degrees, boolean mirror) {
        if ((degrees != 0 || mirror) && b != null) {
            Matrix m = new Matrix();
            // Mirror first.
            // horizontal flip + rotation = -rotation + horizontal flip
            if (mirror) {
                m.postScale(-1, 1);
                degrees = (degrees + 360) % 360;
                if (degrees == 0 || degrees == 180) {
                    m.postTranslate(b.getWidth(), 0);
                } else if (degrees == 90 || degrees == 270) {
                    m.postTranslate(b.getHeight(), 0);
                } else {
                    throw new IllegalArgumentException("Invalid degrees=" + degrees);
                }
            }
            if (degrees != 0) {
                // clockwise
                m.postRotate(degrees,
                        (float) b.getWidth() / 2, (float) b.getHeight() / 2);
            }

            try {
                Bitmap b2 = Bitmap.createBitmap(
                        b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (b != b2) {
                    b.recycle();
                    b = b2;
                }
            } catch (OutOfMemoryError ex) {
                // We have no memory to rotate. Return the original bitmap.
            }
        }
        return b;
    }

    /*
     * Compute the sample size as a function of minSideLength
     * and maxNumOfPixels.
     * minSideLength is used to specify that minimal width or height of a
     * bitmap.
     * maxNumOfPixels is used to specify the maximal size in pixels that is
     * tolerable in terms of memory usage.
     *
     * The function returns a sample size based on the constraints.
     * Both size and minSideLength can be passed in as -1
     * which indicates no care of the corresponding constraint.
     * The functions prefers returning a sample size that
     * generates a smaller bitmap, unless minSideLength = -1.
     *
     * Also, the function rounds up the sample size to a power of 2 or multiple
     * of 8 because BitmapFactory only honors sample size this way.
     * For example, BitmapFactory downsamples an image by 2 even though the
     * request is 3. So we round up the sample size to avoid OOM.
     */
    public static int computeSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        int initialSize = computeInitialSampleSize(options, minSideLength,
                maxNumOfPixels);

        int roundedSize;
        if (initialSize <= 8) {
            roundedSize = 1;
            while (roundedSize < initialSize) {
                roundedSize <<= 1;
            }
        } else {
            roundedSize = (initialSize + 7) / 8 * 8;
        }

        return roundedSize;
    }

    private static int computeInitialSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        double w = options.outWidth;
        double h = options.outHeight;

        int lowerBound = (maxNumOfPixels < 0) ? 1 :
                (int) Math.ceil(Math.sqrt(w * h / maxNumOfPixels));
        int upperBound = (minSideLength < 0) ? 128 :
                (int) Math.min(Math.floor(w / minSideLength),
                Math.floor(h / minSideLength));

        if (upperBound < lowerBound) {
            // return the larger one when there is no overlapping zone.
            return lowerBound;
        }

        if (maxNumOfPixels < 0 && minSideLength < 0) {
            return 1;
        } else if (minSideLength < 0) {
            return lowerBound;
        } else {
            return upperBound;
        }
    }

    public static Bitmap makeBitmap(byte[] jpegData, int maxNumOfPixels) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length,
                    options);
            if (options.mCancel || options.outWidth == -1
                    || options.outHeight == -1) {
                return null;
            }
            options.inSampleSize = computeSampleSize(
                    options, -1, maxNumOfPixels);
            options.inJustDecodeBounds = false;

            options.inDither = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length,
                    options);
        } catch (OutOfMemoryError ex) {
            Log.e(TAG, "Got oom exception ", ex);
            return null;
        }
    }

    public static Bitmap decodeYUV422P(byte[] yuv422p, int width, int height) {
        final int frameSize = width * height;
        int[] rgb = new int[frameSize];

        for (int j = 0, yp = 0; j < height; j++) {
            int up = frameSize + (j * (width / 2)), u = 0, v = 0;
            int vp = ((int)(frameSize * 1.5) + (j * (width / 2)));
            for (int i = 0; i < width; i++, yp++) {
                int y = Math.max(0, (0xff & ((int) yuv422p[yp])) - 16);
                if ((i & 1) == 0) {
                    u = (0xff & yuv422p[up++]) - 128;
                    v = (0xff & yuv422p[vp++]) - 128;
                }

                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);

                if (r < 0) {
                    r = 0;
                } else if (r > 262143) {
                    r = 262143;
                }
                if (g < 0) {
                    g = 0;
                } else if (g > 262143) {
                    g = 262143;
                }
                if (b < 0) {
                    b = 0;
                } else if (b > 262143) {
                    b = 262143;
                }
                rgb[yp] = 0xff000000
                        | ((r << 6) & 0xff0000)
                        | ((g >> 2) & 0xff00)
                        | ((b >> 10) & 0xff);
            }
        }
        return Bitmap.createBitmap(rgb, width, height, Bitmap.Config.ARGB_8888);
    }

    public static void closeSilently(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable t) {
            // do nothing
        }
    }

    public static void Assert(boolean cond) {
        if (!cond) {
            throw new AssertionError();
        }
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    private static void throwIfCameraDisabled(Activity activity) throws CameraDisabledException {
        // Check if device policy has disabled the camera.
        if (ApiHelper.HAS_GET_CAMERA_DISABLED) {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            if (dpm.getCameraDisabled(null)) {
                throw new CameraDisabledException();
            }
        }
    }

    public static CameraManager.CameraProxy openCamera(Activity activity, int cameraId)
            throws CameraHardwareException, CameraDisabledException {
        throwIfCameraDisabled(activity);

        try {
            return CameraHolder.instance().open(cameraId);
        } catch (CameraHardwareException e) {
            // In eng build, we throw the exception so that test tool
            // can detect it and report it
            if ("eng".equals(Build.TYPE)) {
                throw new RuntimeException("openCamera failed", e);
            } else {
                throw e;
            }
        }
    }

    public static void showErrorAndFinish(final Activity activity, int msgId) {
        DialogInterface.OnClickListener buttonListener =
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                activity.finish();
            }
        };
        TypedValue out = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.alertDialogIcon, out, true);
        new AlertDialog.Builder(activity)
                .setCancelable(false)
                .setTitle(R.string.camera_error_title)
                .setMessage(msgId)
                .setNeutralButton(R.string.dialog_ok, buttonListener)
                .setIcon(out.resourceId)
                .show();
    }

    public static <T> T checkNotNull(T object) {
        if (object == null) throw new NullPointerException();
        return object;
    }

    public static boolean equals(Object a, Object b) {
        return (a == b) || (a == null ? false : a.equals(b));
    }

    public static int nextPowerOf2(int n) {
        n -= 1;
        n |= n >>> 16;
        n |= n >>> 8;
        n |= n >>> 4;
        n |= n >>> 2;
        n |= n >>> 1;
        return n + 1;
    }

    public static float distance(float x, float y, float sx, float sy) {
        float dx = x - sx;
        float dy = y - sy;
        return FloatMath.sqrt(dx * dx + dy * dy);
    }

    public static int clamp(int x, int min, int max) {
        if (x > max) return max;
        if (x < min) return min;
        return x;
    }

    public static boolean systemRotationLocked(Activity activity) {
        return Settings.System.getInt(activity.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
    }

    public static int getDisplayRotation(Activity activity) {
        int rotation = activity.getWindowManager().getDefaultDisplay()
                .getRotation();
        switch (rotation) {
            case Surface.ROTATION_0: return 0;
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
        }
        return 0;
    }

    public static int getDisplayOrientation(int degrees, int cameraId) {
        // See android.hardware.Camera.setDisplayOrientation for
        // documentation.
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    public static int getCameraOrientation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        return info.orientation;
    }

    public static int roundOrientation(int orientation, int orientationHistory) {
        boolean changeOrientation = false;
        if (orientationHistory == OrientationEventListener.ORIENTATION_UNKNOWN) {
            changeOrientation = true;
        } else {
            int dist = Math.abs(orientation - orientationHistory);
            dist = Math.min( dist, 360 - dist );
            changeOrientation = ( dist >= 45 + ORIENTATION_HYSTERESIS );
        }
        if (changeOrientation) {
            return ((orientation + 45) / 90 * 90) % 360;
        }
        return orientationHistory;
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private static Point getDefaultDisplaySize(Activity activity, Point size) {
        Display d = activity.getWindowManager().getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= ApiHelper.VERSION_CODES.HONEYCOMB_MR2) {
            d.getSize(size);
        } else {
            size.set(d.getWidth(), d.getHeight());
        }
        return size;
    }

    public static Size getOptimalPreviewSize(Activity currentActivity,
            List<Size> sizes, double targetRatio) {
        // Use a very small tolerance because we want an exact match.
        final double ASPECT_TOLERANCE = 0.001;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        // Because of bugs of overlay and layout, we sometimes will try to
        // layout the viewfinder in the portrait orientation and thus get the
        // wrong size of preview surface. When we change the preview size, the
        // new overlay will be created before the old one closed, which causes
        // an exception. For now, just get the screen size.
        Point point = getDefaultDisplaySize(currentActivity, new Point());
        int targetHeight = Math.min(point.x, point.y);
        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }
        // Cannot find the one match the aspect ratio. This should not happen.
        // Ignore the requirement.
        if (optimalSize == null) {
            Log.w(TAG, "No preview size match the aspect ratio");
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    // Returns the largest picture size which matches the given aspect ratio.
    public static Size getOptimalVideoSnapshotPictureSize(
            List<Size> sizes, double targetRatio) {
        // Use a very small tolerance because we want an exact match.
        final double ASPECT_TOLERANCE = 0.001;
        if (sizes == null) return null;

        Size optimalSize = null;

        // Try to find a size matches aspect ratio and has the largest width
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (optimalSize == null || size.width > optimalSize.width) {
                optimalSize = size;
            }
        }

        // Cannot find one that matches the aspect ratio. This should not happen.
        // Ignore the requirement.
        if (optimalSize == null) {
            Log.w(TAG, "No picture size match the aspect ratio");
            for (Size size : sizes) {
                if (optimalSize == null || size.width > optimalSize.width) {
                    optimalSize = size;
                }
            }
        }
        return optimalSize;
    }

    public static void dumpParameters(Parameters params) {
        Set<String> sortedParams = new TreeSet<String>();
        sortedParams.addAll(Arrays.asList(params.flatten().split(";")));
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        Iterator<String> i = sortedParams.iterator();
        while (i.hasNext()) {
            String nextParam = i.next();
            if ((sb.length() + nextParam.length()) > 2044) {
                Log.d(TAG, "Parameters: " + sb.toString());
                sb = new StringBuilder();
            }
            sb.append(nextParam);
            if (i.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append("]");
        Log.d(TAG, "Parameters: " + sb.toString());
    }

    /**
     * Returns whether the device is voice-capable (meaning, it can do MMS).
     */
    public static boolean isMmsCapable(Context context) {
        TelephonyManager telephonyManager = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) {
            return false;
        }

        try {
            Class<?> partypes[] = new Class[0];
            Method sIsVoiceCapable = TelephonyManager.class.getMethod(
                    "isVoiceCapable", partypes);

            Object arglist[] = new Object[0];
            Object retobj = sIsVoiceCapable.invoke(telephonyManager, arglist);
            return (Boolean) retobj;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // Failure, must be another device.
            // Assume that it is voice capable.
        } catch (IllegalAccessException iae) {
            // Failure, must be an other device.
            // Assume that it is voice capable.
        } catch (NoSuchMethodException nsme) {
        }
        return true;
    }

    // This is for test only. Allow the camera to launch the specific camera.
    public static int getCameraFacingIntentExtras(Activity currentActivity) {
        int cameraId = -1;

        int intentCameraId =
                currentActivity.getIntent().getIntExtra(Util.EXTRAS_CAMERA_FACING, -1);

        if (isFrontCameraIntent(intentCameraId)) {
            // Check if the front camera exist
            int frontCameraId = CameraHolder.instance().getFrontCameraId();
            if (frontCameraId != -1) {
                cameraId = frontCameraId;
            }
        } else if (isBackCameraIntent(intentCameraId)) {
            // Check if the back camera exist
            int backCameraId = CameraHolder.instance().getBackCameraId();
            if (backCameraId != -1) {
                cameraId = backCameraId;
            }
        }
        return cameraId;
    }

    private static boolean isFrontCameraIntent(int intentCameraId) {
        return (intentCameraId == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT);
    }

    private static boolean isBackCameraIntent(int intentCameraId) {
        return (intentCameraId == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK);
    }

    private static int sLocation[] = new int[2];

    // This method is not thread-safe.
    public static boolean pointInView(float x, float y, View v) {
        v.getLocationInWindow(sLocation);
        return x >= sLocation[0] && x < (sLocation[0] + v.getWidth())
                && y >= sLocation[1] && y < (sLocation[1] + v.getHeight());
    }

    public static int[] getRelativeLocation(View reference, View view) {
        reference.getLocationInWindow(sLocation);
        int referenceX = sLocation[0];
        int referenceY = sLocation[1];
        view.getLocationInWindow(sLocation);
        sLocation[0] -= referenceX;
        sLocation[1] -= referenceY;
        return sLocation;
    }

    public static boolean isUriValid(Uri uri, ContentResolver resolver) {
        if (uri == null) return false;

        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
            if (pfd == null) {
                Log.e(TAG, "Fail to open URI. URI=" + uri);
                return false;
            }
            pfd.close();
        } catch (IOException ex) {
            return false;
        }
        return true;
    }

    public static void viewUri(Uri uri, Context context) {
        if (!isUriValid(uri, context.getContentResolver())) {
            Log.e(TAG, "Uri invalid. uri=" + uri);
            return;
        }

        try {
            context.startActivity(new Intent(Util.REVIEW_ACTION, uri));
        } catch (ActivityNotFoundException ex) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "review image fail. uri=" + uri, e);
            }
        }
    }

    public static void dumpRect(RectF rect, String msg) {
        Log.v(TAG, msg + "=(" + rect.left + "," + rect.top
                + "," + rect.right + "," + rect.bottom + ")");
    }

    public static void rectFToRect(RectF rectF, Rect rect) {
        rect.left = Math.round(rectF.left);
        rect.top = Math.round(rectF.top);
        rect.right = Math.round(rectF.right);
        rect.bottom = Math.round(rectF.bottom);
    }

    public static void prepareMatrix(Matrix matrix, boolean mirror, int displayOrientation,
            int viewWidth, int viewHeight) {
        // Need mirror for front camera.
        matrix.setScale(mirror ? -1 : 1, 1);
        // This is the value for android.hardware.Camera.setDisplayOrientation.
        matrix.postRotate(displayOrientation);
        // Camera driver coordinates range from (-1000, -1000) to (1000, 1000).
        // UI coordinates range from (0, 0) to (width, height).
        matrix.postScale(viewWidth / 2000f, viewHeight / 2000f);
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f);
    }

    public static String createJpegName(long dateTaken) {
        synchronized (sImageFileNamer) {
            return sImageFileNamer.generateName(dateTaken);
        }
    }

    public static void broadcastNewPicture(Context context, Uri uri) {
        context.sendBroadcast(new Intent(ACTION_NEW_PICTURE, uri));
        // Keep compatibility
        context.sendBroadcast(new Intent("com.android.camera.NEW_PICTURE", uri));
    }

    public static void fadeIn(View view, float startAlpha, float endAlpha, long duration) {
        if (view.getVisibility() == View.VISIBLE) return;

        view.setVisibility(View.VISIBLE);
        Animation animation = new AlphaAnimation(startAlpha, endAlpha);
        animation.setDuration(duration);
        view.startAnimation(animation);
    }

    public static void fadeIn(View view) {
        fadeIn(view, 0F, 1F, 400);

        // We disabled the button in fadeOut(), so enable it here.
        view.setEnabled(true);
    }

    public static void fadeOut(View view) {
        if (view.getVisibility() != View.VISIBLE) return;

        // Since the button is still clickable before fade-out animation
        // ends, we disable the button first to block click.
        view.setEnabled(false);
        Animation animation = new AlphaAnimation(1F, 0F);
        animation.setDuration(400);
        view.startAnimation(animation);
        view.setVisibility(View.GONE);
    }

    public static int getJpegRotation(int cameraId, int orientation) {
        // See android.hardware.Camera.Parameters.setRotation for
        // documentation.
        int rotation = 0;
        if (orientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            CameraInfo info = CameraHolder.instance().getCameraInfo()[cameraId];
            if (info.facing == CameraInfo.CAMERA_FACING_FRONT) {
                rotation = (info.orientation - orientation + 360) % 360;
            } else {  // back-facing camera
                rotation = (info.orientation + orientation) % 360;
            }
        }
        return rotation;
    }

    public static void setGpsParameters(Parameters parameters, Location loc) {
        // Clear previous GPS location from the parameters.
        parameters.removeGpsData();

        // We always encode GpsTimeStamp
        parameters.setGpsTimestamp(System.currentTimeMillis() / 1000);

        // Set GPS location.
        if (loc != null) {
            double lat = loc.getLatitude();
            double lon = loc.getLongitude();
            boolean hasLatLon = (lat != 0.0d) || (lon != 0.0d);

            if (hasLatLon) {
                Log.d(TAG, "Set gps location");
                parameters.setGpsLatitude(lat);
                parameters.setGpsLongitude(lon);
                parameters.setGpsProcessingMethod(loc.getProvider().toUpperCase());
                if (loc.hasAltitude()) {
                    parameters.setGpsAltitude(loc.getAltitude());
                } else {
                    // for NETWORK_PROVIDER location provider, we may have
                    // no altitude information, but the driver needs it, so
                    // we fake one.
                    parameters.setGpsAltitude(0);
                }
                if (loc.getTime() != 0) {
                    // Location.getTime() is UTC in milliseconds.
                    // gps-timestamp is UTC in seconds.
                    long utcTimeSeconds = loc.getTime() / 1000;
                    parameters.setGpsTimestamp(utcTimeSeconds);
                }
            } else {
                loc = null;
            }
        }
    }

    private static class ImageFileNamer {
        private SimpleDateFormat mFormat;

        // The date (in milliseconds) used to generate the last name.
        private long mLastDate;

        // Number of names generated for the same second.
        private int mSameSecondCount;

        public ImageFileNamer(String format) {
            mFormat = new SimpleDateFormat(format);
        }

        public String generateName(long dateTaken) {
            Date date = new Date(dateTaken);
            String result = mFormat.format(date);

            // If the last name was generated for the same second,
            // we append _1, _2, etc to the name.
            if (dateTaken / 1000 == mLastDate / 1000) {
                mSameSecondCount++;
                result += "_" + mSameSecondCount;
            } else {
                mLastDate = dateTaken;
                mSameSecondCount = 0;
            }

            return result;
        }
    }
	public static void setVideoHfrMode(Activity activity, Parameters params, String value){
	    if (isVideoHfrSupported(params)){
	        if (!value.equals(activity.getString(R.string.setting_off_value))){
	            params.set(VIDEO_HFR, value);
	        } else {
	            params.set(VIDEO_HFR, activity.getString(R.string.setting_off_value));
	        }
	    }
	}

    public static boolean isVideoHDROn(Activity activity, Parameters params) {
        if (!isVideoHdrSupported(params)){
            return false;
        }
        String videoHdr = params.get(VIDEO_HDR);
        if (videoHdr != null && videoHdr.equals(activity.getString(R.string.setting_on_value))) {
            return true;
        }
        return false;
    }	

    public static boolean isVideoHfrOn(Activity activity, Parameters params) {
        if (!isVideoHfrSupported(params)){
            return false;
        }
        String videoHfr = params.get(VIDEO_HFR);
        if (videoHfr != null && !videoHfr.equals(activity.getString(R.string.setting_off_value))) {
            return true;
        }
        return false;
    }
}
