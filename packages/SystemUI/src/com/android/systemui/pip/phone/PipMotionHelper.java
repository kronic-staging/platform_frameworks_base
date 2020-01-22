/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.internal.os.SomeArgs;
import com.android.systemui.pip.PipSnapAlgorithm;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.util.animation.FloatProperties;
import com.android.systemui.util.animation.PhysicsAnimator;

import java.io.PrintWriter;

/**
 * A helper to animate and manipulate the PiP.
 */
public class PipMotionHelper implements Handler.Callback, PipAppOpsListener.Callback {

    private static final String TAG = "PipMotionHelper";
    private static final boolean DEBUG = false;

    private static final int SHRINK_STACK_FROM_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_FULLSCREEN_DURATION = 300;
    private static final int SHIFT_DURATION = 300;

    /** Friction to use for PIP when it moves via physics fling animations. */
    private static final float DEFAULT_FRICTION = 2f;

    // The fraction of the stack width that the user has to drag offscreen to minimize the PiP
    private static final float MINIMIZE_OFFSCREEN_FRACTION = 0.3f;
    // The fraction of the stack height that the user has to drag offscreen to dismiss the PiP
    private static final float DISMISS_OFFSCREEN_FRACTION = 0.3f;

    private static final int MSG_RESIZE_IMMEDIATE = 1;
    private static final int MSG_RESIZE_ANIMATE = 2;
    private static final int MSG_OFFSET_ANIMATE = 3;

    private Context mContext;
    private IActivityManager mActivityManager;
    private IActivityTaskManager mActivityTaskManager;
    private Handler mHandler;

    private PipMenuActivityController mMenuController;
    private PipSnapAlgorithm mSnapAlgorithm;
    private FlingAnimationUtils mFlingAnimationUtils;

    private final Rect mStableInsets = new Rect();

    /** PIP's current bounds on the screen. */
    private final Rect mBounds = new Rect();

    /**
     * Bounds that are animated using the physics animator. PIP is moved to these bounds whenever
     * the {@link #mVsyncTimeAnimator} ticks.
     */
    private final Rect mAnimatedBounds = new Rect();

    /**
     * PhysicsAnimator instance for animating {@link #mAnimatedBounds} using physics animations.
     */
    private PhysicsAnimator<Rect> mAnimatedBoundsPhysicsAnimator = PhysicsAnimator.getInstance(
            mAnimatedBounds);

    /**
     * Time animator whose frame timing comes from the SurfaceFlinger vsync frame provider. At each
     * frame, PIP is moved to {@link #mAnimatedBounds}, which are animated asynchronously using
     * physics animations.
     */
    private TimeAnimator mVsyncTimeAnimator;

    /** FlingConfig instances provided to PhysicsAnimator for fling gestures. */
    private PhysicsAnimator.FlingConfig mFlingConfigX;
    private PhysicsAnimator.FlingConfig mFlingConfigY;

    /** SpringConfig to use for fling-then-spring animations. */
    private final PhysicsAnimator.SpringConfig mSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    public PipMotionHelper(Context context, IActivityManager activityManager,
            IActivityTaskManager activityTaskManager, PipMenuActivityController menuController,
            PipSnapAlgorithm snapAlgorithm, FlingAnimationUtils flingAnimationUtils) {
        mContext = context;
        mHandler = new Handler(ForegroundThread.get().getLooper(), this);
        mActivityManager = activityManager;
        mActivityTaskManager = activityTaskManager;
        mMenuController = menuController;
        mSnapAlgorithm = snapAlgorithm;
        mFlingAnimationUtils = flingAnimationUtils;
        final AnimationHandler vsyncFrameCallbackProvider = new AnimationHandler();
        vsyncFrameCallbackProvider.setProvider(new SfVsyncFrameCallbackProvider());

        onConfigurationChanged();

        // Construct a time animator that uses the vsync frame provider. Physics animations can't
        // use custom frame providers, since they rely on constant time between frames to run the
        // physics simulations. To work around this, we physically-animate a second set of bounds,
        // and apply those animating bounds to the PIP in-sync via this TimeAnimator.
        mVsyncTimeAnimator = new TimeAnimator() {
            @Override
            public AnimationHandler getAnimationHandler() {
                return vsyncFrameCallbackProvider;
            }
        };

        // When the time animator ticks, move PIP to the animated bounds.
        mVsyncTimeAnimator.setTimeListener(
                (animation, totalTime, deltaTime) ->
                        resizePipUnchecked(mAnimatedBounds));

        // Add a listener for cancel/end events that moves PIP to the final animated bounds.
        mVsyncTimeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                resizePipUnchecked(mAnimatedBounds);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                resizePipUnchecked(mAnimatedBounds);
            }
        });
    }

    /**
     * Updates whenever the configuration changes.
     */
    void onConfigurationChanged() {
        mSnapAlgorithm.onConfigurationChanged();
        WindowManagerWrapper.getInstance().getStableInsets(mStableInsets);
    }

    /**
     * Synchronizes the current bounds with the pinned stack.
     */
    void synchronizePinnedStackBounds() {
        cancelAnimations();
        try {
            StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (stackInfo != null) {
                mBounds.set(stackInfo.bounds);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get pinned stack bounds");
        }
    }

    /**
     * Tries to the move the pinned stack to the given {@param bounds}.
     */
    void movePip(Rect toBounds) {
        cancelAnimations();
        resizePipUnchecked(toBounds);
        mBounds.set(toBounds);
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPip() {
        expandPip(false /* skipAnimation */);
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPip(boolean skipAnimation) {
        if (DEBUG) {
            Log.d(TAG, "expandPip: skipAnimation=" + skipAnimation
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelAnimations();
        mMenuController.hideMenuWithoutResize();
        mHandler.post(() -> {
            try {
                mActivityTaskManager.dismissPip(!skipAnimation, EXPAND_STACK_TO_FULLSCREEN_DURATION);
            } catch (RemoteException e) {
                Log.e(TAG, "Error expanding PiP activity", e);
            }
        });
    }

    /**
     * Dismisses the pinned stack.
     */
    @Override
    public void dismissPip() {
        if (DEBUG) {
            Log.d(TAG, "dismissPip: callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelAnimations();
        mMenuController.hideMenuWithoutResize();
        mHandler.post(() -> {
            try {
                mActivityTaskManager.removeStacksInWindowingModes(
                        new int[]{ WINDOWING_MODE_PINNED });
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to remove PiP", e);
            }
        });
    }

    /**
     * @return the PiP bounds.
     */
    Rect getBounds() {
        return mBounds;
    }

    /**
     * @return the closest minimized PiP bounds.
     */
    Rect getClosestMinimizedBounds(Rect stackBounds, Rect movementBounds) {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, stackBounds);
        mSnapAlgorithm.applyMinimizedOffset(toBounds, movementBounds, displaySize, mStableInsets);
        return toBounds;
    }

    /**
     * @return whether the PiP at the current bounds should be minimized.
     */
    boolean shouldMinimizePip() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        if (mBounds.left < 0) {
            float offscreenFraction = (float) -mBounds.left / mBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else if (mBounds.right > displaySize.x) {
            float offscreenFraction = (float) (mBounds.right - displaySize.x) /
                    mBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else {
            return false;
        }
    }

    /**
     * @return whether the PiP at the current bounds should be dismissed.
     */
    boolean shouldDismissPip() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        final int y = displaySize.y - mStableInsets.bottom;
        if (mBounds.bottom > y) {
            float offscreenFraction = (float) (mBounds.bottom - y) / mBounds.height();
            return offscreenFraction >= DISMISS_OFFSCREEN_FRACTION;
        }
        return false;
    }

    /**
     * Animates the PiP to the minimized state, slightly offscreen.
     */
    void animateToClosestMinimizedState(Rect movementBounds, @Nullable Runnable updateAction) {
        final Rect toBounds = getClosestMinimizedBounds(mBounds, movementBounds);

        prepareForBoundsAnimation(movementBounds);

        mAnimatedBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_X, toBounds.left, mSpringConfig)
                .spring(FloatProperties.RECT_Y, toBounds.top, mSpringConfig);

        if (updateAction != null) {
            mAnimatedBoundsPhysicsAnimator.addUpdateListener(
                    (target, values) -> updateAction.run());
        }

        startBoundsAnimation();
    }

    /**
     * Flings the PiP to the closest snap target.
     */
    void flingToSnapTarget(
            float velocityX, float velocityY, Rect movementBounds, Runnable updateAction,
            @Nullable Runnable endAction) {
        prepareForBoundsAnimation(movementBounds);

        mAnimatedBoundsPhysicsAnimator
                .flingThenSpring(
                        FloatProperties.RECT_X, velocityX, mFlingConfigX, mSpringConfig,
                        true /* flingMustReachMinOrMax */)
                .flingThenSpring(
                        FloatProperties.RECT_Y, velocityY, mFlingConfigY, mSpringConfig)
                .addUpdateListener((target, values) -> updateAction.run())
                .withEndActions(endAction);

        startBoundsAnimation();
    }

    /**
     * Animates the PiP to the closest snap target.
     */
    void animateToClosestSnapTarget(Rect movementBounds) {
        prepareForBoundsAnimation(movementBounds);

        final Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mBounds);
        mAnimatedBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_X, toBounds.left, mSpringConfig)
                .spring(FloatProperties.RECT_Y, toBounds.top, mSpringConfig);

        startBoundsAnimation();
    }

    /**
     * Animates the dismissal of the PiP off the edge of the screen.
     */
    void animateDismiss(float velocityX, float velocityY, @Nullable Runnable updateAction) {
        final float velocity = PointF.length(velocityX, velocityY);
        final boolean isFling = velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond();
        final Point dismissEndPoint = getDismissEndPoint(mBounds, velocityX, velocityY, isFling);

        // Set the animated bounds to start at the current bounds. We don't need to rebuild the
        // fling configs here via prepareForBoundsAnimation, since animateDismiss isn't provided
        // with new movement bounds.
        mAnimatedBounds.set(mBounds);

        // Animate to the dismiss end point, and then dismiss PIP.
        mAnimatedBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_X, dismissEndPoint.x, velocityX, mSpringConfig)
                .spring(FloatProperties.RECT_Y, dismissEndPoint.y, velocityY, mSpringConfig)
                .withEndActions(this::dismissPip);

        // If we were provided with an update action, run it whenever there's an update.
        if (updateAction != null) {
            mAnimatedBoundsPhysicsAnimator.addUpdateListener(
                    (target, values) -> updateAction.run());
        }

        startBoundsAnimation();
    }

    /**
     * Animates the PiP to the expanded state to show the menu.
     */
    float animateToExpandedState(Rect expandedBounds, Rect movementBounds,
            Rect expandedMovementBounds) {
        float savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds), movementBounds);
        mSnapAlgorithm.applySnapFraction(expandedBounds, expandedMovementBounds, savedSnapFraction);
        resizeAndAnimatePipUnchecked(expandedBounds, EXPAND_STACK_TO_MENU_DURATION);
        return savedSnapFraction;
    }

    /**
     * Animates the PiP from the expanded state to the normal state after the menu is hidden.
     */
    void animateToUnexpandedState(Rect normalBounds, float savedSnapFraction,
            Rect normalMovementBounds, Rect currentMovementBounds, boolean minimized,
            boolean immediate) {
        if (savedSnapFraction < 0f) {
            // If there are no saved snap fractions, then just use the current bounds
            savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds),
                    currentMovementBounds);
        }
        mSnapAlgorithm.applySnapFraction(normalBounds, normalMovementBounds, savedSnapFraction);
        if (minimized) {
            normalBounds = getClosestMinimizedBounds(normalBounds, normalMovementBounds);
        }
        if (immediate) {
            movePip(normalBounds);
        } else {
            resizeAndAnimatePipUnchecked(normalBounds, SHRINK_STACK_FROM_MENU_DURATION);
        }
    }

    /**
     * Animates the PiP to offset it from the IME or shelf.
     */
    void animateToOffset(Rect originalBounds, int offset) {
        cancelAnimations();
        adjustAndAnimatePipOffset(originalBounds, offset, SHIFT_DURATION);
    }

    private void adjustAndAnimatePipOffset(Rect originalBounds, int offset, int duration) {
        if (offset == 0) {
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = originalBounds;
        args.argi1 = offset;
        args.argi2 = duration;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_OFFSET_ANIMATE, args));
    }

    /**
     * Cancels all existing animations.
     */
    private void cancelAnimations() {
        mAnimatedBoundsPhysicsAnimator.cancel();
        mVsyncTimeAnimator.cancel();
    }

    /**
     * Set new fling configs whose min/max values respect the given movement bounds, and set the
     * animated bounds to PIP's current 'real' bounds.
     */
    private void prepareForBoundsAnimation(Rect movementBounds) {
        mFlingConfigX = new PhysicsAnimator.FlingConfig(
                DEFAULT_FRICTION, movementBounds.left, movementBounds.right);
        mFlingConfigY = new PhysicsAnimator.FlingConfig(
                DEFAULT_FRICTION, movementBounds.top, movementBounds.bottom);

        mAnimatedBounds.set(mBounds);
    }

    /**
     * Starts the physics animator which will update the animated PIP bounds using physics
     * animations, as well as the TimeAnimator which will apply those bounds to PIP at intervals
     * synchronized with the SurfaceFlinger vsync frame provider.
     *
     * This will also add end actions to the bounds animator that cancel the TimeAnimator and update
     * the 'real' bounds to equal the final animated bounds.
     */
    private void startBoundsAnimation() {
        cancelAnimations();

        mAnimatedBoundsPhysicsAnimator
                .withEndActions(
                        mVsyncTimeAnimator::cancel)
                .start();
        mVsyncTimeAnimator.start();
    }

    /**
     * Directly resizes the PiP to the given {@param bounds}.
     */
    private void resizePipUnchecked(Rect toBounds) {
        if (DEBUG) {
            Log.d(TAG, "resizePipUnchecked: toBounds=" + toBounds
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (!toBounds.equals(mBounds)) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = toBounds;
            mHandler.sendMessage(mHandler.obtainMessage(MSG_RESIZE_IMMEDIATE, args));
        }
    }

    /**
     * Directly resizes the PiP to the given {@param bounds}.
     */
    private void resizeAndAnimatePipUnchecked(Rect toBounds, int duration) {
        if (DEBUG) {
            Log.d(TAG, "resizeAndAnimatePipUnchecked: toBounds=" + toBounds
                    + " duration=" + duration + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (!toBounds.equals(mBounds)) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = toBounds;
            args.argi1 = duration;
            mHandler.sendMessage(mHandler.obtainMessage(MSG_RESIZE_ANIMATE, args));
        }
    }

    /**
     * @return the coordinates the PIP should animate to based on the direction of velocity when
     *         dismissing.
     */
    private Point getDismissEndPoint(Rect pipBounds, float velX, float velY, boolean isFling) {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        final float bottomBound = displaySize.y + pipBounds.height() * .1f;
        if (isFling && velX != 0 && velY != 0) {
            // Line is defined by: y = mx + b, m = slope, b = y-intercept
            // Find the slope
            final float slope = velY / velX;
            // Sub in slope and PiP position to solve for y-intercept: b = y - mx
            final float yIntercept = pipBounds.top - slope * pipBounds.left;
            // Now find the point on this line when y = bottom bound: x = (y - b) / m
            final float x = (bottomBound - yIntercept) / slope;
            return new Point((int) x, (int) bottomBound);
        } else {
            // If it wasn't a fling the velocity on 'up' is not reliable for direction of movement,
            // just animate downwards.
            return new Point(pipBounds.left, (int) bottomBound);
        }
    }

    /**
     * @return whether the gesture it towards the dismiss area based on the velocity when
     *         dismissing.
     */
    public boolean isGestureToDismissArea(Rect pipBounds, float velX, float velY,
            boolean isFling) {
        Point endpoint = getDismissEndPoint(pipBounds, velX, velY, isFling);
        // Center the point
        endpoint.x += pipBounds.width() / 2;
        endpoint.y += pipBounds.height() / 2;

        // The dismiss area is the middle third of the screen, half the PIP's height from the bottom
        Point size = new Point();
        mContext.getDisplay().getRealSize(size);
        final int left = size.x / 3;
        Rect dismissArea = new Rect(left, size.y - (pipBounds.height() / 2), left * 2,
                size.y + pipBounds.height());
        return dismissArea.contains(endpoint.x, endpoint.y);
    }

    /**
     * @return the distance between points {@param p1} and {@param p2}.
     */
    private float distanceBetweenRectOffsets(Rect r1, Rect r2) {
        return PointF.length(r1.left - r2.left, r1.top - r2.top);
    }

    /**
     * Handles messages to be processed on the background thread.
     */
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_RESIZE_IMMEDIATE: {
                SomeArgs args = (SomeArgs) msg.obj;
                Rect toBounds = (Rect) args.arg1;
                try {
                    mActivityTaskManager.resizePinnedStack(
                            toBounds, null /* tempPinnedTaskBounds */);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not resize pinned stack to bounds: " + toBounds, e);
                }
                return true;
            }

            case MSG_RESIZE_ANIMATE: {
                SomeArgs args = (SomeArgs) msg.obj;
                Rect toBounds = (Rect) args.arg1;
                int duration = args.argi1;
                try {
                    StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                            WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
                    if (stackInfo == null) {
                        // In the case where we've already re-expanded or dismissed the PiP, then
                        // just skip the resize
                        return true;
                    }

                    mActivityTaskManager.animateResizePinnedStack(stackInfo.stackId, toBounds,
                            duration);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not animate resize pinned stack to bounds: " + toBounds, e);
                }
                return true;
            }

            case MSG_OFFSET_ANIMATE: {
                SomeArgs args = (SomeArgs) msg.obj;
                Rect originalBounds = (Rect) args.arg1;
                final int offset = args.argi1;
                final int duration = args.argi2;
                try {
                    StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                            WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
                    if (stackInfo == null) {
                        // In the case where we've already re-expanded or dismissed the PiP, then
                        // just skip the resize
                        return true;
                    }

                    mActivityTaskManager.offsetPinnedStackBounds(stackInfo.stackId, originalBounds,
                            0/* xOffset */, offset, duration);
                    Rect toBounds = new Rect(originalBounds);
                    toBounds.offset(0, offset);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not animate offset pinned stack with offset: " + offset, e);
                }
                return true;
            }

            default:
                return false;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
        pw.println(innerPrefix + "mStableInsets=" + mStableInsets);
    }
}
