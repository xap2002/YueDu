package com.yuedu.fm;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;


/**
 * Created by dong on 13-6-17.
 */
public class StreamingDownloadMediaPlayer {

    public enum PlayerState {
        IDLE,
        INITIALIZED,
        PREPARING,
        PREPARED,
        STARTED,
        STOPPED,
        PAUSED,
        COMPLETED,
        END;

        @Override
        public String toString() {
            return readableMap.get(this);
        }

        private static Map<PlayerState, String> readableMap;

        static {
            readableMap = new HashMap<PlayerState, String>(9);
            readableMap.put(IDLE, "IDLE");
            readableMap.put(INITIALIZED, "INITIALIZED");
            readableMap.put(PREPARING, "PREPARING");
            readableMap.put(PREPARED, "PREPARED");
            readableMap.put(STARTED, "STARTED");
            readableMap.put(STOPPED, "STOPPED");
            readableMap.put(PAUSED, "PAUSED");
            readableMap.put(COMPLETED, "COMPLETED");
            readableMap.put(END, "END");
        }
    }

    private URL mURL;

    private PlayerState mState;
    private boolean mLooping;
    private File mCacheDir;
    private AudioTrack mAudioTrack;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private StreamingAsyncTask mStreamingTask;
    private int mBufferSize;
    private OnPreparedListener mPreparedListener;

    private abstract class StreamingAsyncTask extends AsyncTask<URL, Void, Void> {
        boolean isPaused = false;
        boolean isStopped = false;
        boolean isPlaying = false;
        ReentrantLock pauseLock = new ReentrantLock();
        Condition unpaused = pauseLock.newCondition();

        public void pause() {
            pauseLock.lock();
            try {
                isPaused = true;
                isPlaying = false;
            } finally {
                pauseLock.unlock();
            }
        }

        public void start() {
            if (isCancelled()) {
                throw new IllegalStateException("play task has been stopped and cancelled");
            }
            resume();
        }

        public void stop() {
            if (isPaused) {
                resume();
            }
            isStopped = true;
            isPlaying = false;
            cancel(true);
        }

        public void resume() {
            pauseLock.lock();
            isPaused = false;
            isPlaying = true;
            try {
                unpaused.signalAll();
            } finally {
                pauseLock.unlock();
            }
        }
    }

    static public interface OnPreparedListener {
        abstract void onPrepared(StreamingDownloadMediaPlayer mediaPlayer);
    }

    public PlayerState getState() {
        return mState;
    }

    public boolean isLooping() {
        return mLooping;
    }

    public File getCacheDir() {
        return mCacheDir;
    }

    public void setOnPreparedListener(OnPreparedListener mPreparedListener) {
        this.mPreparedListener = mPreparedListener;
    }

    public void setCacheDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            throw new IllegalArgumentException("input dir is null or not a directory");
        }
        mCacheDir = dir;
    }

    public void setDataSource(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("input stream is null");
        }
        if (mState != PlayerState.IDLE) {
            throw new IllegalStateException("cannot setDataSource in [" + mState + "] state");
        }
        this.mURL = url;
        this.mState = PlayerState.INITIALIZED;
    }

    public void reset() {
        mURL = null;
        mState = PlayerState.IDLE;
        mLooping = false;
        if (mAudioTrack != null && isPlaying()) {
            mAudioTrack.pause();
            mAudioTrack.flush();
        }

        if (mAudioTrack == null) {
            mBufferSize = 2 * AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, mBufferSize, AudioTrack.MODE_STREAM);
        }
    }

    public void prepare() throws IOException, BitstreamException, DecoderException, InterruptedException {
        if (mState == PlayerState.INITIALIZED || mState == PlayerState.STOPPED || mState == PlayerState.PREPARING) {
            throw new UnsupportedOperationException("not supported");
        } else {
            throw new IllegalStateException("cannot prepare in [" + mState + "] state");
        }
    }

    final private static class FileStreamingPipe extends InputStream {

        private InputStream inputStream;
        private FileOutputStream outputStream;

        private FileStreamingPipe(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public int available() throws IOException {
            return inputStream.available();
        }

        @Override
        public void close() throws IOException {
            inputStream.close();
            if (outputStream != null) {
                outputStream.close();
            }
        }

        @Override
        public void mark(int readlimit) {
            inputStream.mark(readlimit);
        }

        @Override
        public boolean markSupported() {
            return inputStream.markSupported();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int result = inputStream.read(buffer,offset,length);
            if (outputStream != null) {
                outputStream.write(buffer,offset,length);
            }
            return result;
        }

        @Override
        public synchronized void reset() throws IOException {
            inputStream.reset();
        }

        @Override
        public long skip(long byteCount) throws IOException {
            return inputStream.skip(byteCount);
        }

        @Override
        public int read() throws IOException {
            return inputStream.read();
        }
    }

    protected void handleInput(final URL url, final Decoder decoder) throws IOException, BitstreamException, DecoderException, InterruptedException {
        boolean shouldCache = (getCacheDir() != null && getCacheDir().isDirectory());
        final FileOutputStream fileOutputStream;
        if (shouldCache) {
            fileOutputStream = new FileOutputStream(new File(getCacheDir(), SystemClock.elapsedRealtime() + ".mp3"));
        }else {
            fileOutputStream = null;
        }
        mStreamingTask = new StreamingAsyncTask() {
            @Override
            protected Void doInBackground(URL... params) {
                URL url1 = params[0];
                HttpURLConnection connection = null;
                FileStreamingPipe filePipe;
                Bitstream bitstream;
                try {
                    connection = (HttpURLConnection) url1.openConnection();
                    connection.connect();
                    filePipe = new FileStreamingPipe(connection.getInputStream());
                    filePipe.outputStream = fileOutputStream;
                    bitstream = new Bitstream(filePipe);
                    Header header;
                    boolean firstPrepared = false;
                    int totalBytes = 0;
                    int oneshootBytes = 0;
                    while ((header = bitstream.readFrame()) != null && !isStopped) {
                        if (isPaused) {
                            pauseLock.lock();
                            try {
                                unpaused.await();
                            }finally {
                                pauseLock.unlock();
                            }
                        }

                        if (totalBytes >= mBufferSize - 2 * oneshootBytes && !firstPrepared) {
                            firstPrepared = true;
                            notifyPrepared();
                        }

                        SampleBuffer decoderBuffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                        oneshootBytes = decoderBuffer.getBufferLength() * 2;
                        short[] copyBuffer = new short[decoderBuffer.getBufferLength()];
                        System.arraycopy(decoderBuffer.getBuffer(), 0, copyBuffer, 0, decoderBuffer.getBufferLength());
                        mAudioTrack.write(copyBuffer, 0, decoderBuffer.getBufferLength());
                        totalBytes += oneshootBytes;
                        bitstream.closeFrame();
                    }
                    if (filePipe != null) {
                        filePipe.close();
                    }
                    if (bitstream != null) {
                        bitstream.close();
                    }
                    if (filePipe != null) {
                        filePipe.close();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (DecoderException e) {
                    e.printStackTrace();
                } catch (BitstreamException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
                return null;
            }
        };
        mStreamingTask.execute(url);
        mState = PlayerState.PREPARING;
    }

    private void notifyPrepared() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mState = PlayerState.PREPARED;
                if (mPreparedListener != null) {
                    mPreparedListener.onPrepared(StreamingDownloadMediaPlayer.this);
                }
            }
        });
    }

    public void prepareAsync() throws DecoderException, InterruptedException, BitstreamException, IOException {
        if (mState == PlayerState.INITIALIZED || mState == PlayerState.STOPPED) {
            handleInput(mURL, new Decoder());
        } else {
            throw new IllegalStateException("cannot prepareAsync in [" + mState + "] state");
        }
    }

    public void start() {
        if (mState == PlayerState.PREPARED || mState == PlayerState.COMPLETED || mState == PlayerState.PAUSED) {
            mState = PlayerState.STARTED;
            mAudioTrack.play();
            mStreamingTask.start();
        } else {
            throw new IllegalStateException("cannot start in [" + mState + "] state");
        }
    }

    public void pause() {
        if (mState == PlayerState.STARTED) {
            mState = PlayerState.PAUSED;
            mAudioTrack.pause();
            mStreamingTask.pause();
        } else {
            throw new IllegalStateException("cannot pause in [" + mState + "] state");
        }
    }

    public void stop() {
        if (mState == PlayerState.PREPARED || mState == PlayerState.COMPLETED || mState == PlayerState.PAUSED || mState == PlayerState.STARTED) {
            mState = PlayerState.STOPPED;
            mStreamingTask.stop();
            mAudioTrack.pause();
            mAudioTrack.flush();
            mAudioTrack.stop();
        } else {
            throw new IllegalStateException("cannot stop in [" + mState + "] state");
        }
    }

    public void seekTo(long millisecond) {
        //TODO
    }

    public long getCurrentPosition() {
        return Math.round(mAudioTrack.getPlaybackHeadPosition()/mAudioTrack.getSampleRate())*1000;
    }

    public long getDuration() {
        //TODO
        return 0;
    }

    public boolean isPlaying() {
        return mState == PlayerState.STARTED;
    }

    public boolean isPaused() {
        return mState == PlayerState.PAUSED;
    }

    public boolean isCompleted() {
        return mState == PlayerState.COMPLETED;
    }

    public void release() {
        if (mAudioTrack != null) {
            mAudioTrack.pause();
            mAudioTrack.flush();
            mAudioTrack.release();
            mAudioTrack = null;
        }
        mCacheDir = null;
        mHandler = null;
        mURL = null;
        mPreparedListener = null;
        if (mStreamingTask != null) {
            if (!mStreamingTask.isCancelled()) {
                mStreamingTask.cancel(true);
            }
            mStreamingTask.notifyAll();
            mStreamingTask = null;
        }
    }

}
