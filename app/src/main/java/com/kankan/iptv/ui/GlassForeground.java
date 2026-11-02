package com.kankan.iptv.ui;

import android.widget.TextView;

/** Foreground decoration only. Video pixels never select text, hint or icon colors. */
final class GlassForeground {
    private GlassForeground() { }

    static void protect(TextView label, boolean lightControl) {
        float density = label.getResources().getDisplayMetrics().density;
        label.setShadowLayer(lightControl ? 0 : 1.5f * density,
                0, density * .4f, lightControl ? 0 : 0xc0000000);
    }
}
