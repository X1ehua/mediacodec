package com.example.mediacodecencode;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class MainActivity extends Activity implements SurfaceHolder.Callback, PreviewCallback {
    private SurfaceView   mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private Camera        mCamera;
    private Parameters    mParameters;

    final static int WIDTH  = 640;
    final static int HEIGHT = 480;

    private final static int YUV_QUEUE_SIZE = 10;
    private final static int CAMERA_GRANTED = 10001;

    public static ArrayBlockingQueue<byte[]> YUVQueue = new ArrayBlockingQueue<byte[]>(YUV_QUEUE_SIZE);

    private AvcEncoder mAvcEncoder;

    private static String[] PERMISSIONS_STORAGE = {
            "android.permission.CAMERA",
            "android.permission.WRITE_EXTERNAL_STORAGE"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSurfaceView = findViewById(R.id.mSurfaceView);
        SupportAvcCodec();
        if (Build.VERSION.SDK_INT > 22) {
            if (!checkPermissionAllGranted(PERMISSIONS_STORAGE)) {
                ActivityCompat.requestPermissions(MainActivity.this, PERMISSIONS_STORAGE, CAMERA_GRANTED);
            } else {
                init();
            }
        } else {
            init();
        }
    }

    private void init() {
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
    }

    private boolean checkPermissionAllGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                // 只要有一个权限没有被授予, 则直接返回 false
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != CAMERA_GRANTED)
            return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            init();
        } else {
            showWaringDialog();
        }
    }

    private void showWaringDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("警告！")
                .setMessage("请前往设置->应用->PermissionDemo->权限中打开相关权限，否则功能无法正常运行！")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 一般情况下如果用户不授权的话，功能是无法运行的，做退出处理
                        finish();
                    }
                }).show();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mCamera = getBackCamera();
        startCamera(mCamera);
        mAvcEncoder = new AvcEncoder(WIDTH, HEIGHT);
        mAvcEncoder.startThread();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopAll();
    }

    void stopAll() {
        if (null != mCamera) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            mAvcEncoder.stopThread();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, android.hardware.Camera mCamera) {
        putYUVData(data, data.length);
    }

    public void putYUVData(byte[] buffer, int length) {
        if (YUVQueue.size() >= 10) {
            YUVQueue.poll();
        }
        YUVQueue.add(buffer);
    }

    @SuppressLint("NewApi")
    private void SupportAvcCodec() {
        for (int j = MediaCodecList.getCodecCount() - 1; j >= 0; j--) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(j);

            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase("video/avc")) {
                    return;
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void startCamera(Camera mCamera) {
        if (mCamera != null) {
            try {
                mCamera.setPreviewCallback(this);
                mCamera.setDisplayOrientation(90);
                if (mParameters == null) {
                    mParameters = mCamera.getParameters();
                }
                mParameters = mCamera.getParameters();
                mParameters.setPreviewFormat(ImageFormat.NV21);
                mParameters.setPreviewSize(WIDTH, HEIGHT);
                mCamera.setParameters(mParameters);
                mCamera.setPreviewDisplay(mSurfaceHolder);
                mCamera.startPreview();

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Camera getBackCamera() {
        Camera c = null;
        try {
            c = Camera.open(0); // attempt to get a Camera instance
        } catch (Exception e) {
            e.printStackTrace();
        }
        return c; // returns null if mCamera is unavailable
    }
}
