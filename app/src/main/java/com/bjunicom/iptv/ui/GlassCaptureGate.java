package com.bjunicom.iptv.ui;

/** Main-thread ownership of asynchronous captures; stale callbacks cannot revive a stopped scene. */
final class GlassCaptureGate {
    private boolean active, disposed, pending;
    private int generation;

    void start() { if (!disposed) active = true; }
    void stop() { active = false; invalidate(); }
    void invalidate() { generation++; }
    void release() { stop(); disposed = true; }
    boolean isActive() { return active && !disposed; }
    int begin() {
        if (!isActive() || pending) return -1;
        pending = true;
        return generation;
    }
    boolean finish(int token) {
        pending = false;
        return isActive() && token == generation;
    }
}
