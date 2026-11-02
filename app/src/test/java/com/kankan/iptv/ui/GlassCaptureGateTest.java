package com.kankan.iptv.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class GlassCaptureGateTest {
    @Test public void onlyOneCaptureCanOwnABitmapAtATime() {
        GlassCaptureGate gate = new GlassCaptureGate();
        assertEquals(-1, gate.begin());
        gate.start();
        int request = gate.begin();
        assertTrue(request >= 0);
        assertEquals(-1, gate.begin());
        assertTrue(gate.finish(request));
        assertTrue(gate.begin() >= 0);
    }

    @Test public void channelSwitchDiscardsOldFrameBeforeNextCapture() {
        GlassCaptureGate gate = new GlassCaptureGate();
        gate.start();
        int old = gate.begin();
        gate.invalidate();
        assertEquals(-1, gate.begin());
        assertFalse(gate.finish(old));
        assertTrue(gate.finish(gate.begin()));
    }

    @Test public void pauseAndResumeDoesNotPublishOldRequest() {
        GlassCaptureGate gate = new GlassCaptureGate();
        gate.start();
        int old = gate.begin();
        gate.stop();
        assertEquals(-1, gate.begin());
        gate.start();
        assertFalse(gate.finish(old));
        assertTrue(gate.finish(gate.begin()));
    }

    @Test public void releaseIsTerminalEvenWhenCallbackArrivesLate() {
        GlassCaptureGate gate = new GlassCaptureGate();
        gate.start();
        int old = gate.begin();
        gate.release();
        assertFalse(gate.finish(old));
        gate.start();
        assertFalse(gate.isActive());
        assertEquals(-1, gate.begin());
    }
}
