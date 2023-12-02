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
    private final int TIMEOUT_USEC = 12000;

    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted;
    private int mTrackIndex;
    private MediaCodec.BufferInfo mBufferInfo;

    private static final int FRAME_RATE      = 24; // 24 FPS
    private static final int IFRAME_INTERVAL = 2;  // 2 seconds between I-frames
//  private static final int NUM_FRAMES = 30;      // two seconds of video

    private int mWidth   = -1;
    private int mHeight  = -1;
    private int mBitRate = -1;

    public byte[] mConfigData;

    private static final String OUTPUT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()
                                            + "/DCIM/cc/t2.mp4";
    //private BufferedOutputStream mOutputStream = null;

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

        /*
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mWidth * mHeight * 4);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        try {
            mEncoder = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            e.printStackTrace();
        }
    
        mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
        createOutFile();
        */
    }

    /*
    private void createOutFile(){
        File file = new File(OUTPUT_PATH);
        try {
            mOutputStream = new BufferedOutputStream(new FileOutputStream(file));
        } catch (Exception e){ 
            e.printStackTrace();
        }
    }
    */

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
        // mInputSurface = new CodecInputSurface(mEncoder.createInputSurface());
        mEncoder.start();

        try {
            mMuxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    private boolean mThreadRunning = false;
    private byte[] mInputData = null;
    private long mGeneratedIndex = 0;
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;

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
            int ret = doFrame();
            if (ret < 0) {
                mThreadRunning = false;
                Log.w(TAG, "doFrame() failed: " + ret);
                break;
            }

            long now = System.currentTimeMillis();
            if (now - start > 1000 * 8) {
                mThreadRunning = false;
            }
        }

        int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
            inputBuffer.clear();
            mEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }

        drainEncoder(true);

        releaseEncoder();
        Log.w(TAG, ">> releaseEncoder()");

        /*
        try {
            mOutputStream.flush();
            mOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        */
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
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 0;
        }

        try {
            //long startMs = System.currentTimeMillis();
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

            /*
            //MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            while (outputBufferIndex >= 0) {
                // Log.i("AvcEncoder", "Get H264 Buffer Success! flag = " + mBufferInfo.flags +
                //       ",pts = " + mBufferInfo.presentationTimeUs);
                ByteBuffer outputBuffer = mOutputBuffers[outputBufferIndex];
                byte[] outData = new byte[mBufferInfo.size];
                outputBuffer.get(outData);
                Log.i(TAG, "MediaCodec.BufferInfo.flags: " + mBufferInfo.flags);
                if (mBufferInfo.flags == 2) {
                    mConfigData = outData;
                } else if (mBufferInfo.flags == 1) {
                    byte[] keyframe = new byte[mBufferInfo.size + mConfigData.length];
                    System.arraycopy(mConfigData, 0, keyframe, 0, mConfigData.length);
                    System.arraycopy(outData, 0, keyframe, mConfigData.length, outData.length);

                    mOutputStream.write(keyframe, 0, keyframe.length);
                } else {
                    mOutputStream.write(outData, 0, outData.length);
                }

                mEncoder.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            }
            */
        } catch (Throwable t) {
            Log.e(TAG, "Exception in EncoderThread:");
            t.printStackTrace();
            return -1;
        }

        return 0;
    }

    /**
     * Extracts all pending data from the encoder.
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    private void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            //mEncoder.signalEndOfInputStream();
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

    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / FRAME_RATE;
    }
}
