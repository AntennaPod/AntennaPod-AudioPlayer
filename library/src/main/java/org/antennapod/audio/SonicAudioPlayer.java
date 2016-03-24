//Copyright 2012 James Falcon
//
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.

/* Minor modifications by Martin Fietz <Martin.Fietz@gmail.com>
 * The original source can be found here:
 * https://github.com/TheRealFalcon/Prestissimo/blob/master/src/com/falconware/prestissimo/Track.java
 */

package org.antennapod.audio;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import org.vinuxproject.sonic.Sonic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class SonicAudioPlayer extends AbstractAudioPlayer {

    private static final String TAG = SonicAudioPlayer.class.getSimpleName();

    protected final MediaPlayer mMediaPlayer;
    private AudioTrack mTrack;
    private int mBufferSize;
    private Sonic mSonic;
    private MediaExtractor mExtractor;
    private MediaCodec mCodec;
    private Thread mDecoderThread;
    private String mPath;
    private Uri mUri;
    private final ReentrantLock mLock;
    private final Object mDecoderLock;
    private boolean mContinue;
    private boolean mIsDecoding;
    private long mDuration;
    private float mCurrentSpeed;
    private float mCurrentPitch;
    private int mCurrentState;
    private final Context mContext;
    private PowerManager.WakeLock mWakeLock = null;
    private boolean mDownMix;

    private final static String TAG_TRACK = "SonicTrack";

    private final static int STATE_IDLE = 0;
    private final static int STATE_INITIALIZED = 1;
    private final static int STATE_PREPARING = 2;
    private final static int STATE_PREPARED = 3;
    private final static int STATE_STARTED = 4;
    private final static int STATE_PAUSED = 5;
    private final static int STATE_STOPPED = 6;
    private final static int STATE_PLAYBACK_COMPLETED = 7;
    private final static int STATE_END = 8;
    private final static int STATE_ERROR = 9;

    public SonicAudioPlayer(MediaPlayer owningMediaPlayer, Context context) {
        super(owningMediaPlayer, context);
        mMediaPlayer = owningMediaPlayer;
        mCurrentState = STATE_IDLE;
        mCurrentSpeed = 1.0f;
        mCurrentPitch = 1.0f;
        mContinue = false;
        mIsDecoding = false;
        mContext = context;
        mPath = null;
        mUri = null;
        mLock = new ReentrantLock();
        mDecoderLock = new Object();
        mDownMix = false;
    }

    @Override
    public int getAudioSessionId() {
        if(mTrack == null) {
            return 0;
        } else {
            return mTrack.getAudioSessionId();
        }
    }

    @Override
    public boolean canSetPitch() {
        return true;
    }

    @Override
    public boolean canSetSpeed() {
        return true;
    }

    @Override
    public float getCurrentPitchStepsAdjustment() {
        return mCurrentPitch;
    }

    public int getCurrentPosition() {
        switch (mCurrentState) {
            case STATE_ERROR:
            case STATE_IDLE:
            case STATE_INITIALIZED:
                return 0;
            default:
                return (int) (mExtractor.getSampleTime() / 1000);
        }
    }

    @Override
    public float getCurrentSpeedMultiplier() {
        return mCurrentSpeed;
    }

    @Override
    public boolean canDownmix() {
        return true;
    }

    @Override
    public void setDownmix(boolean enable) {
        mDownMix = enable;
    }

    public int getDuration() {
        switch (mCurrentState) {
            case STATE_INITIALIZED:
            case STATE_IDLE:
            case STATE_ERROR:
                error();
                break;
            default:
                return (int) (mDuration / 1000);
        }
        return 0;
    }

    @Override
    public float getMaxSpeedMultiplier() {
        return 4.0f;
    }

    @Override
    public float getMinSpeedMultiplier() {
        return 0.5f;
    }

    @Override
    public boolean isLooping() {
        return false;
    }

    public boolean isPlaying() {
        switch (mCurrentState) {
            case STATE_ERROR:
                error();
                break;
            default:
                return mCurrentState == STATE_STARTED;
        }
        return false;
    }

    public void pause() {
        Log.d(TAG, "pause(), current state: " + mCurrentState);
        switch (mCurrentState) {
            case STATE_PREPARED:
                Log.d(TAG_TRACK, "Prepared, ignore pause()");
                break;
            case STATE_STARTED:
            case STATE_PAUSED:
                mTrack.pause();
                mCurrentState = STATE_PAUSED;
                Log.d(TAG_TRACK, "State changed to STATE_PAUSED");
                break;
            default:
                error();
        }
    }

    public void prepare() {
        Log.d(TAG, "prepare(), current state: " + mCurrentState);
        switch (mCurrentState) {
            case STATE_INITIALIZED:
            case STATE_STOPPED:
                boolean streamInitialized = false;
                try {
                    streamInitialized = initStream();
                } catch (IOException e) {
                    Log.e(TAG_TRACK, "Failed setting data source!", e);
                    error();
                    return;
                }
                if (streamInitialized) {
                    mCurrentState = STATE_PREPARED;
                    Log.d(TAG_TRACK, "State changed to STATE_PREPARED");
                    if (owningMediaPlayer.onPreparedListener != null) {
                        owningMediaPlayer.onPreparedListener.onPrepared(owningMediaPlayer);
                    }
                }
                break;
            default:
                error();
        }
    }

    public void prepareAsync() {
        switch (mCurrentState) {
            case STATE_INITIALIZED:
            case STATE_STOPPED:
                mCurrentState = STATE_PREPARING;
                Log.d(TAG_TRACK, "State changed to STATE_PREPARING");

                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean streamInitialized = false;
                        String lastPath = currentPath();

                        try {
                            streamInitialized = initStream();
                        } catch (IOException e) {
                            if (lastPath.equals(currentPath())) {
                                Log.e(TAG_TRACK, "Failed setting data source!", e);
                                error();
                            }
                            return;
                        }
                        if (streamInitialized) {
                            if (mCurrentState != STATE_ERROR) {
                                mCurrentState = STATE_PREPARED;
                                Log.d(TAG_TRACK, "State changed to STATE_PREPARED");
                            }
                            if (owningMediaPlayer.onPreparedListener != null) {
                                owningMediaPlayer.onPreparedListener.onPrepared(owningMediaPlayer);
                            }
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                break;
            default:
                error();
        }
    }

    public void stop() {
        switch (mCurrentState) {
            case STATE_PREPARED:
            case STATE_STARTED:
            case STATE_STOPPED:
            case STATE_PAUSED:
            case STATE_PLAYBACK_COMPLETED:
                mCurrentState = STATE_STOPPED;
                Log.d(TAG_TRACK, "State changed to STATE_STOPPED");
                mContinue = false;
                mTrack.pause();
                mTrack.flush();
                break;
            default:
                error();
        }
    }

    public void start() {
        switch (mCurrentState) {
            case STATE_PLAYBACK_COMPLETED:
                try {
                    initStream();
                } catch (IOException e) {
                    Log.e(TAG, "initStream() failed");
                    error();
                    return;
                }
                // deliberate fallthrough
            case STATE_PREPARED:
                mCurrentState = STATE_STARTED;
                Log.d(TAG, "State changed to STATE_STARTED");
                mContinue = true;
                mTrack.play();
                decode();
            case STATE_STARTED:
                break;
            case STATE_PAUSED:
                mCurrentState = STATE_STARTED;
                Log.d(TAG, "State changed to STATE_STARTED");
                synchronized (mDecoderLock) {
                    mDecoderLock.notify();
                }
                mTrack.play();
                break;
            default:
                mCurrentState = STATE_ERROR;
                Log.d(TAG, "State changed to STATE_ERROR in start");
                if (mTrack != null) {
                    error();
                } else {
                    Log.d("start", "Attempting to start while in idle after construction. " +
                            "Not allowed by no callbacks called");
                }
        }
    }

    public void release() {
        reset();
        mCurrentState = STATE_END;
    }

    public void reset() {
        mLock.lock();
        mContinue = false;
        try {
            if (mDecoderThread != null
                    && mCurrentState != STATE_PLAYBACK_COMPLETED) {
                while (mIsDecoding) {
                    synchronized (mDecoderLock) {
                        mDecoderLock.notify();
                        mDecoderLock.wait();
                    }
                }
            }
        } catch (InterruptedException e) {
            Log.e(TAG_TRACK, "Interrupted in reset while waiting for decoder thread to stop.",
                    e);
        }
        if (mCodec != null) {
            mCodec.release();
            mCodec = null;
        }
        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
        if (mTrack != null) {
            mTrack.release();
            mTrack = null;
        }
        mPath = null;
        mUri = null;
        mCurrentState = STATE_IDLE;
        Log.d(TAG_TRACK, "State changed to STATE_IDLE");
        mLock.unlock();
    }

    public void seekTo(final int msec) {
        boolean playing = false;
        switch (mCurrentState) {
            case STATE_STARTED:
                playing = true;
                pause();
            case STATE_PREPARED:
            case STATE_PAUSED:
            case STATE_PLAYBACK_COMPLETED:
                mLock.lock();
                if (mTrack == null) {
                    return;
                }
                mTrack.flush();
                mExtractor.seekTo(((long) msec * 1000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                Log.d(TAG, "seek completed, position: " + (mExtractor.getSampleTime() / 1000L));
                if (owningMediaPlayer.onSeekCompleteListener != null) {
                    owningMediaPlayer.onSeekCompleteListener.onSeekComplete(owningMediaPlayer);
                }
                mLock.unlock();
                if (playing) {
                    start();
                }
                break;
            default:
                error();
        }
    }

    @Override
    public void setAudioStreamType(int streamtype) {
        return;
    }

    @Override
    public void setEnableSpeedAdjustment(boolean enableSpeedAdjustment) {
        return;
    }

    @Override
    public void setLooping(boolean loop) {
        return;
    }

    @Override
    public void setPitchStepsAdjustment(float pitchSteps) {
        mCurrentPitch += pitchSteps;
    }

    @Override
    public void setPlaybackPitch(float f) {
        mCurrentSpeed = f;
    }

    @Override
    public void setPlaybackSpeed(float f) {
        mCurrentSpeed = f;
    }

    @Override
    public void setDataSource(String path) {
        switch (mCurrentState) {
            case STATE_IDLE:
                mPath = path;
                mCurrentState = STATE_INITIALIZED;
                Log.d(TAG_TRACK, "Moving state to STATE_INITIALIZED");
                break;
            default:
                error();
        }
    }

    @Override
    public void setDataSource(Context context, Uri uri) {
        switch (mCurrentState) {
            case STATE_IDLE:
                mUri = uri;
                mCurrentState = STATE_INITIALIZED;
                Log.d(TAG_TRACK, "Moving state to STATE_INITIALIZED");
                break;
            default:
                error();
        }
    }

    public void setDownMix(boolean downmix) {
        mDownMix = downmix;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        // Pass call directly to AudioTrack if available.
        if (null != mTrack) {
            mTrack.setStereoVolume(leftVolume, rightVolume);
        }
    }

    @Override
    public void setWakeMode(Context context, int mode) {
        boolean wasHeld = false;
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                wasHeld = true;
                mWakeLock.release();
            }
            mWakeLock = null;
        }

        if(mode > 0) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(mode, this.getClass().getName());
            mWakeLock.setReferenceCounted(false);
            if (wasHeld) {
                mWakeLock.acquire();
            }
        }
    }

    public void error() {
        error(0);
    }

    public void error(int extra) {
        if(mCurrentState == STATE_ERROR) {
            return;
        }
        Log.e(TAG_TRACK, "Moved to error state!");
        mCurrentState = STATE_ERROR;
        if(owningMediaPlayer.onErrorListener != null) {
            boolean handled = owningMediaPlayer.onErrorListener.onError(owningMediaPlayer, 0, extra);
            if (!handled && owningMediaPlayer.onCompletionListener != null) {
                owningMediaPlayer.onCompletionListener.onCompletion(owningMediaPlayer);
            }
        }
    }

    private int findFormatFromChannels(int numChannels) {
        switch (numChannels) {
            case 1:
                return AudioFormat.CHANNEL_OUT_MONO;
            case 2:
                return AudioFormat.CHANNEL_OUT_STEREO;
            case 3:
                return AudioFormat.CHANNEL_OUT_STEREO | AudioFormat.CHANNEL_OUT_FRONT_CENTER;
            case 4:
                return AudioFormat.CHANNEL_OUT_QUAD;
            case 5:
                return AudioFormat.CHANNEL_OUT_QUAD | AudioFormat.CHANNEL_OUT_FRONT_CENTER;
            case 6:
                return AudioFormat.CHANNEL_OUT_5POINT1;
            case 7:
                return AudioFormat.CHANNEL_OUT_5POINT1 | AudioFormat.CHANNEL_OUT_BACK_CENTER;
            case 8:
                if (Build.VERSION.SDK_INT >= 23) {
                    return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
                } else {
                    return -1;
                }
            default:
                return -1; // Error
        }
    }

    private String currentPath() {
        if (mPath != null) {
            return mPath;
        }
        else if (mUri != null) {
            return mUri.toString();
        }

        return null;
    }

    public boolean initStream() throws IOException {
        mExtractor = new MediaExtractor();

        // Save the current path. When "setDataSource" returns, the current media file could have changed
        String lastPath = currentPath();

        if (mPath != null) {
            mExtractor.setDataSource(mPath);
        }
        else if (mUri != null) {
            mExtractor.setDataSource(mContext, mUri, null);
        }
        else {
            throw new IOException();
        }

        if (!lastPath.equals(currentPath())) {
            return false;
        }

        mLock.lock();
        int trackNum = -1;
        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            final MediaFormat oFormat = mExtractor.getTrackFormat(i);
            String mime = oFormat.getString(MediaFormat.KEY_MIME);
            if (trackNum < 0 && mime.startsWith("audio/")) {
                trackNum = i;
            }
            else {
                mExtractor.unselectTrack(i);
            }
        }

        if (trackNum < 0) {
            mLock.unlock();
            throw new IOException("No audio track found");
        }

        final MediaFormat oFormat = mExtractor.getTrackFormat(trackNum);
        try {
            int sampleRate = oFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = oFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            final String mime = oFormat.getString(MediaFormat.KEY_MIME);
            mDuration = oFormat.getLong(MediaFormat.KEY_DURATION);

            Log.v(TAG_TRACK, "Sample rate: " + sampleRate);
            Log.v(TAG_TRACK, "Channel count: " + channelCount);
            Log.v(TAG_TRACK, "Mime type: " + mime);
            Log.v(TAG_TRACK, "Duration: " + mDuration);

            initDevice(sampleRate, channelCount);
            mExtractor.selectTrack(trackNum);
            mCodec = MediaCodec.createDecoderByType(mime);
            mCodec.configure(oFormat, null, null, 0);
        } catch (Throwable th) {
            Log.e(TAG, Log.getStackTraceString(th));
            error();
        }
        mLock.unlock();

        return true;
    }

    private void initDevice(int sampleRate, int numChannels) {
        mLock.lock();
        final int format = findFormatFromChannels(numChannels);
        int oldBufferSize = mBufferSize;
        mBufferSize = AudioTrack.getMinBufferSize(sampleRate, format, AudioFormat.ENCODING_PCM_16BIT);
        if(mBufferSize != oldBufferSize) {
            if (mTrack != null) {
                mTrack.release();
            }
            mTrack = createAudioTrack(sampleRate, format, mBufferSize);
        }
        mSonic = new Sonic(sampleRate, numChannels);
        mLock.unlock();
    }

    private AudioTrack createAudioTrack(int sampleRate,
                                        int channelConfig,
                                        int minBufferSize) {
        for (int i = 4; i >= 1; i--) {
            int bufferSize = minBufferSize * i;

            AudioTrack audioTrack = null;
            try {
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                        channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufferSize,
                        AudioTrack.MODE_STREAM);
                if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                    mBufferSize = bufferSize;
                    return audioTrack;
                } else {
                    audioTrack.release();
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, Log.getStackTraceString(e));
                if (audioTrack != null) {
                    audioTrack.release();
                }
            }
        }
        throw new IllegalStateException("Could not create buffer for AudioTrack");
    }


    @SuppressWarnings("deprecation")
    public void decode() {
        mDecoderThread = new Thread(new Runnable() {

            private int currHeadPos;

            @Override
            public void run() {

                mIsDecoding = true;
                mCodec.start();

                ByteBuffer[] inputBuffers = mCodec.getInputBuffers();
                ByteBuffer[] outputBuffers = mCodec.getOutputBuffers();

                boolean sawInputEOS = false;
                boolean sawOutputEOS = false;

                while (!sawInputEOS && !sawOutputEOS && mContinue) {
                    currHeadPos = mTrack.getPlaybackHeadPosition();
                    if (mCurrentState == STATE_PAUSED) {
                        System.out.println("Decoder changed to PAUSED");
                        try {
                            synchronized (mDecoderLock) {
                                mDecoderLock.wait();
                                System.out.println("Done with wait");
                            }
                        } catch (InterruptedException e) {
                            // Purposely not doing anything here
                        }
                        continue;
                    }

                    if (null != mSonic) {
                        mSonic.setSpeed(mCurrentSpeed);
                        mSonic.setPitch(mCurrentPitch);
                    }

                    int inputBufIndex = mCodec.dequeueInputBuffer(200);
                    if (inputBufIndex >= 0) {
                        ByteBuffer dstBuf = inputBuffers[inputBufIndex];
                        int sampleSize = mExtractor.readSampleData(dstBuf, 0);
                        long presentationTimeUs = 0;
                        if (sampleSize < 0) {
                            sawInputEOS = true;
                            sampleSize = 0;
                        } else {
                            presentationTimeUs = mExtractor.getSampleTime();
                        }
                        mCodec.queueInputBuffer(
                                inputBufIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (!sawInputEOS) {
                            mExtractor.advance();
                        }
                    }

                    final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    byte[] modifiedSamples = new byte[info.size];

                    int res;
                    do {
                        res = mCodec.dequeueOutputBuffer(info, 200);
                        if (res >= 0) {
                            int outputBufIndex = res;
                            final byte[] chunk = new byte[info.size];
                            outputBuffers[res].get(chunk);
                            outputBuffers[res].clear();

                            if (chunk.length > 0) {
                                mSonic.writeBytesToStream(chunk, chunk.length);
                            } else {
                                mSonic.flushStream();
                            }
                            int available = mSonic.samplesAvailable();
                            if (available > 0) {
                                if (modifiedSamples.length < available) {
                                    modifiedSamples = new byte[available];
                                }
                                if (mDownMix && mSonic.getNumChannels() == 2) {
                                    int maxBytes = (available / 4) * 4;
                                    mSonic.readBytesFromStream(modifiedSamples, maxBytes);

                                    for (int i = 0; (i + 3) < modifiedSamples.length; i += 4) {
                                        short left = (short) ((modifiedSamples[i] & 0xff) | (modifiedSamples[i + 1] << 8));
                                        short right = (short) ((modifiedSamples[i + 2] & 0xff) | (modifiedSamples[i + 3] << 8));
                                        short value = (short) (0.5 * left + 0.5 * right);

                                        modifiedSamples[i] = (byte) (value & 0xff);
                                        modifiedSamples[i + 1] = (byte) (value >> 8);
                                        modifiedSamples[i + 2] = (byte) (value & 0xff);
                                        modifiedSamples[i + 3] = (byte) (value >> 8);
                                    }
                                    mTrack.write(modifiedSamples, 0, maxBytes);
                                } else {
                                    mSonic.readBytesFromStream(modifiedSamples, available);
                                    mTrack.write(modifiedSamples, 0, available);
                                }
                            }

                            mCodec.releaseOutputBuffer(outputBufIndex, false);

                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEOS = true;
                            }
                        } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            outputBuffers = mCodec.getOutputBuffers();
                            Log.d("PCM", "Output buffers changed");
                        } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            final MediaFormat oFormat = mCodec.getOutputFormat();
                            Log.d("PCM", "Output format has changed to " + oFormat);
                            int sampleRate = oFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            int channelCount = oFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            if (sampleRate != mSonic.getSampleRate() ||
                                    channelCount != mSonic.getNumChannels()) {
                                mTrack.stop();
                                mLock.lock();
                                mTrack.release();
                                initDevice(sampleRate, channelCount);
                                outputBuffers = mCodec.getOutputBuffers();
                                mTrack.play();
                                mLock.unlock();
                            }
                        }
                    } while (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED ||
                            res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
                }
                Log.d(TAG_TRACK, "Decoding loop exited. Stopping codec and track");
                Log.d(TAG_TRACK, "Duration: " + (int) (mDuration / 1000));
                Log.d(TAG_TRACK, "Current position: " + (int) (mExtractor.getSampleTime() / 1000));
                mCodec.stop();

                // wait for track to finish playing
                int lastHeadPos;
                do {
                    lastHeadPos = currHeadPos;
                    try {
                        Thread.sleep(100);
                        currHeadPos = mTrack.getPlaybackHeadPosition();
                    } catch (InterruptedException e) { /* ignore */ }
                } while(currHeadPos != lastHeadPos);
                mTrack.stop();

                Log.d(TAG_TRACK, "Stopped codec and track");
                Log.d(TAG_TRACK, "Current position: " + (int) (mExtractor.getSampleTime() / 1000));
                mIsDecoding = false;
                if (mContinue && (sawInputEOS || sawOutputEOS)) {
                    mCurrentState = STATE_PLAYBACK_COMPLETED;
                    if (owningMediaPlayer.onCompletionListener != null) {
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                owningMediaPlayer.onCompletionListener.onCompletion(owningMediaPlayer);

                            }
                        });
                        t.setDaemon(true);
                        t.start();
                    }
                } else {
                    Log.d(TAG_TRACK, "Loop ended before saw input eos or output eos");
                    Log.d(TAG_TRACK, "sawInputEOS: " + sawInputEOS);
                    Log.d(TAG_TRACK, "sawOutputEOS: " + sawOutputEOS);
                }
                synchronized (mDecoderLock) {
                    mDecoderLock.notifyAll();
                }
            }
        });
        mDecoderThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e(TAG_TRACK, Log.getStackTraceString(ex));
                error();
            }
        });
        mDecoderThread.setDaemon(true);
        mDecoderThread.start();
    }

}
