package com.example.mediacodecencode;

import java.io.IOException;
import java.nio.ByteBuffer;
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

    private byte[]     mInputYuvData = null;
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private boolean    mMuxerStarted;
    private int        mTrackIndex;
    private MediaCodec.BufferInfo mBufferInfo;

    private boolean      mThreadRunning  = false;
    private long         mFrameIndex = 0;
    private ByteBuffer[] mInputBuffers;

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

        // Set some props. Failing to specify some of these can cause MediaCodec configure() throw unhelpful exception
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        mEncoder = MediaCodec.createEncoderByType("video/avc");
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();

        mMuxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

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
        if (MainActivity.sYUVQueue.size() > 0) {
            byte[] cameraYUVData = MainActivity.sYUVQueue.poll();
            if (mInputYuvData == null)
                mInputYuvData = new byte[mWidth * mHeight * 3 / 2];
            NV21_to_NV12(cameraYUVData, mInputYuvData, mWidth, mHeight);
        }

        if (mInputYuvData == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 0;
        }

        try {
            int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                long pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
                ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(mInputYuvData);
                mEncoder.queueInputBuffer(inputBufferIndex, 0, mInputYuvData.length, pts, 0);
                mFrameIndex += 1;
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
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) { // no output available yet
                if (!endOfStream)
                    break; // out of while
                // else: no output available, spinning to await EOS
            }
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder, should not happen
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            }
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted)
                    throw new RuntimeException("MediaCodec.output_format changed twice");

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack( mEncoder.getOutputFormat() );
                mMuxer.start();
                mMuxerStarted = true;
            }
            else if (encoderStatus < 0) { // let's ignore it
                Log.w(TAG, "mEncoder.dequeueOutputBuffer() returned bad status: " + encoderStatus);
            }
            else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null)
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted)
                        throw new RuntimeException("muxer hasn't started");

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream)
                        Log.w(TAG, "reached end of stream unexpectedly");
                    else
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    break; // out of while
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

    private void NV21_to_NV12(byte[] src_nv21, byte[] dst_nv12, int width, int height) {
        int frameSize = width * height;
        //long t1 = System.nanoTime();
        System.arraycopy(src_nv21, 0, dst_nv12, 0, frameSize);
        for (int i = 0; i < frameSize / 2; i += 2) {
            dst_nv12[frameSize + i - 1] = src_nv21[i + frameSize];
            dst_nv12[frameSize + i]     = src_nv21[i + frameSize - 1];
        }
        //dt_sum += System.nanoTime() - t1;
        //if (++cc % 100 == 0) // 640x480@24FPS 平均 2.0 ms, TODO: 改为 native 计算是否会有明显提升?
        //    Log.i(TAG, "NV21_to_NV12 dt " + (dt_sum / cc));
    }
    //private int cc = 0, dt_sum = 0;
}
