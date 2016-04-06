/*
 * Copyright (C) 2008 ZXing authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.barcodeeye.scan;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.example.qrApp.R;
import com.github.barcodeeye.BaseGlassActivity;
import com.github.barcodeeye.image.ImageManager;
import com.github.barcodeeye.migrated.AmbientLightManager;
import com.github.barcodeeye.migrated.BeepManager;
import com.github.barcodeeye.migrated.FinishListener;
import com.github.barcodeeye.migrated.InactivityTimer;
import com.github.barcodeeye.scan.result.ResultProcessor;
import com.github.barcodeeye.scan.result.ResultProcessorFactory;
import com.github.barcodeeye.scan.ui.ViewfinderView;
import com.google.zxing.*;
import com.google.zxing.client.android.camera.CameraManager;

import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;

/**
 * This activity opens the camera and does the actual scanning on a background
 * thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as
 * the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends BaseGlassActivity implements
        SurfaceHolder.Callback {

    TextView outputText;

    private static final String IMAGE_PREFIX = "BarcodeEye_";

    private static final String TAG = CaptureActivity.class.getSimpleName();

    private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES = EnumSet
            .of(ResultMetadataType.ISSUE_NUMBER,
                    ResultMetadataType.SUGGESTED_PRICE,
                    ResultMetadataType.ERROR_CORRECTION_LEVEL,
                    ResultMetadataType.POSSIBLE_COUNTRY);

    private CameraManager mCameraManager; //работает с методами и сервисами, связанными с камерой
    private CaptureActivityHandler mHandler;//Обрабатывает сообщения которые используются в устройстве, которое находится в стационарном состоянии
    private Result mSavedResultToShow;
    private ViewfinderView mViewfinderView;//наложение прямоугольника и вспомогательных визуальных объектов
    private boolean mHasSurface;
    private Map<DecodeHintType, ?> mDecodeHints; //
    private InactivityTimer mInactivityTimer;//завершение Activity после долгого нахождения в неактивном режиме
    private BeepManager mBeepManager; //управляет гудками и вибрациями в CaptureActivity
    private AmbientLightManager mAmbientLightManager; // изменить яркость в зависимости от освещенности окружающей среды
    private ImageManager mImageManager;// работает с изображениями, в частности сохраняет их

    public static Intent newIntent(Context context) {
        Intent intent = new Intent(context, CaptureActivity.class);
        return intent;
    }

    public ViewfinderView getViewfinderView() {
        return mViewfinderView;
    }

    public Handler getHandler() {
        return mHandler;
    }

    CameraManager getCameraManager() {
        return mCameraManager;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.activity_capture);

        mImageManager = new ImageManager(this);

        mHasSurface = false;
        mInactivityTimer = new InactivityTimer(this);
        mBeepManager = new BeepManager(this);
        mAmbientLightManager = new AmbientLightManager(this);

        mViewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);//записываем значения из R.xml.preferences
    }

    @Override
    protected void onResume() {
        super.onResume();

        // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
        // want to open the camera driver and measure the screen size if we're going to show the help on
        // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
        // off screen.
        mCameraManager = new CameraManager(getApplication());
        mViewfinderView.setCameraManager(mCameraManager);

        mHandler = null;

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        if (mHasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(surfaceHolder);  //вызывает метод этого класса
        } else {
            // Install the callback and wait for surfaceCreated() to init the camera.
            surfaceHolder.addCallback(this);
        }

        mBeepManager.updatePrefs();
        mAmbientLightManager.start(mCameraManager);

        mInactivityTimer.onResume();
    }

    @Override
    protected void onPause() {
        if (mHandler != null) {
            mHandler.quitSynchronously();
            mHandler = null;
        }
        mInactivityTimer.onPause();
        mAmbientLightManager.stop();
        mCameraManager.closeDriver();
        if (!mHasSurface) {
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
            SurfaceHolder surfaceHolder = surfaceView.getHolder();
            surfaceHolder.removeCallback(this);
        }
        super.onPause();
    }

    @Override
    protected boolean onTap() {
        openOptionsMenu();
        return super.onTap();
    }

    @Override
    protected void onDestroy() {
        mInactivityTimer.shutdown();
        super.onDestroy();
    }

    private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
        // Bitmap isn't used yet -- will be used soon
        if (mHandler == null) {
            mSavedResultToShow = result;
        } else {
            if (result != null) {
                mSavedResultToShow = result;
            }
            if (mSavedResultToShow != null) {
                Message message = Message.obtain(mHandler,
                        R.id.decode_succeeded, mSavedResultToShow);
                mHandler.sendMessage(message);
            }
            mSavedResultToShow = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG,
                    "*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!mHasSurface) {
            mHasSurface = true;
            initCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mHasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {

    }

    /**
     * A valid barcode has been found, so give an indication of success and show
     * the results.
     *
     * @param rawResult   The contents of the barcode.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param barcode     A greyscale bitmap of the camera data which was decoded.
     */
    public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
        mInactivityTimer.onActivity();

        boolean fromLiveScan = barcode != null;
        if (fromLiveScan) {
            mBeepManager.playBeepSoundAndVibrate();
            drawResultPoints(barcode, scaleFactor, rawResult, getResources()
                    .getColor(R.color.result_points));
        }

        handleDecodeInternally(rawResult, barcode);
    }

    /**
     * Superimpose a line for 1D or dots for 2D to highlight the key features of
     * the barcode.
     *
     * @param barcode     A bitmap of the captured image.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param rawResult   The decoded results which contains the points to draw.
     */
    private static void drawResultPoints(Bitmap barcode, float scaleFactor,
                                         Result rawResult, int color) {
        ResultPoint[] points = rawResult.getResultPoints();
        if (points != null && points.length > 0) {
            Canvas canvas = new Canvas(barcode);
            Paint paint = new Paint();
            paint.setColor(color);
            if (points.length == 2) {
                paint.setStrokeWidth(4.0f);
                drawLine(canvas, paint, points[0], points[1], scaleFactor);
            } else if (points.length == 4
                    && (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A || rawResult
                    .getBarcodeFormat() == BarcodeFormat.EAN_13)) {
                // Hacky special case -- draw two lines, for the barcode and metadata
                drawLine(canvas, paint, points[0], points[1], scaleFactor);
                drawLine(canvas, paint, points[2], points[3], scaleFactor);
            } else {
                paint.setStrokeWidth(10.0f);
                for (ResultPoint point : points) {
                    if (point != null) {
                        canvas.drawPoint(scaleFactor * point.getX(),
                                scaleFactor * point.getY(), paint);
                    }
                }
            }
        }
    }

    private static void drawLine(Canvas canvas, Paint paint, ResultPoint a,
                                 ResultPoint b, float scaleFactor) {
        if (a != null && b != null) {
            canvas.drawLine(scaleFactor * a.getX(), scaleFactor * a.getY(),
                    scaleFactor * b.getX(), scaleFactor * b.getY(), paint);
        }
    }

    // Put up our own UI for how to handle the decoded contents.
    private void handleDecodeInternally(Result rawResult, Bitmap barcode) {

        Uri imageUri = null;
        String imageName = IMAGE_PREFIX + System.currentTimeMillis() + ".png";
        Log.v(TAG, "Saving image as: " + imageName);
        try {
            imageUri = mImageManager.saveImage(imageName, barcode);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save image!", e);
        }

        ResultProcessor<?> processor = ResultProcessorFactory
                .makeResultProcessor(this, rawResult, imageUri);


//        String URL = "http://192.168.1.69:8081/";
//        GetXMLTask task = new GetXMLTask();
//        task.execute(new String[] { URL });

//
//
//        URL obj = new URL(url);
//        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
//
//        // optional default is GET
//        con.setRequestMethod("GET");
//
//        //add request header
//        con.setRequestProperty("User-Agent", "USER_AGENT");
//
//        con.setRequestProperty("User-Agent1", "USER_AGENT1");
//
//        Intent intent = new Intent(Intent.ACTION_VIEW);


        //отредактированно
        String name = "Andrey";
        String QR = rawResult.toString();
        String Query = "where a>b";
//
        String url = "http://192.168.1.69:8081/hello/get_html?input_1="+name+"&input_2="+QR+"&input_3="+Query;
//        String url = "http://172.20.10.3:8081/hello/get_html";
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        startActivity(intent);

//        startActivity(ResultsActivity.newIntent(this,
//                processor.getCardResults()));
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (mCameraManager.isOpen()) {
            Log.w(TAG,
                    "initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        try {
            mCameraManager.openDriver(surfaceHolder);
            // Creating the handler starts the preview, which can also throw a RuntimeException.
            if (mHandler == null) {
                mHandler = new CaptureActivityHandler(this, null, mDecodeHints,
                        null, mCameraManager);
            }

            decodeOrStoreSavedBitmap(null, null);
        } catch (IOException e) {
            Log.w(TAG, e);
            displayFrameworkBugMessageAndExit();
        } catch (InterruptedException e) {
            Log.w(TAG, e);
            displayFrameworkBugMessageAndExit();
        }
    }

    /**
     * FIXME: This should be a glass compatible view (Card)
     */
    private void displayFrameworkBugMessageAndExit() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.app_name));
        builder.setMessage(getString(R.string.msg_camera_framework_bug));
        builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
        builder.setOnCancelListener(new FinishListener(this));
        builder.show();
    }

    public void restartPreviewAfterDelay(long delayMS) {
        if (mHandler != null) {
            mHandler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
        }
    }

    public void drawViewfinder() {
        mViewfinderView.drawViewfinder();
    }
//
//   private class GetXMLTask extends AsyncTask<String, Void, String> {
//        @Override
//        protected String doInBackground(String... urls) {
//            String output = null;
//            for (String url : urls) {
//                output = getOutputFromUrl(url);
//            }
//            return output;
//        }
//        private String getOutputFromUrl(String url) {
//            String output = null;
//            try {
//                DefaultHttpClient httpClient = new DefaultHttpClient();
//                HttpGet httpGet = new HttpGet(url);
//
//                HttpResponse httpResponse = httpClient.execute(httpGet);
//                HttpEntity httpEntity = httpResponse.getEntity();
//                output = EntityUtils.toString(httpEntity);
//            } catch (UnsupportedEncodingException e) {
//                e.printStackTrace();
//            } catch (ClientProtocolException e) {
//                e.printStackTrace();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//            return output;
//        }
//        @Override
//        protected void onPostExecute(String output) {
//            outputText.setText(output);
//        }
//    }

}


