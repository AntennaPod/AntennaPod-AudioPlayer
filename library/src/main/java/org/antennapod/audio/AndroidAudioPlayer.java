// Copyright 2011, Aocate, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.antennapod.audio;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.IOException;

public class AndroidAudioPlayer extends AbstractAudioPlayer {

    private final static String AMP_TAG = "AndroidMediaPlayer";

    android.media.MediaPlayer mp = null;

    private android.media.MediaPlayer.OnBufferingUpdateListener onBufferingUpdateListener = new android.media.MediaPlayer.OnBufferingUpdateListener() {
        public void onBufferingUpdate(android.media.MediaPlayer mp, int percent) {
            if (owningMediaPlayer != null) {
                owningMediaPlayer.lock.lock();
                try {
                    if ((owningMediaPlayer.onBufferingUpdateListener != null)
                            && (owningMediaPlayer.mpi == AndroidAudioPlayer.this)) {
                        owningMediaPlayer.onBufferingUpdateListener.onBufferingUpdate(owningMediaPlayer, percent);
                    }
                }
                finally {
                    owningMediaPlayer.lock.unlock();
                }
            }

        }
    };

    private android.media.MediaPlayer.OnCompletionListener onCompletionListener = new android.media.MediaPlayer.OnCompletionListener() {
        public void onCompletion(android.media.MediaPlayer mp) {
            Log.d(AMP_TAG, "onCompletionListener being called");
            if (owningMediaPlayer != null) {
                owningMediaPlayer.lock.lock();
                try {
                    if (owningMediaPlayer.onCompletionListener != null) {
                        owningMediaPlayer.onCompletionListener.onCompletion(owningMediaPlayer);
                    }
                }
                finally {
                    owningMediaPlayer.lock.unlock();
                }
            }
        }
    };

    private android.media.MediaPlayer.OnErrorListener onErrorListener = new android.media.MediaPlayer.OnErrorListener() {
        public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
            // Once we're in errored state, any received messages are going to be junked
            if (owningMediaPlayer != null) {
                owningMediaPlayer.lock.lock();
                try {
                    if (owningMediaPlayer.onErrorListener != null) {
                        return owningMediaPlayer.onErrorListener.onError(owningMediaPlayer, what, extra);
                    }
                }
                finally {
                    owningMediaPlayer.lock.unlock();
                }
            }
            return false;
        }
    };

    private android.media.MediaPlayer.OnInfoListener onInfoListener = new android.media.MediaPlayer.OnInfoListener() {
        public boolean onInfo(android.media.MediaPlayer mp, int what, int extra) {
            if (owningMediaPlayer != null) {
                owningMediaPlayer.lock.lock();
                try {
                    if ((owningMediaPlayer.onInfoListener != null)
                            && (owningMediaPlayer.mpi == AndroidAudioPlayer.this)) {
                        return owningMediaPlayer.onInfoListener.onInfo(owningMediaPlayer, what, extra);
                    }
                }
                finally {
                    owningMediaPlayer.lock.unlock();
                }
            }
            return false;
        }
    };

    // We have to assign this.onPreparedListener because the
    // onPreparedListener in owningMediaPlayer sets the state
    // to PREPARED.  Due to prepareAsync, that's the only
    // reasonable place to do it
    // The others it just didn't make sense to have a setOnXListener that didn't use the parameter
    private android.media.MediaPlayer.OnPreparedListener onPreparedListener = new android.media.MediaPlayer.OnPreparedListener() {
        public void onPrepared(android.media.MediaPlayer mp) {
            Log.d(AMP_TAG, "Calling onPreparedListener.onPrepared()");
            if (AndroidAudioPlayer.this.owningMediaPlayer != null) {
                AndroidAudioPlayer.this.lockMuteOnPreparedCount.lock();
                try {
                    if (AndroidAudioPlayer.this.muteOnPreparedCount > 0) {
                        AndroidAudioPlayer.this.muteOnPreparedCount--;
                    }
                    else {
                        AndroidAudioPlayer.this.muteOnPreparedCount = 0;
                        if (AndroidAudioPlayer.this.owningMediaPlayer.onPreparedListener != null) {
                            Log.d(AMP_TAG, "Invoking AndroidMediaPlayer.this.owningMediaPlayer.onPreparedListener.onPrepared");
                            AndroidAudioPlayer.this.owningMediaPlayer.onPreparedListener.onPrepared(AndroidAudioPlayer.this.owningMediaPlayer);
                        }
                    }
                }
                finally {
                    AndroidAudioPlayer.this.lockMuteOnPreparedCount.unlock();
                }
                if (owningMediaPlayer.mpi != AndroidAudioPlayer.this) {
                    Log.d(AMP_TAG, "owningMediaPlayer has changed implementation");
                }
            }
        }
    };

    private android.media.MediaPlayer.OnSeekCompleteListener onSeekCompleteListener = new android.media.MediaPlayer.OnSeekCompleteListener() {
        public void onSeekComplete(android.media.MediaPlayer mp) {
            if (owningMediaPlayer != null) {
                owningMediaPlayer.lock.lock();
                try {
                    lockMuteOnSeekCount.lock();
                    try {
                        if (AndroidAudioPlayer.this.muteOnSeekCount > 0) {
                            AndroidAudioPlayer.this.muteOnSeekCount--;
                        }
                        else {
                            AndroidAudioPlayer.this.muteOnSeekCount = 0;
                            if (AndroidAudioPlayer.this.owningMediaPlayer.onSeekCompleteListener != null) {
                                owningMediaPlayer.onSeekCompleteListener.onSeekComplete(owningMediaPlayer);
                            }
                        }
                    }
                    finally {
                        lockMuteOnSeekCount.unlock();
                    }
                }
                finally {
                    owningMediaPlayer.lock.unlock();
                }
            }
        }
    };

    public AndroidAudioPlayer(org.antennapod.audio.MediaPlayer owningMediaPlayer, Context context) {
        super(owningMediaPlayer, context);

        mp = new MediaPlayer();

//		final ReentrantLock lock = new ReentrantLock();
//		Handler handler = new Handler(Looper.getMainLooper()) {
//            @Override
//            public void handleMessage(Message msg) {
//            	Log.d(AMP_TAG, "Instantiating new AndroidMediaPlayer from Handler");
//            	lock.lock();
//            	if (mp == null) {
//            		mp = new MediaPlayer();
//            	}
//            	lock.unlock();
//            }
//        };
//		
//        long endTime = System.currentTimeMillis() + TIMEOUT_DURATION_MS;
//        
//        while (true) {
//        	// Retry messages until mp isn't null or it's time to give up
//        	handler.sendMessage(handler.obtainMessage());
//        	if ((mp != null)
//        		|| (endTime < System.currentTimeMillis())) {
//        		break;
//        	}
//        	try {
//				Thread.sleep(50);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//        }

        if (mp == null) {
            throw new IllegalStateException("Did not instantiate android.media.MediaPlayer successfully");
        }

        mp.setOnBufferingUpdateListener(this.onBufferingUpdateListener);
        mp.setOnCompletionListener(this.onCompletionListener);
        mp.setOnErrorListener(this.onErrorListener);
        mp.setOnInfoListener(this.onInfoListener);
        Log.d(AMP_TAG, " ++++++++++++++++++++++++++++++++ Setting prepared listener to this.onPreparedListener");
        mp.setOnPreparedListener(this.onPreparedListener);
        mp.setOnSeekCompleteListener(this.onSeekCompleteListener);
    }

    @Override
    public int getAudioSessionId() {
        return mp.getAudioSessionId();
    }

    @Override
    public boolean canSetPitch() {
        if(Build.VERSION.SDK_INT >= 23) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean canSetSpeed() {
        if(Build.VERSION.SDK_INT >= 23) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public float getCurrentPitchStepsAdjustment() {
        return 0;
    }

    @Override
    public int getCurrentPosition() {
        owningMediaPlayer.lock.lock();
        try {
            return mp.getCurrentPosition();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public float getCurrentSpeedMultiplier() {
        return 1f;
    }

    @Override
    public int getDuration() {
        owningMediaPlayer.lock.lock();
        try {
            return mp.getDuration();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public float getMaxSpeedMultiplier() {
        return 1f;
    }

    @Override
    public float getMinSpeedMultiplier() {
        return 1f;
    }

    @Override
    public boolean isLooping() {
        owningMediaPlayer.lock.lock();
        try {
            return mp.isLooping();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public boolean isPlaying() {
        owningMediaPlayer.lock.lock();
        try {
            return mp.isPlaying();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void pause() {
        owningMediaPlayer.lock.lock();
        try {
            mp.pause();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void prepare() throws IllegalStateException, IOException {
        owningMediaPlayer.lock.lock();
        Log.d(AMP_TAG, "prepare()");
        try {
            mp.prepare();
            Log.d(AMP_TAG, "Finish prepare()");
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void prepareAsync() {
        mp.prepareAsync();
    }

    @Override
    public void release() {
        owningMediaPlayer.lock.lock();
        try {
            if (mp != null) {
                Log.d(AMP_TAG, "mp.release()");
                mp.release();
            }
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void reset() {
        owningMediaPlayer.lock.lock();
        try {
            mp.reset();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void seekTo(int msec) throws IllegalStateException {
        owningMediaPlayer.lock.lock();
        try {
            mp.setOnSeekCompleteListener(this.onSeekCompleteListener);
            mp.seekTo(msec);
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void setAudioStreamType(int streamtype) {
        owningMediaPlayer.lock.lock();
        try {
            mp.setAudioStreamType(streamtype);
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void setDataSource(Context context, Uri uri)
            throws IllegalArgumentException, IllegalStateException, IOException {
        owningMediaPlayer.lock.lock();
        try {
            Log.d(AMP_TAG, "setDataSource(context, " + uri.toString() + ")");
            mp.setDataSource(context, uri);
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void setDataSource(String path) throws IllegalArgumentException,
            IllegalStateException, IOException {
        owningMediaPlayer.lock.lock();
        try {
            Log.d(AMP_TAG, "setDataSource(" + path + ")");
            mp.setDataSource(path);
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void setEnableSpeedAdjustment(boolean enableSpeedAdjustment) {
        // Can't!
    }

    @Override
    public void setLooping(boolean loop) {
        owningMediaPlayer.lock.lock();
        try {
            mp.setLooping(loop);
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void setPitchStepsAdjustment(float pitchSteps) {
        if(Build.VERSION.SDK_INT < 23) {
            return;
        }
        PlaybackParams params = mp.getPlaybackParams();
        params.setPitch(params.getPitch() + pitchSteps);
        mp.setPlaybackParams(params);
    }

    @Override
    public void setPlaybackPitch(float f) {
        Log.d(AMP_TAG, "setPlaybackPitch(" + f + ")");
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        PlaybackParams params = mp.getPlaybackParams();
        params.setPitch(f);
        mp.setPlaybackParams(params);
    }

    @Override
    public void setPlaybackSpeed(float f) {
        Log.d(AMP_TAG, "setPlaybackSpeed(" + f + ")");
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        PlaybackParams params = mp.getPlaybackParams();
        params.setSpeed(f);
        mp.setPlaybackParams(params);
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        owningMediaPlayer.lock.lock();
        try {
            mp.setVolume(leftVolume, rightVolume);
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void setWakeMode(Context context, int mode) {
        owningMediaPlayer.lock.lock();
        try {
            if (mode != 0) {
                mp.setWakeMode(context, mode);
            }
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void start() {
        owningMediaPlayer.lock.lock();
        try {
            mp.start();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }

    @Override
    public void stop() {
        owningMediaPlayer.lock.lock();
        try {
            mp.stop();
        }
        finally {
            owningMediaPlayer.lock.unlock();
        }
    }
}
