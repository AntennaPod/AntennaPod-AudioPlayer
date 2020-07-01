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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.antennapod.audio.SonicAudioPlayerState.END;
import static org.antennapod.audio.SonicAudioPlayerState.ERROR;
import static org.antennapod.audio.SonicAudioPlayerState.IDLE;
import static org.antennapod.audio.SonicAudioPlayerState.INITIALIZED;
import static org.antennapod.audio.SonicAudioPlayerState.PAUSED;
import static org.antennapod.audio.SonicAudioPlayerState.PLAYBACK_COMPLETED;
import static org.antennapod.audio.SonicAudioPlayerState.PREPARED;
import static org.antennapod.audio.SonicAudioPlayerState.PREPARING;
import static org.antennapod.audio.SonicAudioPlayerState.STARTED;
import static org.antennapod.audio.SonicAudioPlayerState.STOPPED;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class SonicAudioPlayer extends AbstractAudioPlayer {

    private static final String TAG = SonicAudioPlayer.class.getSimpleName();
    private final static String TAG_TRACK = "SonicTrack";

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
    private AtomicInteger mInitiatingCount = new AtomicInteger(0);
    private AtomicInteger mSeekingCount = new AtomicInteger(0);
    private boolean mIsDecoding;
    private long mDuration;
    private float mCurrentSpeed;
    private float mCurrentPitch;

    private final SonicAudioPlayerState state = new SonicAudioPlayerState();

    private final Context mContext;
    private PowerManager.WakeLock mWakeLock = null;

    private boolean mDownMix;


    SonicAudioPlayer(MediaPlayer owningMediaPlayer, Context context, String userAgent) {
        super(owningMediaPlayer, context, userAgent);
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
        if (mTrack == null) {
            return 0;
        }
        return mTrack.getAudioSessionId();
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
        if (state.is(INITIALIZED) || state.is(IDLE) || state.is(ERROR)) {
            return 0;
        }
        return (int) (mExtractor.getSampleTime() / 1000);
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
        if (state.is(INITIALIZED) || state.is(IDLE) || state.is(ERROR)) {
            error();
            return 0;
        }
        return (int) (mDuration / 1000);
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
        if (state.is(ERROR)) {
            error();
            return false;
        }
        return state.is(STARTED);
    }

    public void pause() {
        Log.d(TAG, "pause(), current state: " + state);
        if (state.is(PREPARED)) {
            Log.d(TAG_TRACK, "PREPARED, ignore pause()");
            return;
        }
        if (!state.is(STARTED) && !state.is(PAUSED)) {
            error();
            return;
        }
        mTrack.pause();
        state.changeTo(PAUSED);
    }

    public void prepare() {
        Log.d(TAG, "prepare(), current state: " + state);
        if (!state.is(INITIALIZED) && !state.is(STOPPED)) {
            error();
            return;
        }
        doPrepare();
    }

    public void prepareAsync() {
        Log.d(TAG, "prepareAsync(), current state: " + state);
        if (!state.is(INITIALIZED) && !state.is(STOPPED)) {
            error();
            return;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                doPrepare();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void doPrepare() {
        boolean streamInitialized;
        String lastPath = currentPath();

        state.changeTo(PREPARING);
        try {
            streamInitialized = initStream();
        } catch (IOException e) {
            String currentPath = currentPath();
            if (currentPath == null || currentPath.equals(lastPath)) {
                Log.e(TAG_TRACK, "Failed setting data source!", e);
                error();
            }
            return;
        }
        if (streamInitialized) {
            if (!state.is(ERROR)) {
                state.changeTo(PREPARED);
            }
            owningMediaPlayer.onPreparedListener.onPrepared(owningMediaPlayer);
        }
    }

    public void stop() {
        if (!state.stoppingAllowed()) {
            error();
            Log.d(TAG_TRACK, "Stopping in current state " + state + " not allowed");
            return;
        }
        state.changeTo(STOPPED);
        mContinue = false;
        mTrack.pause();
        mTrack.flush();
    }

    public void start() {
        if (state.is(STARTED)) {
            return;
        }
        if (state.is(PLAYBACK_COMPLETED) || state.is(PREPARED)) {
            if(state.is(PLAYBACK_COMPLETED)) {
                try {
                    initStream();
                } catch (IOException e) {
                    Log.e(TAG, "initStream() failed");
                    error();
                    return;
                }
            }
            state.changeTo(STARTED);
            mContinue = true;
            mTrack.play();
            decode();
        } else if (state.is(PAUSED)) {
            state.changeTo(STARTED);
            synchronized (mDecoderLock) {
                mDecoderLock.notify();
            }
            mTrack.play();
        } else {
            state.changeTo(ERROR);
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
        state.changeTo(END);
    }

    public void reset() {
        mLock.lock();
        mContinue = false;
        try {
            if (mDecoderThread != null && !state.is(PLAYBACK_COMPLETED)) {
                while (mIsDecoding) {
                    synchronized (mDecoderLock) {
                        mDecoderLock.notify();
                        mDecoderLock.wait();
                    }
                }
            }
        } catch (InterruptedException e) {
            Log.e(TAG_TRACK, "Interrupted in reset while waiting for decoder thread to stop.", e);
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
        mBufferSize = 0;
        state.changeTo(IDLE);
        mLock.unlock();
    }

    public void seekTo(final int msec) {
        boolean playing = false;

        if (!state.seekingAllowed()) {
            error();
            Log.d(TAG_TRACK, "Seeking in current state " + state + " is not seekable");
            return;
        }

        if (state.is(STARTED)) {
            playing = true;
            pause();
        }
        if (mTrack == null) {
            return;
        }
        mTrack.flush();

        final boolean wasPlaying = playing;

        Runnable seekRunnable = new Runnable() {

            @Override
            public void run() {
                String lastPath = currentPath();

                mSeekingCount.incrementAndGet();
                try {
                    mExtractor.seekTo(((long) msec * 1000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                } catch (Exception e) {
                    error();
                    return;
                } finally {
                    mSeekingCount.decrementAndGet();
                }

                // make sure that the current episode didn't change while seeking
                if (mExtractor != null && lastPath != null && lastPath.equals(currentPath()) && !state.is(ERROR)) {

                    Log.d(TAG, "seek completed, position: " + getCurrentPosition());

                    if (owningMediaPlayer.onSeekCompleteListener != null) {
                        owningMediaPlayer.onSeekCompleteListener.onSeekComplete(owningMediaPlayer);
                    }
                    if (wasPlaying) {
                        start();
                    }
                }
            }
        };

        // when streaming, the seeking is started in another thread to prevent UI locking
        if (mUri != null) {
            Thread t = new Thread(seekRunnable);
            t.setDaemon(true);
            t.start();
        } else {
            seekRunnable.run();
        }
    }

    @Override
    public void setAudioStreamType(int streamtype) {}

    @Override
    public void setEnableSpeedAdjustment(boolean enableSpeedAdjustment) {}

    @Override
    public void setLooping(boolean loop) {}

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
        if (!state.settingDataSourceAllowed()) {
            error();
            return;
        }
        mPath = path;
        state.changeTo(INITIALIZED);
    }

    @Override
    public void setDataSource(Context context, Uri uri) {
        if (!state.settingDataSourceAllowed()) {
            error();
            return;
        }
        mUri = uri;
        state.changeTo(INITIALIZED);
    }

    void setDownMix(boolean downmix) {
        mDownMix = downmix;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        // Pass call directly to AudioTrack if available.
        if (mTrack == null) {
            return;
        }
        mTrack.setStereoVolume(leftVolume, rightVolume);
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

        if (mode > 0) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(mode, this.getClass().getName());
            mWakeLock.setReferenceCounted(false);
            if (wasHeld) {
                mWakeLock.acquire();
            }
        }
    }

    private void error() {
        error(0);
    }

    private void error(int extra) {
        if (state.is(ERROR)) {
            return;
        }
        state.changeTo(ERROR);
        if (owningMediaPlayer.onErrorListener != null) {
            boolean handled = owningMediaPlayer.onErrorListener.onError(owningMediaPlayer, 0, extra);
            if (!handled && owningMediaPlayer.onCompletionListener != null) {
                owningMediaPlayer.onCompletionListener.onCompletion(owningMediaPlayer);
            }
        }
    }

    private String currentPath() {
        if (mPath != null) {
            return mPath;
        } else if (mUri != null) {
            return mUri.toString();
        }

        return null;
    }

    private boolean initStream() throws IOException {

        // Since this method could be running in another thread, when "setDataSource" returns
        // we need to check if the media path has changed
        String lastPath = currentPath();

        mInitiatingCount.incrementAndGet();
        try {
            mExtractor = new MediaExtractor();

            if (mPath != null) {
                mExtractor.setDataSource(mPath, getHeaders());
            } else if (mUri != null) {
                mExtractor.setDataSource(mContext, mUri, getHeaders());
            } else {
                throw new IOException("Neither path nor uri set");
            }
        } finally {
            mInitiatingCount.decrementAndGet();
        }

        String currentPath = currentPath();
        if (currentPath == null || !currentPath.equals(lastPath) || state.is(ERROR)) {
            return false;
        }

        mLock.lock();

        if (mExtractor == null) {
            mLock.unlock();
            throw new IOException("Extractor is null");
        }

        int trackNum = -1;
        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            final MediaFormat oFormat = mExtractor.getTrackFormat(i);
            String mime = oFormat.getString(MediaFormat.KEY_MIME);
            if (trackNum < 0 && mime.startsWith("audio/")) {
                trackNum = i;
            } else {
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
        if (mBufferSize != oldBufferSize) {
            if (mTrack != null) {
                mTrack.release();
            }
            mTrack = createAudioTrack(sampleRate, format, mBufferSize);
        }
        mSonic = new Sonic(sampleRate, numChannels);
        mLock.unlock();
    }

    private static int findFormatFromChannels(int numChannels) {
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
                if (audioTrack != null) {
                    audioTrack.release();
                }
            }
        }
        throw new IllegalStateException("Could not create buffer for AudioTrack");
    }

    @SuppressWarnings("deprecation")
    private void decode() {
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
                    if (state.is(PAUSED)) {
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
                                    DownMixer.downMix(modifiedSamples);
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

                if (!((mInitiatingCount.get() > 0) || (mSeekingCount.get() > 0))) {
                    Log.d(TAG_TRACK, "Current position: " + getCurrentPosition());
                }
                mCodec.stop();

                // wait for track to finish playing
                int lastHeadPos;
                do {
                    lastHeadPos = currHeadPos;
                    try {
                        Thread.sleep(100);
                        currHeadPos = mTrack.getPlaybackHeadPosition();
                    } catch (InterruptedException e) { /* ignore */ }
                } while (currHeadPos != lastHeadPos);
                mTrack.stop();

                Log.d(TAG_TRACK, "Stopped codec and track");

                if (!((mInitiatingCount.get() > 0) || (mSeekingCount.get() > 0))) {
                    Log.d(TAG_TRACK, "Current position: " + getCurrentPosition());
                }
                mIsDecoding = false;
                if (mContinue && (sawInputEOS || sawOutputEOS)) {
                    state.changeTo(PLAYBACK_COMPLETED);
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
