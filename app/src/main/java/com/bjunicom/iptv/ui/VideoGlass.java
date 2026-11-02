package com.bjunicom.iptv.ui;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.content.pm.ApplicationInfo;
import com.bjunicom.iptv.R;
import java.util.ArrayList;
import java.util.List;

/** One scene capture shared by every panel; never captures UI or changes the player. */
final class VideoGlass {
    private final ViewGroup video;
    private final ImageView preview;
    private final List<View> panels = new ArrayList<>();
    private final List<GlassDrawable> materials = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread = new HandlerThread("video-glass");
    private final Handler worker;
    private final GlassCaptureGate gate = new GlassCaptureGate();
    private Bitmap backdrop;
    private Bitmap previousBackdrop;
    private long framePublishedAt, lastCaptureAt;
    private static final long LIVE_INTERVAL_MS = 30L;
    private static final long BLEND_MS = 32L;
    private View sourceView;
    private final int[] videoPosition = new int[2];
    private int videoWidth, videoHeight;
    private boolean advanced;
    private final boolean debug;
    private final boolean reference;
    private int captures, published, discarded;
    private float lightX = -1;
    private ValueAnimator lightAnimation;

    void focus(View target) {
        if (target == null || target.getWidth() == 0) return;
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        illuminate(location[0] + target.getWidth() / 2f);
    }

    void illuminate(float screenX) {
        if (lightAnimation != null) lightAnimation.cancel();
        if (lightX < 0 || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            lightX = screenX; invalidatePanels(); return;
        }
        lightAnimation = ValueAnimator.ofFloat(lightX, screenX);
        lightAnimation.setDuration(190);
        lightAnimation.addUpdateListener(a -> { lightX = (float) a.getAnimatedValue(); invalidatePanels(); });
        lightAnimation.start();
    }

    VideoGlass(ViewGroup video, ImageView preview, View... panels) {
        this.video = video;
        this.preview = preview;
        debug = (video.getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        reference = debug && video.getContext() instanceof Activity
                && ((Activity) video.getContext()).getIntent().getBooleanExtra("glass_reference", false);
        boolean forceFallback = debug && video.getContext() instanceof Activity
                && ((Activity) video.getContext()).getIntent().getBooleanExtra("glass_force_fallback", false);
        advanced = Build.VERSION.SDK_INT >= 33 && !forceFallback;
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        for (View panel : panels) addPanel(panel, false);
        if (debug) Log.i("VideoGlass", "backend=" + (advanced ? "AGSL" : "fallback")
                + " optics=" + (reference ? "upstream" : "20261027"));
    }

    void addPanel(View panel, boolean regular) {
        if (panels.contains(panel)) return;
        GlassDrawable material = new GlassDrawable(panel, regular);
        panels.add(panel);
        materials.add(material);
        panel.setBackground(material);
        protectText(panel);
    }

    void removePanel(View panel) {
        int index = panels.indexOf(panel);
        if (index < 0) return;
        GlassDrawable material = materials.remove(index);
        panels.remove(index);
        if (material.lens != null) material.lens.clear();
        panel.setBackground(null);
    }

    private void protectText(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            GlassForeground.protect((TextView) view, view.isSelected() || view.isFocused());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) protectText(group.getChildAt(i));
        }
    }

    void start() { gate.start(); video.removeCallbacks(tick); main.removeCallbacks(tick); main.post(tick); }
    void stop() {
        if (lightAnimation != null) lightAnimation.cancel();
        gate.stop();
        video.removeCallbacks(tick);
        main.removeCallbacks(tick);
        if (debug) Log.i("VideoGlass", "stopped captures=" + captures
                + " published=" + published + " discarded=" + discarded);
    }
    void clear() {
        gate.invalidate();
        backdrop = null;
        previousBackdrop = null;
        sourceView = null;
        for (GlassDrawable material : materials) if (material.lens != null) material.lens.clear();
        invalidatePanels();
        if (gate.isActive()) { video.removeCallbacks(tick); main.removeCallbacks(tick); main.post(tick); }
    }
    void release() { stop(); gate.release(); clear(); workerThread.quitSafely(); }
    private void invalidatePanels() { for (View panel : panels) panel.invalidate(); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!gate.isActive()) return;
            boolean visible = false;
            for (View panel : panels) visible |= panel.isShown();
            boolean live = visible && !preview.isShown() && advanced;
            long now = SystemClock.uptimeMillis();
            if (visible && (!live || now - lastCaptureAt >= LIVE_INTERVAL_MS)) {
                lastCaptureAt = now;
                capture();
            }
            if (live && previousBackdrop != null && now - framePublishedAt <= BLEND_MS + 17L)
                invalidatePanels();
            if (live) video.postOnAnimation(this);
            else main.postDelayed(this, visible && !preview.isShown() ? 900L : 250L);
        }
    };

    private void capture() {
        View renderer = preview.isShown() ? preview : findRenderer(video);
        if (renderer == null || renderer.getWidth() == 0 || renderer.getHeight() == 0) return;
        boolean still = renderer == preview;
        if (renderer != sourceView || videoWidth != renderer.getWidth() || videoHeight != renderer.getHeight()) {
            gate.invalidate();
            backdrop = null;
            previousBackdrop = null;
            sourceView = renderer;
            videoWidth = renderer.getWidth(); videoHeight = renderer.getHeight();
        }
        if (still && backdrop != null) return;
        int token = gate.begin();
        if (token < 0) return;
        int longest = advanced ? (still ? 1920 : 960) : 160;
        int rw = renderer.getWidth(), rh = renderer.getHeight();
        float ratio = (float) longest / Math.max(rw, rh);
        Bitmap sample = Bitmap.createBitmap(Math.max(1, Math.round(rw * ratio)),
                Math.max(1, Math.round(rh * ratio)), Bitmap.Config.ARGB_8888);
        int[] position = new int[2];
        renderer.getLocationOnScreen(position);
        captures++;
        final long started = SystemClock.uptimeMillis();
        try {
            if (still) {
                Drawable drawable = preview.getDrawable();
                if (drawable instanceof BitmapDrawable) {
                    // Copy immutable photo pixels off the UI thread; never call View.draw on a worker.
                    Bitmap photo = ((BitmapDrawable) drawable).getBitmap();
                    Matrix transform = new Matrix();
                    transform.setRectToRect(new RectF(0, 0, photo.getWidth(), photo.getHeight()),
                            new RectF(drawable.getBounds()), Matrix.ScaleToFit.FILL);
                    transform.postConcat(preview.getImageMatrix());
                    transform.postTranslate(preview.getPaddingLeft(), preview.getPaddingTop());
                    transform.postScale((float) sample.getWidth() / rw, (float) sample.getHeight() / rh);
                    worker.post(() -> {
                        new Canvas(sample).drawBitmap(photo, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
                        main.post(() -> process(sample, position, rw, rh, token, renderer, started));
                    });
                } else {
                    Canvas canvas = new Canvas(sample);
                    canvas.scale((float) sample.getWidth() / rw, (float) sample.getHeight() / rh);
                    preview.draw(canvas);
                    process(sample, position, rw, rh, token, renderer, started);
                }
            } else if (renderer instanceof SurfaceView && Build.VERSION.SDK_INT >= 24) {
                SurfaceView surface = (SurfaceView) renderer;
                if (!surface.getHolder().getSurface().isValid()) { reject(sample, token); return; }
                PixelCopy.request(surface, sample, result -> {
                    if (result == PixelCopy.SUCCESS) process(sample, position, rw, rh, token, renderer, started);
                    else reject(sample, token);
                }, main);
            } else if (renderer instanceof TextureView && ((TextureView) renderer).isAvailable()) {
                Bitmap result = ((TextureView) renderer).getBitmap(sample);
                if (result == null) reject(sample, token);
                else process(sample, position, rw, rh, token, renderer, started);
            } else reject(sample, token);
        } catch (IllegalArgumentException | IllegalStateException e) {
            reject(sample, token);
        }
        if (debug && (captures <= 3 || captures % 150 == 0))
            Log.d("VideoGlass", "capture-main-ms=" + (SystemClock.uptimeMillis() - started)
                    + " count=" + captures + " source=" + renderer.getClass().getSimpleName());
    }

    private void reject(Bitmap sample, int token) {
        gate.finish(token);
        sample.recycle();
        discarded++;
    }

    private void process(Bitmap sample, int[] position, int width, int height, int token, View renderer, long started) {
        boolean gpu = advanced;
        worker.post(() -> {
            if (!gpu) blurFallback(sample);
            main.post(() -> {
                if (!gate.finish(token) || renderer != sourceView
                        || renderer.getWidth() != width || renderer.getHeight() != height
                        || (renderer == preview) != preview.isShown()) {
                    sample.recycle(); discarded++; return;
                }
                previousBackdrop = renderer == preview ? null : backdrop;
                backdrop = sample; // Immutable after publication; HWUI owns its remaining lifetime.
                framePublishedAt = SystemClock.uptimeMillis();
                videoPosition[0] = position[0]; videoPosition[1] = position[1];
                videoWidth = width; videoHeight = height;
                published++;
                invalidatePanels();
                if (debug && (published <= 3 || published % 150 == 0))
                    Log.d("VideoGlass", "published=" + published + " age-ms="
                            + (SystemClock.uptimeMillis() - started));
            });
        });
    }

    private static void blurFallback(Bitmap sample) {
        int w = sample.getWidth(), h = sample.getHeight();
        int[] source = new int[w * h], target = new int[w * h];
        sample.getPixels(source, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int red = 0, green = 0, blue = 0;
                for (int offset = -3; offset <= 3; offset++) {
                    int sx = pass == 1 ? x : Math.max(0, Math.min(w - 1, x + offset));
                    int sy = pass == 1 ? Math.max(0, Math.min(h - 1, y + offset)) : y;
                    int color = source[sy * w + sx];
                    red += (color >> 16) & 255; green += (color >> 8) & 255; blue += color & 255;
                }
                target[y * w + x] = 0xff000000 | ((red / 7) << 16) | ((green / 7) << 8) | (blue / 7);
            }
            int[] swap = source; source = target; target = swap;
        }
        sample.setPixels(source, 0, w, 0, 0, w, h);
    }

    private View findRenderer(View view) {
        if (view instanceof SurfaceView || view instanceof TextureView) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findRenderer(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private final class GlassDrawable extends Drawable {
        private final View panel;
        private final boolean regular;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final int[] position = new int[2];
        private final RectF bounds = new RectF(), imageBounds = new RectF();
        private GlassLens lens;

        GlassDrawable(View panel, boolean regular) {
            this.panel = panel;
            this.regular = regular;
            if (advanced) {
                try { lens = new GlassLens(panel.getResources(), reference); }
                catch (RuntimeException e) { advanced = false; Log.w("VideoGlass", "AGSL unavailable; readable fallback", e); }
            }
        }

        @Override public void draw(Canvas canvas) {
            bounds.set(getBounds());
            float density = panel.getResources().getDisplayMetrics().density;
            float radius = Math.min(28 * density, bounds.height() / 2);
            boolean drawer = regular || bounds.height() > bounds.width();
            panel.getLocationOnScreen(position);
            float x = videoPosition[0] - position[0], y = videoPosition[1] - position[1];
            imageBounds.set(x, y, x + videoWidth, y + videoHeight);
            boolean gpu = advanced && lens != null && canvas.isHardwareAccelerated() && backdrop != null;
            if (gpu) {
                float blend = previousBackdrop == null ? 1f : Math.min(1f,
                        (float) (SystemClock.uptimeMillis() - framePublishedAt) / BLEND_MS);
                try { lens.draw(canvas, backdrop, previousBackdrop, blend, imageBounds, bounds, density, radius, drawer,
                        lightX < 0 ? bounds.width() * .25f : lightX - position[0],
                        DeviceUi.isPhone(panel.getContext()) && panel.getId() == R.id.glass_top_bar); }
                catch (RuntimeException e) {
                    advanced = false; gpu = false;
                    lens.clear(); lens = null;
                    Log.w("VideoGlass", "AGSL draw failed; readable fallback", e);
                }
            }
            clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.save(); canvas.clipPath(clip);
            if (!gpu) {
                paint.setColor(0xff181b23);
                canvas.drawRect(bounds, paint);
                if (backdrop != null) canvas.drawBitmap(backdrop, null, imageBounds, paint);
            }
            if (!gpu) {
                paint.setShader(new LinearGradient(0, 0, bounds.width() * .3f, bounds.height(),
                        new int[]{0x99424a58, 0xbb181d29, 0xd0181b23},
                        new float[]{0f, .32f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(bounds, paint); paint.setShader(null);
            }
            canvas.restore();
            if (gpu) return; // Optical contour lighting is computed from the curved surface in AGSL.
            bounds.inset(density / 2, density / 2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
            float reflectionX = lightX < 0 ? 0 : lightX - position[0] - bounds.width() * .35f;
            paint.setShader(new LinearGradient(reflectionX, 0, reflectionX + bounds.width() * .75f, bounds.height(),
                    new int[]{0xc0ffffff, 0x18ffffff, 0x08ffffff, 0x65ffffff},
                    new float[]{0f, .28f, .65f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(bounds, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL); paint.setShader(null);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void getOutline(Outline outline) {
            float density = panel.getResources().getDisplayMetrics().density;
            outline.setRoundRect(getBounds(), Math.min(28f * density, getBounds().height() / 2f));
        }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
