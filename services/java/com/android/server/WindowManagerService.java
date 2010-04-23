/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.os.LocalPowerManager.CHEEK_EVENT;
import static android.os.LocalPowerManager.OTHER_EVENT;
import static android.os.LocalPowerManager.TOUCH_EVENT;
import static android.os.LocalPowerManager.LONG_TOUCH_EVENT;
import static android.os.LocalPowerManager.TOUCH_UP_EVENT;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.MEMORY_TYPE_GPU;
import static android.view.WindowManager.LayoutParams.MEMORY_TYPE_HARDWARE;
import static android.view.WindowManager.LayoutParams.MEMORY_TYPE_PUSH_BUFFERS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;

import com.android.internal.app.IBatteryStats;
import com.android.internal.policy.PolicyManager;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.server.KeyInputQueue.QueuedEvent;
import com.android.server.am.BatteryStatsService;

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Power;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.TokenWatcher;

import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RawInputEvent;
import android.view.Surface;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;
import android.view.WindowManager.LayoutParams;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/** {@hide} */
public class WindowManagerService extends IWindowManager.Stub implements Watchdog.Monitor {
    static final String TAG = "WindowManager";
    static final boolean DEBUG = false;
    static final boolean DEBUG_FOCUS = false;
    static final boolean DEBUG_ANIM = false;
    static final boolean DEBUG_LAYERS = false;
    static final boolean DEBUG_INPUT = false;
    static final boolean DEBUG_INPUT_METHOD = false;
    static final boolean DEBUG_VISIBILITY = false;
    static final boolean DEBUG_ORIENTATION = false;
    static final boolean DEBUG_APP_TRANSITIONS = false;
    static final boolean DEBUG_STARTING_WINDOW = false;
    static final boolean DEBUG_REORDER = false;
    static final boolean SHOW_TRANSACTIONS = false;

    static final boolean PROFILE_ORIENTATION = false;
    static final boolean BLUR = true;
    static final boolean localLOGV = DEBUG;

    static final int LOG_WM_NO_SURFACE_MEMORY = 31000;

    /** How long to wait for first key repeat, in milliseconds */
    static final int KEY_REPEAT_FIRST_DELAY = 750;

    /** How long to wait for subsequent key repeats, in milliseconds */
    static final int KEY_REPEAT_DELAY = 50;

    /** How much to multiply the policy's type layer, to reserve room
     * for multiple windows of the same type and Z-ordering adjustment
     * with TYPE_LAYER_OFFSET. */
    static final int TYPE_LAYER_MULTIPLIER = 10000;

    /** Offset from TYPE_LAYER_MULTIPLIER for moving a group of windows above
     * or below others in the same layer. */
    static final int TYPE_LAYER_OFFSET = 1000;

    /** How much to increment the layer for each window, to reserve room
     * for effect surfaces between them.
     */
    static final int WINDOW_LAYER_MULTIPLIER = 5;

    /** The maximum length we will accept for a loaded animation duration:
     * this is 10 seconds.
     */
    static final int MAX_ANIMATION_DURATION = 10*1000;

    /** Amount of time (in milliseconds) to animate the dim surface from one
     * value to another, when no window animation is driving it.
     */
    static final int DEFAULT_DIM_DURATION = 200;

    /** Amount of time (in milliseconds) to animate the fade-in-out transition for
     * compatible windows.
     */
    static final int DEFAULT_FADE_IN_OUT_DURATION = 400;

    /** Adjustment to time to perform a dim, to make it more dramatic.
     */
    static final int DIM_DURATION_MULTIPLIER = 6;

    static final int INJECT_FAILED = 0;
    static final int INJECT_SUCCEEDED = 1;
    static final int INJECT_NO_PERMISSION = -1;
    
    static final int UPDATE_FOCUS_NORMAL = 0;
    static final int UPDATE_FOCUS_WILL_ASSIGN_LAYERS = 1;
    static final int UPDATE_FOCUS_PLACING_SURFACES = 2;
    static final int UPDATE_FOCUS_WILL_PLACE_SURFACES = 3;

    /** The minimum time between dispatching touch events. */
    int mMinWaitTimeBetweenTouchEvents = 1000 / 35;

    // Last touch event time
    long mLastTouchEventTime = 0;

    // Last touch event type
    int mLastTouchEventType = OTHER_EVENT;

    // Time to wait before calling useractivity again. This saves CPU usage
    // when we get a flood of touch events.
    static final int MIN_TIME_BETWEEN_USERACTIVITIES = 1000;

    // Last time we call user activity
    long mLastUserActivityCallTime = 0;

    // Last time we updated battery stats
    long mLastBatteryStatsCallTime = 0;

    private static final String SYSTEM_SECURE = "ro.secure";
    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    /**
     * Condition waited on by {@link #reenableKeyguard} to know the call to
     * the window policy has finished.
     */
    private boolean mWaitingUntilKeyguardReenabled = false;


    final TokenWatcher mKeyguardDisabled = new TokenWatcher(
            new Handler(), "WindowManagerService.mKeyguardDisabled") {
        public void acquired() {
            mPolicy.enableKeyguard(false);
        }
        public void released() {
            synchronized (mKeyguardDisabled) {
                mPolicy.enableKeyguard(true);
                mWaitingUntilKeyguardReenabled = false;
                mKeyguardDisabled.notifyAll();
            }
        }
    };

    final Context mContext;

    final boolean mHaveInputMethods;

    final boolean mLimitedAlphaCompositing;

    final WindowManagerPolicy mPolicy = PolicyManager.makeNewWindowManager();

    final IActivityManager mActivityManager;

    final IBatteryStats mBatteryStats;

    /**
     * All currently active sessions with clients.
     */
    final HashSet<Session> mSessions = new HashSet<Session>();

    /**
     * Mapping from an IWindow IBinder to the server's Window object.
     * This is also used as the lock for all of our state.
     */
    final HashMap<IBinder, WindowState> mWindowMap = new HashMap<IBinder, WindowState>();

    /**
     * Mapping from a token IBinder to a WindowToken object.
     */
    final HashMap<IBinder, WindowToken> mTokenMap =
            new HashMap<IBinder, WindowToken>();

    /**
     * The same tokens as mTokenMap, stored in a list for efficient iteration
     * over them.
     */
    final ArrayList<WindowToken> mTokenList = new ArrayList<WindowToken>();

    /**
     * Window tokens that are in the process of exiting, but still
     * on screen for animations.
     */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<WindowToken>();

    /**
     * Z-ordered (bottom-most first) list of all application tokens, for
     * controlling the ordering of windows in different applications.  This
     * contains WindowToken objects.
     */
    final ArrayList<AppWindowToken> mAppTokens = new ArrayList<AppWindowToken>();

    /**
     * Application tokens that are in the process of exiting, but still
     * on screen for animations.
     */
    final ArrayList<AppWindowToken> mExitingAppTokens = new ArrayList<AppWindowToken>();

    /**
     * List of window tokens that have finished starting their application,
     * and now need to have the policy remove their windows.
     */
    final ArrayList<AppWindowToken> mFinishedStarting = new ArrayList<AppWindowToken>();

    /**
     * Z-ordered (bottom-most first) list of all Window objects.
     */
    final ArrayList mWindows = new ArrayList();

    /**
     * Windows that are being resized.  Used so we can tell the client about
     * the resize after closing the transaction in which we resized the
     * underlying surface.
     */
    final ArrayList<WindowState> mResizingWindows = new ArrayList<WindowState>();

    /**
     * Windows whose animations have ended and now must be removed.
     */
    final ArrayList<WindowState> mPendingRemove = new ArrayList<WindowState>();

    /**
     * Windows whose surface should be destroyed.
     */
    final ArrayList<WindowState> mDestroySurface = new ArrayList<WindowState>();

    /**
     * Windows that have lost input focus and are waiting for the new
     * focus window to be displayed before they are told about this.
     */
    ArrayList<WindowState> mLosingFocus = new ArrayList<WindowState>();

    /**
     * This is set when we have run out of memory, and will either be an empty
     * list or contain windows that need to be force removed.
     */
    ArrayList<WindowState> mForceRemoves;

    IInputMethodManager mInputMethodManager;

    SurfaceSession mFxSession;
    private DimAnimator mDimAnimator = null;
    Surface mBlurSurface;
    boolean mBlurShown;

    int mTransactionSequence = 0;

    final float[] mTmpFloats = new float[9];

    boolean mSafeMode;
    boolean mDisplayEnabled = false;
    boolean mSystemBooted = false;
    int mRotation = 0;
    int mRequestedRotation = 0;
    int mForcedAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    int mLastRotationFlags;
    ArrayList<IRotationWatcher> mRotationWatchers
            = new ArrayList<IRotationWatcher>();

    boolean mLayoutNeeded = true;
    boolean mAnimationPending = false;
    boolean mDisplayFrozen = false;
    boolean mWindowsFreezingScreen = false;
    long mFreezeGcPending = 0;
    int mAppsFreezingScreen = 0;

    // This is held as long as we have the screen frozen, to give us time to
    // perform a rotation animation when turning off shows the lock screen which
    // changes the orientation.
    PowerManager.WakeLock mScreenFrozenLock;

    // State management of app transitions.  When we are preparing for a
    // transition, mNextAppTransition will be the kind of transition to
    // perform or TRANSIT_NONE if we are not waiting.  If we are waiting,
    // mOpeningApps and mClosingApps are the lists of tokens that will be
    // made visible or hidden at the next transition.
    int mNextAppTransition = WindowManagerPolicy.TRANSIT_NONE;
    boolean mAppTransitionReady = false;
    boolean mAppTransitionTimeout = false;
    boolean mStartingIconInTransition = false;
    boolean mSkipAppTransitionAnimation = false;
    final ArrayList<AppWindowToken> mOpeningApps = new ArrayList<AppWindowToken>();
    final ArrayList<AppWindowToken> mClosingApps = new ArrayList<AppWindowToken>();

    //flag to detect fat touch events
    boolean mFatTouch = false;
    Display mDisplay;

    H mH = new H();

    WindowState mCurrentFocus = null;
    WindowState mLastFocus = null;

    // This just indicates the window the input method is on top of, not
    // necessarily the window its input is going to.
    WindowState mInputMethodTarget = null;
    WindowState mUpcomingInputMethodTarget = null;
    boolean mInputMethodTargetWaitingAnim;
    int mInputMethodAnimLayerAdjustment;

    WindowState mInputMethodWindow = null;
    final ArrayList<WindowState> mInputMethodDialogs = new ArrayList<WindowState>();

    AppWindowToken mFocusedApp = null;

    PowerManagerService mPowerManager;

    float mWindowAnimationScale = 1.0f;
    float mTransitionAnimationScale = 1.0f;

    final KeyWaiter mKeyWaiter = new KeyWaiter();
    final KeyQ mQueue;
    final InputDispatcherThread mInputThread;

    // Who is holding the screen on.
    Session mHoldingScreenOn;

    /**
     * Whether the UI is currently running in touch mode (not showing
     * navigational focus because the user is directly pressing the screen).
     */
    boolean mInTouchMode = false;

    private ViewServer mViewServer;

    final Rect mTempRect = new Rect();

    final Configuration mTempConfiguration = new Configuration();
    int mScreenLayout = Configuration.SCREENLAYOUT_SIZE_UNDEFINED;

    // The frame use to limit the size of the app running in compatibility mode.
    Rect mCompatibleScreenFrame = new Rect();
    // The surface used to fill the outer rim of the app running in compatibility mode.
    Surface mBackgroundFillerSurface = null;
    boolean mBackgroundFillerShown = false;

    public static WindowManagerService main(Context context,
            PowerManagerService pm, boolean haveInputMethods) {
        WMThread thr = new WMThread(context, pm, haveInputMethods);
        thr.start();

        synchronized (thr) {
            while (thr.mService == null) {
                try {
                    thr.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        return thr.mService;
    }

    static class WMThread extends Thread {
        WindowManagerService mService;

        private final Context mContext;
        private final PowerManagerService mPM;
        private final boolean mHaveInputMethods;

        public WMThread(Context context, PowerManagerService pm,
                boolean haveInputMethods) {
            super("WindowManager");
            mContext = context;
            mPM = pm;
            mHaveInputMethods = haveInputMethods;
        }

        public void run() {
            Looper.prepare();
            WindowManagerService s = new WindowManagerService(mContext, mPM,
                    mHaveInputMethods);
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_DISPLAY);

            synchronized (this) {
                mService = s;
                notifyAll();
            }

            Looper.loop();
        }
    }

    static class PolicyThread extends Thread {
        private final WindowManagerPolicy mPolicy;
        private final WindowManagerService mService;
        private final Context mContext;
        private final PowerManagerService mPM;
        boolean mRunning = false;

        public PolicyThread(WindowManagerPolicy policy,
                WindowManagerService service, Context context,
                PowerManagerService pm) {
            super("WindowManagerPolicy");
            mPolicy = policy;
            mService = service;
            mContext = context;
            mPM = pm;
        }

        public void run() {
            Looper.prepare();
            //Looper.myLooper().setMessageLogging(new LogPrinter(
            //        Log.VERBOSE, "WindowManagerPolicy"));
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_FOREGROUND);
            mPolicy.init(mContext, mService, mPM);

            synchronized (this) {
                mRunning = true;
                notifyAll();
            }

            Looper.loop();
        }
    }

    private WindowManagerService(Context context, PowerManagerService pm,
            boolean haveInputMethods) {
        mContext = context;
        mHaveInputMethods = haveInputMethods;
        mLimitedAlphaCompositing = context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_limitedAlpha);

        mPowerManager = pm;
        mPowerManager.setPolicy(mPolicy);
        PowerManager pmc = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mScreenFrozenLock = pmc.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "SCREEN_FROZEN");
        mScreenFrozenLock.setReferenceCounted(false);

        mActivityManager = ActivityManagerNative.getDefault();
        mBatteryStats = BatteryStatsService.getService();

        // Get persisted window scale setting
        mWindowAnimationScale = Settings.System.getFloat(context.getContentResolver(),
                Settings.System.WINDOW_ANIMATION_SCALE, mWindowAnimationScale);
        mTransitionAnimationScale = Settings.System.getFloat(context.getContentResolver(),
                Settings.System.TRANSITION_ANIMATION_SCALE, mTransitionAnimationScale);

        int max_events_per_sec = 35;
        try {
            max_events_per_sec = Integer.parseInt(SystemProperties
                    .get("windowsmgr.max_events_per_sec"));
            if (max_events_per_sec < 1) {
                max_events_per_sec = 35;
            }
        } catch (NumberFormatException e) {
        }
        mMinWaitTimeBetweenTouchEvents = 1000 / max_events_per_sec;

        mQueue = new KeyQ();

        mInputThread = new InputDispatcherThread();

        PolicyThread thr = new PolicyThread(mPolicy, this, context, pm);
        thr.start();

        synchronized (thr) {
            while (!thr.mRunning) {
                try {
                    thr.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        mInputThread.start();

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The window manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Log.e(TAG, "Window Manager Crash", e);
            }
            throw e;
        }
    }

    private void placeWindowAfter(Object pos, WindowState window) {
        final int i = mWindows.indexOf(pos);
        if (localLOGV || DEBUG_FOCUS) Log.v(
            TAG, "Adding window " + window + " at "
            + (i+1) + " of " + mWindows.size() + " (after " + pos + ")");
        mWindows.add(i+1, window);
    }

    private void placeWindowBefore(Object pos, WindowState window) {
        final int i = mWindows.indexOf(pos);
        if (localLOGV || DEBUG_FOCUS) Log.v(
            TAG, "Adding window " + window + " at "
            + i + " of " + mWindows.size() + " (before " + pos + ")");
        mWindows.add(i, window);
    }

    //This method finds out the index of a window that has the same app token as
    //win. used for z ordering the windows in mWindows
    private int findIdxBasedOnAppTokens(WindowState win) {
        //use a local variable to cache mWindows
        ArrayList localmWindows = mWindows;
        int jmax = localmWindows.size();
        if(jmax == 0) {
            return -1;
        }
        for(int j = (jmax-1); j >= 0; j--) {
            WindowState wentry = (WindowState)localmWindows.get(j);
            if(wentry.mAppToken == win.mAppToken) {
                return j;
            }
        }
        return -1;
    }

    private void addWindowToListInOrderLocked(WindowState win, boolean addToToken) {
        final IWindow client = win.mClient;
        final WindowToken token = win.mToken;
        final ArrayList localmWindows = mWindows;

        final int N = localmWindows.size();
        final WindowState attached = win.mAttachedWindow;
        int i;
        if (attached == null) {
            int tokenWindowsPos = token.windows.size();
            if (token.appWindowToken != null) {
                int index = tokenWindowsPos-1;
                if (index >= 0) {
                    // If this application has existing windows, we
                    // simply place the new window on top of them... but
                    // keep the starting window on top.
                    if (win.mAttrs.type == TYPE_BASE_APPLICATION) {
                        // Base windows go behind everything else.
                        placeWindowBefore(token.windows.get(0), win);
                        tokenWindowsPos = 0;
                    } else {
                        AppWindowToken atoken = win.mAppToken;
                        if (atoken != null &&
                                token.windows.get(index) == atoken.startingWindow) {
                            placeWindowBefore(token.windows.get(index), win);
                            tokenWindowsPos--;
                        } else {
                            int newIdx =  findIdxBasedOnAppTokens(win);
                            if(newIdx != -1) {
                                //there is a window above this one associated with the same
                                //apptoken note that the window could be a floating window
                                //that was created later or a window at the top of the list of
                                //windows associated with this token.
                                localmWindows.add(newIdx+1, win);
                            }
                        }
                    }
                } else {
                    if (localLOGV) Log.v(
                        TAG, "Figuring out where to add app window "
                        + client.asBinder() + " (token=" + token + ")");
                    // Figure out where the window should go, based on the
                    // order of applications.
                    final int NA = mAppTokens.size();
                    Object pos = null;
                    for (i=NA-1; i>=0; i--) {
                        AppWindowToken t = mAppTokens.get(i);
                        if (t == token) {
                            i--;
                            break;
                        }
                        if (t.windows.size() > 0) {
                            pos = t.windows.get(0);
                        }
                    }
                    // We now know the index into the apps.  If we found
                    // an app window above, that gives us the position; else
                    // we need to look some more.
                    if (pos != null) {
                        // Move behind any windows attached to this one.
                        WindowToken atoken =
                            mTokenMap.get(((WindowState)pos).mClient.asBinder());
                        if (atoken != null) {
                            final int NC = atoken.windows.size();
                            if (NC > 0) {
                                WindowState bottom = atoken.windows.get(0);
                                if (bottom.mSubLayer < 0) {
                                    pos = bottom;
                                }
                            }
                        }
                        placeWindowBefore(pos, win);
                    } else {
                        while (i >= 0) {
                            AppWindowToken t = mAppTokens.get(i);
                            final int NW = t.windows.size();
                            if (NW > 0) {
                                pos = t.windows.get(NW-1);
                                break;
                            }
                            i--;
                        }
                        if (pos != null) {
                            // Move in front of any windows attached to this
                            // one.
                            WindowToken atoken =
                                mTokenMap.get(((WindowState)pos).mClient.asBinder());
                            if (atoken != null) {
                                final int NC = atoken.windows.size();
                                if (NC > 0) {
                                    WindowState top = atoken.windows.get(NC-1);
                                    if (top.mSubLayer >= 0) {
                                        pos = top;
                                    }
                                }
                            }
                            placeWindowAfter(pos, win);
                        } else {
                            // Just search for the start of this layer.
                            final int myLayer = win.mBaseLayer;
                            for (i=0; i<N; i++) {
                                WindowState w = (WindowState)localmWindows.get(i);
                                if (w.mBaseLayer > myLayer) {
                                    break;
                                }
                            }
                            if (localLOGV || DEBUG_FOCUS) Log.v(
                                TAG, "Adding window " + win + " at "
                                + i + " of " + N);
                            localmWindows.add(i, win);
                        }
                    }
                }
            } else {
                // Figure out where window should go, based on layer.
                final int myLayer = win.mBaseLayer;
                for (i=N-1; i>=0; i--) {
                    if (((WindowState)localmWindows.get(i)).mBaseLayer <= myLayer) {
                        i++;
                        break;
                    }
                }
                if (i < 0) i = 0;
                if (localLOGV || DEBUG_FOCUS) Log.v(
                    TAG, "Adding window " + win + " at "
                    + i + " of " + N);
                localmWindows.add(i, win);
            }
            if (addToToken) {
                token.windows.add(tokenWindowsPos, win);
            }

        } else {
            // Figure out this window's ordering relative to the window
            // it is attached to.
            final int NA = token.windows.size();
            final int sublayer = win.mSubLayer;
            int largestSublayer = Integer.MIN_VALUE;
            WindowState windowWithLargestSublayer = null;
            for (i=0; i<NA; i++) {
                WindowState w = token.windows.get(i);
                final int wSublayer = w.mSubLayer;
                if (wSublayer >= largestSublayer) {
                    largestSublayer = wSublayer;
                    windowWithLargestSublayer = w;
                }
                if (sublayer < 0) {
                    // For negative sublayers, we go below all windows
                    // in the same sublayer.
                    if (wSublayer >= sublayer) {
                        if (addToToken) {
                            token.windows.add(i, win);
                        }
                        placeWindowBefore(
                            wSublayer >= 0 ? attached : w, win);
                        break;
                    }
                } else {
                    // For positive sublayers, we go above all windows
                    // in the same sublayer.
                    if (wSublayer > sublayer) {
                        if (addToToken) {
                            token.windows.add(i, win);
                        }
                        placeWindowBefore(w, win);
                        break;
                    }
                }
            }
            if (i >= NA) {
                if (addToToken) {
                    token.windows.add(win);
                }
                if (sublayer < 0) {
                    placeWindowBefore(attached, win);
                } else {
                    placeWindowAfter(largestSublayer >= 0
                                     ? windowWithLargestSublayer
                                     : attached,
                                     win);
                }
            }
        }

        if (win.mAppToken != null && addToToken) {
            win.mAppToken.allAppWindows.add(win);
        }
    }

    static boolean canBeImeTarget(WindowState w) {
        final int fl = w.mAttrs.flags
                & (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM);
        if (fl == 0 || fl == (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM)) {
            return w.isVisibleOrAdding();
        }
        return false;
    }

    int findDesiredInputMethodWindowIndexLocked(boolean willMove) {
        final ArrayList localmWindows = mWindows;
        final int N = localmWindows.size();
        WindowState w = null;
        int i = N;
        while (i > 0) {
            i--;
            w = (WindowState)localmWindows.get(i);

            //Log.i(TAG, "Checking window @" + i + " " + w + " fl=0x"
            //        + Integer.toHexString(w.mAttrs.flags));
            if (canBeImeTarget(w)) {
                //Log.i(TAG, "Putting input method here!");

                // Yet more tricksyness!  If this window is a "starting"
                // window, we do actually want to be on top of it, but
                // it is not -really- where input will go.  So if the caller
                // is not actually looking to move the IME, look down below
                // for a real window to target...
                if (!willMove
                        && w.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                        && i > 0) {
                    WindowState wb = (WindowState)localmWindows.get(i-1);
                    if (wb.mAppToken == w.mAppToken && canBeImeTarget(wb)) {
                        i--;
                        w = wb;
                    }
                }
                break;
            }
        }

        mUpcomingInputMethodTarget = w;

        if (DEBUG_INPUT_METHOD) Log.v(TAG, "Desired input method target="
                + w + " willMove=" + willMove);

        if (willMove && w != null) {
            final WindowState curTarget = mInputMethodTarget;
            if (curTarget != null && curTarget.mAppToken != null) {

                // Now some fun for dealing with window animations that
                // modify the Z order.  We need to look at all windows below
                // the current target that are in this app, finding the highest
                // visible one in layering.
                AppWindowToken token = curTarget.mAppToken;
                WindowState highestTarget = null;
                int highestPos = 0;
                if (token.animating || token.animation != null) {
                    int pos = 0;
                    pos = localmWindows.indexOf(curTarget);
                    while (pos >= 0) {
                        WindowState win = (WindowState)localmWindows.get(pos);
                        if (win.mAppToken != token) {
                            break;
                        }
                        if (!win.mRemoved) {
                            if (highestTarget == null || win.mAnimLayer >
                                    highestTarget.mAnimLayer) {
                                highestTarget = win;
                                highestPos = pos;
                            }
                        }
                        pos--;
                    }
                }

                if (highestTarget != null) {
                    if (DEBUG_INPUT_METHOD) Log.v(TAG, "mNextAppTransition="
                            + mNextAppTransition + " " + highestTarget
                            + " animating=" + highestTarget.isAnimating()
                            + " layer=" + highestTarget.mAnimLayer
                            + " new layer=" + w.mAnimLayer);

                    if (mNextAppTransition != WindowManagerPolicy.TRANSIT_NONE) {
                        // If we are currently setting up for an animation,
                        // hold everything until we can find out what will happen.
                        mInputMethodTargetWaitingAnim = true;
                        mInputMethodTarget = highestTarget;
                        return highestPos + 1;
                    } else if (highestTarget.isAnimating() &&
                            highestTarget.mAnimLayer > w.mAnimLayer) {
                        // If the window we are currently targeting is involved
                        // with an animation, and it is on top of the next target
                        // we will be over, then hold off on moving until
                        // that is done.
                        mInputMethodTarget = highestTarget;
                        return highestPos + 1;
                    }
                }
            }
        }

        //Log.i(TAG, "Placing input method @" + (i+1));
        if (w != null) {
            if (willMove) {
                RuntimeException e = new RuntimeException();
                e.fillInStackTrace();
                if (DEBUG_INPUT_METHOD) Log.w(TAG, "Moving IM target from "
                        + mInputMethodTarget + " to " + w, e);
                mInputMethodTarget = w;
                if (w.mAppToken != null) {
                    setInputMethodAnimLayerAdjustment(w.mAppToken.animLayerAdjustment);
                } else {
                    setInputMethodAnimLayerAdjustment(0);
                }
            }
            return i+1;
        }
        if (willMove) {
            RuntimeException e = new RuntimeException();
            e.fillInStackTrace();
            if (DEBUG_INPUT_METHOD) Log.w(TAG, "Moving IM target from "
                    + mInputMethodTarget + " to null", e);
            mInputMethodTarget = null;
            setInputMethodAnimLayerAdjustment(0);
        }
        return -1;
    }

    void addInputMethodWindowToListLocked(WindowState win) {
        int pos = findDesiredInputMethodWindowIndexLocked(true);
        if (pos >= 0) {
            win.mTargetAppToken = mInputMethodTarget.mAppToken;
            mWindows.add(pos, win);
            moveInputMethodDialogsLocked(pos+1);
            return;
        }
        win.mTargetAppToken = null;
        addWindowToListInOrderLocked(win, true);
        moveInputMethodDialogsLocked(pos);
    }

    void setInputMethodAnimLayerAdjustment(int adj) {
        if (DEBUG_LAYERS) Log.v(TAG, "Setting im layer adj to " + adj);
        mInputMethodAnimLayerAdjustment = adj;
        WindowState imw = mInputMethodWindow;
        if (imw != null) {
            imw.mAnimLayer = imw.mLayer + adj;
            if (DEBUG_LAYERS) Log.v(TAG, "IM win " + imw
                    + " anim layer: " + imw.mAnimLayer);
            int wi = imw.mChildWindows.size();
            while (wi > 0) {
                wi--;
                WindowState cw = (WindowState)imw.mChildWindows.get(wi);
                cw.mAnimLayer = cw.mLayer + adj;
                if (DEBUG_LAYERS) Log.v(TAG, "IM win " + cw
                        + " anim layer: " + cw.mAnimLayer);
            }
        }
        int di = mInputMethodDialogs.size();
        while (di > 0) {
            di --;
            imw = mInputMethodDialogs.get(di);
            imw.mAnimLayer = imw.mLayer + adj;
            if (DEBUG_LAYERS) Log.v(TAG, "IM win " + imw
                    + " anim layer: " + imw.mAnimLayer);
        }
    }

    private int tmpRemoveWindowLocked(int interestingPos, WindowState win) {
        int wpos = mWindows.indexOf(win);
        if (wpos >= 0) {
            if (wpos < interestingPos) interestingPos--;
            mWindows.remove(wpos);
            int NC = win.mChildWindows.size();
            while (NC > 0) {
                NC--;
                WindowState cw = (WindowState)win.mChildWindows.get(NC);
                int cpos = mWindows.indexOf(cw);
                if (cpos >= 0) {
                    if (cpos < interestingPos) interestingPos--;
                    mWindows.remove(cpos);
                }
            }
        }
        return interestingPos;
    }

    private void reAddWindowToListInOrderLocked(WindowState win) {
        addWindowToListInOrderLocked(win, false);
        // This is a hack to get all of the child windows added as well
        // at the right position.  Child windows should be rare and
        // this case should be rare, so it shouldn't be that big a deal.
        int wpos = mWindows.indexOf(win);
        if (wpos >= 0) {
            mWindows.remove(wpos);
            reAddWindowLocked(wpos, win);
        }
    }

    void logWindowList(String prefix) {
        int N = mWindows.size();
        while (N > 0) {
            N--;
            Log.v(TAG, prefix + "#" + N + ": " + mWindows.get(N));
        }
    }

    void moveInputMethodDialogsLocked(int pos) {
        ArrayList<WindowState> dialogs = mInputMethodDialogs;

        final int N = dialogs.size();
        if (DEBUG_INPUT_METHOD) Log.v(TAG, "Removing " + N + " dialogs w/pos=" + pos);
        for (int i=0; i<N; i++) {
            pos = tmpRemoveWindowLocked(pos, dialogs.get(i));
        }
        if (DEBUG_INPUT_METHOD) {
            Log.v(TAG, "Window list w/pos=" + pos);
            logWindowList("  ");
        }

        if (pos >= 0) {
            final AppWindowToken targetAppToken = mInputMethodTarget.mAppToken;
            if (pos < mWindows.size()) {
                WindowState wp = (WindowState)mWindows.get(pos);
                if (wp == mInputMethodWindow) {
                    pos++;
                }
            }
            if (DEBUG_INPUT_METHOD) Log.v(TAG, "Adding " + N + " dialogs at pos=" + pos);
            for (int i=0; i<N; i++) {
                WindowState win = dialogs.get(i);
                win.mTargetAppToken = targetAppToken;
                pos = reAddWindowLocked(pos, win);
            }
            if (DEBUG_INPUT_METHOD) {
                Log.v(TAG, "Final window list:");
                logWindowList("  ");
            }
            return;
        }
        for (int i=0; i<N; i++) {
            WindowState win = dialogs.get(i);
            win.mTargetAppToken = null;
            reAddWindowToListInOrderLocked(win);
            if (DEBUG_INPUT_METHOD) {
                Log.v(TAG, "No IM target, final list:");
                logWindowList("  ");
            }
        }
    }

    boolean moveInputMethodWindowsIfNeededLocked(boolean needAssignLayers) {
        final WindowState imWin = mInputMethodWindow;
        final int DN = mInputMethodDialogs.size();
        if (imWin == null && DN == 0) {
            return false;
        }

        int imPos = findDesiredInputMethodWindowIndexLocked(true);
        if (imPos >= 0) {
            // In this case, the input method windows are to be placed
            // immediately above the window they are targeting.

            // First check to see if the input method windows are already
            // located here, and contiguous.
            final int N = mWindows.size();
            WindowState firstImWin = imPos < N
                    ? (WindowState)mWindows.get(imPos) : null;

            // Figure out the actual input method window that should be
            // at the bottom of their stack.
            WindowState baseImWin = imWin != null
                    ? imWin : mInputMethodDialogs.get(0);
            if (baseImWin.mChildWindows.size() > 0) {
                WindowState cw = (WindowState)baseImWin.mChildWindows.get(0);
                if (cw.mSubLayer < 0) baseImWin = cw;
            }

            if (firstImWin == baseImWin) {
                // The windows haven't moved...  but are they still contiguous?
                // First find the top IM window.
                int pos = imPos+1;
                while (pos < N) {
                    if (!((WindowState)mWindows.get(pos)).mIsImWindow) {
                        break;
                    }
                    pos++;
                }
                pos++;
                // Now there should be no more input method windows above.
                while (pos < N) {
                    if (((WindowState)mWindows.get(pos)).mIsImWindow) {
                        break;
                    }
                    pos++;
                }
                if (pos >= N) {
                    // All is good!
                    return false;
                }
            }

            if (imWin != null) {
                if (DEBUG_INPUT_METHOD) {
                    Log.v(TAG, "Moving IM from " + imPos);
                    logWindowList("  ");
                }
                imPos = tmpRemoveWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Log.v(TAG, "List after moving with new pos " + imPos + ":");
                    logWindowList("  ");
                }
                imWin.mTargetAppToken = mInputMethodTarget.mAppToken;
                reAddWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Log.v(TAG, "List after moving IM to " + imPos + ":");
                    logWindowList("  ");
                }
                if (DN > 0) moveInputMethodDialogsLocked(imPos+1);
            } else {
                moveInputMethodDialogsLocked(imPos);
            }

        } else {
            // In this case, the input method windows go in a fixed layer,
            // because they aren't currently associated with a focus window.

            if (imWin != null) {
                if (DEBUG_INPUT_METHOD) Log.v(TAG, "Moving IM from " + imPos);
                tmpRemoveWindowLocked(0, imWin);
                imWin.mTargetAppToken = null;
                reAddWindowToListInOrderLocked(imWin);
                if (DEBUG_INPUT_METHOD) {
                    Log.v(TAG, "List with no IM target:");
                    logWindowList("  ");
                }
                if (DN > 0) moveInputMethodDialogsLocked(-1);;
            } else {
                moveInputMethodDialogsLocked(-1);;
            }

        }

        if (needAssignLayers) {
            assignLayersLocked();
        }

        return true;
    }

    void adjustInputMethodDialogsLocked() {
        moveInputMethodDialogsLocked(findDesiredInputMethodWindowIndexLocked(true));
    }

    public int addWindow(Session session, IWindow client,
            WindowManager.LayoutParams attrs, int viewVisibility,
            Rect outContentInsets) {
        int res = mPolicy.checkAddPermission(attrs);
        if (res != WindowManagerImpl.ADD_OKAY) {
            return res;
        }

        boolean reportNewConfig = false;
        WindowState attachedWindow = null;
        WindowState win = null;

        synchronized(mWindowMap) {
            // Instantiating a Display requires talking with the simulator,
            // so don't do it until we know the system is mostly up and
            // running.
            if (mDisplay == null) {
                WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
                mDisplay = wm.getDefaultDisplay();
                mQueue.setDisplay(mDisplay);
                reportNewConfig = true;
            }

            if (mWindowMap.containsKey(client.asBinder())) {
                Log.w(TAG, "Window " + client + " is already added");
                return WindowManagerImpl.ADD_DUPLICATE_ADD;
            }

            if (attrs.type >= FIRST_SUB_WINDOW && attrs.type <= LAST_SUB_WINDOW) {
                attachedWindow = windowForClientLocked(null, attrs.token);
                if (attachedWindow == null) {
                    Log.w(TAG, "Attempted to add window with token that is not a window: "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_SUBWINDOW_TOKEN;
                }
                if (attachedWindow.mAttrs.type >= FIRST_SUB_WINDOW
                        && attachedWindow.mAttrs.type <= LAST_SUB_WINDOW) {
                    Log.w(TAG, "Attempted to add window with token that is a sub-window: "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_SUBWINDOW_TOKEN;
                }
            }

            boolean addToken = false;
            WindowToken token = mTokenMap.get(attrs.token);
            if (token == null) {
                if (attrs.type >= FIRST_APPLICATION_WINDOW
                        && attrs.type <= LAST_APPLICATION_WINDOW) {
                    Log.w(TAG, "Attempted to add application window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
                if (attrs.type == TYPE_INPUT_METHOD) {
                    Log.w(TAG, "Attempted to add input method window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
                token = new WindowToken(attrs.token, -1, false);
                addToken = true;
            } else if (attrs.type >= FIRST_APPLICATION_WINDOW
                    && attrs.type <= LAST_APPLICATION_WINDOW) {
                AppWindowToken atoken = token.appWindowToken;
                if (atoken == null) {
                    Log.w(TAG, "Attempted to add window with non-application token "
                          + token + ".  Aborting.");
                    return WindowManagerImpl.ADD_NOT_APP_TOKEN;
                } else if (atoken.removed) {
                    Log.w(TAG, "Attempted to add window with exiting application token "
                          + token + ".  Aborting.");
                    return WindowManagerImpl.ADD_APP_EXITING;
                }
                if (attrs.type == TYPE_APPLICATION_STARTING && atoken.firstWindowDrawn) {
                    // No need for this guy!
                    if (localLOGV) Log.v(
                            TAG, "**** NO NEED TO START: " + attrs.getTitle());
                    return WindowManagerImpl.ADD_STARTING_NOT_NEEDED;
                }
            } else if (attrs.type == TYPE_INPUT_METHOD) {
                if (token.windowType != TYPE_INPUT_METHOD) {
                    Log.w(TAG, "Attempted to add input method window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
            }

            win = new WindowState(session, client, token,
                    attachedWindow, attrs, viewVisibility);
            if (win.mDeathRecipient == null) {
                // Client has apparently died, so there is no reason to
                // continue.
                Log.w(TAG, "Adding window client " + client.asBinder()
                        + " that is dead, aborting.");
                return WindowManagerImpl.ADD_APP_EXITING;
            }

            mPolicy.adjustWindowParamsLw(win.mAttrs);

            res = mPolicy.prepareAddWindowLw(win, attrs);
            if (res != WindowManagerImpl.ADD_OKAY) {
                return res;
            }

            // From now on, no exceptions or errors allowed!

            res = WindowManagerImpl.ADD_OKAY;

            final long origId = Binder.clearCallingIdentity();

            if (addToken) {
                mTokenMap.put(attrs.token, token);
                mTokenList.add(token);
            }
            win.attach();
            mWindowMap.put(client.asBinder(), win);

            if (attrs.type == TYPE_APPLICATION_STARTING &&
                    token.appWindowToken != null) {
                token.appWindowToken.startingWindow = win;
            }

            boolean imMayMove = true;

            if (attrs.type == TYPE_INPUT_METHOD) {
                mInputMethodWindow = win;
                addInputMethodWindowToListLocked(win);
                imMayMove = false;
            } else if (attrs.type == TYPE_INPUT_METHOD_DIALOG) {
                mInputMethodDialogs.add(win);
                addWindowToListInOrderLocked(win, true);
                adjustInputMethodDialogsLocked();
                imMayMove = false;
            } else {
                addWindowToListInOrderLocked(win, true);
            }

            win.mEnterAnimationPending = true;

            mPolicy.getContentInsetHintLw(attrs, outContentInsets);

            if (mInTouchMode) {
                res |= WindowManagerImpl.ADD_FLAG_IN_TOUCH_MODE;
            }
            if (win == null || win.mAppToken == null || !win.mAppToken.clientHidden) {
                res |= WindowManagerImpl.ADD_FLAG_APP_VISIBLE;
            }

            boolean focusChanged = false;
            if (win.canReceiveKeys()) {
                if ((focusChanged=updateFocusedWindowLocked(UPDATE_FOCUS_WILL_ASSIGN_LAYERS))
                        == true) {
                    imMayMove = false;
                }
            }

            if (imMayMove) {
                moveInputMethodWindowsIfNeededLocked(false);
            }

            assignLayersLocked();
            // Don't do layout here, the window must call
            // relayout to be displayed, so we'll do it there.

            //dump();

            if (focusChanged) {
                if (mCurrentFocus != null) {
                    mKeyWaiter.handleNewWindowLocked(mCurrentFocus);
                }
            }
            if (localLOGV) Log.v(
                TAG, "New client " + client.asBinder()
                + ": window=" + win);
        }

        // sendNewConfiguration() checks caller permissions so we must call it with
        // privilege.  updateOrientationFromAppTokens() clears and resets the caller
        // identity anyway, so it's safe to just clear & restore around this whole
        // block.
        final long origId = Binder.clearCallingIdentity();
        if (reportNewConfig) {
            sendNewConfiguration();
        } else {
            // Update Orientation after adding a window, only if the window needs to be
            // displayed right away
            if (win.isVisibleOrAdding()) {
                if (updateOrientationFromAppTokensUnchecked(null, null) != null) {
                    sendNewConfiguration();
                }
            }
        }
        Binder.restoreCallingIdentity(origId);

        return res;
    }

    public void removeWindow(Session session, IWindow client) {
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client);
            if (win == null) {
                return;
            }
            removeWindowLocked(session, win);
        }
    }

    public void removeWindowLocked(Session session, WindowState win) {

        if (localLOGV || DEBUG_FOCUS) Log.v(
            TAG, "Remove " + win + " client="
            + Integer.toHexString(System.identityHashCode(
                win.mClient.asBinder()))
            + ", surface=" + win.mSurface);

        final long origId = Binder.clearCallingIdentity();

        if (DEBUG_APP_TRANSITIONS) Log.v(
                TAG, "Remove " + win + ": mSurface=" + win.mSurface
                + " mExiting=" + win.mExiting
                + " isAnimating=" + win.isAnimating()
                + " app-animation="
                + (win.mAppToken != null ? win.mAppToken.animation : null)
                + " inPendingTransaction="
                + (win.mAppToken != null ? win.mAppToken.inPendingTransaction : false)
                + " mDisplayFrozen=" + mDisplayFrozen);
        // Visibility of the removed window. Will be used later to update orientation later on.
        boolean wasVisible = false;
        // First, see if we need to run an animation.  If we do, we have
        // to hold off on removing the window until the animation is done.
        // If the display is frozen, just remove immediately, since the
        // animation wouldn't be seen.
        if (win.mSurface != null && !mDisplayFrozen) {
            // If we are not currently running the exit animation, we
            // need to see about starting one.
            if (wasVisible=win.isWinVisibleLw()) {

                int transit = WindowManagerPolicy.TRANSIT_EXIT;
                if (win.getAttrs().type == TYPE_APPLICATION_STARTING) {
                    transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
                }
                // Try starting an animation.
                if (applyAnimationLocked(win, transit, false)) {
                    win.mExiting = true;
                }
            }
            if (win.mExiting || win.isAnimating()) {
                // The exit animation is running... wait for it!
                //Log.i(TAG, "*** Running exit animation...");
                win.mExiting = true;
                win.mRemoveOnExit = true;
                mLayoutNeeded = true;
                updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES);
                performLayoutAndPlaceSurfacesLocked();
                if (win.mAppToken != null) {
                    win.mAppToken.updateReportedVisibilityLocked();
                }
                //dump();
                Binder.restoreCallingIdentity(origId);
                return;
            }
        }

        removeWindowInnerLocked(session, win);
        // Removing a visible window will effect the computed orientation
        // So just update orientation if needed.
        if (wasVisible && computeForcedAppOrientationLocked()
                != mForcedAppOrientation) {
            mH.sendMessage(mH.obtainMessage(H.COMPUTE_AND_SEND_NEW_CONFIGURATION));
        }
        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL);
        Binder.restoreCallingIdentity(origId);
    }

    private void removeWindowInnerLocked(Session session, WindowState win) {
        mKeyWaiter.releasePendingPointerLocked(win.mSession);
        mKeyWaiter.releasePendingTrackballLocked(win.mSession);

        win.mRemoved = true;

        if (mInputMethodTarget == win) {
            moveInputMethodWindowsIfNeededLocked(false);
        }

        mPolicy.removeWindowLw(win);
        win.removeLocked();

        mWindowMap.remove(win.mClient.asBinder());
        mWindows.remove(win);

        if (mInputMethodWindow == win) {
            mInputMethodWindow = null;
        } else if (win.mAttrs.type == TYPE_INPUT_METHOD_DIALOG) {
            mInputMethodDialogs.remove(win);
        }

        final WindowToken token = win.mToken;
        final AppWindowToken atoken = win.mAppToken;
        token.windows.remove(win);
        if (atoken != null) {
            atoken.allAppWindows.remove(win);
        }
        if (localLOGV) Log.v(
                TAG, "**** Removing window " + win + ": count="
                + token.windows.size());
        if (token.windows.size() == 0) {
            if (!token.explicit) {
                mTokenMap.remove(token.token);
                mTokenList.remove(token);
            } else if (atoken != null) {
                atoken.firstWindowDrawn = false;
            }
        }

        if (atoken != null) {
            if (atoken.startingWindow == win) {
                atoken.startingWindow = null;
            } else if (atoken.allAppWindows.size() == 0 && atoken.startingData != null) {
                // If this is the last window and we had requested a starting
                // transition window, well there is no point now.
                atoken.startingData = null;
            } else if (atoken.allAppWindows.size() == 1 && atoken.startingView != null) {
                // If this is the last window except for a starting transition
                // window, we need to get rid of the starting transition.
                if (DEBUG_STARTING_WINDOW) {
                    Log.v(TAG, "Schedule remove starting " + token
                            + ": no more real windows");
                }
                Message m = mH.obtainMessage(H.REMOVE_STARTING, atoken);
                mH.sendMessage(m);
            }
        }

        if (!mInLayout) {
            assignLayersLocked();
            mLayoutNeeded = true;
            performLayoutAndPlaceSurfacesLocked();
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
        }
    }

    private void setTransparentRegionWindow(Session session, IWindow client, Region region) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState w = windowForClientLocked(session, client);
                if ((w != null) && (w.mSurface != null)) {
                    Surface.openTransaction();
                    try {
                        w.mSurface.setTransparentRegionHint(region);
                    } finally {
                        Surface.closeTransaction();
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void setInsetsWindow(Session session, IWindow client,
            int touchableInsets, Rect contentInsets,
            Rect visibleInsets) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState w = windowForClientLocked(session, client);
                if (w != null) {
                    w.mGivenInsetsPending = false;
                    w.mGivenContentInsets.set(contentInsets);
                    w.mGivenVisibleInsets.set(visibleInsets);
                    w.mTouchableInsets = touchableInsets;
                    mLayoutNeeded = true;
                    performLayoutAndPlaceSurfacesLocked();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void getWindowDisplayFrame(Session session, IWindow client,
            Rect outDisplayFrame) {
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client);
            if (win == null) {
                outDisplayFrame.setEmpty();
                return;
            }
            outDisplayFrame.set(win.mDisplayFrame);
        }
    }

    public int relayoutWindow(Session session, IWindow client,
            WindowManager.LayoutParams attrs, int requestedWidth,
            int requestedHeight, int viewVisibility, boolean insetsPending,
            Rect outFrame, Rect outContentInsets, Rect outVisibleInsets,
            Surface outSurface) {
        boolean displayed = false;
        boolean inTouchMode;
        Configuration newConfig = null;
        long origId = Binder.clearCallingIdentity();

        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client);
            if (win == null) {
                return 0;
            }
            win.mRequestedWidth = requestedWidth;
            win.mRequestedHeight = requestedHeight;

            if (attrs != null) {
                mPolicy.adjustWindowParamsLw(attrs);
            }

            int attrChanges = 0;
            int flagChanges = 0;
            if (attrs != null) {
                flagChanges = win.mAttrs.flags ^= attrs.flags;
                attrChanges = win.mAttrs.copyFrom(attrs);
            }

            if (localLOGV) Log.v(
                TAG, "Relayout given client " + client.asBinder()
                + " (" + win.mAttrs.getTitle() + ")");


            if ((attrChanges & WindowManager.LayoutParams.ALPHA_CHANGED) != 0) {
                win.mAlpha = attrs.alpha;
            }

            final boolean scaledWindow =
                ((win.mAttrs.flags & WindowManager.LayoutParams.FLAG_SCALED) != 0);

            if (scaledWindow) {
                // requested{Width|Height} Surface's physical size
                // attrs.{width|height} Size on screen
                win.mHScale = (attrs.width  != requestedWidth)  ?
                        (attrs.width  / (float)requestedWidth) : 1.0f;
                win.mVScale = (attrs.height != requestedHeight) ?
                        (attrs.height / (float)requestedHeight) : 1.0f;
            }

            boolean imMayMove = (flagChanges&(
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) != 0;

            boolean focusMayChange = win.mViewVisibility != viewVisibility
                    || ((flagChanges&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
                    || (!win.mRelayoutCalled);

            win.mRelayoutCalled = true;
            final int oldVisibility = win.mViewVisibility;
            win.mViewVisibility = viewVisibility;
            if (viewVisibility == View.VISIBLE &&
                    (win.mAppToken == null || !win.mAppToken.clientHidden)) {
                displayed = !win.isVisibleLw();
                if (win.mExiting) {
                    win.mExiting = false;
                    win.mAnimation = null;
                }
                if (win.mDestroying) {
                    win.mDestroying = false;
                    mDestroySurface.remove(win);
                }
                if (oldVisibility == View.GONE) {
                    win.mEnterAnimationPending = true;
                }
                if (displayed && win.mSurface != null && !win.mDrawPending
                        && !win.mCommitDrawPending && !mDisplayFrozen) {
                    applyEnterAnimationLocked(win);
                }
                if ((attrChanges&WindowManager.LayoutParams.FORMAT_CHANGED) != 0) {
                    // To change the format, we need to re-build the surface.
                    win.destroySurfaceLocked();
                    displayed = true;
                }
                try {
                    Surface surface = win.createSurfaceLocked();
                    if (surface != null) {
                        outSurface.copyFrom(surface);
                    } else {
                        outSurface.clear();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Exception thrown when creating surface for client "
                         + client + " (" + win.mAttrs.getTitle() + ")",
                         e);
                    Binder.restoreCallingIdentity(origId);
                    return 0;
                }
                if (displayed) {
                    focusMayChange = true;
                }
                if (win.mAttrs.type == TYPE_INPUT_METHOD
                        && mInputMethodWindow == null) {
                    mInputMethodWindow = win;
                    imMayMove = true;
                }
            } else {
                win.mEnterAnimationPending = false;
                if (win.mSurface != null) {
                    // If we are not currently running the exit animation, we
                    // need to see about starting one.
                    if (!win.mExiting) {
                        // Try starting an animation; if there isn't one, we
                        // can destroy the surface right away.
                        int transit = WindowManagerPolicy.TRANSIT_EXIT;
                        if (win.getAttrs().type == TYPE_APPLICATION_STARTING) {
                            transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
                        }
                        if (win.isWinVisibleLw() &&
                              applyAnimationLocked(win, transit, false)) {
                            win.mExiting = true;
                            mKeyWaiter.finishedKey(session, client, true,
                                    KeyWaiter.RETURN_NOTHING);
                        } else if (win.isAnimating()) {
                            // Currently in a hide animation... turn this into
                            // an exit.
                            win.mExiting = true;
                        } else {
                            if (mInputMethodWindow == win) {
                                mInputMethodWindow = null;
                            }
                            win.destroySurfaceLocked();
                        }
                    }
                }
                outSurface.clear();
            }

            if (focusMayChange) {
                //System.out.println("Focus may change: " + win.mAttrs.getTitle());
                if (updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES)) {
                    imMayMove = false;
                }
                //System.out.println("Relayout " + win + ": focus=" + mCurrentFocus);
            }

            // updateFocusedWindowLocked() already assigned layers so we only need to
            // reassign them at this point if the IM window state gets shuffled
            boolean assignLayers = false;

            if (imMayMove) {
                if (moveInputMethodWindowsIfNeededLocked(false)) {
                    assignLayers = true;
                }
            }

            mLayoutNeeded = true;
            win.mGivenInsetsPending = insetsPending;
            if (assignLayers) {
                assignLayersLocked();
            }
            newConfig = updateOrientationFromAppTokensLocked(null, null);
            performLayoutAndPlaceSurfacesLocked();
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
            outFrame.set(win.mFrame);
            outContentInsets.set(win.mContentInsets);
            outVisibleInsets.set(win.mVisibleInsets);
            if (localLOGV) Log.v(
                TAG, "Relayout given client " + client.asBinder()
                + ", requestedWidth=" + requestedWidth
                + ", requestedHeight=" + requestedHeight
                + ", viewVisibility=" + viewVisibility
                + "\nRelayout returning frame=" + outFrame
                + ", surface=" + outSurface);

            if (localLOGV || DEBUG_FOCUS) Log.v(
                TAG, "Relayout of " + win + ": focusMayChange=" + focusMayChange);

            inTouchMode = mInTouchMode;
        }

        if (newConfig != null) {
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);

        return (inTouchMode ? WindowManagerImpl.RELAYOUT_IN_TOUCH_MODE : 0)
                | (displayed ? WindowManagerImpl.RELAYOUT_FIRST_TIME : 0);
    }

    public void finishDrawingWindow(Session session, IWindow client) {
        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client);
            if (win != null && win.finishDrawingLocked()) {
                mLayoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    private AttributeCache.Entry getCachedAnimations(WindowManager.LayoutParams lp) {
        if (DEBUG_ANIM) Log.v(TAG, "Loading animations: params package="
                + (lp != null ? lp.packageName : null)
                + " resId=0x" + (lp != null ? Integer.toHexString(lp.windowAnimations) : null));
        if (lp != null && lp.windowAnimations != 0) {
            // If this is a system resource, don't try to load it from the
            // application resources.  It is nice to avoid loading application
            // resources if we can.
            String packageName = lp.packageName != null ? lp.packageName : "android";
            int resId = lp.windowAnimations;
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Log.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation);
        }
        return null;
    }

    private void applyEnterAnimationLocked(WindowState win) {
        int transit = WindowManagerPolicy.TRANSIT_SHOW;
        if (win.mEnterAnimationPending) {
            win.mEnterAnimationPending = false;
            transit = WindowManagerPolicy.TRANSIT_ENTER;
        }

        applyAnimationLocked(win, transit, true);
    }

    private boolean applyAnimationLocked(WindowState win,
            int transit, boolean isEntrance) {
        if (win.mLocalAnimating && win.mAnimationIsEntrance == isEntrance) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.
            return true;
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (!mDisplayFrozen) {
            int anim = mPolicy.selectAnimationLw(win, transit);
            int attr = -1;
            Animation a = null;
            if (anim != 0) {
                a = AnimationUtils.loadAnimation(mContext, anim);
            } else {
                switch (transit) {
                    case WindowManagerPolicy.TRANSIT_ENTER:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowEnterAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_EXIT:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_SHOW:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowShowAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_HIDE:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowHideAnimation;
                        break;
                }
                if (attr >= 0) {
                    a = loadAnimation(win.mAttrs, attr);
                }
            }
            if (DEBUG_ANIM) Log.v(TAG, "applyAnimation: win=" + win
                    + " anim=" + anim + " attr=0x" + Integer.toHexString(attr)
                    + " mAnimation=" + win.mAnimation
                    + " isEntrance=" + isEntrance);
            if (a != null) {
                if (DEBUG_ANIM) {
                    RuntimeException e = new RuntimeException();
                    e.fillInStackTrace();
                    Log.v(TAG, "Loaded animation " + a + " for " + win, e);
                }
                win.setAnimation(a);
                win.mAnimationIsEntrance = isEntrance;
            }
        } else {
            win.clearAnimation();
        }

        return win.mAnimation != null;
    }

    private Animation loadAnimation(WindowManager.LayoutParams lp, int animAttr) {
        int anim = 0;
        Context context = mContext;
        if (animAttr >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(lp);
            if (ent != null) {
                context = ent.context;
                anim = ent.array.getResourceId(animAttr, 0);
            }
        }
        if (anim != 0) {
            return AnimationUtils.loadAnimation(context, anim);
        }
        return null;
    }

    private boolean applyAnimationLocked(AppWindowToken wtoken,
            WindowManager.LayoutParams lp, int transit, boolean enter) {
        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (!mDisplayFrozen) {
            Animation a;
            if (lp != null && (lp.flags & FLAG_COMPATIBLE_WINDOW) != 0) {
                a = new FadeInOutAnimation(enter);
                if (DEBUG_ANIM) Log.v(TAG,
                        "applying FadeInOutAnimation for a window in compatibility mode");
            } else {
                int animAttr = 0;
                switch (transit) {
                    case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_activityOpenEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_activityOpenExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_OPEN:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskOpenEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskOpenExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_CLOSE:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskCloseEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskCloseExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_TO_FRONT:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskToFrontEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskToFrontExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_TO_BACK:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskToBackEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskToBackExitAnimation;
                        break;
                }
                a = loadAnimation(lp, animAttr);
                if (DEBUG_ANIM) Log.v(TAG, "applyAnimation: wtoken=" + wtoken
                        + " anim=" + a
                        + " animAttr=0x" + Integer.toHexString(animAttr)
                        + " transit=" + transit);
            }
            if (a != null) {
                if (DEBUG_ANIM) {
                    RuntimeException e = new RuntimeException();
                    e.fillInStackTrace();
                    Log.v(TAG, "Loaded animation " + a + " for " + wtoken, e);
                }
                wtoken.setAnimation(a);
            }
        } else {
            wtoken.clearAnimation();
        }

        return wtoken.animation != null;
    }

    // -------------------------------------------------------------
    // Application Window Tokens
    // -------------------------------------------------------------

    public void validateAppTokens(List tokens) {
        int v = tokens.size()-1;
        int m = mAppTokens.size()-1;
        while (v >= 0 && m >= 0) {
            AppWindowToken wtoken = mAppTokens.get(m);
            if (wtoken.removed) {
                m--;
                continue;
            }
            if (tokens.get(v) != wtoken.token) {
                Log.w(TAG, "Tokens out of sync: external is " + tokens.get(v)
                      + " @ " + v + ", internal is " + wtoken.token + " @ " + m);
            }
            v--;
            m--;
        }
        while (v >= 0) {
            Log.w(TAG, "External token not found: " + tokens.get(v) + " @ " + v);
            v--;
        }
        while (m >= 0) {
            AppWindowToken wtoken = mAppTokens.get(m);
            if (!wtoken.removed) {
                Log.w(TAG, "Invalid internal token: " + wtoken.token + " @ " + m);
            }
            m--;
        }
    }

    boolean checkCallingPermission(String permission, String func) {
        // Quick check: if the calling permission is me, it's all okay.
        if (Binder.getCallingPid() == Process.myPid()) {
            return true;
        }

        if (mContext.checkCallingPermission(permission)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " requires " + permission;
        Log.w(TAG, msg);
        return false;
    }

    AppWindowToken findAppWindowToken(IBinder token) {
        WindowToken wtoken = mTokenMap.get(token);
        if (wtoken == null) {
            return null;
        }
        return wtoken.appWindowToken;
    }

    public void addWindowToken(IBinder token, int type) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "addWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            WindowToken wtoken = mTokenMap.get(token);
            if (wtoken != null) {
                Log.w(TAG, "Attempted to add existing input method token: " + token);
                return;
            }
            wtoken = new WindowToken(token, type, true);
            mTokenMap.put(token, wtoken);
            mTokenList.add(wtoken);
        }
    }

    public void removeWindowToken(IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "removeWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            WindowToken wtoken = mTokenMap.remove(token);
            mTokenList.remove(wtoken);
            if (wtoken != null) {
                boolean delayed = false;
                if (!wtoken.hidden) {
                    wtoken.hidden = true;

                    final int N = wtoken.windows.size();
                    boolean changed = false;

                    for (int i=0; i<N; i++) {
                        WindowState win = wtoken.windows.get(i);

                        if (win.isAnimating()) {
                            delayed = true;
                        }

                        if (win.isVisibleNow()) {
                            applyAnimationLocked(win,
                                    WindowManagerPolicy.TRANSIT_EXIT, false);
                            mKeyWaiter.finishedKey(win.mSession, win.mClient, true,
                                    KeyWaiter.RETURN_NOTHING);
                            changed = true;
                        }
                    }

                    if (changed) {
                        mLayoutNeeded = true;
                        performLayoutAndPlaceSurfacesLocked();
                        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL);
                    }

                    if (delayed) {
                        mExitingTokens.add(wtoken);
                    }
                }

            } else {
                Log.w(TAG, "Attempted to remove non-existing token: " + token);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    public void addAppToken(int addPos, IApplicationToken token,
            int groupId, int requestedOrientation, boolean fullscreen) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "addAppToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token.asBinder());
            if (wtoken != null) {
                Log.w(TAG, "Attempted to add existing app token: " + token);
                return;
            }
            wtoken = new AppWindowToken(token);
            wtoken.groupId = groupId;
            wtoken.appFullscreen = fullscreen;
            wtoken.requestedOrientation = requestedOrientation;
            mAppTokens.add(addPos, wtoken);
            if (localLOGV) Log.v(TAG, "Adding new app token: " + wtoken);
            mTokenMap.put(token.asBinder(), wtoken);
            mTokenList.add(wtoken);

            // Application tokens start out hidden.
            wtoken.hidden = true;
            wtoken.hiddenRequested = true;

            //dump();
        }
    }

    public void setAppGroupId(IBinder token, int groupId) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppStartingIcon()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Log.w(TAG, "Attempted to set group id of non-existing app token: " + token);
                return;
            }
            wtoken.groupId = groupId;
        }
    }

    public int getOrientationFromWindowsLocked() {
        int pos = mWindows.size() - 1;
        while (pos >= 0) {
            WindowState wtoken = (WindowState) mWindows.get(pos);
            pos--;
            if (wtoken.mAppToken != null) {
                // We hit an application window. so the orientation will be determined by the
                // app window. No point in continuing further.
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            if (!wtoken.isVisibleLw()) {
                continue;
            }
            int req = wtoken.mAttrs.screenOrientation;
            if((req == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) ||
                    (req == ActivityInfo.SCREEN_ORIENTATION_BEHIND)){
                continue;
            } else {
                return req;
            }
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    public int getOrientationFromAppTokensLocked() {
            int pos = mAppTokens.size() - 1;
            int curGroup = 0;
            int lastOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            boolean findingBehind = false;
            boolean haveGroup = false;
            boolean lastFullscreen = false;
            while (pos >= 0) {
                AppWindowToken wtoken = mAppTokens.get(pos);
                pos--;
                // if we're about to tear down this window and not seek for
                // the behind activity, don't use it for orientation
                if (!findingBehind
                        && (!wtoken.hidden && wtoken.hiddenRequested)) {
                    continue;
                }

                if (!haveGroup) {
                    // We ignore any hidden applications on the top.
                    if (wtoken.hiddenRequested || wtoken.willBeHidden) {
                        continue;
                    }
                    haveGroup = true;
                    curGroup = wtoken.groupId;
                    lastOrientation = wtoken.requestedOrientation;
                } else if (curGroup != wtoken.groupId) {
                    // If we have hit a new application group, and the bottom
                    // of the previous group didn't explicitly say to use
                    // the orientation behind it, and the last app was
                    // full screen, then we'll stick with the
                    // user's orientation.
                    if (lastOrientation != ActivityInfo.SCREEN_ORIENTATION_BEHIND
                            && lastFullscreen) {
                        return lastOrientation;
                    }
                }
                int or = wtoken.requestedOrientation;
                // If this application is fullscreen, and didn't explicitly say
                // to use the orientation behind it, then just take whatever
                // orientation it has and ignores whatever is under it.
                lastFullscreen = wtoken.appFullscreen;
                if (lastFullscreen
                        && or != ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
                    return or;
                }
                // If this application has requested an explicit orientation,
                // then use it.
                if (or == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                        or == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                        or == ActivityInfo.SCREEN_ORIENTATION_SENSOR ||
                        or == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR ||
                        or == ActivityInfo.SCREEN_ORIENTATION_USER) {
                    return or;
                }
                findingBehind |= (or == ActivityInfo.SCREEN_ORIENTATION_BEHIND);
            }
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    public Configuration updateOrientationFromAppTokens(
            Configuration currentConfig, IBinder freezeThisOneIfNeeded) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "updateOrientationFromAppTokens()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        
        Configuration config;
        long ident = Binder.clearCallingIdentity();
        config = updateOrientationFromAppTokensUnchecked(currentConfig,
                freezeThisOneIfNeeded);
        Binder.restoreCallingIdentity(ident);
        return config;
    }

    Configuration updateOrientationFromAppTokensUnchecked(
            Configuration currentConfig, IBinder freezeThisOneIfNeeded) {
        Configuration config;
        synchronized(mWindowMap) {
            config = updateOrientationFromAppTokensLocked(currentConfig, freezeThisOneIfNeeded);
        }
        if (config != null) {
            mLayoutNeeded = true;
            performLayoutAndPlaceSurfacesLocked();
        }
        return config;
    }

    /*
     * The orientation is computed from non-application windows first. If none of
     * the non-application windows specify orientation, the orientation is computed from
     * application tokens.
     * @see android.view.IWindowManager#updateOrientationFromAppTokens(
     * android.os.IBinder)
     */
    Configuration updateOrientationFromAppTokensLocked(
            Configuration appConfig, IBinder freezeThisOneIfNeeded) {
        boolean changed = false;
        long ident = Binder.clearCallingIdentity();
        try {
            int req = computeForcedAppOrientationLocked();

            if (req != mForcedAppOrientation) {
                changed = true;
                mForcedAppOrientation = req;
                //send a message to Policy indicating orientation change to take
                //action like disabling/enabling sensors etc.,
                mPolicy.setCurrentOrientationLw(req);
            }

            if (changed) {
                changed = setRotationUncheckedLocked(
                        WindowManagerPolicy.USE_LAST_ROTATION,
                        mLastRotationFlags & (~Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE));
                if (changed) {
                    if (freezeThisOneIfNeeded != null) {
                        AppWindowToken wtoken = findAppWindowToken(
                                freezeThisOneIfNeeded);
                        if (wtoken != null) {
                            startAppFreezingScreenLocked(wtoken,
                                    ActivityInfo.CONFIG_ORIENTATION);
                        }
                    }
                    return computeNewConfigurationLocked();
                }
            }

            // No obvious action we need to take, but if our current
            // state mismatches the activity maanager's, update it
            if (appConfig != null) {
                mTempConfiguration.setToDefaults();
                if (computeNewConfigurationLocked(mTempConfiguration)) {
                    if (appConfig.diff(mTempConfiguration) != 0) {
                        Log.i(TAG, "Config changed: " + mTempConfiguration);
                        return new Configuration(mTempConfiguration);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return null;
    }

    int computeForcedAppOrientationLocked() {
        int req = getOrientationFromWindowsLocked();
        if (req == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            req = getOrientationFromAppTokensLocked();
        }
        return req;
    }

    public void setAppOrientation(IApplicationToken token, int requestedOrientation) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppOrientation()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token.asBinder());
            if (wtoken == null) {
                Log.w(TAG, "Attempted to set orientation of non-existing app token: " + token);
                return;
            }

            wtoken.requestedOrientation = requestedOrientation;
        }
    }

    public int getAppOrientation(IApplicationToken token) {
        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token.asBinder());
            if (wtoken == null) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }

            return wtoken.requestedOrientation;
        }
    }

    public void setFocusedApp(IBinder token, boolean moveFocusNow) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setFocusedApp()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            boolean changed = false;
            if (token == null) {
                if (DEBUG_FOCUS) Log.v(TAG, "Clearing focused app, was " + mFocusedApp);
                changed = mFocusedApp != null;
                mFocusedApp = null;
                mKeyWaiter.tickle();
            } else {
                AppWindowToken newFocus = findAppWindowToken(token);
                if (newFocus == null) {
                    Log.w(TAG, "Attempted to set focus to non-existing app token: " + token);
                    return;
                }
                changed = mFocusedApp != newFocus;
                mFocusedApp = newFocus;
                if (DEBUG_FOCUS) Log.v(TAG, "Set focused app to: " + mFocusedApp);
                mKeyWaiter.tickle();
            }

            if (moveFocusNow && changed) {
                final long origId = Binder.clearCallingIdentity();
                updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL);
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void prepareAppTransition(int transit) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_APP_TRANSITIONS) Log.v(
                    TAG, "Prepare app transition: transit=" + transit
                    + " mNextAppTransition=" + mNextAppTransition);
            if (!mDisplayFrozen) {
                if (mNextAppTransition == WindowManagerPolicy.TRANSIT_NONE) {
                    mNextAppTransition = transit;
                } else if (transit == WindowManagerPolicy.TRANSIT_TASK_OPEN
                        && mNextAppTransition == WindowManagerPolicy.TRANSIT_TASK_CLOSE) {
                    // Opening a new task always supersedes a close for the anim.
                    mNextAppTransition = transit;
                } else if (transit == WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN
                        && mNextAppTransition == WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE) {
                    // Opening a new activity always supersedes a close for the anim.
                    mNextAppTransition = transit;
                }
                mAppTransitionReady = false;
                mAppTransitionTimeout = false;
                mStartingIconInTransition = false;
                mSkipAppTransitionAnimation = false;
                mH.removeMessages(H.APP_TRANSITION_TIMEOUT);
                mH.sendMessageDelayed(mH.obtainMessage(H.APP_TRANSITION_TIMEOUT),
                        5000);
            }
        }
    }

    public int getPendingAppTransition() {
        return mNextAppTransition;
    }

    public void executeAppTransition() {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "executeAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_APP_TRANSITIONS) Log.v(
                    TAG, "Execute app transition: mNextAppTransition=" + mNextAppTransition);
            if (mNextAppTransition != WindowManagerPolicy.TRANSIT_NONE) {
                mAppTransitionReady = true;
                final long origId = Binder.clearCallingIdentity();
                performLayoutAndPlaceSurfacesLocked();
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void setAppStartingWindow(IBinder token, String pkg,
            int theme, CharSequence nonLocalizedLabel, int labelRes, int icon,
            IBinder transferFrom, boolean createIfNeeded) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppStartingIcon()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_STARTING_WINDOW) Log.v(
                    TAG, "setAppStartingIcon: token=" + token + " pkg=" + pkg
                    + " transferFrom=" + transferFrom);

            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Log.w(TAG, "Attempted to set icon of non-existing app token: " + token);
                return;
            }

            // If the display is frozen, we won't do anything until the
            // actual window is displayed so there is no reason to put in
            // the starting window.
            if (mDisplayFrozen) {
                return;
            }

            if (wtoken.startingData != null) {
                return;
            }

            if (transferFrom != null) {
                AppWindowToken ttoken = findAppWindowToken(transferFrom);
                if (ttoken != null) {
                    WindowState startingWindow = ttoken.startingWindow;
                    if (startingWindow != null) {
                        if (mStartingIconInTransition) {
                            // In this case, the starting icon has already
                            // been displayed, so start letting windows get
                            // shown immediately without any more transitions.
                            mSkipAppTransitionAnimation = true;
                        }
                        if (DEBUG_STARTING_WINDOW) Log.v(TAG,
                                "Moving existing starting from " + ttoken
                                + " to " + wtoken);
                        final long origId = Binder.clearCallingIdentity();

                        // Transfer the starting window over to the new
                        // token.
                        wtoken.startingData = ttoken.startingData;
                        wtoken.startingView = ttoken.startingView;
                        wtoken.startingWindow = startingWindow;
                        ttoken.startingData = null;
                        ttoken.startingView = null;
                        ttoken.startingWindow = null;
                        ttoken.startingMoved = true;
                        startingWindow.mToken = wtoken;
                        startingWindow.mRootToken = wtoken;
                        startingWindow.mAppToken = wtoken;
                        mWindows.remove(startingWindow);
                        ttoken.windows.remove(startingWindow);
                        ttoken.allAppWindows.remove(startingWindow);
                        addWindowToListInOrderLocked(startingWindow, true);
                        wtoken.allAppWindows.add(startingWindow);

                        // Propagate other interesting state between the
                        // tokens.  If the old token is displayed, we should
                        // immediately force the new one to be displayed.  If
                        // it is animating, we need to move that animation to
                        // the new one.
                        if (ttoken.allDrawn) {
                            wtoken.allDrawn = true;
                        }
                        if (ttoken.firstWindowDrawn) {
                            wtoken.firstWindowDrawn = true;
                        }
                        if (!ttoken.hidden) {
                            wtoken.hidden = false;
                            wtoken.hiddenRequested = false;
                            wtoken.willBeHidden = false;
                        }
                        if (wtoken.clientHidden != ttoken.clientHidden) {
                            wtoken.clientHidden = ttoken.clientHidden;
                            wtoken.sendAppVisibilityToClients();
                        }
                        if (ttoken.animation != null) {
                            wtoken.animation = ttoken.animation;
                            wtoken.animating = ttoken.animating;
                            wtoken.animLayerAdjustment = ttoken.animLayerAdjustment;
                            ttoken.animation = null;
                            ttoken.animLayerAdjustment = 0;
                            wtoken.updateLayers();
                            ttoken.updateLayers();
                        }

                        updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES);
                        mLayoutNeeded = true;
                        performLayoutAndPlaceSurfacesLocked();
                        Binder.restoreCallingIdentity(origId);
                        return;
                    } else if (ttoken.startingData != null) {
                        // The previous app was getting ready to show a
                        // starting window, but hasn't yet done so.  Steal it!
                        if (DEBUG_STARTING_WINDOW) Log.v(TAG,
                                "Moving pending starting from " + ttoken
                                + " to " + wtoken);
                        wtoken.startingData = ttoken.startingData;
                        ttoken.startingData = null;
                        ttoken.startingMoved = true;
                        Message m = mH.obtainMessage(H.ADD_STARTING, wtoken);
                        // Note: we really want to do sendMessageAtFrontOfQueue() because we
                        // want to process the message ASAP, before any other queued
                        // messages.
                        mH.sendMessageAtFrontOfQueue(m);
                        return;
                    }
                }
            }

            // There is no existing starting window, and the caller doesn't
            // want us to create one, so that's it!
            if (!createIfNeeded) {
                return;
            }

            mStartingIconInTransition = true;
            wtoken.startingData = new StartingData(
                    pkg, theme, nonLocalizedLabel,
                    labelRes, icon);
            Message m = mH.obtainMessage(H.ADD_STARTING, wtoken);
            // Note: we really want to do sendMessageAtFrontOfQueue() because we
            // want to process the message ASAP, before any other queued
            // messages.
            mH.sendMessageAtFrontOfQueue(m);
        }
    }

    public void setAppWillBeHidden(IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppWillBeHidden()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        AppWindowToken wtoken;

        synchronized(mWindowMap) {
            wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Log.w(TAG, "Attempted to set will be hidden of non-existing app token: " + token);
                return;
            }
            wtoken.willBeHidden = true;
        }
    }

    boolean setTokenVisibilityLocked(AppWindowToken wtoken, WindowManager.LayoutParams lp,
            boolean visible, int transit, boolean performLayout) {
        boolean delayed = false;

        if (wtoken.clientHidden == visible) {
            wtoken.clientHidden = !visible;
            wtoken.sendAppVisibilityToClients();
        }

        wtoken.willBeHidden = false;
        if (wtoken.hidden == visible) {
            final int N = wtoken.allAppWindows.size();
            boolean changed = false;
            if (DEBUG_APP_TRANSITIONS) Log.v(
                TAG, "Changing app " + wtoken + " hidden=" + wtoken.hidden
                + " performLayout=" + performLayout);

            boolean runningAppAnimation = false;

            if (transit != WindowManagerPolicy.TRANSIT_NONE) {
                if (wtoken.animation == sDummyAnimation) {
                    wtoken.animation = null;
                }
                applyAnimationLocked(wtoken, lp, transit, visible);
                changed = true;
                if (wtoken.animation != null) {
                    delayed = runningAppAnimation = true;
                }
            }

            for (int i=0; i<N; i++) {
                WindowState win = wtoken.allAppWindows.get(i);
                if (win == wtoken.startingWindow) {
                    continue;
                }

                if (win.isAnimating()) {
                    delayed = true;
                }

                //Log.i(TAG, "Window " + win + ": vis=" + win.isVisible());
                //win.dump("  ");
                if (visible) {
                    if (!win.isVisibleNow()) {
                        if (!runningAppAnimation) {
                            applyAnimationLocked(win,
                                    WindowManagerPolicy.TRANSIT_ENTER, true);
                        }
                        changed = true;
                    }
                } else if (win.isVisibleNow()) {
                    if (!runningAppAnimation) {
                        applyAnimationLocked(win,
                                WindowManagerPolicy.TRANSIT_EXIT, false);
                    }
                    mKeyWaiter.finishedKey(win.mSession, win.mClient, true,
                            KeyWaiter.RETURN_NOTHING);
                    changed = true;
                }
            }

            wtoken.hidden = wtoken.hiddenRequested = !visible;
            if (!visible) {
                unsetAppFreezingScreenLocked(wtoken, true, true);
            } else {
                // If we are being set visible, and the starting window is
                // not yet displayed, then make sure it doesn't get displayed.
                WindowState swin = wtoken.startingWindow;
                if (swin != null && (swin.mDrawPending
                        || swin.mCommitDrawPending)) {
                    swin.mPolicyVisibility = false;
                    swin.mPolicyVisibilityAfterAnim = false;
                 }
            }

            if (DEBUG_APP_TRANSITIONS) Log.v(TAG, "setTokenVisibilityLocked: " + wtoken
                      + ": hidden=" + wtoken.hidden + " hiddenRequested="
                      + wtoken.hiddenRequested);

            if (changed && performLayout) {
                mLayoutNeeded = true;
                updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES);
                performLayoutAndPlaceSurfacesLocked();
            }
        }

        if (wtoken.animation != null) {
            delayed = true;
        }

        return delayed;
    }

    public void setAppVisibility(IBinder token, boolean visible) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppVisibility()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        AppWindowToken wtoken;

        synchronized(mWindowMap) {
            wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Log.w(TAG, "Attempted to set visibility of non-existing app token: " + token);
                return;
            }

            if (DEBUG_APP_TRANSITIONS || DEBUG_ORIENTATION) {
                RuntimeException e = new RuntimeException();
                e.fillInStackTrace();
                Log.v(TAG, "setAppVisibility(" + token + ", " + visible
                        + "): mNextAppTransition=" + mNextAppTransition
                        + " hidden=" + wtoken.hidden
                        + " hiddenRequested=" + wtoken.hiddenRequested, e);
            }

            // If we are preparing an app transition, then delay changing
            // the visibility of this token until we execute that transition.
            if (!mDisplayFrozen && mNextAppTransition != WindowManagerPolicy.TRANSIT_NONE) {
                // Already in requested state, don't do anything more.
                if (wtoken.hiddenRequested != visible) {
                    return;
                }
                wtoken.hiddenRequested = !visible;

                if (DEBUG_APP_TRANSITIONS) Log.v(
                        TAG, "Setting dummy animation on: " + wtoken);
                wtoken.setDummyAnimation();
                mOpeningApps.remove(wtoken);
                mClosingApps.remove(wtoken);
                wtoken.inPendingTransaction = true;
                if (visible) {
                    mOpeningApps.add(wtoken);
                    wtoken.allDrawn = false;
                    wtoken.startingDisplayed = false;
                    wtoken.startingMoved = false;

                    if (wtoken.clientHidden) {
                        // In the case where we are making an app visible
                        // but holding off for a transition, we still need
                        // to tell the client to make its windows visible so
                        // they get drawn.  Otherwise, we will wait on
                        // performing the transition until all windows have
                        // been drawn, they never will be, and we are sad.
                        wtoken.clientHidden = false;
                        wtoken.sendAppVisibilityToClients();
                    }
                } else {
                    mClosingApps.add(wtoken);
                }
                return;
            }

            final long origId = Binder.clearCallingIdentity();
            setTokenVisibilityLocked(wtoken, null, visible, WindowManagerPolicy.TRANSIT_NONE, true);
            wtoken.updateReportedVisibilityLocked();
            Binder.restoreCallingIdentity(origId);
        }
    }

    void unsetAppFreezingScreenLocked(AppWindowToken wtoken,
            boolean unfreezeSurfaceNow, boolean force) {
        if (wtoken.freezingScreen) {
            if (DEBUG_ORIENTATION) Log.v(TAG, "Clear freezing of " + wtoken
                    + " force=" + force);
            final int N = wtoken.allAppWindows.size();
            boolean unfrozeWindows = false;
            for (int i=0; i<N; i++) {
                WindowState w = wtoken.allAppWindows.get(i);
                if (w.mAppFreezing) {
                    w.mAppFreezing = false;
                    if (w.mSurface != null && !w.mOrientationChanging) {
                        w.mOrientationChanging = true;
                    }
                    unfrozeWindows = true;
                }
            }
            if (force || unfrozeWindows) {
                if (DEBUG_ORIENTATION) Log.v(TAG, "No longer freezing: " + wtoken);
                wtoken.freezingScreen = false;
                mAppsFreezingScreen--;
            }
            if (unfreezeSurfaceNow) {
                if (unfrozeWindows) {
                    mLayoutNeeded = true;
                    performLayoutAndPlaceSurfacesLocked();
                }
                if (mAppsFreezingScreen == 0 && !mWindowsFreezingScreen) {
                    stopFreezingDisplayLocked();
                }
            }
        }
    }

    public void startAppFreezingScreenLocked(AppWindowToken wtoken,
            int configChanges) {
        if (DEBUG_ORIENTATION) {
            RuntimeException e = new RuntimeException();
            e.fillInStackTrace();
            Log.i(TAG, "Set freezing of " + wtoken.appToken
                    + ": hidden=" + wtoken.hidden + " freezing="
                    + wtoken.freezingScreen, e);
        }
        if (!wtoken.hiddenRequested) {
            if (!wtoken.freezingScreen) {
                wtoken.freezingScreen = true;
                mAppsFreezingScreen++;
                if (mAppsFreezingScreen == 1) {
                    startFreezingDisplayLocked();
                    mH.removeMessages(H.APP_FREEZE_TIMEOUT);
                    mH.sendMessageDelayed(mH.obtainMessage(H.APP_FREEZE_TIMEOUT),
                            5000);
                }
            }
            final int N = wtoken.allAppWindows.size();
            for (int i=0; i<N; i++) {
                WindowState w = wtoken.allAppWindows.get(i);
                w.mAppFreezing = true;
            }
        }
    }

    public void startAppFreezingScreen(IBinder token, int configChanges) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppFreezingScreen()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (configChanges == 0 && !mDisplayFrozen) {
                if (DEBUG_ORIENTATION) Log.v(TAG, "Skipping set freeze of " + token);
                return;
            }

            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null || wtoken.appToken == null) {
                Log.w(TAG, "Attempted to freeze screen with non-existing app token: " + wtoken);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            startAppFreezingScreenLocked(wtoken, configChanges);
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void stopAppFreezingScreen(IBinder token, boolean force) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppFreezingScreen()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null || wtoken.appToken == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            if (DEBUG_ORIENTATION) Log.v(TAG, "Clear freezing of " + token
                    + ": hidden=" + wtoken.hidden + " freezing=" + wtoken.freezingScreen);
            unsetAppFreezingScreenLocked(wtoken, true, force);
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void removeAppToken(IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "removeAppToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        AppWindowToken wtoken = null;
        AppWindowToken startingToken = null;
        boolean delayed = false;

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            WindowToken basewtoken = mTokenMap.remove(token);
            mTokenList.remove(basewtoken);
            if (basewtoken != null && (wtoken=basewtoken.appWindowToken) != null) {
                if (DEBUG_APP_TRANSITIONS) Log.v(TAG, "Removing app token: " + wtoken);
                delayed = setTokenVisibilityLocked(wtoken, null, false, WindowManagerPolicy.TRANSIT_NONE, true);
                wtoken.inPendingTransaction = false;
                mOpeningApps.remove(wtoken);
                if (mClosingApps.contains(wtoken)) {
                    delayed = true;
                } else if (mNextAppTransition != WindowManagerPolicy.TRANSIT_NONE) {
                    mClosingApps.add(wtoken);
                    delayed = true;
                }
                if (DEBUG_APP_TRANSITIONS) Log.v(
                        TAG, "Removing app " + wtoken + " delayed=" + delayed
                        + " animation=" + wtoken.animation
                        + " animating=" + wtoken.animating);
                if (delayed) {
                    // set the token aside because it has an active animation to be finished
                    mExitingAppTokens.add(wtoken);
                }
                mAppTokens.remove(wtoken);
                wtoken.removed = true;
                if (wtoken.startingData != null) {
                    startingToken = wtoken;
                }
                unsetAppFreezingScreenLocked(wtoken, true, true);
                if (mFocusedApp == wtoken) {
                    if (DEBUG_FOCUS) Log.v(TAG, "Removing focused app token:" + wtoken);
                    mFocusedApp = null;
                    updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL);
                    mKeyWaiter.tickle();
                }
            } else {
                Log.w(TAG, "Attempted to remove non-existing app token: " + token);
            }

            if (!delayed && wtoken != null) {
                wtoken.updateReportedVisibilityLocked();
            }
        }
        Binder.restoreCallingIdentity(origId);

        if (startingToken != null) {
            if (DEBUG_STARTING_WINDOW) Log.v(TAG, "Schedule remove starting "
                    + startingToken + ": app token removed");
            Message m = mH.obtainMessage(H.REMOVE_STARTING, startingToken);
            mH.sendMessage(m);
        }
    }

    private boolean tmpRemoveAppWindowsLocked(WindowToken token) {
        final int NW = token.windows.size();
        for (int i=0; i<NW; i++) {
            WindowState win = token.windows.get(i);
            mWindows.remove(win);
            int j = win.mChildWindows.size();
            while (j > 0) {
                j--;
                mWindows.remove(win.mChildWindows.get(j));
            }
        }
        return NW > 0;
    }

    void dumpAppTokensLocked() {
        for (int i=mAppTokens.size()-1; i>=0; i--) {
            Log.v(TAG, "  #" + i + ": " + mAppTokens.get(i).token);
        }
    }

    void dumpWindowsLocked() {
        for (int i=mWindows.size()-1; i>=0; i--) {
            Log.v(TAG, "  #" + i + ": " + mWindows.get(i));
        }
    }

    private int findWindowOffsetLocked(int tokenPos) {
        final int NW = mWindows.size();

        if (tokenPos >= mAppTokens.size()) {
            int i = NW;
            while (i > 0) {
                i--;
                WindowState win = (WindowState)mWindows.get(i);
                if (win.getAppToken() != null) {
                    return i+1;
                }
            }
        }

        while (tokenPos > 0) {
            // Find the first app token below the new position that has
            // a window displayed.
            final AppWindowToken wtoken = mAppTokens.get(tokenPos-1);
            if (DEBUG_REORDER) Log.v(TAG, "Looking for lower windows @ "
                    + tokenPos + " -- " + wtoken.token);
            int i = wtoken.windows.size();
            while (i > 0) {
                i--;
                WindowState win = wtoken.windows.get(i);
                int j = win.mChildWindows.size();
                while (j > 0) {
                    j--;
                    WindowState cwin = (WindowState)win.mChildWindows.get(j);
                    if (cwin.mSubLayer >= 0 ) {
                        for (int pos=NW-1; pos>=0; pos--) {
                            if (mWindows.get(pos) == cwin) {
                                if (DEBUG_REORDER) Log.v(TAG,
                                        "Found child win @" + (pos+1));
                                return pos+1;
                            }
                        }
                    }
                }
                for (int pos=NW-1; pos>=0; pos--) {
                    if (mWindows.get(pos) == win) {
                        if (DEBUG_REORDER) Log.v(TAG, "Found win @" + (pos+1));
                        return pos+1;
                    }
                }
            }
            tokenPos--;
        }

        return 0;
    }

    private final int reAddWindowLocked(int index, WindowState win) {
        final int NCW = win.mChildWindows.size();
        boolean added = false;
        for (int j=0; j<NCW; j++) {
            WindowState cwin = (WindowState)win.mChildWindows.get(j);
            if (!added && cwin.mSubLayer >= 0) {
                mWindows.add(index, win);
                index++;
                added = true;
            }
            mWindows.add(index, cwin);
            index++;
        }
        if (!added) {
            mWindows.add(index, win);
            index++;
        }
        return index;
    }

    private final int reAddAppWindowsLocked(int index, WindowToken token) {
        final int NW = token.windows.size();
        for (int i=0; i<NW; i++) {
            index = reAddWindowLocked(index, token.windows.get(i));
        }
        return index;
    }

    public void moveAppToken(int index, IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "moveAppToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_REORDER) Log.v(TAG, "Initial app tokens:");
            if (DEBUG_REORDER) dumpAppTokensLocked();
            final AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null || !mAppTokens.remove(wtoken)) {
                Log.w(TAG, "Attempting to reorder token that doesn't exist: "
                      + token + " (" + wtoken + ")");
                return;
            }
            mAppTokens.add(index, wtoken);
            if (DEBUG_REORDER) Log.v(TAG, "Moved " + token + " to " + index + ":");
            if (DEBUG_REORDER) dumpAppTokensLocked();

            final long origId = Binder.clearCallingIdentity();
            if (DEBUG_REORDER) Log.v(TAG, "Removing windows in " + token + ":");
            if (DEBUG_REORDER) dumpWindowsLocked();
            if (tmpRemoveAppWindowsLocked(wtoken)) {
                if (DEBUG_REORDER) Log.v(TAG, "Adding windows back in:");
                if (DEBUG_REORDER) dumpWindowsLocked();
                reAddAppWindowsLocked(findWindowOffsetLocked(index), wtoken);
                if (DEBUG_REORDER) Log.v(TAG, "Final window list:");
                if (DEBUG_REORDER) dumpWindowsLocked();
                updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES);
                mLayoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void removeAppTokensLocked(List<IBinder> tokens) {
        // XXX This should be done more efficiently!
        // (take advantage of the fact that both lists should be
        // ordered in the same way.)
        int N = tokens.size();
        for (int i=0; i<N; i++) {
            IBinder token = tokens.get(i);
            final AppWindowToken wtoken = findAppWindowToken(token);
            if (!mAppTokens.remove(wtoken)) {
                Log.w(TAG, "Attempting to reorder token that doesn't exist: "
                      + token + " (" + wtoken + ")");
                i--;
                N--;
            }
        }
    }

    private void moveAppWindowsLocked(List<IBinder> tokens, int tokenPos) {
        // First remove all of the windows from the list.
        final int N = tokens.size();
        int i;
        for (i=0; i<N; i++) {
            WindowToken token = mTokenMap.get(tokens.get(i));
            if (token != null) {
                tmpRemoveAppWindowsLocked(token);
            }
        }

        // Where to start adding?
        int pos = findWindowOffsetLocked(tokenPos);

        // And now add them back at the correct place.
        for (i=0; i<N; i++) {
            WindowToken token = mTokenMap.get(tokens.get(i));
            if (token != null) {
                pos = reAddAppWindowsLocked(pos, token);
            }
        }

        updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES);
        mLayoutNeeded = true;
        performLayoutAndPlaceSurfacesLocked();

        //dump();
    }

    public void moveAppTokensToTop(List<IBinder> tokens) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "moveAppTokensToTop()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            removeAppTokensLocked(tokens);
            final int N = tokens.size();
            for (int i=0; i<N; i++) {
                AppWindowToken wt = findAppWindowToken(tokens.get(i));
                if (wt != null) {
                    mAppTokens.add(wt);
                }
            }
            moveAppWindowsLocked(tokens, mAppTokens.size());
        }
        Binder.restoreCallingIdentity(origId);
    }

    public void moveAppTokensToBottom(List<IBinder> tokens) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "moveAppTokensToBottom()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            removeAppTokensLocked(tokens);
            final int N = tokens.size();
            int pos = 0;
            for (int i=0; i<N; i++) {
                AppWindowToken wt = findAppWindowToken(tokens.get(i));
                if (wt != null) {
                    mAppTokens.add(pos, wt);
                    pos++;
                }
            }
            moveAppWindowsLocked(tokens, 0);
        }
        Binder.restoreCallingIdentity(origId);
    }

    // -------------------------------------------------------------
    // Misc IWindowSession methods
    // -------------------------------------------------------------

    public void disableKeyguard(IBinder token, String tag) {
        if (mContext.checkCallingPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        mKeyguardDisabled.acquire(token, tag);
    }

    public void reenableKeyguard(IBinder token) {
        if (mContext.checkCallingPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        synchronized (mKeyguardDisabled) {
            mKeyguardDisabled.release(token);

            if (!mKeyguardDisabled.isAcquired()) {
                // if we are the last one to reenable the keyguard wait until
                // we have actaully finished reenabling until returning
                mWaitingUntilKeyguardReenabled = true;
                while (mWaitingUntilKeyguardReenabled) {
                    try {
                        mKeyguardDisabled.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * @see android.app.KeyguardManager#exitKeyguardSecurely
     */
    public void exitKeyguardSecurely(final IOnKeyguardExitResult callback) {
        if (mContext.checkCallingPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        mPolicy.exitKeyguardSecurely(new WindowManagerPolicy.OnKeyguardExitResult() {
            public void onKeyguardExitResult(boolean success) {
                try {
                    callback.onKeyguardExitResult(success);
                } catch (RemoteException e) {
                    // Client has died, we don't care.
                }
            }
        });
    }

    public boolean inKeyguardRestrictedInputMode() {
        return mPolicy.inKeyguardRestrictedKeyInputMode();
    }

    static float fixScale(float scale) {
        if (scale < 0) scale = 0;
        else if (scale > 20) scale = 20;
        return Math.abs(scale);
    }

    public void setAnimationScale(int which, float scale) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ANIMATION_SCALE,
                "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }

        if (scale < 0) scale = 0;
        else if (scale > 20) scale = 20;
        scale = Math.abs(scale);
        switch (which) {
            case 0: mWindowAnimationScale = fixScale(scale); break;
            case 1: mTransitionAnimationScale = fixScale(scale); break;
        }

        // Persist setting
        mH.obtainMessage(H.PERSIST_ANIMATION_SCALE).sendToTarget();
    }

    public void setAnimationScales(float[] scales) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ANIMATION_SCALE,
                "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }

        if (scales != null) {
            if (scales.length >= 1) {
                mWindowAnimationScale = fixScale(scales[0]);
            }
            if (scales.length >= 2) {
                mTransitionAnimationScale = fixScale(scales[1]);
            }
        }

        // Persist setting
        mH.obtainMessage(H.PERSIST_ANIMATION_SCALE).sendToTarget();
    }

    public float getAnimationScale(int which) {
        switch (which) {
            case 0: return mWindowAnimationScale;
            case 1: return mTransitionAnimationScale;
        }
        return 0;
    }

    public float[] getAnimationScales() {
        return new float[] { mWindowAnimationScale, mTransitionAnimationScale };
    }

    public int getSwitchState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getSwitchState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return KeyInputQueue.getSwitchState(sw);
    }

    public int getSwitchStateForDevice(int devid, int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getSwitchStateForDevice()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return KeyInputQueue.getSwitchState(devid, sw);
    }

    public int getScancodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getScancodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return KeyInputQueue.getScancodeState(sw);
    }

    public int getScancodeStateForDevice(int devid, int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getScancodeStateForDevice()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return KeyInputQueue.getScancodeState(devid, sw);
    }

    public int getKeycodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getKeycodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return KeyInputQueue.getKeycodeState(sw);
    }

    public int getKeycodeStateForDevice(int devid, int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getKeycodeStateForDevice()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return KeyInputQueue.getKeycodeState(devid, sw);
    }

    public boolean hasKeys(int[] keycodes, boolean[] keyExists) {
        return KeyInputQueue.hasKeys(keycodes, keyExists);
    }

    public void enableScreenAfterBoot() {
        synchronized(mWindowMap) {
            if (mSystemBooted) {
                return;
            }
            mSystemBooted = true;
        }

        performEnableScreen();
    }

    public void enableScreenIfNeededLocked() {
        if (mDisplayEnabled) {
            return;
        }
        if (!mSystemBooted) {
            return;
        }
        mH.sendMessage(mH.obtainMessage(H.ENABLE_SCREEN));
    }

    public void performEnableScreen() {
        synchronized(mWindowMap) {
            if (mDisplayEnabled) {
                return;
            }
            if (!mSystemBooted) {
                return;
            }

            // Don't enable the screen until all existing windows
            // have been drawn.
            final int N = mWindows.size();
            for (int i=0; i<N; i++) {
                WindowState w = (WindowState)mWindows.get(i);
                if (w.isVisibleLw() && !w.isDisplayedLw()) {
                    return;
                }
            }

            mDisplayEnabled = true;
            if (false) {
                Log.i(TAG, "ENABLING SCREEN!");
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                this.dump(null, pw, null);
                Log.i(TAG, sw.toString());
            }
            try {
                IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
                if (surfaceFlinger != null) {
                    //Log.i(TAG, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                    Parcel data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    surfaceFlinger.transact(IBinder.FIRST_CALL_TRANSACTION,
                                            data, null, 0);
                    data.recycle();
                }
            } catch (RemoteException ex) {
                Log.e(TAG, "Boot completed: SurfaceFlinger is dead!");
            }
        }

        mPolicy.enableScreenAfterBoot();

        // Make sure the last requested orientation has been applied.
        setRotationUnchecked(WindowManagerPolicy.USE_LAST_ROTATION, false,
                mLastRotationFlags | Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
    }

    public void setInTouchMode(boolean mode) {
        synchronized(mWindowMap) {
            mInTouchMode = mode;
        }
    }

    public void setRotation(int rotation,
            boolean alwaysSendConfiguration, int animFlags) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "setRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        setRotationUnchecked(rotation, alwaysSendConfiguration, animFlags);
    }

    public void setRotationUnchecked(int rotation,
            boolean alwaysSendConfiguration, int animFlags) {
        if(DEBUG_ORIENTATION) Log.v(TAG,
                "alwaysSendConfiguration set to "+alwaysSendConfiguration);

        long origId = Binder.clearCallingIdentity();
        boolean changed;
        synchronized(mWindowMap) {
            changed = setRotationUncheckedLocked(rotation, animFlags);
        }

        if (changed) {
            sendNewConfiguration();
            synchronized(mWindowMap) {
                mLayoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();
            }
        } else if (alwaysSendConfiguration) {
            //update configuration ignoring orientation change
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);
    }

    public boolean setRotationUncheckedLocked(int rotation, int animFlags) {
        boolean changed;
        if (rotation == WindowManagerPolicy.USE_LAST_ROTATION) {
            rotation = mRequestedRotation;
        } else {
            mRequestedRotation = rotation;
            mLastRotationFlags = animFlags;
        }
        if (DEBUG_ORIENTATION) Log.v(TAG, "Overwriting rotation value from " + rotation);
        rotation = mPolicy.rotationForOrientationLw(mForcedAppOrientation,
                mRotation, mDisplayEnabled);
        if (DEBUG_ORIENTATION) Log.v(TAG, "new rotation is set to " + rotation);
        changed = mDisplayEnabled && mRotation != rotation;

        if (changed) {
            if (DEBUG_ORIENTATION) Log.v(TAG,
                    "Rotation changed to " + rotation
                    + " from " + mRotation
                    + " (forceApp=" + mForcedAppOrientation
                    + ", req=" + mRequestedRotation + ")");
            mRotation = rotation;
            mWindowsFreezingScreen = true;
            mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
            mH.sendMessageDelayed(mH.obtainMessage(H.WINDOW_FREEZE_TIMEOUT),
                    2000);
            startFreezingDisplayLocked();
            Log.i(TAG, "Setting rotation to " + rotation + ", animFlags=" + animFlags);
            mQueue.setOrientation(rotation);
            if (mDisplayEnabled) {
                Surface.setOrientation(0, rotation, animFlags);
            }
            for (int i=mWindows.size()-1; i>=0; i--) {
                WindowState w = (WindowState)mWindows.get(i);
                if (w.mSurface != null) {
                    w.mOrientationChanging = true;
                }
            }
            for (int i=mRotationWatchers.size()-1; i>=0; i--) {
                try {
                    mRotationWatchers.get(i).onRotationChanged(rotation);
                } catch (RemoteException e) {
                }
            }
        } //end if changed

        return changed;
    }

    public int getRotation() {
        return mRotation;
    }

    public int watchRotation(IRotationWatcher watcher) {
        final IBinder watcherBinder = watcher.asBinder();
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            public void binderDied() {
                synchronized (mWindowMap) {
                    for (int i=0; i<mRotationWatchers.size(); i++) {
                        if (watcherBinder == mRotationWatchers.get(i).asBinder()) {
                            IRotationWatcher removed = mRotationWatchers.remove(i);
                            if (removed != null) {
                                removed.asBinder().unlinkToDeath(this, 0);
                            }
                            i--;
                        }
                    }
                }
            }
        };

        synchronized (mWindowMap) {
            try {
                watcher.asBinder().linkToDeath(dr, 0);
                mRotationWatchers.add(watcher);
            } catch (RemoteException e) {
                // Client died, no cleanup needed.
            }

            return mRotation;
        }
    }

    /**
     * Starts the view server on the specified port.
     *
     * @param port The port to listener to.
     *
     * @return True if the server was successfully started, false otherwise.
     *
     * @see com.android.server.ViewServer
     * @see com.android.server.ViewServer#VIEW_SERVER_DEFAULT_PORT
     */
    public boolean startViewServer(int port) {
        if (isSystemSecure()) {
            return false;
        }

        if (!checkCallingPermission(Manifest.permission.DUMP, "startViewServer")) {
            return false;
        }

        if (port < 1024) {
            return false;
        }

        if (mViewServer != null) {
            if (!mViewServer.isRunning()) {
                try {
                    return mViewServer.start();
                } catch (IOException e) {
                    Log.w(TAG, "View server did not start");
                }
            }
            return false;
        }

        try {
            mViewServer = new ViewServer(this, port);
            return mViewServer.start();
        } catch (IOException e) {
            Log.w(TAG, "View server did not start");
        }
        return false;
    }

    private boolean isSystemSecure() {
        return "1".equals(SystemProperties.get(SYSTEM_SECURE, "1")) &&
                "0".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
    }

    /**
     * Stops the view server if it exists.
     *
     * @return True if the server stopped, false if it wasn't started or
     *         couldn't be stopped.
     *
     * @see com.android.server.ViewServer
     */
    public boolean stopViewServer() {
        if (isSystemSecure()) {
            return false;
        }

        if (!checkCallingPermission(Manifest.permission.DUMP, "stopViewServer")) {
            return false;
        }

        if (mViewServer != null) {
            return mViewServer.stop();
        }
        return false;
    }

    /**
     * Indicates whether the view server is running.
     *
     * @return True if the server is running, false otherwise.
     *
     * @see com.android.server.ViewServer
     */
    public boolean isViewServerRunning() {
        if (isSystemSecure()) {
            return false;
        }

        if (!checkCallingPermission(Manifest.permission.DUMP, "isViewServerRunning")) {
            return false;
        }

        return mViewServer != null && mViewServer.isRunning();
    }

    /**
     * Lists all availble windows in the system. The listing is written in the
     * specified Socket's output stream with the following syntax:
     * windowHashCodeInHexadecimal windowName
     * Each line of the ouput represents a different window.
     *
     * @param client The remote client to send the listing to.
     * @return False if an error occured, true otherwise.
     */
    boolean viewServerListWindows(Socket client) {
        if (isSystemSecure()) {
            return false;
        }

        boolean result = true;

        Object[] windows;
        synchronized (mWindowMap) {
            windows = new Object[mWindows.size()];
            //noinspection unchecked
            windows = mWindows.toArray(windows);
        }

        BufferedWriter out = null;

        // Any uncaught exception will crash the system process
        try {
            OutputStream clientStream = client.getOutputStream();
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);

            final int count = windows.length;
            for (int i = 0; i < count; i++) {
                final WindowState w = (WindowState) windows[i];
                out.write(Integer.toHexString(System.identityHashCode(w)));
                out.write(' ');
                out.append(w.mAttrs.getTitle());
                out.write('\n');
            }

            out.write("DONE.\n");
            out.flush();
        } catch (Exception e) {
            result = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    result = false;
                }
            }
        }

        return result;
    }

    /**
     * Sends a command to a target window. The result of the command, if any, will be
     * written in the output stream of the specified socket.
     *
     * The parameters must follow this syntax:
     * windowHashcode extra
     *
     * Where XX is the length in characeters of the windowTitle.
     *
     * The first parameter is the target window. The window with the specified hashcode
     * will be the target. If no target can be found, nothing happens. The extra parameters
     * will be delivered to the target window and as parameters to the command itself.
     *
     * @param client The remote client to sent the result, if any, to.
     * @param command The command to execute.
     * @param parameters The command parameters.
     *
     * @return True if the command was successfully delivered, false otherwise. This does
     *         not indicate whether the command itself was successful.
     */
    boolean viewServerWindowCommand(Socket client, String command, String parameters) {
        if (isSystemSecure()) {
            return false;
        }

        boolean success = true;
        Parcel data = null;
        Parcel reply = null;

        // Any uncaught exception will crash the system process
        try {
            // Find the hashcode of the window
            int index = parameters.indexOf(' ');
            if (index == -1) {
                index = parameters.length();
            }
            final String code = parameters.substring(0, index);
            int hashCode = "ffffffff".equals(code) ? -1 : Integer.parseInt(code, 16);

            // Extract the command's parameter after the window description
            if (index < parameters.length()) {
                parameters = parameters.substring(index + 1);
            } else {
                parameters = "";
            }

            final WindowManagerService.WindowState window = findWindow(hashCode);
            if (window == null) {
                return false;
            }

            data = Parcel.obtain();
            data.writeInterfaceToken("android.view.IWindow");
            data.writeString(command);
            data.writeString(parameters);
            data.writeInt(1);
            ParcelFileDescriptor.fromSocket(client).writeToParcel(data, 0);

            reply = Parcel.obtain();

            final IBinder binder = window.mClient.asBinder();
            // TODO: GET THE TRANSACTION CODE IN A SAFER MANNER
            binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);

            reply.readException();

        } catch (Exception e) {
            Log.w(TAG, "Could not send command " + command + " with parameters " + parameters, e);
            success = false;
        } finally {
            if (data != null) {
                data.recycle();
            }
            if (reply != null) {
                reply.recycle();
            }
        }

        return success;
    }

    private WindowState findWindow(int hashCode) {
        if (hashCode == -1) {
            return getFocusedWindow();
        }

        synchronized (mWindowMap) {
            final ArrayList windows = mWindows;
            final int count = windows.size();

            for (int i = 0; i < count; i++) {
                WindowState w = (WindowState) windows.get(i);
                if (System.identityHashCode(w) == hashCode) {
                    return w;
                }
            }
        }

        return null;
    }

    /*
     * Instruct the Activity Manager to fetch the current configuration and broadcast
     * that to config-changed listeners if appropriate.
     */
    void sendNewConfiguration() {
        try {
            mActivityManager.updateConfiguration(null);
        } catch (RemoteException e) {
        }
    }

    public Configuration computeNewConfiguration() {
        synchronized (mWindowMap) {
            return computeNewConfigurationLocked();
        }
    }

    Configuration computeNewConfigurationLocked() {
        Configuration config = new Configuration();
        if (!computeNewConfigurationLocked(config)) {
            return null;
        }
        Log.i(TAG, "Config changed: " + config);
        long now = SystemClock.uptimeMillis();
        //Log.i(TAG, "Config changing, gc pending: " + mFreezeGcPending + ", now " + now);
        if (mFreezeGcPending != 0) {
            if (now > (mFreezeGcPending+1000)) {
                //Log.i(TAG, "Gc!  " + now + " > " + (mFreezeGcPending+1000));
                mH.removeMessages(H.FORCE_GC);
                Runtime.getRuntime().gc();
                mFreezeGcPending = now;
            }
        } else {
            mFreezeGcPending = now;
        }
        return config;
    }

    boolean computeNewConfigurationLocked(Configuration config) {
        if (mDisplay == null) {
            return false;
        }
        mQueue.getInputConfiguration(config);
        final int dw = mDisplay.getWidth();
        final int dh = mDisplay.getHeight();
        int orientation = Configuration.ORIENTATION_SQUARE;
        if (dw < dh) {
            orientation = Configuration.ORIENTATION_PORTRAIT;
        } else if (dw > dh) {
            orientation = Configuration.ORIENTATION_LANDSCAPE;
        }
        config.orientation = orientation;
        
        DisplayMetrics dm = new DisplayMetrics();
        mDisplay.getMetrics(dm);
        CompatibilityInfo.updateCompatibleScreenFrame(dm, orientation, mCompatibleScreenFrame);

        if (mScreenLayout == Configuration.SCREENLAYOUT_SIZE_UNDEFINED) {
            // Note we only do this once because at this point we don't
            // expect the screen to change in this way at runtime, and want
            // to avoid all of this computation for every config change.
            int longSize = dw;
            int shortSize = dh;
            if (longSize < shortSize) {
                int tmp = longSize;
                longSize = shortSize;
                shortSize = tmp;
            }
            longSize = (int)(longSize/dm.density);
            shortSize = (int)(shortSize/dm.density);

            // These semi-magic numbers define our compatibility modes for
            // applications with different screens.  Don't change unless you
            // make sure to test lots and lots of apps!
            if (longSize < 470) {
                // This is shorter than an HVGA normal density screen (which
                // is 480 pixels on its long side).
                mScreenLayout = Configuration.SCREENLAYOUT_SIZE_SMALL
                        | Configuration.SCREENLAYOUT_LONG_NO;
            } else {
                // Is this a large screen?
                if (longSize > 640 && shortSize >= 480) {
                    // VGA or larger screens at medium density are the point
                    // at which we consider it to be a large screen.
                    mScreenLayout = Configuration.SCREENLAYOUT_SIZE_LARGE;
                } else {
                    mScreenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL;
                    
                    // If this screen is wider than normal HVGA, or taller
                    // than FWVGA, then for old apps we want to run in size
                    // compatibility mode.
                    if (shortSize > 321 || longSize > 570) {
                        mScreenLayout |= Configuration.SCREENLAYOUT_COMPAT_NEEDED;
                    }
                }
                
                // Is this a long screen?
                if (((longSize*3)/5) >= (shortSize-1)) {
                    // Anything wider than WVGA (5:3) is considering to be long.
                    mScreenLayout |= Configuration.SCREENLAYOUT_LONG_YES;
                } else {
                    mScreenLayout |= Configuration.SCREENLAYOUT_LONG_NO;
                }
            }
        }
        config.screenLayout = mScreenLayout;
        
        config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        mPolicy.adjustConfigurationLw(config);
        return true;
    }

    // -------------------------------------------------------------
    // Input Events and Focus Management
    // -------------------------------------------------------------

    private final void wakeupIfNeeded(WindowState targetWin, int eventType) {
        long curTime = SystemClock.uptimeMillis();

        if (eventType == LONG_TOUCH_EVENT || eventType == CHEEK_EVENT) {
            if (mLastTouchEventType == eventType &&
                    (curTime - mLastUserActivityCallTime) < MIN_TIME_BETWEEN_USERACTIVITIES) {
                return;
            }
            mLastUserActivityCallTime = curTime;
            mLastTouchEventType = eventType;
        }

        if (targetWin == null
                || targetWin.mAttrs.type != WindowManager.LayoutParams.TYPE_KEYGUARD) {
            mPowerManager.userActivity(curTime, false, eventType, false);
        }
    }

    // tells if it's a cheek event or not -- this function is stateful
    private static final int EVENT_NONE = 0;
    private static final int EVENT_UNKNOWN = 0;
    private static final int EVENT_CHEEK = 0;
    private static final int EVENT_IGNORE_DURATION = 300; // ms
    private static final float CHEEK_THRESHOLD = 0.6f;
    private int mEventState = EVENT_NONE;
    private float mEventSize;

    private int eventType(MotionEvent ev) {
        float size = ev.getSize();
        switch (ev.getAction()) {
        case MotionEvent.ACTION_DOWN:
            mEventSize = size;
            return (mEventSize > CHEEK_THRESHOLD) ? CHEEK_EVENT : TOUCH_EVENT;
        case MotionEvent.ACTION_UP:
            if (size > mEventSize) mEventSize = size;
            return (mEventSize > CHEEK_THRESHOLD) ? CHEEK_EVENT : TOUCH_UP_EVENT;
        case MotionEvent.ACTION_MOVE:
            final int N = ev.getHistorySize();
            if (size > mEventSize) mEventSize = size;
            if (mEventSize > CHEEK_THRESHOLD) return CHEEK_EVENT;
            for (int i=0; i<N; i++) {
                size = ev.getHistoricalSize(i);
                if (size > mEventSize) mEventSize = size;
                if (mEventSize > CHEEK_THRESHOLD) return CHEEK_EVENT;
            }
            if (ev.getEventTime() < ev.getDownTime() + EVENT_IGNORE_DURATION) {
                return TOUCH_EVENT;
            } else {
                return LONG_TOUCH_EVENT;
            }
        default:
            // not good
            return OTHER_EVENT;
        }
    }

    /**
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    private int dispatchPointer(QueuedEvent qev, MotionEvent ev, int pid, int uid) {
        if (DEBUG_INPUT || WindowManagerPolicy.WATCH_POINTER) Log.v(TAG,
                "dispatchPointer " + ev);

        Object targetObj = mKeyWaiter.waitForNextEventTarget(null, qev,
                ev, true, false, pid, uid);

        int action = ev.getAction();

        if (action == MotionEvent.ACTION_UP) {
            // let go of our target
            mKeyWaiter.mMotionTarget = null;
            mPowerManager.logPointerUpEvent();
        } else if (action == MotionEvent.ACTION_DOWN) {
            mPowerManager.logPointerDownEvent();
        }

        if (targetObj == null) {
            // In this case we are either dropping the event, or have received
            // a move or up without a down.  It is common to receive move
            // events in such a way, since this means the user is moving the
            // pointer without actually pressing down.  All other cases should
            // be atypical, so let's log them.
            if (action != MotionEvent.ACTION_MOVE) {
                Log.w(TAG, "No window to dispatch pointer action " + ev.getAction());
            }
            if (qev != null) {
                mQueue.recycleEvent(qev);
            }
            ev.recycle();
            return INJECT_FAILED;
        }
        if (targetObj == mKeyWaiter.CONSUMED_EVENT_TOKEN) {
            if (qev != null) {
                mQueue.recycleEvent(qev);
            }
            ev.recycle();
            return INJECT_SUCCEEDED;
        }

        WindowState target = (WindowState)targetObj;

        final long eventTime = ev.getEventTime();

        //Log.i(TAG, "Sending " + ev + " to " + target);

        if (uid != 0 && uid != target.mSession.mUid) {
            if (mContext.checkPermission(
                    android.Manifest.permission.INJECT_EVENTS, pid, uid)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission denied: injecting pointer event from pid "
                        + pid + " uid " + uid + " to window " + target
                        + " owned by uid " + target.mSession.mUid);
                if (qev != null) {
                    mQueue.recycleEvent(qev);
                }
                ev.recycle();
                return INJECT_NO_PERMISSION;
            }
        }

        if ((target.mAttrs.flags &
                        WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES) != 0) {
            //target wants to ignore fat touch events
            boolean cheekPress = mPolicy.isCheekPressedAgainstScreen(ev);
            //explicit flag to return without processing event further
            boolean returnFlag = false;
            if((action == MotionEvent.ACTION_DOWN)) {
                mFatTouch = false;
                if(cheekPress) {
                    mFatTouch = true;
                    returnFlag = true;
                }
            } else {
                if(action == MotionEvent.ACTION_UP) {
                    if(mFatTouch) {
                        //earlier even was invalid doesnt matter if current up is cheekpress or not
                        mFatTouch = false;
                        returnFlag = true;
                    } else if(cheekPress) {
                        //cancel the earlier event
                        ev.setAction(MotionEvent.ACTION_CANCEL);
                        action = MotionEvent.ACTION_CANCEL;
                    }
                } else if(action == MotionEvent.ACTION_MOVE) {
                    if(mFatTouch) {
                        //two cases here
                        //an invalid down followed by 0 or moves(valid or invalid)
                        //a valid down,  invalid move, more moves. want to ignore till up
                        returnFlag = true;
                    } else if(cheekPress) {
                        //valid down followed by invalid moves
                        //an invalid move have to cancel earlier action
                        ev.setAction(MotionEvent.ACTION_CANCEL);
                        action = MotionEvent.ACTION_CANCEL;
                        if (DEBUG_INPUT) Log.v(TAG, "Sending cancel for invalid ACTION_MOVE");
                        //note that the subsequent invalid moves will not get here
                        mFatTouch = true;
                    }
                }
            } //else if action
            if(returnFlag) {
                //recycle que, ev
                if (qev != null) {
                    mQueue.recycleEvent(qev);
                }
                ev.recycle();
                return INJECT_FAILED;
            }
        } //end if target

        // Enable this for testing the "right" value
        if (false && action == MotionEvent.ACTION_DOWN) {
            int max_events_per_sec = 35;
            try {
                max_events_per_sec = Integer.parseInt(SystemProperties
                        .get("windowsmgr.max_events_per_sec"));
                if (max_events_per_sec < 1) {
                    max_events_per_sec = 35;
                }
            } catch (NumberFormatException e) {
            }
            mMinWaitTimeBetweenTouchEvents = 1000 / max_events_per_sec;
        }

        /*
         * Throttle events to minimize CPU usage when there's a flood of events
         * e.g. constant contact with the screen
         */
        if (action == MotionEvent.ACTION_MOVE) {
            long nextEventTime = mLastTouchEventTime + mMinWaitTimeBetweenTouchEvents;
            long now = SystemClock.uptimeMillis();
            if (now < nextEventTime) {
                try {
                    Thread.sleep(nextEventTime - now);
                } catch (InterruptedException e) {
                }
                mLastTouchEventTime = nextEventTime;
            } else {
                mLastTouchEventTime = now;
            }
        }

        synchronized(mWindowMap) {
            if (qev != null && action == MotionEvent.ACTION_MOVE) {
                mKeyWaiter.bindTargetWindowLocked(target,
                        KeyWaiter.RETURN_PENDING_POINTER, qev);
                ev = null;
            } else {
                if (action == MotionEvent.ACTION_DOWN) {
                    WindowState out = mKeyWaiter.mOutsideTouchTargets;
                    if (out != null) {
                        MotionEvent oev = MotionEvent.obtain(ev);
                        oev.setAction(MotionEvent.ACTION_OUTSIDE);
                        do {
                            final Rect frame = out.mFrame;
                            oev.offsetLocation(-(float)frame.left, -(float)frame.top);
                            try {
                                out.mClient.dispatchPointer(oev, eventTime);
                            } catch (android.os.RemoteException e) {
                                Log.i(TAG, "WINDOW DIED during outside motion dispatch: " + out);
                            }
                            oev.offsetLocation((float)frame.left, (float)frame.top);
                            out = out.mNextOutsideTouch;
                        } while (out != null);
                        mKeyWaiter.mOutsideTouchTargets = null;
                    }
                }
                final Rect frame = target.mFrame;
                ev.offsetLocation(-(float)frame.left, -(float)frame.top);
                mKeyWaiter.bindTargetWindowLocked(target);
            }
        }

        // finally offset the event to the target's coordinate system and
        // dispatch the event.
        try {
            if (DEBUG_INPUT || DEBUG_FOCUS || WindowManagerPolicy.WATCH_POINTER) {
                Log.v(TAG, "Delivering pointer " + qev + " to " + target);
            }
            target.mClient.dispatchPointer(ev, eventTime);
            return INJECT_SUCCEEDED;
        } catch (android.os.RemoteException e) {
            Log.i(TAG, "WINDOW DIED during motion dispatch: " + target);
            mKeyWaiter.mMotionTarget = null;
            try {
                removeWindow(target.mSession, target.mClient);
            } catch (java.util.NoSuchElementException ex) {
                // This will happen if the window has already been
                // removed.
            }
        }
        return INJECT_FAILED;
    }

    /**
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    private int dispatchTrackball(QueuedEvent qev, MotionEvent ev, int pid, int uid) {
        if (DEBUG_INPUT) Log.v(
                TAG, "dispatchTrackball [" + ev.getAction() +"] <" + ev.getX() + ", " + ev.getY() + ">");

        Object focusObj = mKeyWaiter.waitForNextEventTarget(null, qev,
                ev, false, false, pid, uid);
        if (focusObj == null) {
            Log.w(TAG, "No focus window, dropping trackball: " + ev);
            if (qev != null) {
                mQueue.recycleEvent(qev);
            }
            ev.recycle();
            return INJECT_FAILED;
        }
        if (focusObj == mKeyWaiter.CONSUMED_EVENT_TOKEN) {
            if (qev != null) {
                mQueue.recycleEvent(qev);
            }
            ev.recycle();
            return INJECT_SUCCEEDED;
        }

        WindowState focus = (WindowState)focusObj;

        if (uid != 0 && uid != focus.mSession.mUid) {
            if (mContext.checkPermission(
                    android.Manifest.permission.INJECT_EVENTS, pid, uid)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission denied: injecting key event from pid "
                        + pid + " uid " + uid + " to window " + focus
                        + " owned by uid " + focus.mSession.mUid);
                if (qev != null) {
                    mQueue.recycleEvent(qev);
                }
                ev.recycle();
                return INJECT_NO_PERMISSION;
            }
        }

        final long eventTime = ev.getEventTime();

        synchronized(mWindowMap) {
            if (qev != null && ev.getAction() == MotionEvent.ACTION_MOVE) {
                mKeyWaiter.bindTargetWindowLocked(focus,
                        KeyWaiter.RETURN_PENDING_TRACKBALL, qev);
                // We don't deliver movement events to the client, we hold
                // them and wait for them to call back.
                ev = null;
            } else {
                mKeyWaiter.bindTargetWindowLocked(focus);
            }
        }

        try {
            focus.mClient.dispatchTrackball(ev, eventTime);
            return INJECT_SUCCEEDED;
        } catch (android.os.RemoteException e) {
            Log.i(TAG, "WINDOW DIED during key dispatch: " + focus);
            try {
                removeWindow(focus.mSession, focus.mClient);
            } catch (java.util.NoSuchElementException ex) {
                // This will happen if the window has already been
                // removed.
            }
        }

        return INJECT_FAILED;
    }

    /**
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    private int dispatchKey(KeyEvent event, int pid, int uid) {
        if (DEBUG_INPUT) Log.v(TAG, "Dispatch key: " + event);

        Object focusObj = mKeyWaiter.waitForNextEventTarget(event, null,
                null, false, false, pid, uid);
        if (focusObj == null) {
            Log.w(TAG, "No focus window, dropping: " + event);
            return INJECT_FAILED;
        }
        if (focusObj == mKeyWaiter.CONSUMED_EVENT_TOKEN) {
            return INJECT_SUCCEEDED;
        }

        WindowState focus = (WindowState)focusObj;

        if (DEBUG_INPUT) Log.v(
            TAG, "Dispatching to " + focus + ": " + event);

        if (uid != 0 && uid != focus.mSession.mUid) {
            if (mContext.checkPermission(
                    android.Manifest.permission.INJECT_EVENTS, pid, uid)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission denied: injecting key event from pid "
                        + pid + " uid " + uid + " to window " + focus
                        + " owned by uid " + focus.mSession.mUid);
                return INJECT_NO_PERMISSION;
            }
        }

        synchronized(mWindowMap) {
            mKeyWaiter.bindTargetWindowLocked(focus);
        }

        // NOSHIP extra state logging
        mKeyWaiter.recordDispatchState(event, focus);
        // END NOSHIP

        try {
            if (DEBUG_INPUT || DEBUG_FOCUS) {
                Log.v(TAG, "Delivering key " + event.getKeyCode()
                        + " to " + focus);
            }
            focus.mClient.dispatchKey(event);
            return INJECT_SUCCEEDED;
        } catch (android.os.RemoteException e) {
            Log.i(TAG, "WINDOW DIED during key dispatch: " + focus);
            try {
                removeWindow(focus.mSession, focus.mClient);
            } catch (java.util.NoSuchElementException ex) {
                // This will happen if the window has already been
                // removed.
            }
        }

        return INJECT_FAILED;
    }

    public void pauseKeyDispatching(IBinder _token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "pauseKeyDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mWindowMap) {
            WindowToken token = mTokenMap.get(_token);
            if (token != null) {
                mKeyWaiter.pauseDispatchingLocked(token);
            }
        }
    }

    public void resumeKeyDispatching(IBinder _token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "resumeKeyDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mWindowMap) {
            WindowToken token = mTokenMap.get(_token);
            if (token != null) {
                mKeyWaiter.resumeDispatchingLocked(token);
            }
        }
    }

    public void setEventDispatching(boolean enabled) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "resumeKeyDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mWindowMap) {
            mKeyWaiter.setEventDispatchingLocked(enabled);
        }
    }

    /**
     * Injects a keystroke event into the UI.
     *
     * @param ev A motion event describing the keystroke action.  (Be sure to use
     * {@link SystemClock#uptimeMillis()} as the timebase.)
     * @param sync If true, wait for the event to be completed before returning to the caller.
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    public boolean injectKeyEvent(KeyEvent ev, boolean sync) {
        long downTime = ev.getDownTime();
        long eventTime = ev.getEventTime();

        int action = ev.getAction();
        int code = ev.getKeyCode();
        int repeatCount = ev.getRepeatCount();
        int metaState = ev.getMetaState();
        int deviceId = ev.getDeviceId();
        int scancode = ev.getScanCode();

        if (eventTime == 0) eventTime = SystemClock.uptimeMillis();
        if (downTime == 0) downTime = eventTime;

        KeyEvent newEvent = new KeyEvent(downTime, eventTime, action, code, repeatCount, metaState,
                deviceId, scancode, KeyEvent.FLAG_FROM_SYSTEM);

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        final int result = dispatchKey(newEvent, pid, uid);
        if (sync) {
            mKeyWaiter.waitForNextEventTarget(null, null, null, false, true, pid, uid);
        }
        Binder.restoreCallingIdentity(ident);
        switch (result) {
            case INJECT_NO_PERMISSION:
                throw new SecurityException(
                        "Injecting to another application requires INJECT_EVENT permission");
            case INJECT_SUCCEEDED:
                return true;
        }
        return false;
    }

    /**
     * Inject a pointer (touch) event into the UI.
     *
     * @param ev A motion event describing the pointer (touch) action.  (As noted in
     * {@link MotionEvent#obtain(long, long, int, float, float, int)}, be sure to use
     * {@link SystemClock#uptimeMillis()} as the timebase.)
     * @param sync If true, wait for the event to be completed before returning to the caller.
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    public boolean injectPointerEvent(MotionEvent ev, boolean sync) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        final int result = dispatchPointer(null, ev, pid, uid);
        if (sync) {
            mKeyWaiter.waitForNextEventTarget(null, null, null, false, true, pid, uid);
        }
        Binder.restoreCallingIdentity(ident);
        switch (result) {
            case INJECT_NO_PERMISSION:
                throw new SecurityException(
                        "Injecting to another application requires INJECT_EVENT permission");
            case INJECT_SUCCEEDED:
                return true;
        }
        return false;
    }

    /**
     * Inject a trackball (navigation device) event into the UI.
     *
     * @param ev A motion event describing the trackball action.  (As noted in
     * {@link MotionEvent#obtain(long, long, int, float, float, int)}, be sure to use
     * {@link SystemClock#uptimeMillis()} as the timebase.)
     * @param sync If true, wait for the event to be completed before returning to the caller.
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    public boolean injectTrackballEvent(MotionEvent ev, boolean sync) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        final int result = dispatchTrackball(null, ev, pid, uid);
        if (sync) {
            mKeyWaiter.waitForNextEventTarget(null, null, null, false, true, pid, uid);
        }
        Binder.restoreCallingIdentity(ident);
        switch (result) {
            case INJECT_NO_PERMISSION:
                throw new SecurityException(
                        "Injecting to another application requires INJECT_EVENT permission");
            case INJECT_SUCCEEDED:
                return true;
        }
        return false;
    }

    private WindowState getFocusedWindow() {
        synchronized (mWindowMap) {
            return getFocusedWindowLocked();
        }
    }

    private WindowState getFocusedWindowLocked() {
        return mCurrentFocus;
    }

    /**
     * This class holds the state for dispatching key events.  This state
     * is protected by the KeyWaiter instance, NOT by the window lock.  You
     * can be holding the main window lock while acquire the KeyWaiter lock,
     * but not the other way around.
     */
    final class KeyWaiter {
        // NOSHIP debugging
        public class DispatchState {
            private KeyEvent event;
            private WindowState focus;
            private long time;
            private WindowState lastWin;
            private IBinder lastBinder;
            private boolean finished;
            private boolean gotFirstWindow;
            private boolean eventDispatching;
            private long timeToSwitch;
            private boolean wasFrozen;
            private boolean focusPaused;
            private WindowState curFocus;

            DispatchState(KeyEvent theEvent, WindowState theFocus) {
                focus = theFocus;
                event = theEvent;
                time = System.currentTimeMillis();
                // snapshot KeyWaiter state
                lastWin = mLastWin;
                lastBinder = mLastBinder;
                finished = mFinished;
                gotFirstWindow = mGotFirstWindow;
                eventDispatching = mEventDispatching;
                timeToSwitch = mTimeToSwitch;
                wasFrozen = mWasFrozen;
                curFocus = mCurrentFocus;
                // cache the paused state at ctor time as well
                if (theFocus == null || theFocus.mToken == null) {
                    Log.i(TAG, "focus " + theFocus + " mToken is null at event dispatch!");
                    focusPaused = false;
                } else {
                    focusPaused = theFocus.mToken.paused;
                }
            }

            public String toString() {
                return "{{" + event + " to " + focus + " @ " + time
                        + " lw=" + lastWin + " lb=" + lastBinder
                        + " fin=" + finished + " gfw=" + gotFirstWindow
                        + " ed=" + eventDispatching + " tts=" + timeToSwitch
                        + " wf=" + wasFrozen + " fp=" + focusPaused
                        + " mcf=" + mCurrentFocus + "}}";
            }
        };
        private DispatchState mDispatchState = null;
        public void recordDispatchState(KeyEvent theEvent, WindowState theFocus) {
            mDispatchState = new DispatchState(theEvent, theFocus);
        }
        // END NOSHIP

        public static final int RETURN_NOTHING = 0;
        public static final int RETURN_PENDING_POINTER = 1;
        public static final int RETURN_PENDING_TRACKBALL = 2;

        final Object SKIP_TARGET_TOKEN = new Object();
        final Object CONSUMED_EVENT_TOKEN = new Object();

        private WindowState mLastWin = null;
        private IBinder mLastBinder = null;
        private boolean mFinished = true;
        private boolean mGotFirstWindow = false;
        private boolean mEventDispatching = true;
        private long mTimeToSwitch = 0;
        /* package */ boolean mWasFrozen = false;

        // Target of Motion events
        WindowState mMotionTarget;

        // Windows above the target who would like to receive an "outside"
        // touch event for any down events outside of them.
        WindowState mOutsideTouchTargets;

        /**
         * Wait for the last event dispatch to complete, then find the next
         * target that should receive the given event and wait for that one
         * to be ready to receive it.
         */
        Object waitForNextEventTarget(KeyEvent nextKey, QueuedEvent qev,
                MotionEvent nextMotion, boolean isPointerEvent,
                boolean failIfTimeout, int callingPid, int callingUid) {
            long startTime = SystemClock.uptimeMillis();
            long keyDispatchingTimeout = 5 * 1000;
            long waitedFor = 0;

            while (true) {
                // Figure out which window we care about.  It is either the
                // last window we are waiting to have process the event or,
                // if none, then the next window we think the event should go
                // to.  Note: we retrieve mLastWin outside of the lock, so
                // it may change before we lock.  Thus we must check it again.
                WindowState targetWin = mLastWin;
                boolean targetIsNew = targetWin == null;
                if (DEBUG_INPUT) Log.v(
                        TAG, "waitForLastKey: mFinished=" + mFinished +
                        ", mLastWin=" + mLastWin);
                if (targetIsNew) {
                    Object target = findTargetWindow(nextKey, qev, nextMotion,
                            isPointerEvent, callingPid, callingUid);
                    if (target == SKIP_TARGET_TOKEN) {
                        // The user has pressed a special key, and we are
                        // dropping all pending events before it.
                        if (DEBUG_INPUT) Log.v(TAG, "Skipping: " + nextKey
                                + " " + nextMotion);
                        return null;
                    }
                    if (target == CONSUMED_EVENT_TOKEN) {
                        if (DEBUG_INPUT) Log.v(TAG, "Consumed: " + nextKey
                                + " " + nextMotion);
                        return target;
                    }
                    targetWin = (WindowState)target;
                }

                AppWindowToken targetApp = null;

                // Now: is it okay to send the next event to this window?
                synchronized (this) {
                    // First: did we come here based on the last window not
                    // being null, but it changed by the time we got here?
                    // If so, try again.
                    if (!targetIsNew && mLastWin == null) {
                        continue;
                    }

                    // We never dispatch events if not finished with the
                    // last one, or the display is frozen.
                    if (mFinished && !mDisplayFrozen) {
                        // If event dispatching is disabled, then we
                        // just consume the events.
                        if (!mEventDispatching) {
                            if (DEBUG_INPUT) Log.v(TAG,
                                    "Skipping event; dispatching disabled: "
                                    + nextKey + " " + nextMotion);
                            return null;
                        }
                        if (targetWin != null) {
                            // If this is a new target, and that target is not
                            // paused or unresponsive, then all looks good to
                            // handle the event.
                            if (targetIsNew && !targetWin.mToken.paused) {
                                return targetWin;
                            }

                        // If we didn't find a target window, and there is no
                        // focused app window, then just eat the events.
                        } else if (mFocusedApp == null) {
                            if (DEBUG_INPUT) Log.v(TAG,
                                    "Skipping event; no focused app: "
                                    + nextKey + " " + nextMotion);
                            return null;
                        }
                    }

                    if (DEBUG_INPUT) Log.v(
                            TAG, "Waiting for last key in " + mLastBinder
                            + " target=" + targetWin
                            + " mFinished=" + mFinished
                            + " mDisplayFrozen=" + mDisplayFrozen
                            + " targetIsNew=" + targetIsNew
                            + " paused="
                            + (targetWin != null ? targetWin.mToken.paused : false)
                            + " mFocusedApp=" + mFocusedApp
                            + " mCurrentFocus=" + mCurrentFocus);

                    targetApp = targetWin != null
                            ? targetWin.mAppToken : mFocusedApp;

                    long curTimeout = keyDispatchingTimeout;
                    if (mTimeToSwitch != 0) {
                        long now = SystemClock.uptimeMillis();
                        if (mTimeToSwitch <= now) {
                            // If an app switch key has been pressed, and we have
                            // waited too long for the current app to finish
                            // processing keys, then wait no more!
                            doFinishedKeyLocked(true);
                            continue;
                        }
                        long switchTimeout = mTimeToSwitch - now;
                        if (curTimeout > switchTimeout) {
                            curTimeout = switchTimeout;
                        }
                    }

                    try {
                        // after that continue
                        // processing keys, so we don't get stuck.
                        if (DEBUG_INPUT) Log.v(
                                TAG, "Waiting for key dispatch: " + curTimeout);
                        wait(curTimeout);
                        if (DEBUG_INPUT) Log.v(TAG, "Finished waiting @"
                                + SystemClock.uptimeMillis() + " startTime="
                                + startTime + " switchTime=" + mTimeToSwitch
                                + " target=" + targetWin + " mLW=" + mLastWin
                                + " mLB=" + mLastBinder + " fin=" + mFinished
                                + " mCurrentFocus=" + mCurrentFocus);
                    } catch (InterruptedException e) {
                    }
                }

                // If we were frozen during configuration change, restart the
                // timeout checks from now; otherwise look at whether we timed
                // out before awakening.
                if (mWasFrozen) {
                    waitedFor = 0;
                    mWasFrozen = false;
                } else {
                    waitedFor = SystemClock.uptimeMillis() - startTime;
                }

                if (waitedFor >= keyDispatchingTimeout && mTimeToSwitch == 0) {
                    IApplicationToken at = null;
                    synchronized (this) {
                        Log.w(TAG, "Key dispatching timed out sending to " +
                              (targetWin != null ? targetWin.mAttrs.getTitle()
                              : "<null>"));
                        // NOSHIP debugging
                        Log.w(TAG, "Dispatch state: " + mDispatchState);
                        Log.w(TAG, "Current state:  " + new DispatchState(nextKey, targetWin));
                        // END NOSHIP
                        //dump();
                        if (targetWin != null) {
                            at = targetWin.getAppToken();
                        } else if (targetApp != null) {
                            at = targetApp.appToken;
                        }
                    }

                    boolean abort = true;
                    if (at != null) {
                        try {
                            long timeout = at.getKeyDispatchingTimeout();
                            if (timeout > waitedFor) {
                                // we did not wait the proper amount of time for this application.
                                // set the timeout to be the real timeout and wait again.
                                keyDispatchingTimeout = timeout - waitedFor;
                                continue;
                            } else {
                                abort = at.keyDispatchingTimedOut();
                            }
                        } catch (RemoteException ex) {
                        }
                    }

                    synchronized (this) {
                        if (abort && (mLastWin == targetWin || targetWin == null)) {
                            mFinished = true;
                            if (mLastWin != null) {
                                if (DEBUG_INPUT) Log.v(TAG,
                                        "Window " + mLastWin +
                                        " timed out on key input");
                                if (mLastWin.mToken.paused) {
                                    Log.w(TAG, "Un-pausing dispatching to this window");
                                    mLastWin.mToken.paused = false;
                                }
                            }
                            if (mMotionTarget == targetWin) {
                                mMotionTarget = null;
                            }
                            mLastWin = null;
                            mLastBinder = null;
                            if (failIfTimeout || targetWin == null) {
                                return null;
                            }
                        } else {
                            Log.w(TAG, "Continuing to wait for key to be dispatched");
                            startTime = SystemClock.uptimeMillis();
                        }
                    }
                }
            }
        }

        Object findTargetWindow(KeyEvent nextKey, QueuedEvent qev,
                MotionEvent nextMotion, boolean isPointerEvent,
                int callingPid, int callingUid) {
            mOutsideTouchTargets = null;

            if (nextKey != null) {
                // Find the target window for a normal key event.
                final int keycode = nextKey.getKeyCode();
                final int repeatCount = nextKey.getRepeatCount();
                final boolean down = nextKey.getAction() != KeyEvent.ACTION_UP;
                boolean dispatch = mKeyWaiter.checkShouldDispatchKey(keycode);

                if (!dispatch) {
                    if (callingUid == 0 ||
                            mContext.checkPermission(
                                    android.Manifest.permission.INJECT_EVENTS,
                                    callingPid, callingUid)
                                    == PackageManager.PERMISSION_GRANTED) {
                        mPolicy.interceptKeyTi(null, keycode,
                                nextKey.getMetaState(), down, repeatCount);
                    }
                    Log.w(TAG, "Event timeout during app switch: dropping "
                            + nextKey);
                    return SKIP_TARGET_TOKEN;
                }

                // System.out.println("##### [" + SystemClock.uptimeMillis() + "] WindowManagerService.dispatchKey(" + keycode + ", " + down + ", " + repeatCount + ")");

                WindowState focus = null;
                synchronized(mWindowMap) {
                    focus = getFocusedWindowLocked();
                }

                wakeupIfNeeded(focus, LocalPowerManager.BUTTON_EVENT);

                if (callingUid == 0 ||
                        (focus != null && callingUid == focus.mSession.mUid) ||
                        mContext.checkPermission(
                                android.Manifest.permission.INJECT_EVENTS,
                                callingPid, callingUid)
                                == PackageManager.PERMISSION_GRANTED) {
                    if (mPolicy.interceptKeyTi(focus,
                            keycode, nextKey.getMetaState(), down, repeatCount)) {
                        return CONSUMED_EVENT_TOKEN;
                    }
                }

                return focus;

            } else if (!isPointerEvent) {
                boolean dispatch = mKeyWaiter.checkShouldDispatchKey(-1);
                if (!dispatch) {
                    Log.w(TAG, "Event timeout during app switch: dropping trackball "
                            + nextMotion);
                    return SKIP_TARGET_TOKEN;
                }

                WindowState focus = null;
                synchronized(mWindowMap) {
                    focus = getFocusedWindowLocked();
                }

                wakeupIfNeeded(focus, LocalPowerManager.BUTTON_EVENT);
                return focus;
            }

            if (nextMotion == null) {
                return SKIP_TARGET_TOKEN;
            }

            boolean dispatch = mKeyWaiter.checkShouldDispatchKey(
                    KeyEvent.KEYCODE_UNKNOWN);
            if (!dispatch) {
                Log.w(TAG, "Event timeout during app switch: dropping pointer "
                        + nextMotion);
                return SKIP_TARGET_TOKEN;
            }

            // Find the target window for a pointer event.
            int action = nextMotion.getAction();
            final float xf = nextMotion.getX();
            final float yf = nextMotion.getY();
            final long eventTime = nextMotion.getEventTime();

            final boolean screenWasOff = qev != null
                    && (qev.flags&WindowManagerPolicy.FLAG_BRIGHT_HERE) != 0;

            WindowState target = null;

            synchronized(mWindowMap) {
                synchronized (this) {
                    if (action == MotionEvent.ACTION_DOWN) {
                        if (mMotionTarget != null) {
                            // this is weird, we got a pen down, but we thought it was
                            // already down!
                            // XXX: We should probably send an ACTION_UP to the current
                            // target.
                            Log.w(TAG, "Pointer down received while already down in: "
                                    + mMotionTarget);
                            mMotionTarget = null;
                        }

                        // ACTION_DOWN is special, because we need to lock next events to
                        // the window we'll land onto.
                        final int x = (int)xf;
                        final int y = (int)yf;

                        final ArrayList windows = mWindows;
                        final int N = windows.size();
                        WindowState topErrWindow = null;
                        final Rect tmpRect = mTempRect;
                        for (int i=N-1; i>=0; i--) {
                            WindowState child = (WindowState)windows.get(i);
                            //Log.i(TAG, "Checking dispatch to: " + child);
                            final int flags = child.mAttrs.flags;
                            if ((flags & WindowManager.LayoutParams.FLAG_SYSTEM_ERROR) != 0) {
                                if (topErrWindow == null) {
                                    topErrWindow = child;
                                }
                            }
                            if (!child.isVisibleLw()) {
                                //Log.i(TAG, "Not visible!");
                                continue;
                            }
                            if ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                                //Log.i(TAG, "Not touchable!");
                                if ((flags & WindowManager.LayoutParams
                                        .FLAG_WATCH_OUTSIDE_TOUCH) != 0) {
                                    child.mNextOutsideTouch = mOutsideTouchTargets;
                                    mOutsideTouchTargets = child;
                                }
                                continue;
                            }
                            tmpRect.set(child.mFrame);
                            if (child.mTouchableInsets == ViewTreeObserver
                                        .InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT) {
                                // The touch is inside of the window if it is
                                // inside the frame, AND the content part of that
                                // frame that was given by the application.
                                tmpRect.left += child.mGivenContentInsets.left;
                                tmpRect.top += child.mGivenContentInsets.top;
                                tmpRect.right -= child.mGivenContentInsets.right;
                                tmpRect.bottom -= child.mGivenContentInsets.bottom;
                            } else if (child.mTouchableInsets == ViewTreeObserver
                                        .InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE) {
                                // The touch is inside of the window if it is
                                // inside the frame, AND the visible part of that
                                // frame that was given by the application.
                                tmpRect.left += child.mGivenVisibleInsets.left;
                                tmpRect.top += child.mGivenVisibleInsets.top;
                                tmpRect.right -= child.mGivenVisibleInsets.right;
                                tmpRect.bottom -= child.mGivenVisibleInsets.bottom;
                            }
                            final int touchFlags = flags &
                                (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                            if (tmpRect.contains(x, y) || touchFlags == 0) {
                                //Log.i(TAG, "Using this target!");
                                if (!screenWasOff || (flags &
                                        WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING) != 0) {
                                    mMotionTarget = child;
                                } else {
                                    //Log.i(TAG, "Waking, skip!");
                                    mMotionTarget = null;
                                }
                                break;
                            }

                            if ((flags & WindowManager.LayoutParams
                                    .FLAG_WATCH_OUTSIDE_TOUCH) != 0) {
                                child.mNextOutsideTouch = mOutsideTouchTargets;
                                mOutsideTouchTargets = child;
                                //Log.i(TAG, "Adding to outside target list: " + child);
                            }
                        }

                        // if there's an error window but it's not accepting
                        // focus (typically because it is not yet visible) just
                        // wait for it -- any other focused window may in fact
                        // be in ANR state.
                        if (topErrWindow != null && mMotionTarget != topErrWindow) {
                            mMotionTarget = null;
                        }
                    }

                    target = mMotionTarget;
                }
            }

            wakeupIfNeeded(target, eventType(nextMotion));

            // Pointer events are a little different -- if there isn't a
            // target found for any event, then just drop it.
            return target != null ? target : SKIP_TARGET_TOKEN;
        }

        boolean checkShouldDispatchKey(int keycode) {
            synchronized (this) {
                if (mPolicy.isAppSwitchKeyTqTiLwLi(keycode)) {
                    mTimeToSwitch = 0;
                    return true;
                }
                if (mTimeToSwitch != 0
                        && mTimeToSwitch < SystemClock.uptimeMillis()) {
                    return false;
                }
                return true;
            }
        }

        void bindTargetWindowLocked(WindowState win,
                int pendingWhat, QueuedEvent pendingMotion) {
            synchronized (this) {
                bindTargetWindowLockedLocked(win, pendingWhat, pendingMotion);
            }
        }

        void bindTargetWindowLocked(WindowState win) {
            synchronized (this) {
                bindTargetWindowLockedLocked(win, RETURN_NOTHING, null);
            }
        }

        void bindTargetWindowLockedLocked(WindowState win,
                int pendingWhat, QueuedEvent pendingMotion) {
            mLastWin = win;
            mLastBinder = win.mClient.asBinder();
            mFinished = false;
            if (pendingMotion != null) {
                final Session s = win.mSession;
                if (pendingWhat == RETURN_PENDING_POINTER) {
                    releasePendingPointerLocked(s);
                    s.mPendingPointerMove = pendingMotion;
                    s.mPendingPointerWindow = win;
                    if (DEBUG_INPUT) Log.v(TAG,
                            "bindTargetToWindow " + s.mPendingPointerMove);
                } else if (pendingWhat == RETURN_PENDING_TRACKBALL) {
                    releasePendingTrackballLocked(s);
                    s.mPendingTrackballMove = pendingMotion;
                    s.mPendingTrackballWindow = win;
                }
            }
        }

        void releasePendingPointerLocked(Session s) {
            if (DEBUG_INPUT) Log.v(TAG,
                    "releasePendingPointer " + s.mPendingPointerMove);
            if (s.mPendingPointerMove != null) {
                mQueue.recycleEvent(s.mPendingPointerMove);
                s.mPendingPointerMove = null;
            }
        }

        void releasePendingTrackballLocked(Session s) {
            if (s.mPendingTrackballMove != null) {
                mQueue.recycleEvent(s.mPendingTrackballMove);
                s.mPendingTrackballMove = null;
            }
        }

        MotionEvent finishedKey(Session session, IWindow client, boolean force,
                int returnWhat) {
            if (DEBUG_INPUT) Log.v(
                TAG, "finishedKey: client=" + client + ", force=" + force);

            if (client == null) {
                return null;
            }

            synchronized (this) {
                if (DEBUG_INPUT) Log.v(
                    TAG, "finishedKey: client=" + client.asBinder()
                    + ", force=" + force + ", last=" + mLastBinder
                    + " (token=" + (mLastWin != null ? mLastWin.mToken : null) + ")");

                QueuedEvent qev = null;
                WindowState win = null;
                if (returnWhat == RETURN_PENDING_POINTER) {
                    qev = session.mPendingPointerMove;
                    win = session.mPendingPointerWindow;
                    session.mPendingPointerMove = null;
                    session.mPendingPointerWindow = null;
                } else if (returnWhat == RETURN_PENDING_TRACKBALL) {
                    qev = session.mPendingTrackballMove;
                    win = session.mPendingTrackballWindow;
                    session.mPendingTrackballMove = null;
                    session.mPendingTrackballWindow = null;
                }

                if (mLastBinder == client.asBinder()) {
                    if (DEBUG_INPUT) Log.v(
                        TAG, "finishedKey: last paused="
                        + ((mLastWin != null) ? mLastWin.mToken.paused : "null"));
                    if (mLastWin != null && (!mLastWin.mToken.paused || force
                            || !mEventDispatching)) {
                        doFinishedKeyLocked(false);
                    } else {
                        // Make sure to wake up anyone currently waiting to
                        // dispatch a key, so they can re-evaluate their
                        // current situation.
                        mFinished = true;
                        notifyAll();
                    }
                }

                if (qev != null) {
                    MotionEvent res = (MotionEvent)qev.event;
                    if (DEBUG_INPUT) Log.v(TAG,
                            "Returning pending motion: " + res);
                    mQueue.recycleEvent(qev);
                    if (win != null && returnWhat == RETURN_PENDING_POINTER) {
                        res.offsetLocation(-win.mFrame.left, -win.mFrame.top);
                    }
                    return res;
                }
                return null;
            }
        }

        void tickle() {
            synchronized (this) {
                notifyAll();
            }
        }

        void handleNewWindowLocked(WindowState newWindow) {
            if (!newWindow.canReceiveKeys()) {
                return;
            }
            synchronized (this) {
                if (DEBUG_INPUT) Log.v(
                    TAG, "New key dispatch window: win="
                    + newWindow.mClient.asBinder()
                    + ", last=" + mLastBinder
                    + " (token=" + (mLastWin != null ? mLastWin.mToken : null)
                    + "), finished=" + mFinished + ", paused="
                    + newWindow.mToken.paused);

                // Displaying a window implicitly causes dispatching to
                // be unpaused.  (This is to protect against bugs if someone
                // pauses dispatching but forgets to resume.)
                newWindow.mToken.paused = false;

                mGotFirstWindow = true;

                if ((newWindow.mAttrs.flags & FLAG_SYSTEM_ERROR) != 0) {
                    if (DEBUG_INPUT) Log.v(TAG,
                            "New SYSTEM_ERROR window; resetting state");
                    mLastWin = null;
                    mLastBinder = null;
                    mMotionTarget = null;
                    mFinished = true;
                } else if (mLastWin != null) {
                    // If the new window is above the window we are
                    // waiting on, then stop waiting and let key dispatching
                    // start on the new guy.
                    if (DEBUG_INPUT) Log.v(
                        TAG, "Last win layer=" + mLastWin.mLayer
                        + ", new win layer=" + newWindow.mLayer);
                    if (newWindow.mLayer >= mLastWin.mLayer) {
                        // The new window is above the old; finish pending input to the last
                        // window and start directing it to the new one.
                        mLastWin.mToken.paused = false;
                        doFinishedKeyLocked(true);  // does a notifyAll()
                    }
                    // Either the new window is lower, so there is no need to wake key waiters,
                    // or we just finished key input to the previous window, which implicitly
                    // notified the key waiters.  In both cases, we don't need to issue the
                    // notification here.
                    return;
                }

                // Now that we've put a new window state in place, make the event waiter
                // take notice and retarget its attentions.
                notifyAll();
            }
        }

        void pauseDispatchingLocked(WindowToken token) {
            synchronized (this)
            {
                if (DEBUG_INPUT) Log.v(TAG, "Pausing WindowToken " + token);
                token.paused = true;

                /*
                if (mLastWin != null && !mFinished && mLastWin.mBaseLayer <= layer) {
                    mPaused = true;
                } else {
                    if (mLastWin == null) {
                        Log.i(TAG, "Key dispatching not paused: no last window.");
                    } else if (mFinished) {
                        Log.i(TAG, "Key dispatching not paused: finished last key.");
                    } else {
                        Log.i(TAG, "Key dispatching not paused: window in higher layer.");
                    }
                }
                */
            }
        }

        void resumeDispatchingLocked(WindowToken token) {
            synchronized (this) {
                if (token.paused) {
                    if (DEBUG_INPUT) Log.v(
                        TAG, "Resuming WindowToken " + token
                        + ", last=" + mLastBinder
                        + " (token=" + (mLastWin != null ? mLastWin.mToken : null)
                        + "), finished=" + mFinished + ", paused="
                        + token.paused);
                    token.paused = false;
                    if (mLastWin != null && mLastWin.mToken == token && mFinished) {
                        doFinishedKeyLocked(true);
                    } else {
                        notifyAll();
                    }
                }
            }
        }

        void setEventDispatchingLocked(boolean enabled) {
            synchronized (this) {
                mEventDispatching = enabled;
                notifyAll();
            }
        }

        void appSwitchComing() {
            synchronized (this) {
                // Don't wait for more than .5 seconds for app to finish
                // processing the pending events.
                long now = SystemClock.uptimeMillis() + 500;
                if (DEBUG_INPUT) Log.v(TAG, "appSwitchComing: " + now);
                if (mTimeToSwitch == 0 || now < mTimeToSwitch) {
                    mTimeToSwitch = now;
                }
                notifyAll();
            }
        }

        private final void doFinishedKeyLocked(boolean doRecycle) {
            if (mLastWin != null) {
                releasePendingPointerLocked(mLastWin.mSession);
                releasePendingTrackballLocked(mLastWin.mSession);
            }

            if (mLastWin == null || !mLastWin.mToken.paused
                || !mLastWin.isVisibleLw()) {
                // If the current window has been paused, we aren't -really-
                // finished...  so let the waiters still wait.
                mLastWin = null;
                mLastBinder = null;
            }
            mFinished = true;
            notifyAll();
        }
    }

    private class KeyQ extends KeyInputQueue
            implements KeyInputQueue.FilterCallback {
        PowerManager.WakeLock mHoldingScreen;

        KeyQ() {
            super(mContext);
            PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
            mHoldingScreen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "KEEP_SCREEN_ON_FLAG");
            mHoldingScreen.setReferenceCounted(false);
        }

        @Override
        boolean preprocessEvent(InputDevice device, RawInputEvent event) {
            if (mPolicy.preprocessInputEventTq(event)) {
                return true;
            }

            switch (event.type) {
                case RawInputEvent.EV_KEY: {
                    // XXX begin hack
                    if (DEBUG) {
                        if (event.keycode == KeyEvent.KEYCODE_G) {
                            if (event.value != 0) {
                                // G down
                                mPolicy.screenTurnedOff(WindowManagerPolicy.OFF_BECAUSE_OF_USER);
                            }
                            return false;
                        }
                        if (event.keycode == KeyEvent.KEYCODE_D) {
                            if (event.value != 0) {
                                //dump();
                            }
                            return false;
                        }
                    }
                    // XXX end hack

                    boolean screenIsOff = !mPowerManager.screenIsOn();
                    boolean screenIsDim = !mPowerManager.screenIsBright();
                    int actions = mPolicy.interceptKeyTq(event, !screenIsOff);

		    // Start ONE hack
		    // Change green/red key events
                    if ((actions & WindowManagerPolicy.ACTION_TRANSLATE_KEY) != 0) {
                    	if (event.keycode == KeyEvent.KEYCODE_ENDCALL) {
				/* Red becomes back... */
				event.keycode = KeyEvent.KEYCODE_BACK;
			} else if (event.keycode == KeyEvent.KEYCODE_CALL) {
				/* Green becomes menu... */
				event.keycode = KeyEvent.KEYCODE_MENU;
			}
		    }
		    // Finish ONE hack

                    if ((actions & WindowManagerPolicy.ACTION_GO_TO_SLEEP) != 0) {
                        mPowerManager.goToSleep(event.when);
                    }

                    if (screenIsOff) {
                        event.flags |= WindowManagerPolicy.FLAG_WOKE_HERE;
                    }
                    if (screenIsDim) {
                        event.flags |= WindowManagerPolicy.FLAG_BRIGHT_HERE;
                    }
                    if ((actions & WindowManagerPolicy.ACTION_POKE_USER_ACTIVITY) != 0) {
                        mPowerManager.userActivity(event.when, false,
                                LocalPowerManager.BUTTON_EVENT, false);
                    }

                    if ((actions & WindowManagerPolicy.ACTION_PASS_TO_USER) != 0) {
                        if (event.value != 0 && mPolicy.isAppSwitchKeyTqTiLwLi(event.keycode)) {
                            filterQueue(this);
                            mKeyWaiter.appSwitchComing();
                        }
                        return true;
                    } else {
                        return false;
                    }
                }

                case RawInputEvent.EV_REL: {
                    boolean screenIsOff = !mPowerManager.screenIsOn();
                    boolean screenIsDim = !mPowerManager.screenIsBright();
                    if (screenIsOff) {
                        if (!mPolicy.isWakeRelMovementTq(event.deviceId,
                                device.classes, event)) {
                            //Log.i(TAG, "dropping because screenIsOff and !isWakeKey");
                            return false;
                        }
                        event.flags |= WindowManagerPolicy.FLAG_WOKE_HERE;
                    }
                    if (screenIsDim) {
                        event.flags |= WindowManagerPolicy.FLAG_BRIGHT_HERE;
                    }
                    return true;
                }

                case RawInputEvent.EV_ABS: {
                    boolean screenIsOff = !mPowerManager.screenIsOn();
                    boolean screenIsDim = !mPowerManager.screenIsBright();
                    if (screenIsOff) {
                        if (!mPolicy.isWakeAbsMovementTq(event.deviceId,
                                device.classes, event)) {
                            //Log.i(TAG, "dropping because screenIsOff and !isWakeKey");
                            return false;
                        }
                        event.flags |= WindowManagerPolicy.FLAG_WOKE_HERE;
                    }
                    if (screenIsDim) {
                        event.flags |= WindowManagerPolicy.FLAG_BRIGHT_HERE;
                    }
                    return true;
                }

                default:
                    return true;
            }
        }

        public int filterEvent(QueuedEvent ev) {
            switch (ev.classType) {
                case RawInputEvent.CLASS_KEYBOARD:
                    KeyEvent ke = (KeyEvent)ev.event;
                    if (mPolicy.isMovementKeyTi(ke.getKeyCode())) {
                        Log.w(TAG, "Dropping movement key during app switch: "
                                + ke.getKeyCode() + ", action=" + ke.getAction());
                        return FILTER_REMOVE;
                    }
                    return FILTER_ABORT;
                default:
                    return FILTER_KEEP;
            }
        }

        /**
         * Must be called with the main window manager lock held.
         */
        void setHoldScreenLocked(boolean holding) {
            boolean state = mHoldingScreen.isHeld();
            if (holding != state) {
                if (holding) {
                    mHoldingScreen.acquire();
                } else {
                    mPolicy.screenOnStoppedLw();
                    mHoldingScreen.release();
                }
            }
        }
    };

    public boolean detectSafeMode() {
        mSafeMode = mPolicy.detectSafeMode();
        return mSafeMode;
    }

    public void systemReady() {
        mPolicy.systemReady();
    }

    private final class InputDispatcherThread extends Thread {
        // Time to wait when there is nothing to do: 9999 seconds.
        static final int LONG_WAIT=9999*1000;

        public InputDispatcherThread() {
            super("InputDispatcher");
        }

        @Override
        public void run() {
            while (true) {
                try {
                    process();
                } catch (Exception e) {
                    Log.e(TAG, "Exception in input dispatcher", e);
                }
            }
        }

        private void process() {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);

            // The last key event we saw
            KeyEvent lastKey = null;

            // Last keydown time for auto-repeating keys
            long lastKeyTime = SystemClock.uptimeMillis();
            long nextKeyTime = lastKeyTime+LONG_WAIT;

            // How many successive repeats we generated
            int keyRepeatCount = 0;

            // Need to report that configuration has changed?
            boolean configChanged = false;

            while (true) {
                long curTime = SystemClock.uptimeMillis();

                if (DEBUG_INPUT) Log.v(
                    TAG, "Waiting for next key: now=" + curTime
                    + ", repeat @ " + nextKeyTime);

                // Retrieve next event, waiting only as long as the next
                // repeat timeout.  If the configuration has changed, then
                // don't wait at all -- we'll report the change as soon as
                // we have processed all events.
                QueuedEvent ev = mQueue.getEvent(
                    (int)((!configChanged && curTime < nextKeyTime)
                            ? (nextKeyTime-curTime) : 0));

                if (DEBUG_INPUT && ev != null) Log.v(
                        TAG, "Event: type=" + ev.classType + " data=" + ev.event);

                try {
                    if (ev != null) {
                        curTime = ev.when;
                        int eventType;
                        if (ev.classType == RawInputEvent.CLASS_TOUCHSCREEN) {
                            eventType = eventType((MotionEvent)ev.event);
                        } else if (ev.classType == RawInputEvent.CLASS_KEYBOARD ||
                                    ev.classType == RawInputEvent.CLASS_TRACKBALL) {
                            eventType = LocalPowerManager.BUTTON_EVENT;
                        } else {
                            eventType = LocalPowerManager.OTHER_EVENT;
                        }
                        try {
                            long now = SystemClock.uptimeMillis();

                            if ((now - mLastBatteryStatsCallTime)
                                    >= MIN_TIME_BETWEEN_USERACTIVITIES) {
                                mLastBatteryStatsCallTime = now;
                                mBatteryStats.noteInputEvent();
                            }
                        } catch (RemoteException e) {
                            // Ignore
                        }
                        mPowerManager.userActivity(curTime, false, eventType, false);
                        switch (ev.classType) {
                            case RawInputEvent.CLASS_KEYBOARD:
                                KeyEvent ke = (KeyEvent)ev.event;
                                if (ke.isDown()) {
                                    lastKey = ke;
                                    keyRepeatCount = 0;
                                    lastKeyTime = curTime;
                                    nextKeyTime = lastKeyTime
                                            + KEY_REPEAT_FIRST_DELAY;
                                    if (DEBUG_INPUT) Log.v(
                                        TAG, "Received key down: first repeat @ "
                                        + nextKeyTime);
                                } else {
                                    lastKey = null;
                                    // Arbitrary long timeout.
                                    lastKeyTime = curTime;
                                    nextKeyTime = curTime + LONG_WAIT;
                                    if (DEBUG_INPUT) Log.v(
                                        TAG, "Received key up: ignore repeat @ "
                                        + nextKeyTime);
                                }
                                dispatchKey((KeyEvent)ev.event, 0, 0);
                                mQueue.recycleEvent(ev);
                                break;
                            case RawInputEvent.CLASS_TOUCHSCREEN:
                                //Log.i(TAG, "Read next event " + ev);
                                dispatchPointer(ev, (MotionEvent)ev.event, 0, 0);
                                break;
                            case RawInputEvent.CLASS_TRACKBALL:
                                dispatchTrackball(ev, (MotionEvent)ev.event, 0, 0);
                                break;
                            case RawInputEvent.CLASS_CONFIGURATION_CHANGED:
                                configChanged = true;
                                break;
                            default:
                                mQueue.recycleEvent(ev);
                            break;
                        }

                    } else if (configChanged) {
                        configChanged = false;
                        sendNewConfiguration();

                    } else if (lastKey != null) {
                        curTime = SystemClock.uptimeMillis();

                        // Timeout occurred while key was down.  If it is at or
                        // past the key repeat time, dispatch the repeat.
                        if (DEBUG_INPUT) Log.v(
                            TAG, "Key timeout: repeat=" + nextKeyTime
                            + ", now=" + curTime);
                        if (curTime < nextKeyTime) {
                            continue;
                        }

                        lastKeyTime = nextKeyTime;
                        nextKeyTime = nextKeyTime + KEY_REPEAT_DELAY;
                        keyRepeatCount++;
                        if (DEBUG_INPUT) Log.v(
                            TAG, "Key repeat: count=" + keyRepeatCount
                            + ", next @ " + nextKeyTime);
                        dispatchKey(KeyEvent.changeTimeRepeat(lastKey, curTime, keyRepeatCount), 0, 0);

                    } else {
                        curTime = SystemClock.uptimeMillis();

                        lastKeyTime = curTime;
                        nextKeyTime = curTime + LONG_WAIT;
                    }

                } catch (Exception e) {
                    Log.e(TAG,
                        "Input thread received uncaught exception: " + e, e);
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Client Session State
    // -------------------------------------------------------------

    private final class Session extends IWindowSession.Stub
            implements IBinder.DeathRecipient {
        final IInputMethodClient mClient;
        final IInputContext mInputContext;
        final int mUid;
        final int mPid;
        final String mStringName;
        SurfaceSession mSurfaceSession;
        int mNumWindow = 0;
        boolean mClientDead = false;

        /**
         * Current pointer move event being dispatched to client window...  must
         * hold key lock to access.
         */
        QueuedEvent mPendingPointerMove;
        WindowState mPendingPointerWindow;

        /**
         * Current trackball move event being dispatched to client window...  must
         * hold key lock to access.
         */
        QueuedEvent mPendingTrackballMove;
        WindowState mPendingTrackballWindow;

        public Session(IInputMethodClient client, IInputContext inputContext) {
            mClient = client;
            mInputContext = inputContext;
            mUid = Binder.getCallingUid();
            mPid = Binder.getCallingPid();
            StringBuilder sb = new StringBuilder();
            sb.append("Session{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" uid ");
            sb.append(mUid);
            sb.append("}");
            mStringName = sb.toString();

            synchronized (mWindowMap) {
                if (mInputMethodManager == null && mHaveInputMethods) {
                    IBinder b = ServiceManager.getService(
                            Context.INPUT_METHOD_SERVICE);
                    mInputMethodManager = IInputMethodManager.Stub.asInterface(b);
                }
            }
            long ident = Binder.clearCallingIdentity();
            try {
                // Note: it is safe to call in to the input method manager
                // here because we are not holding our lock.
                if (mInputMethodManager != null) {
                    mInputMethodManager.addClient(client, inputContext,
                            mUid, mPid);
                } else {
                    client.setUsingInputMethod(false);
                }
                client.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                // The caller has died, so we can just forget about this.
                try {
                    if (mInputMethodManager != null) {
                        mInputMethodManager.removeClient(client);
                    }
                } catch (RemoteException ee) {
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                // Log all 'real' exceptions thrown to the caller
                if (!(e instanceof SecurityException)) {
                    Log.e(TAG, "Window Session Crash", e);
                }
                throw e;
            }
        }

        public void binderDied() {
            // Note: it is safe to call in to the input method manager
            // here because we are not holding our lock.
            try {
                if (mInputMethodManager != null) {
                    mInputMethodManager.removeClient(mClient);
                }
            } catch (RemoteException e) {
            }
            synchronized(mWindowMap) {
                mClient.asBinder().unlinkToDeath(this, 0);
                mClientDead = true;
                killSessionLocked();
            }
        }

        public int add(IWindow window, WindowManager.LayoutParams attrs,
                int viewVisibility, Rect outContentInsets) {
            return addWindow(this, window, attrs, viewVisibility, outContentInsets);
        }

        public void remove(IWindow window) {
            removeWindow(this, window);
        }

        public int relayout(IWindow window, WindowManager.LayoutParams attrs,
                int requestedWidth, int requestedHeight, int viewFlags,
                boolean insetsPending, Rect outFrame, Rect outContentInsets,
                Rect outVisibleInsets, Surface outSurface) {
            return relayoutWindow(this, window, attrs,
                    requestedWidth, requestedHeight, viewFlags, insetsPending,
                    outFrame, outContentInsets, outVisibleInsets, outSurface);
        }

        public void setTransparentRegion(IWindow window, Region region) {
            setTransparentRegionWindow(this, window, region);
        }

        public void setInsets(IWindow window, int touchableInsets,
                Rect contentInsets, Rect visibleInsets) {
            setInsetsWindow(this, window, touchableInsets, contentInsets,
                    visibleInsets);
        }

        public void getDisplayFrame(IWindow window, Rect outDisplayFrame) {
            getWindowDisplayFrame(this, window, outDisplayFrame);
        }

        public void finishDrawing(IWindow window) {
            if (localLOGV) Log.v(
                TAG, "IWindow finishDrawing called for " + window);
            finishDrawingWindow(this, window);
        }

        public void finishKey(IWindow window) {
            if (localLOGV) Log.v(
                TAG, "IWindow finishKey called for " + window);
            mKeyWaiter.finishedKey(this, window, false,
                    KeyWaiter.RETURN_NOTHING);
        }

        public MotionEvent getPendingPointerMove(IWindow window) {
            if (localLOGV) Log.v(
                    TAG, "IWindow getPendingMotionEvent called for " + window);
            return mKeyWaiter.finishedKey(this, window, false,
                    KeyWaiter.RETURN_PENDING_POINTER);
        }

        public MotionEvent getPendingTrackballMove(IWindow window) {
            if (localLOGV) Log.v(
                    TAG, "IWindow getPendingMotionEvent called for " + window);
            return mKeyWaiter.finishedKey(this, window, false,
                    KeyWaiter.RETURN_PENDING_TRACKBALL);
        }

        public void setInTouchMode(boolean mode) {
            synchronized(mWindowMap) {
                mInTouchMode = mode;
            }
        }

        public boolean getInTouchMode() {
            synchronized(mWindowMap) {
                return mInTouchMode;
            }
        }

        public boolean performHapticFeedback(IWindow window, int effectId,
                boolean always) {
            synchronized(mWindowMap) {
                long ident = Binder.clearCallingIdentity();
                try {
                    return mPolicy.performHapticFeedbackLw(
                            windowForClientLocked(this, window), effectId, always);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        void windowAddedLocked() {
            if (mSurfaceSession == null) {
                if (localLOGV) Log.v(
                    TAG, "First window added to " + this + ", creating SurfaceSession");
                mSurfaceSession = new SurfaceSession();
                mSessions.add(this);
            }
            mNumWindow++;
        }

        void windowRemovedLocked() {
            mNumWindow--;
            killSessionLocked();
        }

        void killSessionLocked() {
            if (mNumWindow <= 0 && mClientDead) {
                mSessions.remove(this);
                if (mSurfaceSession != null) {
                    if (localLOGV) Log.v(
                        TAG, "Last window removed from " + this
                        + ", destroying " + mSurfaceSession);
                    try {
                        mSurfaceSession.kill();
                    } catch (Exception e) {
                        Log.w(TAG, "Exception thrown when killing surface session "
                            + mSurfaceSession + " in session " + this
                            + ": " + e.toString());
                    }
                    mSurfaceSession = null;
                }
            }
        }

        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("mNumWindow="); pw.print(mNumWindow);
                    pw.print(" mClientDead="); pw.print(mClientDead);
                    pw.print(" mSurfaceSession="); pw.println(mSurfaceSession);
            if (mPendingPointerWindow != null || mPendingPointerMove != null) {
                pw.print(prefix);
                        pw.print("mPendingPointerWindow="); pw.print(mPendingPointerWindow);
                        pw.print(" mPendingPointerMove="); pw.println(mPendingPointerMove);
            }
            if (mPendingTrackballWindow != null || mPendingTrackballMove != null) {
                pw.print(prefix);
                        pw.print("mPendingTrackballWindow="); pw.print(mPendingTrackballWindow);
                        pw.print(" mPendingTrackballMove="); pw.println(mPendingTrackballMove);
            }
        }

        @Override
        public String toString() {
            return mStringName;
        }
    }

    // -------------------------------------------------------------
    // Client Window State
    // -------------------------------------------------------------

    private final class WindowState implements WindowManagerPolicy.WindowState {
        final Session mSession;
        final IWindow mClient;
        WindowToken mToken;
        WindowToken mRootToken;
        AppWindowToken mAppToken;
        AppWindowToken mTargetAppToken;
        final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
        final DeathRecipient mDeathRecipient;
        final WindowState mAttachedWindow;
        final ArrayList mChildWindows = new ArrayList();
        final int mBaseLayer;
        final int mSubLayer;
        final boolean mLayoutAttached;
        final boolean mIsImWindow;
        int mViewVisibility;
        boolean mPolicyVisibility = true;
        boolean mPolicyVisibilityAfterAnim = true;
        boolean mAppFreezing;
        Surface mSurface;
        boolean mAttachedHidden;    // is our parent window hidden?
        boolean mLastHidden;        // was this window last hidden?
        int mRequestedWidth;
        int mRequestedHeight;
        int mLastRequestedWidth;
        int mLastRequestedHeight;
        int mReqXPos;
        int mReqYPos;
        int mLayer;
        int mAnimLayer;
        int mLastLayer;
        boolean mHaveFrame;

        WindowState mNextOutsideTouch;

        // Actual frame shown on-screen (may be modified by animation)
        final Rect mShownFrame = new Rect();
        final Rect mLastShownFrame = new Rect();

        /**
         * Insets that determine the actually visible area
         */
        final Rect mVisibleInsets = new Rect();
        final Rect mLastVisibleInsets = new Rect();
        boolean mVisibleInsetsChanged;

        /**
         * Insets that are covered by system windows
         */
        final Rect mContentInsets = new Rect();
        final Rect mLastContentInsets = new Rect();
        boolean mContentInsetsChanged;

        /**
         * Set to true if we are waiting for this window to receive its
         * given internal insets before laying out other windows based on it.
         */
        boolean mGivenInsetsPending;

        /**
         * These are the content insets that were given during layout for
         * this window, to be applied to windows behind it.
         */
        final Rect mGivenContentInsets = new Rect();

        /**
         * These are the visible insets that were given during layout for
         * this window, to be applied to windows behind it.
         */
        final Rect mGivenVisibleInsets = new Rect();

        /**
         * Flag indicating whether the touchable region should be adjusted by
         * the visible insets; if false the area outside the visible insets is
         * NOT touchable, so we must use those to adjust the frame during hit
         * tests.
         */
        int mTouchableInsets = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;

        // Current transformation being applied.
        float mDsDx=1, mDtDx=0, mDsDy=0, mDtDy=1;
        float mLastDsDx=1, mLastDtDx=0, mLastDsDy=0, mLastDtDy=1;
        float mHScale=1, mVScale=1;
        float mLastHScale=1, mLastVScale=1;
        final Matrix mTmpMatrix = new Matrix();

        // "Real" frame that the application sees.
        final Rect mFrame = new Rect();
        final Rect mLastFrame = new Rect();

        final Rect mContainingFrame = new Rect();
        final Rect mDisplayFrame = new Rect();
        final Rect mContentFrame = new Rect();
        final Rect mVisibleFrame = new Rect();

        float mShownAlpha = 1;
        float mAlpha = 1;
        float mLastAlpha = 1;

        // Set to true if, when the window gets displayed, it should perform
        // an enter animation.
        boolean mEnterAnimationPending;

        // Currently running animation.
        boolean mAnimating;
        boolean mLocalAnimating;
        Animation mAnimation;
        boolean mAnimationIsEntrance;
        boolean mHasTransformation;
        boolean mHasLocalTransformation;
        final Transformation mTransformation = new Transformation();

        // This is set after IWindowSession.relayout() has been called at
        // least once for the window.  It allows us to detect the situation
        // where we don't yet have a surface, but should have one soon, so
        // we can give the window focus before waiting for the relayout.
        boolean mRelayoutCalled;

        // This is set after the Surface has been created but before the
        // window has been drawn.  During this time the surface is hidden.
        boolean mDrawPending;

        // This is set after the window has finished drawing for the first
        // time but before its surface is shown.  The surface will be
        // displayed when the next layout is run.
        boolean mCommitDrawPending;

        // This is set during the time after the window's drawing has been
        // committed, and before its surface is actually shown.  It is used
        // to delay showing the surface until all windows in a token are ready
        // to be shown.
        boolean mReadyToShow;

        // Set when the window has been shown in the screen the first time.
        boolean mHasDrawn;

        // Currently running an exit animation?
        boolean mExiting;

        // Currently on the mDestroySurface list?
        boolean mDestroying;

        // Completely remove from window manager after exit animation?
        boolean mRemoveOnExit;

        // Set when the orientation is changing and this window has not yet
        // been updated for the new orientation.
        boolean mOrientationChanging;

        // Is this window now (or just being) removed?
        boolean mRemoved;

        WindowState(Session s, IWindow c, WindowToken token,
               WindowState attachedWindow, WindowManager.LayoutParams a,
               int viewVisibility) {
            mSession = s;
            mClient = c;
            mToken = token;
            mAttrs.copyFrom(a);
            mViewVisibility = viewVisibility;
            DeathRecipient deathRecipient = new DeathRecipient();
            mAlpha = a.alpha;
            if (localLOGV) Log.v(
                TAG, "Window " + this + " client=" + c.asBinder()
                + " token=" + token + " (" + mAttrs.token + ")");
            try {
                c.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                mDeathRecipient = null;
                mAttachedWindow = null;
                mLayoutAttached = false;
                mIsImWindow = false;
                mBaseLayer = 0;
                mSubLayer = 0;
                return;
            }
            mDeathRecipient = deathRecipient;

            if ((mAttrs.type >= FIRST_SUB_WINDOW &&
                    mAttrs.type <= LAST_SUB_WINDOW)) {
                // The multiplier here is to reserve space for multiple
                // windows in the same type layer.
                mBaseLayer = mPolicy.windowTypeToLayerLw(
                        attachedWindow.mAttrs.type) * TYPE_LAYER_MULTIPLIER
                        + TYPE_LAYER_OFFSET;
                mSubLayer = mPolicy.subWindowTypeToLayerLw(a.type);
                mAttachedWindow = attachedWindow;
                mAttachedWindow.mChildWindows.add(this);
                mLayoutAttached = mAttrs.type !=
                        WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
                mIsImWindow = attachedWindow.mAttrs.type == TYPE_INPUT_METHOD
                        || attachedWindow.mAttrs.type == TYPE_INPUT_METHOD_DIALOG;
            } else {
                // The multiplier here is to reserve space for multiple
                // windows in the same type layer.
                mBaseLayer = mPolicy.windowTypeToLayerLw(a.type)
                        * TYPE_LAYER_MULTIPLIER
                        + TYPE_LAYER_OFFSET;
                mSubLayer = 0;
                mAttachedWindow = null;
                mLayoutAttached = false;
                mIsImWindow = mAttrs.type == TYPE_INPUT_METHOD
                        || mAttrs.type == TYPE_INPUT_METHOD_DIALOG;
            }

            WindowState appWin = this;
            while (appWin.mAttachedWindow != null) {
                appWin = mAttachedWindow;
            }
            WindowToken appToken = appWin.mToken;
            while (appToken.appWindowToken == null) {
                WindowToken parent = mTokenMap.get(appToken.token);
                if (parent == null || appToken == parent) {
                    break;
                }
                appToken = parent;
            }
            mRootToken = appToken;
            mAppToken = appToken.appWindowToken;

            mSurface = null;
            mRequestedWidth = 0;
            mRequestedHeight = 0;
            mLastRequestedWidth = 0;
            mLastRequestedHeight = 0;
            mReqXPos = 0;
            mReqYPos = 0;
            mLayer = 0;
            mAnimLayer = 0;
            mLastLayer = 0;
        }

        void attach() {
            if (localLOGV) Log.v(
                TAG, "Attaching " + this + " token=" + mToken
                + ", list=" + mToken.windows);
            mSession.windowAddedLocked();
        }

        public void computeFrameLw(Rect pf, Rect df, Rect cf, Rect vf) {
            mHaveFrame = true;

            final Rect container = mContainingFrame;
            container.set(pf);

            final Rect display = mDisplayFrame;
            display.set(df);

            if ((mAttrs.flags & FLAG_COMPATIBLE_WINDOW) != 0) {
                container.intersect(mCompatibleScreenFrame);
                if ((mAttrs.flags & FLAG_LAYOUT_NO_LIMITS) == 0) {
                    display.intersect(mCompatibleScreenFrame);
                }
            }

            final int pw = container.right - container.left;
            final int ph = container.bottom - container.top;

            int w,h;
            if ((mAttrs.flags & mAttrs.FLAG_SCALED) != 0) {
                w = mAttrs.width < 0 ? pw : mAttrs.width;
                h = mAttrs.height< 0 ? ph : mAttrs.height;
            } else {
                w = mAttrs.width == mAttrs.FILL_PARENT ? pw : mRequestedWidth;
                h = mAttrs.height== mAttrs.FILL_PARENT ? ph : mRequestedHeight;
            }

            final Rect content = mContentFrame;
            content.set(cf);

            final Rect visible = mVisibleFrame;
            visible.set(vf);

            final Rect frame = mFrame;

            //System.out.println("In: w=" + w + " h=" + h + " container=" +
            //                   container + " x=" + mAttrs.x + " y=" + mAttrs.y);

            Gravity.apply(mAttrs.gravity, w, h, container,
                    (int) (mAttrs.x + mAttrs.horizontalMargin * pw),
                    (int) (mAttrs.y + mAttrs.verticalMargin * ph), frame);

            //System.out.println("Out: " + mFrame);

            // Now make sure the window fits in the overall display.
            Gravity.applyDisplay(mAttrs.gravity, df, frame);
            
            // Make sure the content and visible frames are inside of the
            // final window frame.
            if (content.left < frame.left) content.left = frame.left;
            if (content.top < frame.top) content.top = frame.top;
            if (content.right > frame.right) content.right = frame.right;
            if (content.bottom > frame.bottom) content.bottom = frame.bottom;
            if (visible.left < frame.left) visible.left = frame.left;
            if (visible.top < frame.top) visible.top = frame.top;
            if (visible.right > frame.right) visible.right = frame.right;
            if (visible.bottom > frame.bottom) visible.bottom = frame.bottom;

            final Rect contentInsets = mContentInsets;
            contentInsets.left = content.left-frame.left;
            contentInsets.top = content.top-frame.top;
            contentInsets.right = frame.right-content.right;
            contentInsets.bottom = frame.bottom-content.bottom;

            final Rect visibleInsets = mVisibleInsets;
            visibleInsets.left = visible.left-frame.left;
            visibleInsets.top = visible.top-frame.top;
            visibleInsets.right = frame.right-visible.right;
            visibleInsets.bottom = frame.bottom-visible.bottom;

            if (localLOGV) {
                //if ("com.google.android.youtube".equals(mAttrs.packageName)
                //        && mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
                    Log.v(TAG, "Resolving (mRequestedWidth="
                            + mRequestedWidth + ", mRequestedheight="
                            + mRequestedHeight + ") to" + " (pw=" + pw + ", ph=" + ph
                            + "): frame=" + mFrame.toShortString()
                            + " ci=" + contentInsets.toShortString()
                            + " vi=" + visibleInsets.toShortString());
                //}
            }
        }

        public Rect getFrameLw() {
            return mFrame;
        }

        public Rect getShownFrameLw() {
            return mShownFrame;
        }

        public Rect getDisplayFrameLw() {
            return mDisplayFrame;
        }

        public Rect getContentFrameLw() {
            return mContentFrame;
        }

        public Rect getVisibleFrameLw() {
            return mVisibleFrame;
        }

        public boolean getGivenInsetsPendingLw() {
            return mGivenInsetsPending;
        }

        public Rect getGivenContentInsetsLw() {
            return mGivenContentInsets;
        }

        public Rect getGivenVisibleInsetsLw() {
            return mGivenVisibleInsets;
        }

        public WindowManager.LayoutParams getAttrs() {
            return mAttrs;
        }

        public int getSurfaceLayer() {
            return mLayer;
        }

        public IApplicationToken getAppToken() {
            return mAppToken != null ? mAppToken.appToken : null;
        }

        public boolean hasAppShownWindows() {
            return mAppToken != null ? mAppToken.firstWindowDrawn : false;
        }

        public boolean hasAppStartingIcon() {
            return mAppToken != null ? (mAppToken.startingData != null) : false;
        }

        public WindowManagerPolicy.WindowState getAppStartingWindow() {
            return mAppToken != null ? mAppToken.startingWindow : null;
        }

        public void setAnimation(Animation anim) {
            if (localLOGV) Log.v(
                TAG, "Setting animation in " + this + ": " + anim);
            mAnimating = false;
            mLocalAnimating = false;
            mAnimation = anim;
            mAnimation.restrictDuration(MAX_ANIMATION_DURATION);
            mAnimation.scaleCurrentDuration(mWindowAnimationScale);
        }

        public void clearAnimation() {
            if (mAnimation != null) {
                mAnimating = true;
                mLocalAnimating = false;
                mAnimation = null;
            }
        }

        Surface createSurfaceLocked() {
            if (mSurface == null) {
                mDrawPending = true;
                mCommitDrawPending = false;
                mReadyToShow = false;
                if (mAppToken != null) {
                    mAppToken.allDrawn = false;
                }

                int flags = 0;
                if (mAttrs.memoryType == MEMORY_TYPE_HARDWARE) {
                    flags |= Surface.HARDWARE;
                } else if (mAttrs.memoryType == MEMORY_TYPE_GPU) {
                    flags |= Surface.GPU;
                } else if (mAttrs.memoryType == MEMORY_TYPE_PUSH_BUFFERS) {
                    flags |= Surface.PUSH_BUFFERS;
                }

                if ((mAttrs.flags&WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                    flags |= Surface.SECURE;
                }
                if (DEBUG_VISIBILITY) Log.v(
                    TAG, "Creating surface in session "
                    + mSession.mSurfaceSession + " window " + this
                    + " w=" + mFrame.width()
                    + " h=" + mFrame.height() + " format="
                    + mAttrs.format + " flags=" + flags);

                int w = mFrame.width();
                int h = mFrame.height();
                if ((mAttrs.flags & LayoutParams.FLAG_SCALED) != 0) {
                    // for a scaled surface, we always want the requested
                    // size.
                    w = mRequestedWidth;
                    h = mRequestedHeight;
                }

                try {
                    mSurface = new Surface(
                            mSession.mSurfaceSession, mSession.mPid,
                            0, w, h, mAttrs.format, flags);
                } catch (Surface.OutOfResourcesException e) {
                    Log.w(TAG, "OutOfResourcesException creating surface");
                    reclaimSomeSurfaceMemoryLocked(this, "create");
                    return null;
                } catch (Exception e) {
                    Log.e(TAG, "Exception creating surface", e);
                    return null;
                }

                if (localLOGV) Log.v(
                    TAG, "Got surface: " + mSurface
                    + ", set left=" + mFrame.left + " top=" + mFrame.top
                    + ", animLayer=" + mAnimLayer);
                if (SHOW_TRANSACTIONS) {
                    Log.i(TAG, ">>> OPEN TRANSACTION");
                    Log.i(TAG, "  SURFACE " + mSurface + ": CREATE ("
                            + mAttrs.getTitle() + ") pos=(" +
                          mFrame.left + "," + mFrame.top + ") (" +
                          mFrame.width() + "x" + mFrame.height() + "), layer=" +
                          mAnimLayer + " HIDE");
                }
                Surface.openTransaction();
                try {
                    try {
                        mSurface.setPosition(mFrame.left, mFrame.top);
                        mSurface.setLayer(mAnimLayer);
                        mSurface.hide();
                        if ((mAttrs.flags&WindowManager.LayoutParams.FLAG_DITHER) != 0) {
                            mSurface.setFlags(Surface.SURFACE_DITHER,
                                    Surface.SURFACE_DITHER);
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Error creating surface in " + w, e);
                        reclaimSomeSurfaceMemoryLocked(this, "create-init");
                    }
                    mLastHidden = true;
                } finally {
                    if (SHOW_TRANSACTIONS) Log.i(TAG, "<<< CLOSE TRANSACTION");
                    Surface.closeTransaction();
                }
                if (localLOGV) Log.v(
                        TAG, "Created surface " + this);
            }
            return mSurface;
        }

        void destroySurfaceLocked() {
            // Window is no longer on-screen, so can no longer receive
            // key events...  if we were waiting for it to finish
            // handling a key event, the wait is over!
            mKeyWaiter.finishedKey(mSession, mClient, true,
                    KeyWaiter.RETURN_NOTHING);
            mKeyWaiter.releasePendingPointerLocked(mSession);
            mKeyWaiter.releasePendingTrackballLocked(mSession);

            if (mAppToken != null && this == mAppToken.startingWindow) {
                mAppToken.startingDisplayed = false;
            }

            if (localLOGV) Log.v(
                TAG, "Window " + this
                + " destroying surface " + mSurface + ", session " + mSession);
            if (mSurface != null) {
                try {
                    if (SHOW_TRANSACTIONS) {
                        RuntimeException ex = new RuntimeException();
                        ex.fillInStackTrace();
                        Log.i(TAG, "  SURFACE " + mSurface + ": DESTROY ("
                                + mAttrs.getTitle() + ")", ex);
                    }
                    mSurface.clear();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Exception thrown when destroying Window " + this
                        + " surface " + mSurface + " session " + mSession
                        + ": " + e.toString());
                }
                mSurface = null;
                mDrawPending = false;
                mCommitDrawPending = false;
                mReadyToShow = false;

                int i = mChildWindows.size();
                while (i > 0) {
                    i--;
                    WindowState c = (WindowState)mChildWindows.get(i);
                    c.mAttachedHidden = true;
                }
            }
        }

        boolean finishDrawingLocked() {
            if (mDrawPending) {
                if (SHOW_TRANSACTIONS || DEBUG_ORIENTATION) Log.v(
                    TAG, "finishDrawingLocked: " + mSurface);
                mCommitDrawPending = true;
                mDrawPending = false;
                return true;
            }
            return false;
        }

        // This must be called while inside a transaction.
        void commitFinishDrawingLocked(long currentTime) {
            //Log.i(TAG, "commitFinishDrawingLocked: " + mSurface);
            if (!mCommitDrawPending) {
                return;
            }
            mCommitDrawPending = false;
            mReadyToShow = true;
            final boolean starting = mAttrs.type == TYPE_APPLICATION_STARTING;
            final AppWindowToken atoken = mAppToken;
            if (atoken == null || atoken.allDrawn || starting) {
                performShowLocked();
            }
        }

        // This must be called while inside a transaction.
        boolean performShowLocked() {
            if (DEBUG_VISIBILITY) {
                RuntimeException e = new RuntimeException();
                e.fillInStackTrace();
                Log.v(TAG, "performShow on " + this
                        + ": readyToShow=" + mReadyToShow + " readyForDisplay=" + isReadyForDisplay()
                        + " starting=" + (mAttrs.type == TYPE_APPLICATION_STARTING), e);
            }
            if (mReadyToShow && isReadyForDisplay()) {
                if (SHOW_TRANSACTIONS || DEBUG_ORIENTATION) Log.i(
                        TAG, "  SURFACE " + mSurface + ": SHOW (performShowLocked)");
                if (DEBUG_VISIBILITY) Log.v(TAG, "Showing " + this
                        + " during animation: policyVis=" + mPolicyVisibility
                        + " attHidden=" + mAttachedHidden
                        + " tok.hiddenRequested="
                        + (mAppToken != null ? mAppToken.hiddenRequested : false)
                        + " tok.idden="
                        + (mAppToken != null ? mAppToken.hidden : false)
                        + " animating=" + mAnimating
                        + " tok animating="
                        + (mAppToken != null ? mAppToken.animating : false));
                if (!showSurfaceRobustlyLocked(this)) {
                    return false;
                }
                mLastAlpha = -1;
                mHasDrawn = true;
                mLastHidden = false;
                mReadyToShow = false;
                enableScreenIfNeededLocked();

                applyEnterAnimationLocked(this);

                int i = mChildWindows.size();
                while (i > 0) {
                    i--;
                    WindowState c = (WindowState)mChildWindows.get(i);
                    if (c.mSurface != null && c.mAttachedHidden) {
                        c.mAttachedHidden = false;
                        c.performShowLocked();
                    }
                }

                if (mAttrs.type != TYPE_APPLICATION_STARTING
                        && mAppToken != null) {
                    mAppToken.firstWindowDrawn = true;
                    if (mAnimation == null && mAppToken.startingData != null) {
                        if (DEBUG_STARTING_WINDOW) Log.v(TAG, "Finish starting "
                                + mToken
                                + ": first real window is shown, no animation");
                        mFinishedStarting.add(mAppToken);
                        mH.sendEmptyMessage(H.FINISHED_STARTING);
                    }
                    mAppToken.updateReportedVisibilityLocked();
                }
            }
            return true;
        }

        // This must be called while inside a transaction.  Returns true if
        // there is more animation to run.
        boolean stepAnimationLocked(long currentTime, int dw, int dh) {
            if (!mDisplayFrozen) {
                // We will run animations as long as the display isn't frozen.

                if (!mDrawPending && !mCommitDrawPending && mAnimation != null) {
                    mHasTransformation = true;
                    mHasLocalTransformation = true;
                    if (!mLocalAnimating) {
                        if (DEBUG_ANIM) Log.v(
                            TAG, "Starting animation in " + this +
                            " @ " + currentTime + ": ww=" + mFrame.width() + " wh=" + mFrame.height() +
                            " dw=" + dw + " dh=" + dh + " scale=" + mWindowAnimationScale);
                        mAnimation.initialize(mFrame.width(), mFrame.height(), dw, dh);
                        mAnimation.setStartTime(currentTime);
                        mLocalAnimating = true;
                        mAnimating = true;
                    }
                    mTransformation.clear();
                    final boolean more = mAnimation.getTransformation(
                        currentTime, mTransformation);
                    if (DEBUG_ANIM) Log.v(
                        TAG, "Stepped animation in " + this +
                        ": more=" + more + ", xform=" + mTransformation);
                    if (more) {
                        // we're not done!
                        return true;
                    }
                    if (DEBUG_ANIM) Log.v(
                        TAG, "Finished animation in " + this +
                        " @ " + currentTime);
                    mAnimation = null;
                    //WindowManagerService.this.dump();
                }
                mHasLocalTransformation = false;
                if ((!mLocalAnimating || mAnimationIsEntrance) && mAppToken != null
                        && mAppToken.hasTransformation) {
                    // When our app token is animating, we kind-of pretend like
                    // we are as well.  Note the mLocalAnimating mAnimationIsEntrance
                    // part of this check means that we will only do this if
                    // our window is not currently exiting, or it is not
                    // locally animating itself.  The idea being that one that
                    // is exiting and doing a local animation should be removed
                    // once that animation is done.
                    mAnimating = true;
                    mHasTransformation = true;
                    mTransformation.clear();
                    return false;
                } else if (mHasTransformation) {
                    // Little trick to get through the path below to act like
                    // we have finished an animation.
                    mAnimating = true;
                } else if (isAnimating()) {
                    mAnimating = true;
                }
            } else if (mAnimation != null) {
                // If the display is frozen, and there is a pending animation,
                // clear it and make sure we run the cleanup code.
                mAnimating = true;
                mLocalAnimating = true;
                mAnimation = null;
            }

            if (!mAnimating && !mLocalAnimating) {
                return false;
            }

            if (DEBUG_ANIM) Log.v(
                TAG, "Animation done in " + this + ": exiting=" + mExiting
                + ", reportedVisible="
                + (mAppToken != null ? mAppToken.reportedVisible : false));

            mAnimating = false;
            mLocalAnimating = false;
            mAnimation = null;
            mAnimLayer = mLayer;
            if (mIsImWindow) {
                mAnimLayer += mInputMethodAnimLayerAdjustment;
            }
            if (DEBUG_LAYERS) Log.v(TAG, "Stepping win " + this
                    + " anim layer: " + mAnimLayer);
            mHasTransformation = false;
            mHasLocalTransformation = false;
            mPolicyVisibility = mPolicyVisibilityAfterAnim;
            mTransformation.clear();
            if (mHasDrawn
                    && mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                    && mAppToken != null
                    && mAppToken.firstWindowDrawn
                    && mAppToken.startingData != null) {
                if (DEBUG_STARTING_WINDOW) Log.v(TAG, "Finish starting "
                        + mToken + ": first real window done animating");
                mFinishedStarting.add(mAppToken);
                mH.sendEmptyMessage(H.FINISHED_STARTING);
            }

            finishExit();

            if (mAppToken != null) {
                mAppToken.updateReportedVisibilityLocked();
            }

            return false;
        }

        void finishExit() {
            if (DEBUG_ANIM) Log.v(
                    TAG, "finishExit in " + this
                    + ": exiting=" + mExiting
                    + " remove=" + mRemoveOnExit
                    + " windowAnimating=" + isWindowAnimating());

            final int N = mChildWindows.size();
            for (int i=0; i<N; i++) {
                ((WindowState)mChildWindows.get(i)).finishExit();
            }

            if (!mExiting) {
                return;
            }

            if (isWindowAnimating()) {
                return;
            }

            if (localLOGV) Log.v(
                    TAG, "Exit animation finished in " + this
                    + ": remove=" + mRemoveOnExit);
            if (mSurface != null) {
                mDestroySurface.add(this);
                mDestroying = true;
                if (SHOW_TRANSACTIONS) Log.i(
                        TAG, "  SURFACE " + mSurface + ": HIDE (finishExit)");
                try {
                    mSurface.hide();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Error hiding surface in " + this, e);
                }
                mLastHidden = true;
                mKeyWaiter.releasePendingPointerLocked(mSession);
            }
            mExiting = false;
            if (mRemoveOnExit) {
                mPendingRemove.add(this);
                mRemoveOnExit = false;
            }
        }

        boolean isIdentityMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
            if (dsdx < .99999f || dsdx > 1.00001f) return false;
            if (dtdy < .99999f || dtdy > 1.00001f) return false;
            if (dtdx < -.000001f || dtdx > .000001f) return false;
            if (dsdy < -.000001f || dsdy > .000001f) return false;
            return true;
        }

        void computeShownFrameLocked() {
            final boolean selfTransformation = mHasLocalTransformation;
            Transformation attachedTransformation =
                    (mAttachedWindow != null && mAttachedWindow.mHasLocalTransformation)
                    ? mAttachedWindow.mTransformation : null;
            Transformation appTransformation =
                    (mAppToken != null && mAppToken.hasTransformation)
                    ? mAppToken.transformation : null;
            if (selfTransformation || attachedTransformation != null
                    || appTransformation != null) {
                // cache often used attributes locally
                final Rect frame = mFrame;
                final float tmpFloats[] = mTmpFloats;
                final Matrix tmpMatrix = mTmpMatrix;

                // Compute the desired transformation.
                tmpMatrix.setTranslate(frame.left, frame.top);
                if (selfTransformation) {
                    tmpMatrix.preConcat(mTransformation.getMatrix());
                }
                if (attachedTransformation != null) {
                    tmpMatrix.preConcat(attachedTransformation.getMatrix());
                }
                if (appTransformation != null) {
                    tmpMatrix.preConcat(appTransformation.getMatrix());
                }

                // "convert" it into SurfaceFlinger's format
                // (a 2x2 matrix + an offset)
                // Here we must not transform the position of the surface
                // since it is already included in the transformation.
                //Log.i(TAG, "Transform: " + matrix);

                tmpMatrix.getValues(tmpFloats);
                mDsDx = tmpFloats[Matrix.MSCALE_X];
                mDtDx = tmpFloats[Matrix.MSKEW_X];
                mDsDy = tmpFloats[Matrix.MSKEW_Y];
                mDtDy = tmpFloats[Matrix.MSCALE_Y];
                int x = (int)tmpFloats[Matrix.MTRANS_X];
                int y = (int)tmpFloats[Matrix.MTRANS_Y];
                int w = frame.width();
                int h = frame.height();
                mShownFrame.set(x, y, x+w, y+h);

                // Now set the alpha...  but because our current hardware
                // can't do alpha transformation on a non-opaque surface,
                // turn it off if we are running an animation that is also
                // transforming since it is more important to have that
                // animation be smooth.
                mShownAlpha = mAlpha;
                if (!mLimitedAlphaCompositing
                        || (!PixelFormat.formatHasAlpha(mAttrs.format)
                        || (isIdentityMatrix(mDsDx, mDtDx, mDsDy, mDtDy)
                                && x == frame.left && y == frame.top))) {
                    //Log.i(TAG, "Applying alpha transform");
                    if (selfTransformation) {
                        mShownAlpha *= mTransformation.getAlpha();
                    }
                    if (attachedTransformation != null) {
                        mShownAlpha *= attachedTransformation.getAlpha();
                    }
                    if (appTransformation != null) {
                        mShownAlpha *= appTransformation.getAlpha();
                    }
                } else {
                    //Log.i(TAG, "Not applying alpha transform");
                }

                if (localLOGV) Log.v(
                    TAG, "Continuing animation in " + this +
                    ": " + mShownFrame +
                    ", alpha=" + mTransformation.getAlpha());
                return;
            }

            mShownFrame.set(mFrame);
            mShownAlpha = mAlpha;
            mDsDx = 1;
            mDtDx = 0;
            mDsDy = 0;
            mDtDy = 1;
        }

        /**
         * Is this window visible?  It is not visible if there is no
         * surface, or we are in the process of running an exit animation
         * that will remove the surface, or its app token has been hidden.
         */
        public boolean isVisibleLw() {
            final AppWindowToken atoken = mAppToken;
            return mSurface != null && mPolicyVisibility && !mAttachedHidden
                    && (atoken == null || !atoken.hiddenRequested)
                    && !mExiting && !mDestroying;
        }

        /**
         * Is this window visible, ignoring its app token?  It is not visible
         * if there is no surface, or we are in the process of running an exit animation
         * that will remove the surface.
         */
        public boolean isWinVisibleLw() {
            final AppWindowToken atoken = mAppToken;
            return mSurface != null && mPolicyVisibility && !mAttachedHidden
                    && (atoken == null || !atoken.hiddenRequested || atoken.animating)
                    && !mExiting && !mDestroying;
        }

        /**
         * The same as isVisible(), but follows the current hidden state of
         * the associated app token, not the pending requested hidden state.
         */
        boolean isVisibleNow() {
            return mSurface != null && mPolicyVisibility && !mAttachedHidden
                    && !mRootToken.hidden && !mExiting && !mDestroying;
        }

        /**
         * Same as isVisible(), but we also count it as visible between the
         * call to IWindowSession.add() and the first relayout().
         */
        boolean isVisibleOrAdding() {
            final AppWindowToken atoken = mAppToken;
            return (mSurface != null
                            || (!mRelayoutCalled && mViewVisibility == View.VISIBLE))
                    && mPolicyVisibility && !mAttachedHidden
                    && (atoken == null || !atoken.hiddenRequested)
                    && !mExiting && !mDestroying;
        }

        /**
         * Is this window currently on-screen?  It is on-screen either if it
         * is visible or it is currently running an animation before no longer
         * being visible.
         */
        boolean isOnScreen() {
            final AppWindowToken atoken = mAppToken;
            if (atoken != null) {
                return mSurface != null && mPolicyVisibility && !mDestroying
                        && ((!mAttachedHidden && !atoken.hiddenRequested)
                                || mAnimating || atoken.animating);
            } else {
                return mSurface != null && mPolicyVisibility && !mDestroying
                        && (!mAttachedHidden || mAnimating);
            }
        }

        /**
         * Like isOnScreen(), but we don't return true if the window is part
         * of a transition that has not yet been started.
         */
        boolean isReadyForDisplay() {
            final AppWindowToken atoken = mAppToken;
            final boolean animating = atoken != null ? atoken.animating : false;
            return mSurface != null && mPolicyVisibility && !mDestroying
                    && ((!mAttachedHidden && !mRootToken.hidden)
                            || mAnimating || animating);
        }

        /** Is the window or its container currently animating? */
        boolean isAnimating() {
            final WindowState attached = mAttachedWindow;
            final AppWindowToken atoken = mAppToken;
            return mAnimation != null
                    || (attached != null && attached.mAnimation != null)
                    || (atoken != null &&
                            (atoken.animation != null
                                    || atoken.inPendingTransaction));
        }

        /** Is this window currently animating? */
        boolean isWindowAnimating() {
            return mAnimation != null;
        }

        /**
         * Like isOnScreen, but returns false if the surface hasn't yet
         * been drawn.
         */
        public boolean isDisplayedLw() {
            final AppWindowToken atoken = mAppToken;
            return mSurface != null && mPolicyVisibility && !mDestroying
                && !mDrawPending && !mCommitDrawPending
                && ((!mAttachedHidden &&
                        (atoken == null || !atoken.hiddenRequested))
                        || mAnimating);
        }

        public boolean fillsScreenLw(int screenWidth, int screenHeight,
                                   boolean shownFrame, boolean onlyOpaque) {
            if (mSurface == null) {
                return false;
            }
            if (mAppToken != null && !mAppToken.appFullscreen) {
                return false;
            }
            if (onlyOpaque && mAttrs.format != PixelFormat.OPAQUE) {
                return false;
            }
            final Rect frame = shownFrame ? mShownFrame : mFrame;

            if ((mAttrs.flags & FLAG_COMPATIBLE_WINDOW) != 0) {
                return frame.left <= mCompatibleScreenFrame.left &&
                        frame.top <= mCompatibleScreenFrame.top &&
                        frame.right >= mCompatibleScreenFrame.right &&
                        frame.bottom >= mCompatibleScreenFrame.bottom;
            } else {
                return frame.left <= 0 && frame.top <= 0
                        && frame.right >= screenWidth
                        && frame.bottom >= screenHeight;
            }
        }

        /**
         * Return true if the window is opaque and fully drawn.
         */
        boolean isOpaqueDrawn() {
            return mAttrs.format == PixelFormat.OPAQUE && mSurface != null
                    && mAnimation == null && !mDrawPending && !mCommitDrawPending;
        }

        boolean needsBackgroundFiller(int screenWidth, int screenHeight) {
            return
                 // only if the application is requesting compatible window
                 (mAttrs.flags & FLAG_COMPATIBLE_WINDOW) != 0 &&
                 // only if it's visible
                 mHasDrawn && mViewVisibility == View.VISIBLE &&
                 // and only if the application fills the compatible screen
                 mFrame.left <= mCompatibleScreenFrame.left &&
                 mFrame.top <= mCompatibleScreenFrame.top &&
                 mFrame.right >= mCompatibleScreenFrame.right &&
                 mFrame.bottom >= mCompatibleScreenFrame.bottom &&
                 // and starting window do not need background filler
                 mAttrs.type != mAttrs.TYPE_APPLICATION_STARTING;
        }

        boolean isFullscreen(int screenWidth, int screenHeight) {
            return mFrame.left <= 0 && mFrame.top <= 0 &&
                mFrame.right >= screenWidth && mFrame.bottom >= screenHeight;
        }

        void removeLocked() {
            if (mAttachedWindow != null) {
                mAttachedWindow.mChildWindows.remove(this);
            }
            destroySurfaceLocked();
            mSession.windowRemovedLocked();
            try {
                mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
            } catch (RuntimeException e) {
                // Ignore if it has already been removed (usually because
                // we are doing this as part of processing a death note.)
            }
        }

        private class DeathRecipient implements IBinder.DeathRecipient {
            public void binderDied() {
                try {
                    synchronized(mWindowMap) {
                        WindowState win = windowForClientLocked(mSession, mClient);
                        Log.i(TAG, "WIN DEATH: " + win);
                        if (win != null) {
                            removeWindowLocked(mSession, win);
                        }
                    }
                } catch (IllegalArgumentException ex) {
                    // This will happen if the window has already been
                    // removed.
                }
            }
        }

        /** Returns true if this window desires key events. */
        public final boolean canReceiveKeys() {
            return     isVisibleOrAdding()
                    && (mViewVisibility == View.VISIBLE)
                    && ((mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0);
        }

        public boolean hasDrawnLw() {
            return mHasDrawn;
        }

        public boolean showLw(boolean doAnimation) {
            if (!mPolicyVisibility || !mPolicyVisibilityAfterAnim) {
                mPolicyVisibility = true;
                mPolicyVisibilityAfterAnim = true;
                if (doAnimation) {
                    applyAnimationLocked(this, WindowManagerPolicy.TRANSIT_ENTER, true);
                }
                requestAnimationLocked(0);
                return true;
            }
            return false;
        }

        public boolean hideLw(boolean doAnimation) {
            boolean current = doAnimation ? mPolicyVisibilityAfterAnim
                    : mPolicyVisibility;
            if (current) {
                if (doAnimation) {
                    applyAnimationLocked(this, WindowManagerPolicy.TRANSIT_EXIT, false);
                    if (mAnimation == null) {
                        doAnimation = false;
                    }
                }
                if (doAnimation) {
                    mPolicyVisibilityAfterAnim = false;
                } else {
                    mPolicyVisibilityAfterAnim = false;
                    mPolicyVisibility = false;
                }
                requestAnimationLocked(0);
                return true;
            }
            return false;
        }

        void dump(PrintWriter pw, String prefix) {
            StringBuilder sb = new StringBuilder(64);

            pw.print(prefix); pw.print("mSession="); pw.print(mSession);
                    pw.print(" mClient="); pw.println(mClient.asBinder());
            pw.print(prefix); pw.print("mAttrs="); pw.println(mAttrs);
            if (mAttachedWindow != null || mLayoutAttached) {
                pw.print(prefix); pw.print("mAttachedWindow="); pw.print(mAttachedWindow);
                        pw.print(" mLayoutAttached="); pw.println(mLayoutAttached);
            }
            if (mIsImWindow) {
                pw.print(prefix); pw.print("mIsImWindow="); pw.println(mIsImWindow);
            }
            pw.print(prefix); pw.print("mBaseLayer="); pw.print(mBaseLayer);
                    pw.print(" mSubLayer="); pw.print(mSubLayer);
                    pw.print(" mAnimLayer="); pw.print(mLayer); pw.print("+");
                    pw.print((mTargetAppToken != null ? mTargetAppToken.animLayerAdjustment
                          : (mAppToken != null ? mAppToken.animLayerAdjustment : 0)));
                    pw.print("="); pw.print(mAnimLayer);
                    pw.print(" mLastLayer="); pw.println(mLastLayer);
            if (mSurface != null) {
                pw.print(prefix); pw.print("mSurface="); pw.println(mSurface);
            }
            pw.print(prefix); pw.print("mToken="); pw.println(mToken);
            pw.print(prefix); pw.print("mRootToken="); pw.println(mRootToken);
            if (mAppToken != null) {
                pw.print(prefix); pw.print("mAppToken="); pw.println(mAppToken);
            }
            if (mTargetAppToken != null) {
                pw.print(prefix); pw.print("mTargetAppToken="); pw.println(mTargetAppToken);
            }
            pw.print(prefix); pw.print("mViewVisibility=0x");
                    pw.print(Integer.toHexString(mViewVisibility));
                    pw.print(" mLastHidden="); pw.print(mLastHidden);
                    pw.print(" mHaveFrame="); pw.println(mHaveFrame);
            if (!mPolicyVisibility || !mPolicyVisibilityAfterAnim || mAttachedHidden) {
                pw.print(prefix); pw.print("mPolicyVisibility=");
                        pw.print(mPolicyVisibility);
                        pw.print(" mPolicyVisibilityAfterAnim=");
                        pw.print(mPolicyVisibilityAfterAnim);
                        pw.print(" mAttachedHidden="); pw.println(mAttachedHidden);
            }
            pw.print(prefix); pw.print("Requested w="); pw.print(mRequestedWidth);
                    pw.print(" h="); pw.print(mRequestedHeight);
                    pw.print(" x="); pw.print(mReqXPos);
                    pw.print(" y="); pw.println(mReqYPos);
            pw.print(prefix); pw.print("mGivenContentInsets=");
                    mGivenContentInsets.printShortString(pw);
                    pw.print(" mGivenVisibleInsets=");
                    mGivenVisibleInsets.printShortString(pw);
                    pw.println();
            if (mTouchableInsets != 0 || mGivenInsetsPending) {
                pw.print(prefix); pw.print("mTouchableInsets="); pw.print(mTouchableInsets);
                        pw.print(" mGivenInsetsPending="); pw.println(mGivenInsetsPending);
            }
            pw.print(prefix); pw.print("mShownFrame=");
                    mShownFrame.printShortString(pw);
                    pw.print(" last="); mLastShownFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("mFrame="); mFrame.printShortString(pw);
                    pw.print(" last="); mLastFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("mContainingFrame=");
                    mContainingFrame.printShortString(pw);
                    pw.print(" mDisplayFrame=");
                    mDisplayFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("mContentFrame="); mContentFrame.printShortString(pw);
                    pw.print(" mVisibleFrame="); mVisibleFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("mContentInsets="); mContentInsets.printShortString(pw);
                    pw.print(" last="); mLastContentInsets.printShortString(pw);
                    pw.print(" mVisibleInsets="); mVisibleInsets.printShortString(pw);
                    pw.print(" last="); mLastVisibleInsets.printShortString(pw);
                    pw.println();
            if (mShownAlpha != 1 || mAlpha != 1 || mLastAlpha != 1) {
                pw.print(prefix); pw.print("mShownAlpha="); pw.print(mShownAlpha);
                        pw.print(" mAlpha="); pw.print(mAlpha);
                        pw.print(" mLastAlpha="); pw.println(mLastAlpha);
            }
            if (mAnimating || mLocalAnimating || mAnimationIsEntrance
                    || mAnimation != null) {
                pw.print(prefix); pw.print("mAnimating="); pw.print(mAnimating);
                        pw.print(" mLocalAnimating="); pw.print(mLocalAnimating);
                        pw.print(" mAnimationIsEntrance="); pw.print(mAnimationIsEntrance);
                        pw.print(" mAnimation="); pw.println(mAnimation);
            }
            if (mHasTransformation || mHasLocalTransformation) {
                pw.print(prefix); pw.print("XForm: has=");
                        pw.print(mHasTransformation);
                        pw.print(" hasLocal="); pw.print(mHasLocalTransformation);
                        pw.print(" "); mTransformation.printShortString(pw);
                        pw.println();
            }
            pw.print(prefix); pw.print("mDrawPending="); pw.print(mDrawPending);
                    pw.print(" mCommitDrawPending="); pw.print(mCommitDrawPending);
                    pw.print(" mReadyToShow="); pw.print(mReadyToShow);
                    pw.print(" mHasDrawn="); pw.println(mHasDrawn);
            if (mExiting || mRemoveOnExit || mDestroying || mRemoved) {
                pw.print(prefix); pw.print("mExiting="); pw.print(mExiting);
                        pw.print(" mRemoveOnExit="); pw.print(mRemoveOnExit);
                        pw.print(" mDestroying="); pw.print(mDestroying);
                        pw.print(" mRemoved="); pw.println(mRemoved);
            }
            if (mOrientationChanging || mAppFreezing) {
                pw.print(prefix); pw.print("mOrientationChanging=");
                        pw.print(mOrientationChanging);
                        pw.print(" mAppFreezing="); pw.println(mAppFreezing);
            }
            if (mHScale != 1 || mVScale != 1) {
                pw.print(prefix); pw.print("mHScale="); pw.print(mHScale);
                        pw.print(" mVScale="); pw.println(mVScale);
            }
        }

        @Override
        public String toString() {
            return "Window{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + mAttrs.getTitle() + " paused=" + mToken.paused + "}";
        }
    }

    // -------------------------------------------------------------
    // Window Token State
    // -------------------------------------------------------------

    class WindowToken {
        // The actual token.
        final IBinder token;

        // The type of window this token is for, as per WindowManager.LayoutParams.
        final int windowType;

        // Set if this token was explicitly added by a client, so should
        // not be removed when all windows are removed.
        final boolean explicit;

        // For printing.
        String stringName;

        // If this is an AppWindowToken, this is non-null.
        AppWindowToken appWindowToken;

        // All of the windows associated with this token.
        final ArrayList<WindowState> windows = new ArrayList<WindowState>();

        // Is key dispatching paused for this token?
        boolean paused = false;

        // Should this token's windows be hidden?
        boolean hidden;

        // Temporary for finding which tokens no longer have visible windows.
        boolean hasVisible;

        WindowToken(IBinder _token, int type, boolean _explicit) {
            token = _token;
            windowType = type;
            explicit = _explicit;
        }

        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("token="); pw.println(token);
            pw.print(prefix); pw.print("windows="); pw.println(windows);
            pw.print(prefix); pw.print("windowType="); pw.print(windowType);
                    pw.print(" hidden="); pw.print(hidden);
                    pw.print(" hasVisible="); pw.println(hasVisible);
        }

        @Override
        public String toString() {
            if (stringName == null) {
                StringBuilder sb = new StringBuilder();
                sb.append("WindowToken{");
                sb.append(Integer.toHexString(System.identityHashCode(this)));
                sb.append(" token="); sb.append(token); sb.append('}');
                stringName = sb.toString();
            }
            return stringName;
        }
    };

    class AppWindowToken extends WindowToken {
        // Non-null only for application tokens.
        final IApplicationToken appToken;

        // All of the windows and child windows that are included in this
        // application token.  Note this list is NOT sorted!
        final ArrayList<WindowState> allAppWindows = new ArrayList<WindowState>();

        int groupId = -1;
        boolean appFullscreen;
        int requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

        // These are used for determining when all windows associated with
        // an activity have been drawn, so they can be made visible together
        // at the same time.
        int lastTransactionSequence = mTransactionSequence-1;
        int numInterestingWindows;
        int numDrawnWindows;
        boolean inPendingTransaction;
        boolean allDrawn;

        // Is this token going to be hidden in a little while?  If so, it
        // won't be taken into account for setting the screen orientation.
        boolean willBeHidden;

        // Is this window's surface needed?  This is almost like hidden, except
        // it will sometimes be true a little earlier: when the token has
        // been shown, but is still waiting for its app transition to execute
        // before making its windows shown.
        boolean hiddenRequested;

        // Have we told the window clients to hide themselves?
        boolean clientHidden;

        // Last visibility state we reported to the app token.
        boolean reportedVisible;

        // Set to true when the token has been removed from the window mgr.
        boolean removed;

        // Have we been asked to have this token keep the screen frozen?
        boolean freezingScreen;

        boolean animating;
        Animation animation;
        boolean hasTransformation;
        final Transformation transformation = new Transformation();

        // Offset to the window of all layers in the token, for use by
        // AppWindowToken animations.
        int animLayerAdjustment;

        // Information about an application starting window if displayed.
        StartingData startingData;
        WindowState startingWindow;
        View startingView;
        boolean startingDisplayed;
        boolean startingMoved;
        boolean firstWindowDrawn;

        AppWindowToken(IApplicationToken _token) {
            super(_token.asBinder(),
                    WindowManager.LayoutParams.TYPE_APPLICATION, true);
            appWindowToken = this;
            appToken = _token;
        }

        public void setAnimation(Animation anim) {
            if (localLOGV) Log.v(
                TAG, "Setting animation in " + this + ": " + anim);
            animation = anim;
            animating = false;
            anim.restrictDuration(MAX_ANIMATION_DURATION);
            anim.scaleCurrentDuration(mTransitionAnimationScale);
            int zorder = anim.getZAdjustment();
            int adj = 0;
            if (zorder == Animation.ZORDER_TOP) {
                adj = TYPE_LAYER_OFFSET;
            } else if (zorder == Animation.ZORDER_BOTTOM) {
                adj = -TYPE_LAYER_OFFSET;
            }

            if (animLayerAdjustment != adj) {
                animLayerAdjustment = adj;
                updateLayers();
            }
        }

        public void setDummyAnimation() {
            if (animation == null) {
                if (localLOGV) Log.v(
                    TAG, "Setting dummy animation in " + this);
                animation = sDummyAnimation;
            }
        }

        public void clearAnimation() {
            if (animation != null) {
                animation = null;
                animating = true;
            }
        }

        void updateLayers() {
            final int N = allAppWindows.size();
            final int adj = animLayerAdjustment;
            for (int i=0; i<N; i++) {
                WindowState w = allAppWindows.get(i);
                w.mAnimLayer = w.mLayer + adj;
                if (DEBUG_LAYERS) Log.v(TAG, "Updating layer " + w + ": "
                        + w.mAnimLayer);
                if (w == mInputMethodTarget) {
                    setInputMethodAnimLayerAdjustment(adj);
                }
            }
        }

        void sendAppVisibilityToClients() {
            final int N = allAppWindows.size();
            for (int i=0; i<N; i++) {
                WindowState win = allAppWindows.get(i);
                if (win == startingWindow && clientHidden) {
                    // Don't hide the starting window.
                    continue;
                }
                try {
                    if (DEBUG_VISIBILITY) Log.v(TAG,
                            "Setting visibility of " + win + ": " + (!clientHidden));
                    win.mClient.dispatchAppVisibility(!clientHidden);
                } catch (RemoteException e) {
                }
            }
        }

        void showAllWindowsLocked() {
            final int NW = allAppWindows.size();
            for (int i=0; i<NW; i++) {
                WindowState w = allAppWindows.get(i);
                if (DEBUG_VISIBILITY) Log.v(TAG,
                        "performing show on: " + w);
                w.performShowLocked();
            }
        }

        // This must be called while inside a transaction.
        boolean stepAnimationLocked(long currentTime, int dw, int dh) {
            if (!mDisplayFrozen) {
                // We will run animations as long as the display isn't frozen.

                if (animation == sDummyAnimation) {
                    // This guy is going to animate, but not yet.  For now count
                    // it is not animating for purposes of scheduling transactions;
                    // when it is really time to animate, this will be set to
                    // a real animation and the next call will execute normally.
                    return false;
                }

                if ((allDrawn || animating || startingDisplayed) && animation != null) {
                    if (!animating) {
                        if (DEBUG_ANIM) Log.v(
                            TAG, "Starting animation in " + this +
                            " @ " + currentTime + ": dw=" + dw + " dh=" + dh
                            + " scale=" + mTransitionAnimationScale
                            + " allDrawn=" + allDrawn + " animating=" + animating);
                        animation.initialize(dw, dh, dw, dh);
                        animation.setStartTime(currentTime);
                        animating = true;
                    }
                    transformation.clear();
                    final boolean more = animation.getTransformation(
                        currentTime, transformation);
                    if (DEBUG_ANIM) Log.v(
                        TAG, "Stepped animation in " + this +
                        ": more=" + more + ", xform=" + transformation);
                    if (more) {
                        // we're done!
                        hasTransformation = true;
                        return true;
                    }
                    if (DEBUG_ANIM) Log.v(
                        TAG, "Finished animation in " + this +
                        " @ " + currentTime);
                    animation = null;
                }
            } else if (animation != null) {
                // If the display is frozen, and there is a pending animation,
                // clear it and make sure we run the cleanup code.
                animating = true;
                animation = null;
            }

            hasTransformation = false;

            if (!animating) {
                return false;
            }

            clearAnimation();
            animating = false;
            if (mInputMethodTarget != null && mInputMethodTarget.mAppToken == this) {
                moveInputMethodWindowsIfNeededLocked(true);
            }

            if (DEBUG_ANIM) Log.v(
                    TAG, "Animation done in " + this
                    + ": reportedVisible=" + reportedVisible);

            transformation.clear();
            if (animLayerAdjustment != 0) {
                animLayerAdjustment = 0;
                updateLayers();
            }

            final int N = windows.size();
            for (int i=0; i<N; i++) {
                ((WindowState)windows.get(i)).finishExit();
            }
            updateReportedVisibilityLocked();

            return false;
        }

        void updateReportedVisibilityLocked() {
            if (appToken == null) {
                return;
            }

            int numInteresting = 0;
            int numVisible = 0;
            boolean nowGone = true;

            if (DEBUG_VISIBILITY) Log.v(TAG, "Update reported visibility: " + this);
            final int N = allAppWindows.size();
            for (int i=0; i<N; i++) {
                WindowState win = allAppWindows.get(i);
                if (win == startingWindow || win.mAppFreezing) {
                    continue;
                }
                if (DEBUG_VISIBILITY) {
                    Log.v(TAG, "Win " + win + ": isDisplayed="
                            + win.isDisplayedLw()
                            + ", isAnimating=" + win.isAnimating());
                    if (!win.isDisplayedLw()) {
                        Log.v(TAG, "Not displayed: s=" + win.mSurface
                                + " pv=" + win.mPolicyVisibility
                                + " dp=" + win.mDrawPending
                                + " cdp=" + win.mCommitDrawPending
                                + " ah=" + win.mAttachedHidden
                                + " th="
                                + (win.mAppToken != null
                                        ? win.mAppToken.hiddenRequested : false)
                                + " a=" + win.mAnimating);
                    }
                }
                numInteresting++;
                if (win.isDisplayedLw()) {
                    if (!win.isAnimating()) {
                        numVisible++;
                    }
                    nowGone = false;
                } else if (win.isAnimating()) {
                    nowGone = false;
                }
            }

            boolean nowVisible = numInteresting > 0 && numVisible >= numInteresting;
            if (DEBUG_VISIBILITY) Log.v(TAG, "VIS " + this + ": interesting="
                    + numInteresting + " visible=" + numVisible);
            if (nowVisible != reportedVisible) {
                if (DEBUG_VISIBILITY) Log.v(
                        TAG, "Visibility changed in " + this
                        + ": vis=" + nowVisible);
                reportedVisible = nowVisible;
                Message m = mH.obtainMessage(
                        H.REPORT_APPLICATION_TOKEN_WINDOWS,
                        nowVisible ? 1 : 0,
                        nowGone ? 1 : 0,
                        this);
                    mH.sendMessage(m);
            }
        }

        void dump(PrintWriter pw, String prefix) {
            super.dump(pw, prefix);
            if (appToken != null) {
                pw.print(prefix); pw.println("app=true");
            }
            if (allAppWindows.size() > 0) {
                pw.print(prefix); pw.print("allAppWindows="); pw.println(allAppWindows);
            }
            pw.print(prefix); pw.print("groupId="); pw.print(groupId);
                    pw.print(" requestedOrientation="); pw.println(requestedOrientation);
            pw.print(prefix); pw.print("hiddenRequested="); pw.print(hiddenRequested);
                    pw.print(" clientHidden="); pw.print(clientHidden);
                    pw.print(" willBeHidden="); pw.print(willBeHidden);
                    pw.print(" reportedVisible="); pw.println(reportedVisible);
            if (paused || freezingScreen) {
                pw.print(prefix); pw.print("paused="); pw.print(paused);
                        pw.print(" freezingScreen="); pw.println(freezingScreen);
            }
            if (numInterestingWindows != 0 || numDrawnWindows != 0
                    || inPendingTransaction || allDrawn) {
                pw.print(prefix); pw.print("numInterestingWindows=");
                        pw.print(numInterestingWindows);
                        pw.print(" numDrawnWindows="); pw.print(numDrawnWindows);
                        pw.print(" inPendingTransaction="); pw.print(inPendingTransaction);
                        pw.print(" allDrawn="); pw.println(allDrawn);
            }
            if (animating || animation != null) {
                pw.print(prefix); pw.print("animating="); pw.print(animating);
                        pw.print(" animation="); pw.println(animation);
            }
            if (animLayerAdjustment != 0) {
                pw.print(prefix); pw.print("animLayerAdjustment="); pw.println(animLayerAdjustment);
            }
            if (hasTransformation) {
                pw.print(prefix); pw.print("hasTransformation="); pw.print(hasTransformation);
                        pw.print(" transformation="); transformation.printShortString(pw);
                        pw.println();
            }
            if (startingData != null || removed || firstWindowDrawn) {
                pw.print(prefix); pw.print("startingData="); pw.print(startingData);
                        pw.print(" removed="); pw.print(removed);
                        pw.print(" firstWindowDrawn="); pw.println(firstWindowDrawn);
            }
            if (startingWindow != null || startingView != null
                    || startingDisplayed || startingMoved) {
                pw.print(prefix); pw.print("startingWindow="); pw.print(startingWindow);
                        pw.print(" startingView="); pw.print(startingView);
                        pw.print(" startingDisplayed="); pw.print(startingDisplayed);
                        pw.print(" startingMoved"); pw.println(startingMoved);
            }
        }

        @Override
        public String toString() {
            if (stringName == null) {
                StringBuilder sb = new StringBuilder();
                sb.append("AppWindowToken{");
                sb.append(Integer.toHexString(System.identityHashCode(this)));
                sb.append(" token="); sb.append(token); sb.append('}');
                stringName = sb.toString();
            }
            return stringName;
        }
    }

    public static WindowManager.LayoutParams findAnimations(
            ArrayList<AppWindowToken> order,
            ArrayList<AppWindowToken> openingTokenList1,
            ArrayList<AppWindowToken> closingTokenList2) {
        // We need to figure out which animation to use...

        // First, check if there is a compatible window in opening/closing
        // apps, and use it if exists.
        WindowManager.LayoutParams animParams = null;
        int animSrc = 0;
        animParams = findCompatibleWindowParams(openingTokenList1);
        if (animParams == null) {
            animParams = findCompatibleWindowParams(closingTokenList2);
        }
        if (animParams != null) {
            return animParams;
        }
        
        //Log.i(TAG, "Looking for animations...");
        for (int i=order.size()-1; i>=0; i--) {
            AppWindowToken wtoken = order.get(i);
            //Log.i(TAG, "Token " + wtoken + " with " + wtoken.windows.size() + " windows");
            if (openingTokenList1.contains(wtoken) || closingTokenList2.contains(wtoken)) {
                int j = wtoken.windows.size();
                while (j > 0) {
                    j--;
                    WindowState win = wtoken.windows.get(j);
                    //Log.i(TAG, "Window " + win + ": type=" + win.mAttrs.type);
                    if (win.mAttrs.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                            || win.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
                        //Log.i(TAG, "Found base or application window, done!");
                        if (wtoken.appFullscreen) {
                            return win.mAttrs;
                        }
                        if (animSrc < 2) {
                            animParams = win.mAttrs;
                            animSrc = 2;
                        }
                    } else if (animSrc < 1 && win.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION) {
                        //Log.i(TAG, "Found normal window, we may use this...");
                        animParams = win.mAttrs;
                        animSrc = 1;
                    }
                }
            }
        }

        return animParams;
    }

    private static LayoutParams findCompatibleWindowParams(ArrayList<AppWindowToken> tokenList) {
        for (int appCount = tokenList.size() - 1; appCount >= 0; appCount--) {
            AppWindowToken wtoken = tokenList.get(appCount);
            // Just checking one window is sufficient as all windows have the compatible flag 
            // if the application is in compatibility mode.
            if (wtoken.windows.size() > 0) {
                WindowManager.LayoutParams params = wtoken.windows.get(0).mAttrs;
                if ((params.flags & FLAG_COMPATIBLE_WINDOW) != 0) {
                    return params;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------
    // DummyAnimation
    // -------------------------------------------------------------

    // This is an animation that does nothing: it just immediately finishes
    // itself every time it is called.  It is used as a stub animation in cases
    // where we want to synchronize multiple things that may be animating.
    static final class DummyAnimation extends Animation {
        public boolean getTransformation(long currentTime, Transformation outTransformation) {
            return false;
        }
    }
    static final Animation sDummyAnimation = new DummyAnimation();

    // -------------------------------------------------------------
    // Async Handler
    // -------------------------------------------------------------

    static final class StartingData {
        final String pkg;
        final int theme;
        final CharSequence nonLocalizedLabel;
        final int labelRes;
        final int icon;

        StartingData(String _pkg, int _theme, CharSequence _nonLocalizedLabel,
                int _labelRes, int _icon) {
            pkg = _pkg;
            theme = _theme;
            nonLocalizedLabel = _nonLocalizedLabel;
            labelRes = _labelRes;
            icon = _icon;
        }
    }

    private final class H extends Handler {
        public static final int REPORT_FOCUS_CHANGE = 2;
        public static final int REPORT_LOSING_FOCUS = 3;
        public static final int ANIMATE = 4;
        public static final int ADD_STARTING = 5;
        public static final int REMOVE_STARTING = 6;
        public static final int FINISHED_STARTING = 7;
        public static final int REPORT_APPLICATION_TOKEN_WINDOWS = 8;
        public static final int WINDOW_FREEZE_TIMEOUT = 11;
        public static final int HOLD_SCREEN_CHANGED = 12;
        public static final int APP_TRANSITION_TIMEOUT = 13;
        public static final int PERSIST_ANIMATION_SCALE = 14;
        public static final int FORCE_GC = 15;
        public static final int ENABLE_SCREEN = 16;
        public static final int APP_FREEZE_TIMEOUT = 17;
        public static final int COMPUTE_AND_SEND_NEW_CONFIGURATION = 18;

        private Session mLastReportedHold;

        public H() {
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REPORT_FOCUS_CHANGE: {
                    WindowState lastFocus;
                    WindowState newFocus;

                    synchronized(mWindowMap) {
                        lastFocus = mLastFocus;
                        newFocus = mCurrentFocus;
                        if (lastFocus == newFocus) {
                            // Focus is not changing, so nothing to do.
                            return;
                        }
                        mLastFocus = newFocus;
                        //Log.i(TAG, "Focus moving from " + lastFocus
                        //        + " to " + newFocus);
                        if (newFocus != null && lastFocus != null
                                && !newFocus.isDisplayedLw()) {
                            //Log.i(TAG, "Delaying loss of focus...");
                            mLosingFocus.add(lastFocus);
                            lastFocus = null;
                        }
                    }

                    if (lastFocus != newFocus) {
                        //System.out.println("Changing focus from " + lastFocus
                        //                   + " to " + newFocus);
                        if (newFocus != null) {
                            try {
                                //Log.i(TAG, "Gaining focus: " + newFocus);
                                newFocus.mClient.windowFocusChanged(true, mInTouchMode);
                            } catch (RemoteException e) {
                                // Ignore if process has died.
                            }
                        }

                        if (lastFocus != null) {
                            try {
                                //Log.i(TAG, "Losing focus: " + lastFocus);
                                lastFocus.mClient.windowFocusChanged(false, mInTouchMode);
                            } catch (RemoteException e) {
                                // Ignore if process has died.
                            }
                        }
                    }
                } break;

                case REPORT_LOSING_FOCUS: {
                    ArrayList<WindowState> losers;

                    synchronized(mWindowMap) {
                        losers = mLosingFocus;
                        mLosingFocus = new ArrayList<WindowState>();
                    }

                    final int N = losers.size();
                    for (int i=0; i<N; i++) {
                        try {
                            //Log.i(TAG, "Losing delayed focus: " + losers.get(i));
                            losers.get(i).mClient.windowFocusChanged(false, mInTouchMode);
                        } catch (RemoteException e) {
                             // Ignore if process has died.
                        }
                    }
                } break;

                case ANIMATE: {
                    synchronized(mWindowMap) {
                        mAnimationPending = false;
                        performLayoutAndPlaceSurfacesLocked();
                    }
                } break;

                case ADD_STARTING: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;
                    final StartingData sd = wtoken.startingData;

                    if (sd == null) {
                        // Animation has been canceled... do nothing.
                        return;
                    }

                    if (DEBUG_STARTING_WINDOW) Log.v(TAG, "Add starting "
                            + wtoken + ": pkg=" + sd.pkg);

                    View view = null;
                    try {
                        view = mPolicy.addStartingWindow(
                            wtoken.token, sd.pkg,
                            sd.theme, sd.nonLocalizedLabel, sd.labelRes,
                            sd.icon);
                    } catch (Exception e) {
                        Log.w(TAG, "Exception when adding starting window", e);
                    }

                    if (view != null) {
                        boolean abort = false;

                        synchronized(mWindowMap) {
                            if (wtoken.removed || wtoken.startingData == null) {
                                // If the window was successfully added, then
                                // we need to remove it.
                                if (wtoken.startingWindow != null) {
                                    if (DEBUG_STARTING_WINDOW) Log.v(TAG,
                                            "Aborted starting " + wtoken
                                            + ": removed=" + wtoken.removed
                                            + " startingData=" + wtoken.startingData);
                                    wtoken.startingWindow = null;
                                    wtoken.startingData = null;
                                    abort = true;
                                }
                            } else {
                                wtoken.startingView = view;
                            }
                            if (DEBUG_STARTING_WINDOW && !abort) Log.v(TAG,
                                    "Added starting " + wtoken
                                    + ": startingWindow="
                                    + wtoken.startingWindow + " startingView="
                                    + wtoken.startingView);
                        }

                        if (abort) {
                            try {
                                mPolicy.removeStartingWindow(wtoken.token, view);
                            } catch (Exception e) {
                                Log.w(TAG, "Exception when removing starting window", e);
                            }
                        }
                    }
                } break;

                case REMOVE_STARTING: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;
                    IBinder token = null;
                    View view = null;
                    synchronized (mWindowMap) {
                        if (DEBUG_STARTING_WINDOW) Log.v(TAG, "Remove starting "
                                + wtoken + ": startingWindow="
                                + wtoken.startingWindow + " startingView="
                                + wtoken.startingView);
                        if (wtoken.startingWindow != null) {
                            view = wtoken.startingView;
                            token = wtoken.token;
                            wtoken.startingData = null;
                            wtoken.startingView = null;
                            wtoken.startingWindow = null;
                        }
                    }
                    if (view != null) {
                        try {
                            mPolicy.removeStartingWindow(token, view);
                        } catch (Exception e) {
                            Log.w(TAG, "Exception when removing starting window", e);
                        }
                    }
                } break;

                case FINISHED_STARTING: {
                    IBinder token = null;
                    View view = null;
                    while (true) {
                        synchronized (mWindowMap) {
                            final int N = mFinishedStarting.size();
                            if (N <= 0) {
                                break;
                            }
                            AppWindowToken wtoken = mFinishedStarting.remove(N-1);

                            if (DEBUG_STARTING_WINDOW) Log.v(TAG,
                                    "Finished starting " + wtoken
                                    + ": startingWindow=" + wtoken.startingWindow
                                    + " startingView=" + wtoken.startingView);

                            if (wtoken.startingWindow == null) {
                                continue;
                            }

                            view = wtoken.startingView;
                            token = wtoken.token;
                            wtoken.startingData = null;
                            wtoken.startingView = null;
                            wtoken.startingWindow = null;
                        }

                        try {
                            mPolicy.removeStartingWindow(token, view);
                        } catch (Exception e) {
                            Log.w(TAG, "Exception when removing starting window", e);
                        }
                    }
                } break;

                case REPORT_APPLICATION_TOKEN_WINDOWS: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;

                    boolean nowVisible = msg.arg1 != 0;
                    boolean nowGone = msg.arg2 != 0;

                    try {
                        if (DEBUG_VISIBILITY) Log.v(
                                TAG, "Reporting visible in " + wtoken
                                + " visible=" + nowVisible
                                + " gone=" + nowGone);
                        if (nowVisible) {
                            wtoken.appToken.windowsVisible();
                        } else {
                            wtoken.appToken.windowsGone();
                        }
                    } catch (RemoteException ex) {
                    }
                } break;

                case WINDOW_FREEZE_TIMEOUT: {
                    synchronized (mWindowMap) {
                        Log.w(TAG, "Window freeze timeout expired.");
                        int i = mWindows.size();
                        while (i > 0) {
                            i--;
                            WindowState w = (WindowState)mWindows.get(i);
                            if (w.mOrientationChanging) {
                                w.mOrientationChanging = false;
                                Log.w(TAG, "Force clearing orientation change: " + w);
                            }
                        }
                        performLayoutAndPlaceSurfacesLocked();
                    }
                    break;
                }

                case HOLD_SCREEN_CHANGED: {
                    Session oldHold;
                    Session newHold;
                    synchronized (mWindowMap) {
                        oldHold = mLastReportedHold;
                        newHold = (Session)msg.obj;
                        mLastReportedHold = newHold;
                    }

                    if (oldHold != newHold) {
                        try {
                            if (oldHold != null) {
                                mBatteryStats.noteStopWakelock(oldHold.mUid,
                                        "window",
                                        BatteryStats.WAKE_TYPE_WINDOW);
                            }
                            if (newHold != null) {
                                mBatteryStats.noteStartWakelock(newHold.mUid,
                                        "window",
                                        BatteryStats.WAKE_TYPE_WINDOW);
                            }
                        } catch (RemoteException e) {
                        }
                    }
                    break;
                }

                case APP_TRANSITION_TIMEOUT: {
                    synchronized (mWindowMap) {
                        if (mNextAppTransition != WindowManagerPolicy.TRANSIT_NONE) {
                            if (DEBUG_APP_TRANSITIONS) Log.v(TAG,
                                    "*** APP TRANSITION TIMEOUT");
                            mAppTransitionReady = true;
                            mAppTransitionTimeout = true;
                            performLayoutAndPlaceSurfacesLocked();
                        }
                    }
                    break;
                }

                case PERSIST_ANIMATION_SCALE: {
                    Settings.System.putFloat(mContext.getContentResolver(),
                            Settings.System.WINDOW_ANIMATION_SCALE, mWindowAnimationScale);
                    Settings.System.putFloat(mContext.getContentResolver(),
                            Settings.System.TRANSITION_ANIMATION_SCALE, mTransitionAnimationScale);
                    break;
                }

                case FORCE_GC: {
                    synchronized(mWindowMap) {
                        if (mAnimationPending) {
                            // If we are animating, don't do the gc now but
                            // delay a bit so we don't interrupt the animation.
                            mH.sendMessageDelayed(mH.obtainMessage(H.FORCE_GC),
                                    2000);
                            return;
                        }
                        // If we are currently rotating the display, it will
                        // schedule a new message when done.
                        if (mDisplayFrozen) {
                            return;
                        }
                        mFreezeGcPending = 0;
                    }
                    Runtime.getRuntime().gc();
                    break;
                }

                case ENABLE_SCREEN: {
                    performEnableScreen();
                    break;
                }

                case APP_FREEZE_TIMEOUT: {
                    synchronized (mWindowMap) {
                        Log.w(TAG, "App freeze timeout expired.");
                        int i = mAppTokens.size();
                        while (i > 0) {
                            i--;
                            AppWindowToken tok = mAppTokens.get(i);
                            if (tok.freezingScreen) {
                                Log.w(TAG, "Force clearing freeze: " + tok);
                                unsetAppFreezingScreenLocked(tok, true, true);
                            }
                        }
                    }
                    break;
                }

                case COMPUTE_AND_SEND_NEW_CONFIGURATION: {
                    if (updateOrientationFromAppTokensUnchecked(null, null) != null) {
                        sendNewConfiguration();
                    }
                    break;
                }

            }
        }
    }

    // -------------------------------------------------------------
    // IWindowManager API
    // -------------------------------------------------------------

    public IWindowSession openSession(IInputMethodClient client,
            IInputContext inputContext) {
        if (client == null) throw new IllegalArgumentException("null client");
        if (inputContext == null) throw new IllegalArgumentException("null inputContext");
        return new Session(client, inputContext);
    }

    public boolean inputMethodClientHasFocus(IInputMethodClient client) {
        synchronized (mWindowMap) {
            // The focus for the client is the window immediately below
            // where we would place the input method window.
            int idx = findDesiredInputMethodWindowIndexLocked(false);
            WindowState imFocus;
            if (idx > 0) {
                imFocus = (WindowState)mWindows.get(idx-1);
                if (imFocus != null) {
                    if (imFocus.mSession.mClient != null &&
                            imFocus.mSession.mClient.asBinder() == client.asBinder()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------

    final WindowState windowForClientLocked(Session session, IWindow client) {
        return windowForClientLocked(session, client.asBinder());
    }

    final WindowState windowForClientLocked(Session session, IBinder client) {
        WindowState win = mWindowMap.get(client);
        if (localLOGV) Log.v(
            TAG, "Looking up client " + client + ": " + win);
        if (win == null) {
            RuntimeException ex = new RuntimeException();
            Log.w(TAG, "Requested window " + client + " does not exist", ex);
            return null;
        }
        if (session != null && win.mSession != session) {
            RuntimeException ex = new RuntimeException();
            Log.w(TAG, "Requested window " + client + " is in session " +
                  win.mSession + ", not " + session, ex);
            return null;
        }

        return win;
    }

    private final void assignLayersLocked() {
        int N = mWindows.size();
        int curBaseLayer = 0;
        int curLayer = 0;
        int i;

        for (i=0; i<N; i++) {
            WindowState w = (WindowState)mWindows.get(i);
            if (w.mBaseLayer == curBaseLayer || w.mIsImWindow) {
                curLayer += WINDOW_LAYER_MULTIPLIER;
                w.mLayer = curLayer;
            } else {
                curBaseLayer = curLayer = w.mBaseLayer;
                w.mLayer = curLayer;
            }
            if (w.mTargetAppToken != null) {
                w.mAnimLayer = w.mLayer + w.mTargetAppToken.animLayerAdjustment;
            } else if (w.mAppToken != null) {
                w.mAnimLayer = w.mLayer + w.mAppToken.animLayerAdjustment;
            } else {
                w.mAnimLayer = w.mLayer;
            }
            if (w.mIsImWindow) {
                w.mAnimLayer += mInputMethodAnimLayerAdjustment;
            }
            if (DEBUG_LAYERS) Log.v(TAG, "Assign layer " + w + ": "
                    + w.mAnimLayer);
            //System.out.println(
            //    "Assigned layer " + curLayer + " to " + w.mClient.asBinder());
        }
    }

    private boolean mInLayout = false;
    private final void performLayoutAndPlaceSurfacesLocked() {
        if (mInLayout) {
            if (DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Log.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout");
            return;
        }

        boolean recoveringMemory = false;
        if (mForceRemoves != null) {
            recoveringMemory = true;
            // Wait a little it for things to settle down, and off we go.
            for (int i=0; i<mForceRemoves.size(); i++) {
                WindowState ws = mForceRemoves.get(i);
                Log.i(TAG, "Force removing: " + ws);
                removeWindowInnerLocked(ws.mSession, ws);
            }
            mForceRemoves = null;
            Log.w(TAG, "Due to memory failure, waiting a bit for next layout");
            Object tmp = new Object();
            synchronized (tmp) {
                try {
                    tmp.wait(250);
                } catch (InterruptedException e) {
                }
            }
        }

        mInLayout = true;
        try {
            performLayoutAndPlaceSurfacesLockedInner(recoveringMemory);

            int i = mPendingRemove.size()-1;
            if (i >= 0) {
                while (i >= 0) {
                    WindowState w = mPendingRemove.get(i);
                    removeWindowInnerLocked(w.mSession, w);
                    i--;
                }
                mPendingRemove.clear();

                mInLayout = false;
                assignLayersLocked();
                mLayoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();

            } else {
                mInLayout = false;
                if (mLayoutNeeded) {
                    requestAnimationLocked(0);
                }
            }
        } catch (RuntimeException e) {
            mInLayout = false;
            Log.e(TAG, "Unhandled exception while layout out windows", e);
        }
    }

    private final void performLayoutLockedInner() {
        final int dw = mDisplay.getWidth();
        final int dh = mDisplay.getHeight();

        final int N = mWindows.size();
        int repeats = 0;
        int i;

        // FIRST LOOP: Perform a layout, if needed.

        while (mLayoutNeeded) {
            mPolicy.beginLayoutLw(dw, dh);

            // First perform layout of any root windows (not attached
            // to another window).
            int topAttached = -1;
            for (i = N-1; i >= 0; i--) {
                WindowState win = (WindowState) mWindows.get(i);

                // Don't do layout of a window if it is not visible, or
                // soon won't be visible, to avoid wasting time and funky
                // changes while a window is animating away.
                final AppWindowToken atoken = win.mAppToken;
                final boolean gone = win.mViewVisibility == View.GONE
                        || !win.mRelayoutCalled
                        || win.mRootToken.hidden
                        || (atoken != null && atoken.hiddenRequested)
                        || !win.mPolicyVisibility
                        || win.mAttachedHidden
                        || win.mExiting || win.mDestroying;

                // If this view is GONE, then skip it -- keep the current
                // frame, and let the caller know so they can ignore it
                // if they want.  (We do the normal layout for INVISIBLE
                // windows, since that means "perform layout as normal,
                // just don't display").
                if (!gone || !win.mHaveFrame) {
                    if (!win.mLayoutAttached) {
                        mPolicy.layoutWindowLw(win, win.mAttrs, null);
                    } else {
                        if (topAttached < 0) topAttached = i;
                    }
                }
            }

            // Now perform layout of attached windows, which usually
            // depend on the position of the window they are attached to.
            // XXX does not deal with windows that are attached to windows
            // that are themselves attached.
            for (i = topAttached; i >= 0; i--) {
                WindowState win = (WindowState) mWindows.get(i);

                // If this view is GONE, then skip it -- keep the current
                // frame, and let the caller know so they can ignore it
                // if they want.  (We do the normal layout for INVISIBLE
                // windows, since that means "perform layout as normal,
                // just don't display").
                if (win.mLayoutAttached) {
                    if ((win.mViewVisibility != View.GONE && win.mRelayoutCalled)
                            || !win.mHaveFrame) {
                        mPolicy.layoutWindowLw(win, win.mAttrs, win.mAttachedWindow);
                    }
                }
            }

            if (!mPolicy.finishLayoutLw()) {
                mLayoutNeeded = false;
            } else if (repeats > 2) {
                Log.w(TAG, "Layout repeat aborted after too many iterations");
                mLayoutNeeded = false;
            } else {
                repeats++;
            }
        }
    }

    private final void performLayoutAndPlaceSurfacesLockedInner(
            boolean recoveringMemory) {
        final long currentTime = SystemClock.uptimeMillis();
        final int dw = mDisplay.getWidth();
        final int dh = mDisplay.getHeight();

        final int N = mWindows.size();
        int i;

        // FIRST LOOP: Perform a layout, if needed.
        performLayoutLockedInner();

        if (mFxSession == null) {
            mFxSession = new SurfaceSession();
        }

        if (SHOW_TRANSACTIONS) Log.i(TAG, ">>> OPEN TRANSACTION");

        // Initialize state of exiting tokens.
        for (i=mExitingTokens.size()-1; i>=0; i--) {
            mExitingTokens.get(i).hasVisible = false;
        }

        // Initialize state of exiting applications.
        for (i=mExitingAppTokens.size()-1; i>=0; i--) {
            mExitingAppTokens.get(i).hasVisible = false;
        }

        // SECOND LOOP: Execute animations and update visibility of windows.
        boolean orientationChangeComplete = true;
        Session holdScreen = null;
        float screenBrightness = -1;
        boolean focusDisplayed = false;
        boolean animating = false;

        Surface.openTransaction();
        try {
            boolean restart;

            do {
                final int transactionSequence = ++mTransactionSequence;

                // Update animations of all applications, including those
                // associated with exiting/removed apps
                boolean tokensAnimating = false;
                final int NAT = mAppTokens.size();
                for (i=0; i<NAT; i++) {
                    if (mAppTokens.get(i).stepAnimationLocked(currentTime, dw, dh)) {
                        tokensAnimating = true;
                    }
                }
                final int NEAT = mExitingAppTokens.size();
                for (i=0; i<NEAT; i++) {
                    if (mExitingAppTokens.get(i).stepAnimationLocked(currentTime, dw, dh)) {
                        tokensAnimating = true;
                    }
                }

                animating = tokensAnimating;
                restart = false;

                boolean tokenMayBeDrawn = false;

                mPolicy.beginAnimationLw(dw, dh);

                for (i=N-1; i>=0; i--) {
                    WindowState w = (WindowState)mWindows.get(i);

                    final WindowManager.LayoutParams attrs = w.mAttrs;

                    if (w.mSurface != null) {
                        // Execute animation.
                        w.commitFinishDrawingLocked(currentTime);
                        if (w.stepAnimationLocked(currentTime, dw, dh)) {
                            animating = true;
                            //w.dump("  ");
                        }

                        mPolicy.animatingWindowLw(w, attrs);
                    }

                    final AppWindowToken atoken = w.mAppToken;
                    if (atoken != null && (!atoken.allDrawn || atoken.freezingScreen)) {
                        if (atoken.lastTransactionSequence != transactionSequence) {
                            atoken.lastTransactionSequence = transactionSequence;
                            atoken.numInterestingWindows = atoken.numDrawnWindows = 0;
                            atoken.startingDisplayed = false;
                        }
                        if ((w.isOnScreen() || w.mAttrs.type
                                == WindowManager.LayoutParams.TYPE_BASE_APPLICATION)
                                && !w.mExiting && !w.mDestroying) {
                            if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) {
                                Log.v(TAG, "Eval win " + w + ": isDisplayed="
                                        + w.isDisplayedLw()
                                        + ", isAnimating=" + w.isAnimating());
                                if (!w.isDisplayedLw()) {
                                    Log.v(TAG, "Not displayed: s=" + w.mSurface
                                            + " pv=" + w.mPolicyVisibility
                                            + " dp=" + w.mDrawPending
                                            + " cdp=" + w.mCommitDrawPending
                                            + " ah=" + w.mAttachedHidden
                                            + " th=" + atoken.hiddenRequested
                                            + " a=" + w.mAnimating);
                                }
                            }
                            if (w != atoken.startingWindow) {
                                if (!atoken.freezingScreen || !w.mAppFreezing) {
                                    atoken.numInterestingWindows++;
                                    if (w.isDisplayedLw()) {
                                        atoken.numDrawnWindows++;
                                        if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) Log.v(TAG,
                                                "tokenMayBeDrawn: " + atoken
                                                + " freezingScreen=" + atoken.freezingScreen
                                                + " mAppFreezing=" + w.mAppFreezing);
                                        tokenMayBeDrawn = true;
                                    }
                                }
                            } else if (w.isDisplayedLw()) {
                                atoken.startingDisplayed = true;
                            }
                        }
                    } else if (w.mReadyToShow) {
                        w.performShowLocked();
                    }
                }

                if (mPolicy.finishAnimationLw()) {
                    restart = true;
                }

                if (tokenMayBeDrawn) {
                    // See if any windows have been drawn, so they (and others
                    // associated with them) can now be shown.
                    final int NT = mTokenList.size();
                    for (i=0; i<NT; i++) {
                        AppWindowToken wtoken = mTokenList.get(i).appWindowToken;
                        if (wtoken == null) {
                            continue;
                        }
                        if (wtoken.freezingScreen) {
                            int numInteresting = wtoken.numInterestingWindows;
                            if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                                if (DEBUG_VISIBILITY) Log.v(TAG,
                                        "allDrawn: " + wtoken
                                        + " interesting=" + numInteresting
                                        + " drawn=" + wtoken.numDrawnWindows);
                                wtoken.showAllWindowsLocked();
                                unsetAppFreezingScreenLocked(wtoken, false, true);
                                orientationChangeComplete = true;
                            }
                        } else if (!wtoken.allDrawn) {
                            int numInteresting = wtoken.numInterestingWindows;
                            if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                                if (DEBUG_VISIBILITY) Log.v(TAG,
                                        "allDrawn: " + wtoken
                                        + " interesting=" + numInteresting
                                        + " drawn=" + wtoken.numDrawnWindows);
                                wtoken.allDrawn = true;
                                restart = true;

                                // We can now show all of the drawn windows!
                                if (!mOpeningApps.contains(wtoken)) {
                                    wtoken.showAllWindowsLocked();
                                }
                            }
                        }
                    }
                }

                // If we are ready to perform an app transition, check through
                // all of the app tokens to be shown and see if they are ready
                // to go.
                if (mAppTransitionReady) {
                    int NN = mOpeningApps.size();
                    boolean goodToGo = true;
                    if (DEBUG_APP_TRANSITIONS) Log.v(TAG,
                            "Checking " + NN + " opening apps (frozen="
                            + mDisplayFrozen + " timeout="
                            + mAppTransitionTimeout + ")...");
                    if (!mDisplayFrozen && !mAppTransitionTimeout) {
                        // If the display isn't frozen, wait to do anything until
                        // all of the apps are ready.  Otherwise just go because
                        // we'll unfreeze the display when everyone is ready.
                        for (i=0; i<NN && goodToGo; i++) {
                            AppWindowToken wtoken = mOpeningApps.get(i);
                            if (DEBUG_APP_TRANSITIONS) Log.v(TAG,
                                    "Check opening app" + wtoken + ": allDrawn="
                                    + wtoken.allDrawn + " startingDisplayed="
                                    + wtoken.startingDisplayed);
                            if (!wtoken.allDrawn && !wtoken.startingDisplayed
                                    && !wtoken.startingMoved) {
                                goodToGo = false;
                            }
                        }
                    }
                    if (goodToGo) {
                        if (DEBUG_APP_TRANSITIONS) Log.v(TAG, "**** GOOD TO GO");
                        int transit = mNextAppTransition;
                        if (mSkipAppTransitionAnimation) {
                            transit = WindowManagerPolicy.TRANSIT_NONE;
                        }
                        mNextAppTransition = WindowManagerPolicy.TRANSIT_NONE;
                        mAppTransitionReady = false;
                        mAppTransitionTimeout = false;
                        mStartingIconInTransition = false;
                        mSkipAppTransitionAnimation = false;

                        mH.removeMessages(H.APP_TRANSITION_TIMEOUT);

                        // We need to figure out which animation to use...
                        WindowManager.LayoutParams lp = findAnimations(mAppTokens,
                                mOpeningApps, mClosingApps);

                        NN = mOpeningApps.size();
                        for (i=0; i<NN; i++) {
                            AppWindowToken wtoken = mOpeningApps.get(i);
                            if (DEBUG_APP_TRANSITIONS) Log.v(TAG,
                                    "Now opening app" + wtoken);
                            wtoken.reportedVisible = false;
                            wtoken.inPendingTransaction = false;
                            setTokenVisibilityLocked(wtoken, lp, true, transit, false);
                            wtoken.updateReportedVisibilityLocked();
                            wtoken.showAllWindowsLocked();
                        }
                        NN = mClosingApps.size();
                        for (i=0; i<NN; i++) {
                            AppWindowToken wtoken = mClosingApps.get(i);
                            if (DEBUG_APP_TRANSITIONS) Log.v(TAG,
                                    "Now closing app" + wtoken);
                            wtoken.inPendingTransaction = false;
                            setTokenVisibilityLocked(wtoken, lp, false, transit, false);
                            wtoken.updateReportedVisibilityLocked();
                            // Force the allDrawn flag, because we want to start
                            // this guy's animations regardless of whether it's
                            // gotten drawn.
                            wtoken.allDrawn = true;
                        }

                        mOpeningApps.clear();
                        mClosingApps.clear();

                        // This has changed the visibility of windows, so perform
                        // a new layout to get them all up-to-date.
                        mLayoutNeeded = true;
                        if (!moveInputMethodWindowsIfNeededLocked(true)) {
                            assignLayersLocked();
                        }
                        performLayoutLockedInner();
                        updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES);

                        restart = true;
                    }
                }
            } while (restart);

            // THIRD LOOP: Update the surfaces of all windows.

            final boolean someoneLosingFocus = mLosingFocus.size() != 0;

            boolean obscured = false;
            boolean blurring = false;
            boolean dimming = false;
            boolean covered = false;
            boolean syswin = false;
            boolean backgroundFillerShown = false;

            for (i=N-1; i>=0; i--) {
                WindowState w = (WindowState)mWindows.get(i);

                boolean displayed = false;
                final WindowManager.LayoutParams attrs = w.mAttrs;
                final int attrFlags = attrs.flags;

                if (w.mSurface != null) {
                    w.computeShownFrameLocked();
                    if (localLOGV) Log.v(
                            TAG, "Placing surface #" + i + " " + w.mSurface
                            + ": new=" + w.mShownFrame + ", old="
                            + w.mLastShownFrame);

                    boolean resize;
                    int width, height;
                    if ((w.mAttrs.flags & w.mAttrs.FLAG_SCALED) != 0) {
                        resize = w.mLastRequestedWidth != w.mRequestedWidth ||
                        w.mLastRequestedHeight != w.mRequestedHeight;
                        // for a scaled surface, we just want to use
                        // the requested size.
                        width  = w.mRequestedWidth;
                        height = w.mRequestedHeight;
                        w.mLastRequestedWidth = width;
                        w.mLastRequestedHeight = height;
                        w.mLastShownFrame.set(w.mShownFrame);
                        try {
                            w.mSurface.setPosition(w.mShownFrame.left, w.mShownFrame.top);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "Error positioning surface in " + w, e);
                            if (!recoveringMemory) {
                                reclaimSomeSurfaceMemoryLocked(w, "position");
                            }
                        }
                    } else {
                        resize = !w.mLastShownFrame.equals(w.mShownFrame);
                        width = w.mShownFrame.width();
                        height = w.mShownFrame.height();
                        w.mLastShownFrame.set(w.mShownFrame);
                        if (resize) {
                            if (SHOW_TRANSACTIONS) Log.i(
                                    TAG, "  SURFACE " + w.mSurface + ": ("
                                    + w.mShownFrame.left + ","
                                    + w.mShownFrame.top + ") ("
                                    + w.mShownFrame.width() + "x"
                                    + w.mShownFrame.height() + ")");
                        }
                    }

                    if (resize) {
                        if (width < 1) width = 1;
                        if (height < 1) height = 1;
                        if (w.mSurface != null) {
                            try {
                                w.mSurface.setSize(width, height);
                                w.mSurface.setPosition(w.mShownFrame.left,
                                        w.mShownFrame.top);
                            } catch (RuntimeException e) {
                                // If something goes wrong with the surface (such
                                // as running out of memory), don't take down the
                                // entire system.
                                Log.e(TAG, "Failure updating surface of " + w
                                        + "size=(" + width + "x" + height
                                        + "), pos=(" + w.mShownFrame.left
                                        + "," + w.mShownFrame.top + ")", e);
                                if (!recoveringMemory) {
                                    reclaimSomeSurfaceMemoryLocked(w, "size");
                                }
                            }
                        }
                    }
                    if (!w.mAppFreezing) {
                        w.mContentInsetsChanged =
                            !w.mLastContentInsets.equals(w.mContentInsets);
                        w.mVisibleInsetsChanged =
                            !w.mLastVisibleInsets.equals(w.mVisibleInsets);
                        if (!w.mLastFrame.equals(w.mFrame)
                                || w.mContentInsetsChanged
                                || w.mVisibleInsetsChanged) {
                            w.mLastFrame.set(w.mFrame);
                            w.mLastContentInsets.set(w.mContentInsets);
                            w.mLastVisibleInsets.set(w.mVisibleInsets);
                            // If the orientation is changing, then we need to
                            // hold off on unfreezing the display until this
                            // window has been redrawn; to do that, we need
                            // to go through the process of getting informed
                            // by the application when it has finished drawing.
                            if (w.mOrientationChanging) {
                                if (DEBUG_ORIENTATION) Log.v(TAG,
                                        "Orientation start waiting for draw in "
                                        + w + ", surface " + w.mSurface);
                                w.mDrawPending = true;
                                w.mCommitDrawPending = false;
                                w.mReadyToShow = false;
                                if (w.mAppToken != null) {
                                    w.mAppToken.allDrawn = false;
                                }
                            }
                            if (DEBUG_ORIENTATION) Log.v(TAG,
                                    "Resizing window " + w + " to " + w.mFrame);
                            mResizingWindows.add(w);
                        } else if (w.mOrientationChanging) {
                            if (!w.mDrawPending && !w.mCommitDrawPending) {
                                if (DEBUG_ORIENTATION) Log.v(TAG,
                                        "Orientation not waiting for draw in "
                                        + w + ", surface " + w.mSurface);
                                w.mOrientationChanging = false;
                            }
                        }
                    }

                    if (w.mAttachedHidden) {
                        if (!w.mLastHidden) {
                            //dump();
                            w.mLastHidden = true;
                            if (SHOW_TRANSACTIONS) Log.i(
                                    TAG, "  SURFACE " + w.mSurface + ": HIDE (performLayout-attached)");
                            if (w.mSurface != null) {
                                try {
                                    w.mSurface.hide();
                                } catch (RuntimeException e) {
                                    Log.w(TAG, "Exception hiding surface in " + w);
                                }
                            }
                            mKeyWaiter.releasePendingPointerLocked(w.mSession);
                        }
                        // If we are waiting for this window to handle an
                        // orientation change, well, it is hidden, so
                        // doesn't really matter.  Note that this does
                        // introduce a potential glitch if the window
                        // becomes unhidden before it has drawn for the
                        // new orientation.
                        if (w.mOrientationChanging) {
                            w.mOrientationChanging = false;
                            if (DEBUG_ORIENTATION) Log.v(TAG,
                                    "Orientation change skips hidden " + w);
                        }
                    } else if (!w.isReadyForDisplay()) {
                        if (!w.mLastHidden) {
                            //dump();
                            w.mLastHidden = true;
                            if (SHOW_TRANSACTIONS) Log.i(
                                    TAG, "  SURFACE " + w.mSurface + ": HIDE (performLayout-ready)");
                            if (w.mSurface != null) {
                                try {
                                    w.mSurface.hide();
                                } catch (RuntimeException e) {
                                    Log.w(TAG, "Exception exception hiding surface in " + w);
                                }
                            }
                            mKeyWaiter.releasePendingPointerLocked(w.mSession);
                        }
                        // If we are waiting for this window to handle an
                        // orientation change, well, it is hidden, so
                        // doesn't really matter.  Note that this does
                        // introduce a potential glitch if the window
                        // becomes unhidden before it has drawn for the
                        // new orientation.
                        if (w.mOrientationChanging) {
                            w.mOrientationChanging = false;
                            if (DEBUG_ORIENTATION) Log.v(TAG,
                                    "Orientation change skips hidden " + w);
                        }
                    } else if (w.mLastLayer != w.mAnimLayer
                            || w.mLastAlpha != w.mShownAlpha
                            || w.mLastDsDx != w.mDsDx
                            || w.mLastDtDx != w.mDtDx
                            || w.mLastDsDy != w.mDsDy
                            || w.mLastDtDy != w.mDtDy
                            || w.mLastHScale != w.mHScale
                            || w.mLastVScale != w.mVScale
                            || w.mLastHidden) {
                        displayed = true;
                        w.mLastAlpha = w.mShownAlpha;
                        w.mLastLayer = w.mAnimLayer;
                        w.mLastDsDx = w.mDsDx;
                        w.mLastDtDx = w.mDtDx;
                        w.mLastDsDy = w.mDsDy;
                        w.mLastDtDy = w.mDtDy;
                        w.mLastHScale = w.mHScale;
                        w.mLastVScale = w.mVScale;
                        if (SHOW_TRANSACTIONS) Log.i(
                                TAG, "  SURFACE " + w.mSurface + ": alpha="
                                + w.mShownAlpha + " layer=" + w.mAnimLayer);
                        if (w.mSurface != null) {
                            try {
                                w.mSurface.setAlpha(w.mShownAlpha);
                                w.mSurface.setLayer(w.mAnimLayer);
                                w.mSurface.setMatrix(
                                        w.mDsDx*w.mHScale, w.mDtDx*w.mVScale,
                                        w.mDsDy*w.mHScale, w.mDtDy*w.mVScale);
                            } catch (RuntimeException e) {
                                Log.w(TAG, "Error updating surface in " + w, e);
                                if (!recoveringMemory) {
                                    reclaimSomeSurfaceMemoryLocked(w, "update");
                                }
                            }
                        }

                        if (w.mLastHidden && !w.mDrawPending
                                && !w.mCommitDrawPending
                                && !w.mReadyToShow) {
                            if (SHOW_TRANSACTIONS) Log.i(
                                    TAG, "  SURFACE " + w.mSurface + ": SHOW (performLayout)");
                            if (DEBUG_VISIBILITY) Log.v(TAG, "Showing " + w
                                    + " during relayout");
                            if (showSurfaceRobustlyLocked(w)) {
                                w.mHasDrawn = true;
                                w.mLastHidden = false;
                            } else {
                                w.mOrientationChanging = false;
                            }
                        }
                        if (w.mSurface != null) {
                            w.mToken.hasVisible = true;
                        }
                    } else {
                        displayed = true;
                    }

                    if (displayed) {
                        if (!covered) {
                            if (attrs.width == LayoutParams.FILL_PARENT
                                    && attrs.height == LayoutParams.FILL_PARENT) {
                                covered = true;
                            }
                        }
                        if (w.mOrientationChanging) {
                            if (w.mDrawPending || w.mCommitDrawPending) {
                                orientationChangeComplete = false;
                                if (DEBUG_ORIENTATION) Log.v(TAG,
                                        "Orientation continue waiting for draw in " + w);
                            } else {
                                w.mOrientationChanging = false;
                                if (DEBUG_ORIENTATION) Log.v(TAG,
                                        "Orientation change complete in " + w);
                            }
                        }
                        w.mToken.hasVisible = true;
                    }
                } else if (w.mOrientationChanging) {
                    if (DEBUG_ORIENTATION) Log.v(TAG,
                            "Orientation change skips hidden " + w);
                    w.mOrientationChanging = false;
                }

                final boolean canBeSeen = w.isDisplayedLw();

                if (someoneLosingFocus && w == mCurrentFocus && canBeSeen) {
                    focusDisplayed = true;
                }

                // Update effect.
                if (!obscured) {
                    if (w.mSurface != null) {
                        if ((attrFlags&FLAG_KEEP_SCREEN_ON) != 0) {
                            holdScreen = w.mSession;
                        }
                        if (!syswin && w.mAttrs.screenBrightness >= 0
                                && screenBrightness < 0) {
                            screenBrightness = w.mAttrs.screenBrightness;
                        }
                        if (attrs.type == WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG
                                || attrs.type == WindowManager.LayoutParams.TYPE_KEYGUARD
                                || attrs.type == WindowManager.LayoutParams.TYPE_SYSTEM_ERROR) {
                            syswin = true;
                        }
                    }

                    boolean opaqueDrawn = w.isOpaqueDrawn();
                    if (opaqueDrawn && w.isFullscreen(dw, dh)) {
                        // This window completely covers everything behind it,
                        // so we want to leave all of them as unblurred (for
                        // performance reasons).
                        obscured = true;
                    } else if (opaqueDrawn && w.needsBackgroundFiller(dw, dh)) {
                        if (SHOW_TRANSACTIONS) Log.d(TAG, "showing background filler");
                        // This window is in compatibility mode, and needs background filler.
                        obscured = true;
                        if (mBackgroundFillerSurface == null) {
                            try {
                                mBackgroundFillerSurface = new Surface(mFxSession, 0,
                                        0, dw, dh,
                                        PixelFormat.OPAQUE,
                                        Surface.FX_SURFACE_NORMAL);
                            } catch (Exception e) {
                                Log.e(TAG, "Exception creating filler surface", e);
                            }
                        }
                        try {
                            mBackgroundFillerSurface.setPosition(0, 0);
                            mBackgroundFillerSurface.setSize(dw, dh);
                            // Using the same layer as Dim because they will never be shown at the
                            // same time.
                            mBackgroundFillerSurface.setLayer(w.mAnimLayer - 1);
                            mBackgroundFillerSurface.show();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Exception showing filler surface");
                        }
                        backgroundFillerShown = true;
                        mBackgroundFillerShown = true;
                    } else if (canBeSeen && !obscured &&
                            (attrFlags&FLAG_BLUR_BEHIND|FLAG_DIM_BEHIND) != 0) {
                        if (localLOGV) Log.v(TAG, "Win " + w
                                + ": blurring=" + blurring
                                + " obscured=" + obscured
                                + " displayed=" + displayed);
                        if ((attrFlags&FLAG_DIM_BEHIND) != 0) {
                            if (!dimming) {
                                //Log.i(TAG, "DIM BEHIND: " + w);
                                dimming = true;
                                if (mDimAnimator == null) {
                                    mDimAnimator = new DimAnimator(mFxSession);
                                }
                                mDimAnimator.show(dw, dh);
                            }
                            mDimAnimator.updateParameters(w, currentTime);
                        }
                        if ((attrFlags&FLAG_BLUR_BEHIND) != 0) {
                            if (!blurring) {
                                //Log.i(TAG, "BLUR BEHIND: " + w);
                                blurring = true;
                                mBlurShown = true;
                                if (mBlurSurface == null) {
                                    if (SHOW_TRANSACTIONS) Log.i(TAG, "  BLUR "
                                            + mBlurSurface + ": CREATE");
                                    try {
                                        mBlurSurface = new Surface(mFxSession, 0,
                                                -1, 16, 16,
                                                PixelFormat.OPAQUE,
                                                Surface.FX_SURFACE_BLUR);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception creating Blur surface", e);
                                    }
                                }
                                if (SHOW_TRANSACTIONS) Log.i(TAG, "  BLUR "
                                        + mBlurSurface + ": SHOW pos=(0,0) (" +
                                        dw + "x" + dh + "), layer=" + (w.mAnimLayer-1));
                                if (mBlurSurface != null) {
                                    mBlurSurface.setPosition(0, 0);
                                    mBlurSurface.setSize(dw, dh);
                                    try {
                                        mBlurSurface.show();
                                    } catch (RuntimeException e) {
                                        Log.w(TAG, "Failure showing blur surface", e);
                                    }
                                }
                            }
                            mBlurSurface.setLayer(w.mAnimLayer-2);
                        }
                    }
                }
            }
            
            if (backgroundFillerShown == false && mBackgroundFillerShown) {
                mBackgroundFillerShown = false;
                if (SHOW_TRANSACTIONS) Log.d(TAG, "hiding background filler");
                try {
                    mBackgroundFillerSurface.hide();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Exception hiding filler surface", e);
                }
            }

            if (mDimAnimator != null && mDimAnimator.mDimShown) {
                animating |= mDimAnimator.updateSurface(dimming, currentTime, mDisplayFrozen);
            }

            if (!blurring && mBlurShown) {
                if (SHOW_TRANSACTIONS) Log.i(TAG, "  BLUR " + mBlurSurface
                        + ": HIDE");
                try {
                    mBlurSurface.hide();
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Illegal argument exception hiding blur surface");
                }
                mBlurShown = false;
            }

            if (SHOW_TRANSACTIONS) Log.i(TAG, "<<< CLOSE TRANSACTION");
        } catch (RuntimeException e) {
            Log.e(TAG, "Unhandled exception in Window Manager", e);
        }

        Surface.closeTransaction();

        if (DEBUG_ORIENTATION && mDisplayFrozen) Log.v(TAG,
                "With display frozen, orientationChangeComplete="
                + orientationChangeComplete);
        if (orientationChangeComplete) {
            if (mWindowsFreezingScreen) {
                mWindowsFreezingScreen = false;
                mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
            }
            if (mAppsFreezingScreen == 0) {
                stopFreezingDisplayLocked();
            }
        }

        i = mResizingWindows.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mResizingWindows.get(i);
                try {
                    win.mClient.resized(win.mFrame.width(),
                            win.mFrame.height(), win.mLastContentInsets,
                            win.mLastVisibleInsets, win.mDrawPending);
                    win.mContentInsetsChanged = false;
                    win.mVisibleInsetsChanged = false;
                } catch (RemoteException e) {
                    win.mOrientationChanging = false;
                }
            } while (i > 0);
            mResizingWindows.clear();
        }

        // Destroy the surface of any windows that are no longer visible.
        i = mDestroySurface.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mDestroySurface.get(i);
                win.mDestroying = false;
                if (mInputMethodWindow == win) {
                    mInputMethodWindow = null;
                }
                win.destroySurfaceLocked();
            } while (i > 0);
            mDestroySurface.clear();
        }

        // Time to remove any exiting tokens?
        for (i=mExitingTokens.size()-1; i>=0; i--) {
            WindowToken token = mExitingTokens.get(i);
            if (!token.hasVisible) {
                mExitingTokens.remove(i);
            }
        }

        // Time to remove any exiting applications?
        for (i=mExitingAppTokens.size()-1; i>=0; i--) {
            AppWindowToken token = mExitingAppTokens.get(i);
            if (!token.hasVisible && !mClosingApps.contains(token)) {
                mAppTokens.remove(token);
                mExitingAppTokens.remove(i);
            }
        }

        if (focusDisplayed) {
            mH.sendEmptyMessage(H.REPORT_LOSING_FOCUS);
        }
        if (animating) {
            requestAnimationLocked(currentTime+(1000/60)-SystemClock.uptimeMillis());
        }
        mQueue.setHoldScreenLocked(holdScreen != null);
        if (screenBrightness < 0 || screenBrightness > 1.0f) {
            mPowerManager.setScreenBrightnessOverride(-1);
        } else {
            mPowerManager.setScreenBrightnessOverride((int)
                    (screenBrightness * Power.BRIGHTNESS_ON));
        }
        if (holdScreen != mHoldingScreenOn) {
            mHoldingScreenOn = holdScreen;
            Message m = mH.obtainMessage(H.HOLD_SCREEN_CHANGED, holdScreen);
            mH.sendMessage(m);
        }
    }

    void requestAnimationLocked(long delay) {
        if (!mAnimationPending) {
            mAnimationPending = true;
            mH.sendMessageDelayed(mH.obtainMessage(H.ANIMATE), delay);
        }
    }

    /**
     * Have the surface flinger show a surface, robustly dealing with
     * error conditions.  In particular, if there is not enough memory
     * to show the surface, then we will try to get rid of other surfaces
     * in order to succeed.
     *
     * @return Returns true if the surface was successfully shown.
     */
    boolean showSurfaceRobustlyLocked(WindowState win) {
        try {
            if (win.mSurface != null) {
                win.mSurface.show();
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failure showing surface " + win.mSurface + " in " + win);
        }

        reclaimSomeSurfaceMemoryLocked(win, "show");

        return false;
    }

    void reclaimSomeSurfaceMemoryLocked(WindowState win, String operation) {
        final Surface surface = win.mSurface;

        EventLog.writeEvent(LOG_WM_NO_SURFACE_MEMORY, win.toString(),
                win.mSession.mPid, operation);

        if (mForceRemoves == null) {
            mForceRemoves = new ArrayList<WindowState>();
        }

        long callingIdentity = Binder.clearCallingIdentity();
        try {
            // There was some problem...   first, do a sanity check of the
            // window list to make sure we haven't left any dangling surfaces
            // around.
            int N = mWindows.size();
            boolean leakedSurface = false;
            Log.i(TAG, "Out of memory for surface!  Looking for leaks...");
            for (int i=0; i<N; i++) {
                WindowState ws = (WindowState)mWindows.get(i);
                if (ws.mSurface != null) {
                    if (!mSessions.contains(ws.mSession)) {
                        Log.w(TAG, "LEAKED SURFACE (session doesn't exist): "
                                + ws + " surface=" + ws.mSurface
                                + " token=" + win.mToken
                                + " pid=" + ws.mSession.mPid
                                + " uid=" + ws.mSession.mUid);
                        ws.mSurface.clear();
                        ws.mSurface = null;
                        mForceRemoves.add(ws);
                        i--;
                        N--;
                        leakedSurface = true;
                    } else if (win.mAppToken != null && win.mAppToken.clientHidden) {
                        Log.w(TAG, "LEAKED SURFACE (app token hidden): "
                                + ws + " surface=" + ws.mSurface
                                + " token=" + win.mAppToken);
                        ws.mSurface.clear();
                        ws.mSurface = null;
                        leakedSurface = true;
                    }
                }
            }

            boolean killedApps = false;
            if (!leakedSurface) {
                Log.w(TAG, "No leaked surfaces; killing applicatons!");
                SparseIntArray pidCandidates = new SparseIntArray();
                for (int i=0; i<N; i++) {
                    WindowState ws = (WindowState)mWindows.get(i);
                    if (ws.mSurface != null) {
                        pidCandidates.append(ws.mSession.mPid, ws.mSession.mPid);
                    }
                }
                if (pidCandidates.size() > 0) {
                    int[] pids = new int[pidCandidates.size()];
                    for (int i=0; i<pids.length; i++) {
                        pids[i] = pidCandidates.keyAt(i);
                    }
                    try {
                        if (mActivityManager.killPidsForMemory(pids)) {
                            killedApps = true;
                        }
                    } catch (RemoteException e) {
                    }
                }
            }

            if (leakedSurface || killedApps) {
                // We managed to reclaim some memory, so get rid of the trouble
                // surface and ask the app to request another one.
                Log.w(TAG, "Looks like we have reclaimed some memory, clearing surface for retry.");
                if (surface != null) {
                    surface.clear();
                    win.mSurface = null;
                }

                try {
                    win.mClient.dispatchGetNewSurface();
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    private boolean updateFocusedWindowLocked(int mode) {
        WindowState newFocus = computeFocusedWindowLocked();
        if (mCurrentFocus != newFocus) {
            // This check makes sure that we don't already have the focus
            // change message pending.
            mH.removeMessages(H.REPORT_FOCUS_CHANGE);
            mH.sendEmptyMessage(H.REPORT_FOCUS_CHANGE);
            if (localLOGV) Log.v(
                TAG, "Changing focus from " + mCurrentFocus + " to " + newFocus);
            final WindowState oldFocus = mCurrentFocus;
            mCurrentFocus = newFocus;
            mLosingFocus.remove(newFocus);

            final WindowState imWindow = mInputMethodWindow;
            if (newFocus != imWindow && oldFocus != imWindow) {
                if (moveInputMethodWindowsIfNeededLocked(
                        mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS &&
                        mode != UPDATE_FOCUS_WILL_PLACE_SURFACES)) {
                    mLayoutNeeded = true;
                }
                if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                    performLayoutLockedInner();
                } else if (mode == UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                    // Client will do the layout, but we need to assign layers
                    // for handleNewWindowLocked() below.
                    assignLayersLocked();
                }
            }

            if (newFocus != null && mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS) {
                mKeyWaiter.handleNewWindowLocked(newFocus);
            }
            return true;
        }
        return false;
    }

    private WindowState computeFocusedWindowLocked() {
        WindowState result = null;
        WindowState win;

        int i = mWindows.size() - 1;
        int nextAppIndex = mAppTokens.size()-1;
        WindowToken nextApp = nextAppIndex >= 0
            ? mAppTokens.get(nextAppIndex) : null;

        while (i >= 0) {
            win = (WindowState)mWindows.get(i);

            if (localLOGV || DEBUG_FOCUS) Log.v(
                TAG, "Looking for focus: " + i
                + " = " + win
                + ", flags=" + win.mAttrs.flags
                + ", canReceive=" + win.canReceiveKeys());

            AppWindowToken thisApp = win.mAppToken;

            // If this window's application has been removed, just skip it.
            if (thisApp != null && thisApp.removed) {
                i--;
                continue;
            }

            // If there is a focused app, don't allow focus to go to any
            // windows below it.  If this is an application window, step
            // through the app tokens until we find its app.
            if (thisApp != null && nextApp != null && thisApp != nextApp
                    && win.mAttrs.type != TYPE_APPLICATION_STARTING) {
                int origAppIndex = nextAppIndex;
                while (nextAppIndex > 0) {
                    if (nextApp == mFocusedApp) {
                        // Whoops, we are below the focused app...  no focus
                        // for you!
                        if (localLOGV || DEBUG_FOCUS) Log.v(
                            TAG, "Reached focused app: " + mFocusedApp);
                        return null;
                    }
                    nextAppIndex--;
                    nextApp = mAppTokens.get(nextAppIndex);
                    if (nextApp == thisApp) {
                        break;
                    }
                }
                if (thisApp != nextApp) {
                    // Uh oh, the app token doesn't exist!  This shouldn't
                    // happen, but if it does we can get totally hosed...
                    // so restart at the original app.
                    nextAppIndex = origAppIndex;
                    nextApp = mAppTokens.get(nextAppIndex);
                }
            }

            // Dispatch to this window if it is wants key events.
            if (win.canReceiveKeys()) {
                if (DEBUG_FOCUS) Log.v(
                        TAG, "Found focus @ " + i + " = " + win);
                result = win;
                break;
            }

            i--;
        }

        return result;
    }

    private void startFreezingDisplayLocked() {
        if (mDisplayFrozen) {
            // Freezing the display also suspends key event delivery, to
            // keep events from going astray while the display is reconfigured.
            // If someone has changed orientation again while the screen is
            // still frozen, the events will continue to be blocked while the
            // successive orientation change is processed.  To prevent spurious
            // ANRs, we reset the event dispatch timeout in this case.
            synchronized (mKeyWaiter) {
                mKeyWaiter.mWasFrozen = true;
            }
            return;
        }

        mScreenFrozenLock.acquire();

        long now = SystemClock.uptimeMillis();
        //Log.i(TAG, "Freezing, gc pending: " + mFreezeGcPending + ", now " + now);
        if (mFreezeGcPending != 0) {
            if (now > (mFreezeGcPending+1000)) {
                //Log.i(TAG, "Gc!  " + now + " > " + (mFreezeGcPending+1000));
                mH.removeMessages(H.FORCE_GC);
                Runtime.getRuntime().gc();
                mFreezeGcPending = now;
            }
        } else {
            mFreezeGcPending = now;
        }

        mDisplayFrozen = true;
        if (mNextAppTransition != WindowManagerPolicy.TRANSIT_NONE) {
            mNextAppTransition = WindowManagerPolicy.TRANSIT_NONE;
            mAppTransitionReady = true;
        }

        if (PROFILE_ORIENTATION) {
            File file = new File("/data/system/frozen");
            Debug.startMethodTracing(file.toString(), 8 * 1024 * 1024);
        }
        Surface.freezeDisplay(0);
    }

    private void stopFreezingDisplayLocked() {
        if (!mDisplayFrozen) {
            return;
        }

        mDisplayFrozen = false;
        mH.removeMessages(H.APP_FREEZE_TIMEOUT);
        if (PROFILE_ORIENTATION) {
            Debug.stopMethodTracing();
        }
        Surface.unfreezeDisplay(0);

        // Reset the key delivery timeout on unfreeze, too.  We force a wakeup here
        // too because regular key delivery processing should resume immediately.
        synchronized (mKeyWaiter) {
            mKeyWaiter.mWasFrozen = true;
            mKeyWaiter.notifyAll();
        }

        // A little kludge: a lot could have happened while the
        // display was frozen, so now that we are coming back we
        // do a gc so that any remote references the system
        // processes holds on others can be released if they are
        // no longer needed.
        mH.removeMessages(H.FORCE_GC);
        mH.sendMessageDelayed(mH.obtainMessage(H.FORCE_GC),
                2000);

        mScreenFrozenLock.release();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission("android.permission.DUMP")
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WindowManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized(mWindowMap) {
            pw.println("Current Window Manager state:");
            for (int i=mWindows.size()-1; i>=0; i--) {
                WindowState w = (WindowState)mWindows.get(i);
                pw.print("  Window #"); pw.print(i); pw.print(' ');
                        pw.print(w); pw.println(":");
                w.dump(pw, "    ");
            }
            if (mInputMethodDialogs.size() > 0) {
                pw.println(" ");
                pw.println("  Input method dialogs:");
                for (int i=mInputMethodDialogs.size()-1; i>=0; i--) {
                    WindowState w = mInputMethodDialogs.get(i);
                    pw.print("  IM Dialog #"); pw.print(i); pw.print(": "); pw.println(w);
                }
            }
            if (mPendingRemove.size() > 0) {
                pw.println(" ");
                pw.println("  Remove pending for:");
                for (int i=mPendingRemove.size()-1; i>=0; i--) {
                    WindowState w = mPendingRemove.get(i);
                    pw.print("  Remove #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mForceRemoves != null && mForceRemoves.size() > 0) {
                pw.println(" ");
                pw.println("  Windows force removing:");
                for (int i=mForceRemoves.size()-1; i>=0; i--) {
                    WindowState w = mForceRemoves.get(i);
                    pw.print("  Removing #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mDestroySurface.size() > 0) {
                pw.println(" ");
                pw.println("  Windows waiting to destroy their surface:");
                for (int i=mDestroySurface.size()-1; i>=0; i--) {
                    WindowState w = mDestroySurface.get(i);
                    pw.print("  Destroy #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mLosingFocus.size() > 0) {
                pw.println(" ");
                pw.println("  Windows losing focus:");
                for (int i=mLosingFocus.size()-1; i>=0; i--) {
                    WindowState w = mLosingFocus.get(i);
                    pw.print("  Losing #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mSessions.size() > 0) {
                pw.println(" ");
                pw.println("  All active sessions:");
                Iterator<Session> it = mSessions.iterator();
                while (it.hasNext()) {
                    Session s = it.next();
                    pw.print("  Session "); pw.print(s); pw.println(':');
                    s.dump(pw, "    ");
                }
            }
            if (mTokenMap.size() > 0) {
                pw.println(" ");
                pw.println("  All tokens:");
                Iterator<WindowToken> it = mTokenMap.values().iterator();
                while (it.hasNext()) {
                    WindowToken token = it.next();
                    pw.print("  Token "); pw.print(token.token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            if (mTokenList.size() > 0) {
                pw.println(" ");
                pw.println("  Window token list:");
                for (int i=0; i<mTokenList.size(); i++) {
                    pw.print("  #"); pw.print(i); pw.print(": ");
                            pw.println(mTokenList.get(i));
                }
            }
            if (mAppTokens.size() > 0) {
                pw.println(" ");
                pw.println("  Application tokens in Z order:");
                for (int i=mAppTokens.size()-1; i>=0; i--) {
                    pw.print("  App #"); pw.print(i); pw.print(": ");
                            pw.println(mAppTokens.get(i));
                }
            }
            if (mFinishedStarting.size() > 0) {
                pw.println(" ");
                pw.println("  Finishing start of application tokens:");
                for (int i=mFinishedStarting.size()-1; i>=0; i--) {
                    WindowToken token = mFinishedStarting.get(i);
                    pw.print("  Finished Starting #"); pw.print(i);
                            pw.print(' '); pw.print(token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            if (mExitingTokens.size() > 0) {
                pw.println(" ");
                pw.println("  Exiting tokens:");
                for (int i=mExitingTokens.size()-1; i>=0; i--) {
                    WindowToken token = mExitingTokens.get(i);
                    pw.print("  Exiting #"); pw.print(i);
                            pw.print(' '); pw.print(token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            if (mExitingAppTokens.size() > 0) {
                pw.println(" ");
                pw.println("  Exiting application tokens:");
                for (int i=mExitingAppTokens.size()-1; i>=0; i--) {
                    WindowToken token = mExitingAppTokens.get(i);
                    pw.print("  Exiting App #"); pw.print(i);
                            pw.print(' '); pw.print(token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            pw.println(" ");
            pw.print("  mCurrentFocus="); pw.println(mCurrentFocus);
            pw.print("  mLastFocus="); pw.println(mLastFocus);
            pw.print("  mFocusedApp="); pw.println(mFocusedApp);
            pw.print("  mInputMethodTarget="); pw.println(mInputMethodTarget);
            pw.print("  mInputMethodWindow="); pw.println(mInputMethodWindow);
            pw.print("  mInTouchMode="); pw.println(mInTouchMode);
            pw.print("  mSystemBooted="); pw.print(mSystemBooted);
                    pw.print(" mDisplayEnabled="); pw.println(mDisplayEnabled);
            pw.print("  mLayoutNeeded="); pw.print(mLayoutNeeded);
                    pw.print(" mBlurShown="); pw.println(mBlurShown);
            if (mDimAnimator != null) {
                mDimAnimator.printTo(pw);
            } else {
                pw.print( "  no DimAnimator ");
            }
            pw.print("  mInputMethodAnimLayerAdjustment=");
                    pw.println(mInputMethodAnimLayerAdjustment);
            pw.print("  mDisplayFrozen="); pw.print(mDisplayFrozen);
                    pw.print(" mWindowsFreezingScreen="); pw.print(mWindowsFreezingScreen);
                    pw.print(" mAppsFreezingScreen="); pw.println(mAppsFreezingScreen);
            pw.print("  mRotation="); pw.print(mRotation);
                    pw.print(", mForcedAppOrientation="); pw.print(mForcedAppOrientation);
                    pw.print(", mRequestedRotation="); pw.println(mRequestedRotation);
            pw.print("  mAnimationPending="); pw.print(mAnimationPending);
                    pw.print(" mWindowAnimationScale="); pw.print(mWindowAnimationScale);
                    pw.print(" mTransitionWindowAnimationScale="); pw.println(mTransitionAnimationScale);
            pw.print("  mNextAppTransition=0x");
                    pw.print(Integer.toHexString(mNextAppTransition));
                    pw.print(", mAppTransitionReady="); pw.print(mAppTransitionReady);
                    pw.print(", mAppTransitionTimeout="); pw.println( mAppTransitionTimeout);
            pw.print("  mStartingIconInTransition="); pw.print(mStartingIconInTransition);
                    pw.print(", mSkipAppTransitionAnimation="); pw.println(mSkipAppTransitionAnimation);
            if (mOpeningApps.size() > 0) {
                pw.print("  mOpeningApps="); pw.println(mOpeningApps);
            }
            if (mClosingApps.size() > 0) {
                pw.print("  mClosingApps="); pw.println(mClosingApps);
            }
            pw.print("  DisplayWidth="); pw.print(mDisplay.getWidth());
                    pw.print(" DisplayHeight="); pw.println(mDisplay.getHeight());
            pw.println("  KeyWaiter state:");
            pw.print("    mLastWin="); pw.print(mKeyWaiter.mLastWin);
                    pw.print(" mLastBinder="); pw.println(mKeyWaiter.mLastBinder);
            pw.print("    mFinished="); pw.print(mKeyWaiter.mFinished);
                    pw.print(" mGotFirstWindow="); pw.print(mKeyWaiter.mGotFirstWindow);
                    pw.print(" mEventDispatching="); pw.print(mKeyWaiter.mEventDispatching);
                    pw.print(" mTimeToSwitch="); pw.println(mKeyWaiter.mTimeToSwitch);
        }
    }

    public void monitor() {
        synchronized (mWindowMap) { }
        synchronized (mKeyguardDisabled) { }
        synchronized (mKeyWaiter) { }
    }

    /**
     * DimAnimator class that controls the dim animation. This holds the surface and
     * all state used for dim animation. 
     */
    private static class DimAnimator {
        Surface mDimSurface;
        boolean mDimShown = false;
        float mDimCurrentAlpha;
        float mDimTargetAlpha;
        float mDimDeltaPerMs;
        long mLastDimAnimTime;

        DimAnimator (SurfaceSession session) {
            if (mDimSurface == null) {
                if (SHOW_TRANSACTIONS) Log.i(TAG, "  DIM "
                        + mDimSurface + ": CREATE");
                try {
                    mDimSurface = new Surface(session, 0, -1, 16, 16, PixelFormat.OPAQUE,
                            Surface.FX_SURFACE_DIM);
                } catch (Exception e) {
                    Log.e(TAG, "Exception creating Dim surface", e);
                }
            }
        }

        /**
         * Show the dim surface.
         */
        void show(int dw, int dh) {
            if (SHOW_TRANSACTIONS) Log.i(TAG, "  DIM " + mDimSurface + ": SHOW pos=(0,0) (" +
                    dw + "x" + dh + ")");
            mDimShown = true;
            try {
                mDimSurface.setPosition(0, 0);
                mDimSurface.setSize(dw, dh);
                mDimSurface.show();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failure showing dim surface", e);
            }
        }

        /**
         * Set's the dim surface's layer and update dim parameters that will be used in
         * {@link updateSurface} after all windows are examined.
         */
        void updateParameters(WindowState w, long currentTime) {
            mDimSurface.setLayer(w.mAnimLayer-1);

            final float target = w.mExiting ? 0 : w.mAttrs.dimAmount;
            if (SHOW_TRANSACTIONS) Log.i(TAG, "layer=" + (w.mAnimLayer-1) + ", target=" + target);
            if (mDimTargetAlpha != target) {
                // If the desired dim level has changed, then
                // start an animation to it.
                mLastDimAnimTime = currentTime;
                long duration = (w.mAnimating && w.mAnimation != null)
                        ? w.mAnimation.computeDurationHint()
                        : DEFAULT_DIM_DURATION;
                if (target > mDimTargetAlpha) {
                    // This is happening behind the activity UI,
                    // so we can make it run a little longer to
                    // give a stronger impression without disrupting
                    // the user.
                    duration *= DIM_DURATION_MULTIPLIER;
                }
                if (duration < 1) {
                    // Don't divide by zero
                    duration = 1;
                }
                mDimTargetAlpha = target;
                mDimDeltaPerMs = (mDimTargetAlpha-mDimCurrentAlpha) / duration;
            }
        }
            
        /**
         * Updating the surface's alpha. Returns true if the animation continues, or returns
         * false when the animation is finished and the dim surface is hidden.
         */
        boolean updateSurface(boolean dimming, long currentTime, boolean displayFrozen) {
            if (!dimming) {
                if (mDimTargetAlpha != 0) {
                    mLastDimAnimTime = currentTime;
                    mDimTargetAlpha = 0;
                    mDimDeltaPerMs = (-mDimCurrentAlpha) / DEFAULT_DIM_DURATION;
                }
            }
            
            boolean animating = false;
            if (mLastDimAnimTime != 0) {
                mDimCurrentAlpha += mDimDeltaPerMs
                        * (currentTime-mLastDimAnimTime);
                boolean more = true;
                if (displayFrozen) {
                    // If the display is frozen, there is no reason to animate.
                    more = false;
                } else if (mDimDeltaPerMs > 0) {
                    if (mDimCurrentAlpha > mDimTargetAlpha) {
                        more = false;
                    }
                } else if (mDimDeltaPerMs < 0) {
                    if (mDimCurrentAlpha < mDimTargetAlpha) {
                        more = false;
                    }
                } else {
                    more = false;
                }

                // Do we need to continue animating?
                if (more) {
                    if (SHOW_TRANSACTIONS) Log.i(TAG, "  DIM "
                            + mDimSurface + ": alpha=" + mDimCurrentAlpha);
                    mLastDimAnimTime = currentTime;
                    mDimSurface.setAlpha(mDimCurrentAlpha);
                    animating = true;
                } else {
                    mDimCurrentAlpha = mDimTargetAlpha;
                    mLastDimAnimTime = 0;
                    if (SHOW_TRANSACTIONS) Log.i(TAG, "  DIM "
                            + mDimSurface + ": final alpha=" + mDimCurrentAlpha);
                    mDimSurface.setAlpha(mDimCurrentAlpha);
                    if (!dimming) {
                        if (SHOW_TRANSACTIONS) Log.i(TAG, "  DIM " + mDimSurface
                                + ": HIDE");
                        try {
                            mDimSurface.hide();
                        } catch (RuntimeException e) {
                            Log.w(TAG, "Illegal argument exception hiding dim surface");
                        }
                        mDimShown = false;
                    }
                }
            }
            return animating;
        }

        public void printTo(PrintWriter pw) {
            pw.print("  mDimShown="); pw.print(mDimShown);
            pw.print(" current="); pw.print(mDimCurrentAlpha);
            pw.print(" target="); pw.print(mDimTargetAlpha);
            pw.print(" delta="); pw.print(mDimDeltaPerMs);
            pw.print(" lastAnimTime="); pw.println(mLastDimAnimTime);
        }
    }

    /**
     * Animation that fade in after 0.5 interpolate time, or fade out in reverse order.
     * This is used for opening/closing transition for apps in compatible mode.
     */
    private static class FadeInOutAnimation extends Animation {
        int mWidth;
        boolean mFadeIn;

        public FadeInOutAnimation(boolean fadeIn) {
            setInterpolator(new AccelerateInterpolator());
            setDuration(DEFAULT_FADE_IN_OUT_DURATION);
            mFadeIn = fadeIn;
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            float x = interpolatedTime;
            if (!mFadeIn) {
                x = 1.0f - x; // reverse the interpolation for fade out
            }
            if (x < 0.5) {
                // move the window out of the screen.
                t.getMatrix().setTranslate(mWidth, 0);
            } else {
                t.getMatrix().setTranslate(0, 0);// show
                t.setAlpha((x - 0.5f) * 2);
            }
        }

        @Override
        public void initialize(int width, int height, int parentWidth, int parentHeight) {
            // width is the screen width {@see AppWindowToken#stepAnimatinoLocked}
            mWidth = width;
        }

        @Override
        public int getZAdjustment() {
            return Animation.ZORDER_TOP;
        }
    }
}
