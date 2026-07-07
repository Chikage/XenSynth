/*
 * MIT License
 *
 * Copyright (c) 2025-2026 Donny Yale
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * ===========================================
 * Project: QmBlurView
 * Created Date: 2025-10-21
 * Author: Donny Yale
 * GitHub: https://github.com/QmDeve/QmBlurView
 * Website: https://blurview.qmdeve.com
 * ===========================================
 */

package com.qmdeve.blurview;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native blur implementation,
 * Gaussian blur through JNI call cpp code
 */
public class BlurNative implements Blur {

    // The maximum value of the blur radius
    private static final int MAX_RADIUS = 100;

    // The minimum value of the blur radius
    private static final int MIN_RADIUS = 2;

    // Thread pool configuration
    private static final int THREAD_COUNT;
    private static final ExecutorService EXECUTOR;

    static {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        THREAD_COUNT = Math.max(2, Math.min(5, cpuCount));
        EXECUTOR = Executors.newFixedThreadPool(THREAD_COUNT, r -> {
            Thread t = new Thread(r, "NativeBlurThread");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        System.loadLibrary("QmBlur");
    }

    private final AtomicBoolean isBlurring = new AtomicBoolean(false);
    private float radius = MAX_RADIUS;
    private int blurRounds = 2; // Default to 2 iterations (each = horizontal + vertical pass) for better performance

    /**
     *
     * @param bitmap Bitmap objects to be blurred
     * @param radius Blur radius
     * @param threadCount Total number of threads
     * @param threadIndex Current thread index
     * @param round Blur round
     */
    public static native void blur(
            Object bitmap,
            int radius,
            int threadCount,
            int threadIndex,
            int round
    );

    @Override
    public boolean prepare(Bitmap buffer, float radius) {
        this.radius = clamp(radius);
        return true;
    }

    /**
     * Set the number of blur iterations
     * Each iteration applies both horizontal and vertical blur passes
     * More iterations = stronger blur effect
     * @param rounds Number of blur iterations (1-15)
     */
    public void setBlurRounds(int rounds) {
        this.blurRounds = Math.max(1, Math.min(15, rounds));
    }

    /**
     * Get the current number of blur rounds
     * @return Current blur rounds
     */
    public int getBlurRounds() {
        return blurRounds;
    }

    @Override
    public void release() {
        // Shared executor, do not shutdown
    }

    @Override
    public void blur(Bitmap input, Bitmap output) {
        if (input == null || output == null ||
                input.isRecycled() || output.isRecycled()) return;

        if (!isBlurring.compareAndSet(false, true)) return;

        try {
            if (input != output) {
                // Clear the output bitmap to ensure no previous content remains
                // This is important when bitmaps are reused from pools
                output.eraseColor(0);
                new Canvas(output).drawBitmap(input, 0, 0, null);
            }
            // Apply blur iterations
            // Each iteration is a complete 2-pass blur (horizontal + vertical)
            // More iterations = stronger blur effect
            for (int iteration = 0; iteration < blurRounds; iteration++) {
                doBlurRound(output, 1); // Horizontal pass
                doBlurRound(output, 2); // Vertical pass
            }
        } catch (Exception e) {
            // Only print stack trace if debug mode is enabled
            // Note: DEBUG may be null if Context was never provided
            if (Boolean.TRUE.equals(DEBUG)) e.printStackTrace();
        } finally {
            isBlurring.set(false);
        }
    }

    /**
     * Perform fuzzy operations
     * @param bitmap Blurry bitmaps are needed
     * @param round Blur round
     */
    private void doBlurRound(Bitmap bitmap, int round) {
        int r = (int) radius;

        // Optimization: For small images or single-core devices, skip thread overhead
        if (THREAD_COUNT == 1) {
            blur(bitmap, r, 1, 0, round);
            return;
        }

        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int index = i;
            EXECUTOR.execute(() -> {
                try {
                    blur(bitmap, r, THREAD_COUNT, index, round);
                } catch (Exception e) {
                    // Only print stack trace if debug mode is enabled
                    // Note: DEBUG may be null if Context was never provided
                    if (Boolean.TRUE.equals(DEBUG)) e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static float clamp(float value) {
        return Math.max((float) BlurNative.MIN_RADIUS, Math.min((float) BlurNative.MAX_RADIUS, value));
    }

    private static Boolean DEBUG = null;

    /**
     * Determine whether it is currently in debugging mode
     * @param ctx Context
     * @return Boolean
     */
    static boolean isDebug(Context ctx) {
        if (DEBUG == null && ctx != null) {
            DEBUG = (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        }
        return Boolean.TRUE.equals(DEBUG);
    }
}