package com.example.mediacodecencode;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;

public class AvcEncoder implements Runnable {
    private final static String TAG = "MediaCodec";
    private static final boolean VERBOSE = false;

    private static final int FRAME_RATE        = 24; // 24 FPS
    private static final int IFRAME_INTERVAL   = 2;  // 2 seconds between I-frames
    private static final int AUTO_STOP_SECONDS = 15;

    private int mWidth   = -1;
    private int mHeight  = -1;
    private int mBitRate = -1;

    private byte[]     mInputData = null;
    public  byte[]     mConfigData;
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private boolean    mMuxerStarted;
    private int        mTrackIndex;
    private MediaCodec.BufferInfo mBufferInfo;

    private boolean      mThreadRunning  = false;
    private long         mGeneratedIndex = 0;
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;

    private static final String OUTPUT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()
                                            + "/DCIM/cc/t2.mp4";

    public AvcEncoder(int width, int height) {
        mWidth   = width;
        mHeight  = height;
        mBitRate = mWidth * mHeight * 4;

        try {
            prepareEncoder();
        }
        catch(Exception e) {
            Log.e(TAG, ">> prepareEncoder() failed:");
            e.printStackTrace();
        }
    }

    private void prepareEncoder() throws Exception {
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        mEncoder = MediaCodec.createEncoderByType("video/avc");
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();

        try {
            mMuxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    public void startThread() {
        new Thread(this).start();
    }

    public void stopThread() {
        mThreadRunning = false;
        Log.w(TAG, ">> stopThread()");
    }

    @Override
    public void run() {
        mThreadRunning = true;
        mInputBuffers  = mEncoder.getInputBuffers();
        mOutputBuffers = mEncoder.getOutputBuffers();
        long start = System.currentTimeMillis();

        while (mThreadRunning) {
            int ret = doFrame(); // queueInputBuffer

            if (ret < 0) {
                mThreadRunning = false;
                Log.w(TAG, "doFrame() failed: " + ret);
                break;
            }

            long now = System.currentTimeMillis();
            if (now - start > AUTO_STOP_SECONDS * 1000) { // N 秒自动停止
                mThreadRunning = false;
                Log.w(TAG, ">> Auto stopped at " + AUTO_STOP_SECONDS + " seconds.");
            }
        }

        try {
            drainEncoder(true);
        }
        catch (Exception e) {
            Log.w(TAG, ">> drainEncoder(true) failed: " + e.toString());
        }

        releaseEncoder();
        Log.d(TAG, ">> releaseEncoder()");
    }

    int doFrame() {
        if (MainActivity.YUVQueue.size() > 0) {
            mInputData = MainActivity.YUVQueue.poll();
            byte[] yuv420sp = new byte[mWidth * mHeight * 3 / 2];
            NV21ToNV12(mInputData, yuv420sp, mWidth, mHeight);
            mInputData = yuv420sp;
        }

        if (mInputData == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 0;
        }

        try {
            long pts = 0;
            int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                pts = computePresentationTime(mGeneratedIndex);
                ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(mInputData);
                mEncoder.queueInputBuffer(inputBufferIndex, 0, mInputData.length, pts, 0);
                mGeneratedIndex += 1;
            }

            drainEncoder(false);
            return 0;
        }
        catch (Throwable t) {
            Log.e(TAG, "drainEncoder(false) failed:");
            t.printStackTrace();
            return -1;
        }
    }

    /**
     * Extracts all pending data from the encoder.
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    private void drainEncoder(boolean endOfStream) throws Exception {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");

            int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                inputBuffer.clear();
                mEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    private void releaseEncoder() {
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    private void NV21ToNV12(byte[] nv21, byte[] nv12, int width, int height) {
        if (nv21 == null || nv12 == null) {
            return;
        }
        int framesize = width * height;
        int i = 0, j = 0;
        System.arraycopy(nv21, 0, nv12, 0, framesize);
        for (i = 0; i < framesize; i++) {
            nv12[i] = nv21[i];
        }
        for (j = 0; j < framesize / 2; j += 2) {
            nv12[framesize + j - 1] = nv21[j + framesize];
        }
        for (j = 0; j < framesize / 2; j += 2) {
            nv12[framesize + j] = nv21[j + framesize - 1];
        }
    }

    /* Generates the presentation time for frame N, in microseconds. */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
    }
}
