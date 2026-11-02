package com.kankan.iptv.ui;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import com.kankan.iptv.R;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** API 33 boundary. Only the backdrop is shaded; Android draws children normally afterwards. */
@TargetApi(33)
final class GlassLens {
    private final RuntimeShader lens;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mapping = new Matrix();
    private Bitmap source;
    private BitmapShader bitmapShader;
    private Bitmap previousSource;
    private BitmapShader previousShader;
    private final boolean reference;

    GlassLens(Resources resources, boolean reference) {
        this.reference = reference;
        try (InputStream in = resources.openRawResource(reference ? R.raw.liquidglass_reference : R.raw.liquidglass_effect);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            lens = new RuntimeShader(out.toString("UTF-8"));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize glass shader", e);
        }
        lens.setFloatUniform("offset", 0f, 0f);
        lens.setFloatUniform("depthEffect", 0.3f);
        lens.setFloatUniform("chromaticAberration", reference ? .5f : .045f);
        lens.setFloatUniform("contrast", 0f);
        lens.setFloatUniform("whitePoint", 0f);
        lens.setFloatUniform("chromaMultiplier", 1f);
        lens.setFloatUniform("tintColor", 0.06f, 0.08f, 0.11f);
        paint.setShader(lens);
    }

    void draw(Canvas canvas, Bitmap bitmap, Bitmap previous, float blend, RectF sourceBounds, RectF bounds,
              float density, float radius, boolean drawer, float lightX, boolean balancedFrame) {
        if (source != bitmap) {
            source = bitmap;
            bitmapShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        mapping.setScale(sourceBounds.width() / bitmap.getWidth(), sourceBounds.height() / bitmap.getHeight());
        mapping.postTranslate(sourceBounds.left, sourceBounds.top);
        bitmapShader.setLocalMatrix(mapping);
        lens.setInputShader("content", bitmapShader);
        if (!reference) {
            Bitmap old = previous == null ? bitmap : previous;
            if (previousSource != old) {
                previousSource = old;
                previousShader = new BitmapShader(old, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                previousShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            previousShader.setLocalMatrix(mapping);
            lens.setInputShader("previousContent", previousShader);
            lens.setFloatUniform("frameBlend", blend);
            lens.setFloatUniform("sourceBounds", sourceBounds.left, sourceBounds.top, sourceBounds.right, sourceBounds.bottom);
            lens.setFloatUniform("blurRadius", .75f * density);
            lens.setFloatUniform("pixelDensity", density);
            // A filled action needs an evenly lit frame so equal insets also read as equal.
            lens.setFloatUniform("lightPosition", lightX,
                    bounds.height() * (balancedFrame ? .5f : -.7f));
            lens.setFloatUniform("interiorBlurRadius", (drawer ? 5f : .75f) * density);
            lens.setFloatUniform("regularStrength", drawer ? 1f : 0f);
        }
        lens.setFloatUniform("size", bounds.width(), bounds.height());
        lens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        float edge = Math.min(radius * .80f, Math.min(bounds.width(), bounds.height()) * .24f);
        lens.setFloatUniform("refractionHeight", reference ? 20f * density : edge);
        // Negative displacement follows the upstream lens: an inward optical bevel.
        lens.setFloatUniform("refractionAmount", reference ? -70f * density : -edge * 1.65f);
        // Neutral optics: no fixed dark or blue-gray overlay on the video.
        lens.setFloatUniform("tintAlpha", 0f);
        canvas.drawRoundRect(bounds, radius, radius, paint);
    }

    void clear() {
        // Release the input bitmap as well as the Java reference when a scene is invalidated.
        source = null;
        previousSource = null;
        bitmapShader = null;
        previousShader = null;
        lens.setInputShader("content", new android.graphics.LinearGradient(0, 0, 1, 1,
                0xff10131a, 0xff10131a, Shader.TileMode.CLAMP));
        if (!reference) lens.setInputShader("previousContent", new android.graphics.LinearGradient(0, 0, 1, 1,
                0xff10131a, 0xff10131a, Shader.TileMode.CLAMP));
    }
}
