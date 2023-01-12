package com.onscripter;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.onscripter.exception.NativeONSException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * This class is a wrapper to render ONScripter games inside a single view object
 * without any extra code. All you need to do is create the object by the
 * constructor and add it to your layout. Then you can set a ONScripterEventListener
 * if you want to. Finally it is your job to set the size of this view.
 *
 * You must also pass the following events from your activity for this ONScripterView
 * to act normally: <b>onPause and onResume</b> and should not call exitApp() when <i>onDestroy</i>
 * occurs because it will freeze, please exit the app before onStop() or finish() when
 * onGameFinished() occurs.
 * @author Matthew Ng
 *
 */
public class ONScripterView extends DemoGLSurfaceView {
    private static final String TAG = "ONScripterView";

    private static final int MSG_AUTO_MODE = 1;
    private static final int MSG_SKIP_MODE = 2;
    private static final int MSG_SINGLE_PAGE_MODE = 3;
    private static final int MSG_ERROR_MESSAGE = 4;

    public interface ONScripterEventListener {
        void autoStateChanged(boolean selected);
        void skipStateChanged(boolean selected);
        void singlePageStateChanged(boolean selected);
        void videoRequested(@NonNull String videoPath, boolean clickToSkip, boolean shouldLoop);
        void onNativeError(NativeONSException e, String line, String backtrace);
        void onReady();
        void onUserMessage(UserMessage messageId);
        void onGameFinished();
    }

    private static class UpdateHandler extends Handler {
        private final WeakReference<ONScripterView> mThisView;
        UpdateHandler(ONScripterView activity) {
            mThisView = new WeakReference<>(activity);
        }
        @Override
        public void handleMessage(Message msg)
        {
            ONScripterView view = mThisView.get();
            if (view != null) {
                if (msg.what < MSG_ERROR_MESSAGE) {
                    view.updateControls(msg.what, (Boolean)msg.obj);
                } else {
                    view.sendUserMessage(msg.what);
                }
            }
        }
    }

    /* Called from ONScripter.h */
    @Keep
    private static void receiveMessageFromNDK(int mode, boolean flag) {
        if (sHandler != null) {
            Message msg = new Message();
            msg.obj = flag;
            msg.what = mode;
            sHandler.sendMessage(msg);
        }
    }

    public enum UserMessage {
        CORRUPT_SAVE_FILE
    };

    private final AudioThread mAudioThread;
    private final Handler mMainHandler;

    // Native methods
    private native void nativeSetSentenceFontScale(double scale);
    private native void nativeLoadSaveFile(int number);
    private native int nativeGetDialogFontSize();

    /**
     * Constructor with parameters
     * @param builder used for view constructor
     */
    public ONScripterView(@NonNull Builder builder) {
        super(builder);

        mAudioThread = new AudioThread();
        mMainHandler = new Handler(Looper.getMainLooper());
        sHandler = new UpdateHandler(this);

        setFocusableInTouchMode(true);
        setFocusable(true);
        requestFocus();
    }

    /** Receive State Updates from Native Code */
    private static UpdateHandler sHandler;

    private ONScripterEventListener mListener;
    private boolean mGameReady;
    boolean mIsVideoPlaying = false;
    boolean mHasExit = false;

    @Override
    public void exitApp() {
        mHasExit = true;
        super.exitApp();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!mHasExit) {
            mAudioThread.onPause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mAudioThread.onResume();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Prevent touches to occur until the game is ready
        if (!mGameReady) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Set the listener for game events
     * @param listener listener object
     */
    public void setONScripterEventListener(ONScripterEventListener listener) {
        mListener = listener;
    }

    /**
     * Send native key press to the app
     * @param keyCode the key to simulate into the game
     */
    public void keyDown(int keyCode) {
        triggerKeyEvent(keyCode, 1);
    }

    public void keyUp(int keyCode){
        triggerKeyEvent(keyCode, 0);
    }

    /**
     * Get the font size of the text currently showing
     * @return font size (pixels)
     */
    public int getGameFontSize() {
        return !mHasExit ? nativeGetDialogFontSize() : 0;
    }

    /**
     * Get the render width of the game. This value is not the size of this view and is set in the
     * script
     * @return width of the game
     */
    public int getGameWidth() {
        return mRenderer.nativeGetWidth();
    }

    /**
     * Get the render height of the game. This value is not the size of this view and is set in the
     * script
     * @return height of the game
     */
    public int getGameHeight() {
        return mRenderer.nativeGetHeight();
    }

    /**
     * Set the font scaling where 1.0 is default 100% size
     * @param scaleFactor scale factor
     */
    public void setFontScaling(double scaleFactor) {
        if (!mHasExit) {
            nativeSetSentenceFontScale(scaleFactor);
        }
    }

    public void loadSaveFile(int number) {
        if (!mHasExit) {
            nativeLoadSaveFile(number);
        }
    }

    /* Called from ONScripter.h */
    @Keep
    protected void playVideo(String filepath, boolean clickToSkip, boolean shouldLoop){
        if (!mHasExit && mListener != null) {
            File videoFile = new File(filepath);
            if (!videoFile.exists()) {
                Log.e(TAG, "Cannot play video because it either does not exist. File: " + filepath);
                return;
            }
            mIsVideoPlaying = true;
            mListener.videoRequested(filepath, clickToSkip, shouldLoop);
            mIsVideoPlaying = false;
        }
    }

    /* Called from ONScripter.h */
    @Keep
    protected void receiveException(final String message, final String currentLineBuffer,
                                    final String backtrace) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentLineBuffer != null) {
                    Log.e(TAG, message + "\nCurrent line: " + currentLineBuffer + "\n" + backtrace);
                } else {
                    Log.e(TAG, message + "\n" + backtrace);
                }
                if (mListener != null) {
                    NativeONSException exception = new NativeONSException(message);
                    mListener.onNativeError(exception, currentLineBuffer, backtrace);
                }
            }
        });
    }

    /* Called from ONScripter.h */
    @Keep
    protected void receiveReady() {
        mGameReady = true;
        post(new Runnable() {
            @Override
            public void run() {
                if (mListener != null) {
                    mListener.onReady();
                }
            }
        });
    }

    /* Called from ONScripter.h */
    @Keep
    protected void onLoadFile(String filename, String savePath) {
    }

    /* Called from ONScripter.h */
    @Keep
    @Override
    protected void onFinish() {
        super.onFinish();
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener != null) {
                    mListener.onGameFinished();
                }
            }
        });
    }

    private void updateControls(int mode, boolean flag) {
        if (mListener != null) {
            switch(mode) {
                case MSG_AUTO_MODE:
                    mListener.autoStateChanged(flag);
                    break;
                case MSG_SKIP_MODE:
                    mListener.skipStateChanged(flag);
                    break;
                case MSG_SINGLE_PAGE_MODE:
                    mListener.singlePageStateChanged(flag);
                    break;
            }
        }
    }

    private void sendUserMessage(int messageIdFromNDK) {
        if (mListener != null) {
            switch(messageIdFromNDK) {
                case MSG_ERROR_MESSAGE:
                    mListener.onUserMessage(UserMessage.CORRUPT_SAVE_FILE);
                    break;
            }
        }
    }

    public static class Builder {
        @NonNull
        final Context context;
        @NonNull
        final String gameFolder;
        @NonNull
        final String saveFolder;
        @NonNull
        String fontPath;
        @Nullable
        String screenshotPath;
        boolean useHQAudio;
        boolean renderOutline;
        boolean readParentAssets;

        public Builder(@NonNull Context context, @NonNull String gameFolder, @NonNull String saveFolder, @NonNull String fontPath) {
            this.context = context;
            this.gameFolder = gameFolder;
            this.saveFolder = saveFolder;
            this.fontPath = fontPath;
        }

        public Builder setFontPath(@NonNull String fontPath) {
            this.fontPath = fontPath;
            return this;
        }

        public Builder setScreenshotPath(@NonNull String screenshotPath) {
            this.screenshotPath = screenshotPath;
            return this;
        }

        public Builder useHQAudio() {
            useHQAudio = true;
            return this;
        }

        public Builder useRenderOutline() {
            renderOutline = true;
            return this;
        }

        public Builder readParentAssets() {
            readParentAssets = true;
            return this;
        }

        public ONScripterView create() {
            return new ONScripterView(this);
        }
    }

    // Load the libraries
    static {
        System.loadLibrary("sdl");
        System.loadLibrary("onscripter");
    }
}
