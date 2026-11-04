package com.kankan.iptv.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.kankan.iptv.R;
import java.io.File;

/** Reproducible optics/video fixture; never modifies channels, settings or playback history. */
public final class GlassLabActivity extends Activity {
    private VideoGlass glass;
    private MediaPlayer media;
    private boolean prepared;
    private Surface textureSurface;
    private final Handler handler = new Handler();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout video = new FrameLayout(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        String mode = getIntent().getStringExtra("mode");
        if ("surface".equals(mode) || "texture".equals(mode)) {
            preview.setVisibility(View.GONE);
            FrameLayout.LayoutParams playbackSize = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
            if ("surface".equals(mode)) {
                SurfaceView surface = new SurfaceView(this);
                video.addView(surface, playbackSize);
                surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                    public void surfaceCreated(SurfaceHolder h) { play(h.getSurface(), surface); }
                    public void surfaceChanged(SurfaceHolder h, int f, int w, int he) { }
                    public void surfaceDestroyed(SurfaceHolder h) { releaseMedia(); }
                });
            } else {
                TextureView texture = new TextureView(this);
                video.addView(texture, playbackSize);
                texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                        textureSurface = new Surface(t); play(textureSurface, texture);
                    }
                    public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { }
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                        releaseMedia();
                        if (textureSurface != null) { textureSurface.release(); textureSurface = null; }
                        return true;
                    }
                    public void onSurfaceTextureUpdated(SurfaceTexture t) { }
                });
            }
        } else if ("photo".equals(mode)) preview.setImageResource(R.drawable.preview_landscape);
        else {
            Bitmap pattern = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(pattern);
            Paint p = new Paint();
            c.drawColor("white".equals(mode) ? Color.WHITE : Color.rgb(20, 28, 40));
            if (!"white".equals(mode) && !"black".equals(mode)) {
                for (int y = 0; y < 1000; y += 40) for (int x = 0; x < 1600; x += 40) {
                    p.setColor((x / 40 + y / 40) % 2 == 0 ? 0xff8baec4 : 0xff283d50);
                    c.drawRect(x, y, x + 40, y + 40, p);
                }
                p.setColor(0xffffca68); p.setStrokeWidth(3);
                for (int x = 0; x < 1600; x += 80) c.drawLine(x, 0, x, 1000, p);
            }
            preview.setImageBitmap(pattern);
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(24), dp(12), dp(24), dp(12));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(660), dp(108), Gravity.CENTER);
        root.addView(panel, position);
        TextView label = new TextView(this);
        label.setText("看看电视\n材质验证 · 非直播");
        label.setTextSize(20); label.setTextColor(Color.WHITE);
        panel.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        for (String text : new String[]{"频道", "设置"}) {
            TextView button = new TextView(this);
            button.setText(text); button.setTextColor(Color.WHITE); button.setTextSize(18);
            button.setGravity(Gravity.CENTER); button.setFocusable(true); button.setClickable(true);
            button.setBackgroundResource(R.drawable.bg_group_tab);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(96), dp(52)); bp.leftMargin = dp(12);
            panel.addView(button, bp);
            GlassMotion.install(button);
            button.setOnFocusChangeListener((v, focused) -> { GlassMotion.focus(v, focused); if (focused) glass.focus(v); });
        }
        setContentView(root);
        glass = new VideoGlass(video, preview);
        glass.addPanel(panel, getIntent().getBooleanExtra("regular_glass", false));
        if (getIntent().getBooleanExtra("hide_panels", false)) handler.postDelayed(() -> {
            panel.setVisibility(View.GONE);
            android.util.Log.i("GlassLab", "panels-hidden");
        }, 5000);
    }

    private void play(Surface surface, View renderer) {
        releaseMedia();
        try {
            media = new MediaPlayer();
            media.setDataSource(new File(getExternalFilesDir(null), "glass-test.mp4").getAbsolutePath());
            media.setSurface(surface); media.setVolume(0, 0); media.setLooping(true);
            media.setOnPreparedListener(p -> {
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                float scale = Math.min((float) width / p.getVideoWidth(), (float) height / p.getVideoHeight());
                renderer.setLayoutParams(new FrameLayout.LayoutParams(Math.round(p.getVideoWidth() * scale),
                        Math.round(p.getVideoHeight() * scale), Gravity.CENTER));
                prepared = true; glass.clear(); p.start();
                android.util.Log.i("GlassLab", "video-start " + renderer.getClass().getSimpleName());
            });
            media.prepareAsync();
        } catch (Exception e) { android.util.Log.e("GlassLab", "video failed", e); }
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void releaseMedia() { prepared = false; if (media != null) { media.release(); media = null; } }
    @Override protected void onResume() { super.onResume(); glass.start(); if (media != null && prepared) media.start(); }
    @Override protected void onPause() { glass.stop(); if (media != null && prepared && media.isPlaying()) media.pause(); super.onPause(); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); glass.release(); releaseMedia();
        if (textureSurface != null) { textureSurface.release(); textureSurface = null; }
        super.onDestroy();
    }
}
