package com.bjunicom.iptv.ui;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.network.NetworkSnapshot;
import com.bjunicom.iptv.scanner.MulticastScannerEngine;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** First-use guidance and explicit, cancellable source checks. Never switches an ISP automatically. */
final class SourceNetworkController {
    private static final int SAMPLE_LIMIT = 4;
    private static final int CHANNEL_TIMEOUT_MS = 4000;
    private static final int CHECK_TIMEOUT_SECONDS = 40;
    private final MainActivity activity;
    private final IptvSettings settings;
    private final ChannelRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MulticastScannerEngine> engines = new CopyOnWriteArrayList<>();
    private final ConnectivityManager connectivity;
    private ExecutorService workers;
    private ExecutorService coordinator;
    private AlertDialog dialog;
    private Snackbar notice;
    private int generation;
    private boolean observing;
    private boolean resumed;
    private boolean closed;
    private long lastFailureNotice;
    private final Runnable networkChanged = this::checkNetworkChange;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { scheduleNetworkCheck(); }
        @Override public void onLost(Network network) { scheduleNetworkCheck(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties link) { scheduleNetworkCheck(); }
    };

    SourceNetworkController(MainActivity activity, IptvSettings settings, ChannelRepository repository) {
        this.activity = activity; this.settings = settings; this.repository = repository;
        connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void showWelcome() {
        if (closed || dialog != null || activity.isFinishing()) return;
        dialog = WelcomeDialogUi.show(activity, new WelcomeDialogUi.Actions() {
            @Override public void useSource(int choice) {
                dialog = null;
                if (choice == 3) activity.openSourceChoice(true);
                else activity.switchIspProvider(choice);
            }
            @Override public void checkSources() { dialog = null; checkNow(); }
        });
        dialog.setOnDismissListener(d -> { if (dialog == d) dialog = null; });
    }

    void bindSettings(View settingsView) {
        TextView source = settingsView.findViewById(R.id.tv_network_source_value);
        source.setText(settings.hasChosenSource() ? settings.getIspDisplayName() : "尚未选择节目源");
        TextView status = settingsView.findViewById(R.id.tv_network_check_source);
        status.setText(settings.hasChosenSource()
                ? "当前：" + settings.getIspDisplayName() + " · 按当前设置抽查"
                : "尚未选择节目源 · 可先检测可用源");
        View check = settingsView.findViewById(R.id.btn_check_network_sources);
        check.setOnClickListener(v -> checkNow());
        check.setNextFocusLeftId(R.id.settings_tab_network);
        android.widget.Button toggle = settingsView.findViewById(R.id.btn_network_advanced);
        View advanced = settingsView.findViewById(R.id.network_advanced_section);
        toggle.setNextFocusLeftId(R.id.settings_tab_network);
        advanced.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> {
            boolean open = advanced.getVisibility() == View.VISIBLE;
            advanced.setVisibility(open ? View.GONE : View.VISIBLE);
            toggle.setText(open ? "IPTV 连接设置（高级）" : "收起连接设置");
        });
    }

    void start() {
        resumed = true;
        checkNetworkChange();
        if (connectivity == null || observing || closed) return;
        try {
            // Observe independent IPTV Ethernet as well as the default Internet network.
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().build(), callback);
            observing = true;
        } catch (RuntimeException ignored) { }
    }

    private void scheduleNetworkCheck() {
        main.post(() -> {
            if (!resumed || closed) return;
            main.removeCallbacks(networkChanged);
            main.postDelayed(networkChanged, 1200);
        });
    }

    private void checkNetworkChange() {
        if (!resumed || closed) return;
        acceptNetwork(NetworkSnapshot.read(activity));
    }

    void acceptNetwork(NetworkSnapshot snapshot) {
        String previous = settings.getLastNetworkFingerprint();
        if (!snapshot.fingerprint.isEmpty()) settings.setLastNetworkFingerprint(snapshot.fingerprint);
        if (!settings.hasChosenSource() || previous.isEmpty() || previous.equals(snapshot.fingerprint)) return;
        cancelCheck(); // Results from the previous network must not be shown as current.
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        showNotice(snapshot.fingerprint.isEmpty() ? "网络已断开，请检查网络连接。"
                : "网络已变化，仍使用" + settings.getIspShortName() + "。可重新检测。", "检测节目源");
    }

    void playbackFailed(boolean networkFailure) {
        if (!networkFailure || dialog != null || closed) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastFailureNotice != 0 && now - lastFailureNotice < 30000) return;
        lastFailureNotice = now;
        showNotice("暂时无法播放，可检测网络和节目源。", "检测节目源");
    }

    private void showNotice(String message, String action) {
        if (dialog != null || activity.isFinishing()) return;
        if (DeviceUi.isTelevision(activity)) {
            dialog = new MaterialAlertDialogBuilder(activity).setTitle("网络状态提醒")
                    .setMessage(message).setPositiveButton(action, (d, w) -> { dialog = null; checkNow(); })
                    .setNegativeButton("稍后", (d, w) -> dialog = null).create();
            dialog.setOnDismissListener(d -> { if (dialog == d) dialog = null; });
            NetworkDialogUi.show(activity, dialog);
        } else {
            if (notice != null) notice.dismiss();
            notice = Snackbar.make(activity.findViewById(R.id.root_container), message, 12000)
                    .setAction(action, v -> checkNow());
            notice.show();
        }
    }

    void checkNow() {
        if (closed || activity.isFinishing()) return;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        cancelCheck();
        final int run = generation;
        final CheckConfig config = activity.networkCheckConfig();
        final int activeProvider = config.provider;
        final boolean sourceChosen = settings.hasChosenSource();
        final Map<Integer, List<IptvChannel>> samples = new LinkedHashMap<>();
        if (activeProvider >= 0) samples.put(activeProvider, sample(config.channels));
        for (int provider = 0; provider <= 2; provider++) {
            if (!samples.containsKey(provider)) samples.put(provider, sample(repository.previewChannels(provider, true)));
        }
        NetworkSnapshot network = NetworkSnapshot.read(activity);
        LinearLayout progressContent = content(activity);
        addText(progressContent, network.description, 18, true);
        addText(progressContent, config.description(), 14, false);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(activity);
        progressContent.addView(spinner, new LinearLayout.LayoutParams(AdaptiveViews.dp(activity, 32), AdaptiveViews.dp(activity, 32)));
        addText(progressContent, "正在抽查频道，连接失败时会补测其他方式。\n最长约 40 秒，可随时取消。", 14, false);
        dialog = new MaterialAlertDialogBuilder(activity).setTitle("检测节目源")
                .setView(scroll(progressContent)).setNegativeButton("取消检测", (d, w) -> cancelCheck()).create();
        AlertDialog progress = dialog;
        progress.setOnCancelListener(d -> cancelCheck());
        progress.setOnDismissListener(d -> {
            if (dialog == d) { dialog = null; cancelCheck(); }
        });
        NetworkDialogUi.show(activity, progress);
        workers = Executors.newFixedThreadPool(samples.size());
        coordinator = Executors.newSingleThreadExecutor();
        Map<Integer, Future<SampleResult>> pending = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<IptvChannel>> source : samples.entrySet()) {
            pending.put(source.getKey(), workers.submit(() -> checkSample(source.getValue(), config)));
        }
        coordinator.execute(() -> {
            StringBuilder report = new StringBuilder(network.description + "\n"
                    + (config.changed ? "按当前修改临时检测，不会保存。" : sourceChosen
                    ? "当前节目源：" + IptvSettings.getIspDisplayName(activeProvider) : "尚未选择节目源")
                    + "\n优先检测：" + modeName(config.mode) + "；失败后补测其他连接方式，不改变设置。\n\n");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHECK_TIMEOUT_SECONDS);
            int suggested = -1;
            boolean currentReceived = false;
            boolean currentNeedsFallback = false;
            Map<Integer, SampleResult> checkedSources = new LinkedHashMap<>();
            for (Map.Entry<Integer, Future<SampleResult>> source : pending.entrySet()) {
                SampleResult checked;
                try { checked = source.getValue().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (Exception error) { checked = new SampleResult(0, 0, "检查未完成，请重试", "检查超时或已中断"); }
                checkedSources.put(source.getKey(), checked);
                if (checked.received > 0) {
                    if (source.getKey() == activeProvider) {
                        currentReceived = true;
                        currentNeedsFallback = checked.usedFallback;
                    }
                    if (suggested < 0) suggested = source.getKey();
                }
                report.append(IptvSettings.getIspDisplayName(source.getKey())).append("\n").append(checked.detail).append("\n\n");
            }
            String recommendation = currentReceived ? (currentNeedsFallback
                    ? "当前连接方式未成功，补测方式收到媒体数据。请按详情确认连接设置后尝试播放。"
                    : "当前节目源已收到媒体数据，可继续尝试播放。")
                    : suggested >= 0 ? "可尝试：" + IptvSettings.getIspDisplayName(suggested) + "（抽查收到数据）"
                    : "本次抽查未确认可用节目源，不能据此判定全部频道失效。请查看连接详情。";
            final int recommended = suggested;
            main.post(() -> {
                if (closed || run != generation || activity.isFinishing()) return;
                cancelCheck();
                progress.dismiss();
                if (!network.fingerprint.equals(NetworkSnapshot.read(activity).fingerprint)) {
                    showNotice("网络在检测期间发生变化，请重新检测。", "重新检测");
                    return;
                }
                LinearLayout resultContent = content(activity);
                addText(resultContent, recommendation, 18, true);
                addText(resultContent, network.description + " · " + config.description(), 14, false);
                for (Map.Entry<Integer, SampleResult> source : checkedSources.entrySet()) {
                    SampleResult checked = source.getValue();
                    addText(resultContent, IptvSettings.getIspDisplayName(source.getKey())
                            + (source.getKey() == activeProvider ? " · 本次选择" : ""), 16, true);
                    addText(resultContent, checked.total == 0 ? checked.reason
                            : "抽查 " + checked.total + " 个频道，" + checked.received + " 个收到数据"
                             + (checked.received == 0 ? "\n" + checked.reason : checked.usedFallback
                             ? "\n当前方式未成功，补测方式可用。请查看详情确认连接设置。" : ""), 14, false);
                }
                addText(resultContent, "每个节目源最多抽查 4 个不同频道，收到数据后结束该源抽查。结果不代表全部频道或实际解码效果。", 12, false);
                TextView details = addText(resultContent, report.toString(), 13, false);
                details.setVisibility(View.GONE);
                android.widget.Button expand = AdaptiveViews.action(resultContent, View.generateViewId(), "查看详情");
                resultContent.addView(expand, resultContent.getChildCount() - 1);
                expand.setOnClickListener(v -> {
                    boolean showing = details.getVisibility() == View.VISIBLE;
                    details.setVisibility(showing ? View.GONE : View.VISIBLE);
                    expand.setText(showing ? "查看详情" : "收起详情");
                    NetworkDialogUi.fit(activity, dialog);
                });
                dialog = new MaterialAlertDialogBuilder(activity).setTitle("检测结果")
                        .setView(scroll(resultContent))
                        .setPositiveButton(recommended >= 0 ? "选择推荐源" : "选择节目源", (d, w) -> {
                            dialog = null;
                            if (recommended >= 0) activity.stageNetworkSource(recommended);
                            else activity.openSourceChoice(false);
                        })
                        .setNeutralButton("重新检测", (d, w) -> { dialog = null; checkNow(); })
                        .setNegativeButton("关闭", (d, w) -> { dialog = null; }).create();
                dialog.setOnDismissListener(d -> { if (dialog == d) dialog = null; });
                NetworkDialogUi.show(activity, dialog);
            });
        });
    }

    SampleResult checkSample(List<IptvChannel> channels, CheckConfig config) {
        if (channels.isEmpty()) return new SampleResult(0, 0, "没有频道，请导入频道列表", "未加载到频道");
        MulticastScannerEngine engine = new MulticastScannerEngine(activity.getApplicationContext(), config.mode, config.proxy);
        engines.add(engine);
        try {
            int received = 0;
            int attempted = 0;
            boolean usedFallback = false;
            StringBuilder details = new StringBuilder();
            String reason = "未收到视频数据，请检查网络或 IPTV 接入。";
            for (IptvChannel channel : channels) {
                if (Thread.currentThread().isInterrupted()) break;
                MulticastScannerEngine.ChannelCheck checked = engine.checkChannel(channel, CHANNEL_TIMEOUT_MS);
                attempted++;
                if (checked.connected) { received++; usedFallback = checked.usedFallback; }
                else reason = friendlyReason(checked.detail);
                details.append(channel.getDisplayName()).append("：").append(checked.detail).append("\n");
                if (checked.connected) break;
            }
            return new SampleResult(attempted, received, reason, details.toString().trim(), usedFallback);
        } finally { engine.cancel(); engines.remove(engine); }
    }

    static final class SampleResult {
        final int total, received;
        final String reason, detail;
        final boolean usedFallback;
        SampleResult(int total, int received, String reason, String detail) {
            this(total, received, reason, detail, false);
        }
        SampleResult(int total, int received, String reason, String detail, boolean usedFallback) {
            this.total = total; this.received = received; this.reason = reason; this.detail = detail;
            this.usedFallback = usedFallback;
        }
    }

    static List<IptvChannel> sample(List<IptvChannel> channels) {
        List<IptvChannel> result = new ArrayList<>();
        Map<String, IptvChannel> unique = new LinkedHashMap<>();
        for (IptvChannel channel : channels) {
            if (channel != null && channel.getRawUrl() != null && !channel.getRawUrl().trim().isEmpty())
                unique.putIfAbsent(channel.getRawUrl().trim(), channel);
        }
        List<IptvChannel> candidates = new ArrayList<>(unique.values());
        int count = Math.min(SAMPLE_LIMIT, candidates.size());
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : i * (candidates.size() - 1) / (count - 1);
            result.add(candidates.get(index).copy());
        }
        return result;
    }

    private static LinearLayout content(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = AdaptiveViews.dp(context, DeviceUi.isTelevision(context) ? 24 : 18);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    private static ScrollView scroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(content.getContext());
        scroll.setFillViewport(false);
        scroll.addView(content);
        return scroll;
    }

    private static TextView addText(LinearLayout parent, String text, float size, boolean strong) {
        TextView view = new TextView(parent.getContext());
        view.setText(text);
        view.setTextSize(DeviceUi.isTelevision(parent.getContext()) ? Math.max(size, 18) : size);
        view.setTextColor(androidx.core.content.ContextCompat.getColor(parent.getContext(),
                strong ? R.color.text_primary : R.color.text_secondary));
        if (strong) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, AdaptiveViews.dp(parent.getContext(), 4), 0, AdaptiveViews.dp(parent.getContext(), 8));
        parent.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    static String friendlyReason(String detail) {
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (detail.contains("补测")) return "所选连接方式和补测方式均未收到数据，请查看详情检查 IPTV 接入和连接设置。";
        if (lower.contains("403") || lower.contains("401") || detail.contains("授权")) return "频道访问受限，可能需要授权或当地网络。";
        if (lower.contains("resolve") || lower.contains("dns") || detail.contains("域名")) return "无法解析频道地址，请检查网络。";
        if (lower.contains("timed out") || detail.contains("超时")) return "频道连接超时，请稍后重试。";
        if (lower.contains("connect")) return "无法连接频道服务器，请检查网络。";
        if (detail.contains("组播")) return "未收到 IPTV 数据，请检查 IPTV 接入或代理设置。";
        return "未收到视频数据，请查看详情或更换节目源。";
    }

    private static String modeName(int mode) {
        return mode == IptvSettings.STREAM_MODE_UDPXY ? "路由器代理" : mode == IptvSettings.STREAM_MODE_LOCAL_PROXY
                ? "内置代理" : "组播直连";
    }

    static final class CheckConfig {
        final int provider, mode;
        final String proxy;
        final List<IptvChannel> channels;
        final boolean changed;
        CheckConfig(int provider, int mode, String proxy, List<IptvChannel> channels, boolean changed) {
            this.provider = provider; this.mode = mode; this.proxy = proxy;
            this.channels = channels; this.changed = changed;
        }
        String description() {
            return (provider < 0 ? "尚未选择节目源" : IptvSettings.getIspDisplayName(provider))
                    + "\n" + modeName(mode) + (mode == IptvSettings.STREAM_MODE_UDPXY ? " · " + (proxy.isEmpty() ? "未填写地址" : proxy) : "")
                    + (changed ? "\n按当前修改检测，不会保存。" : "\n使用已保存的配置。");
        }
    }

    private void cancelCheck() {
        generation++;
        if (workers != null) { workers.shutdownNow(); workers = null; }
        if (coordinator != null) { coordinator.shutdownNow(); coordinator = null; }
        for (MulticastScannerEngine engine : engines) engine.cancel();
        engines.clear();
    }

    void stop() {
        resumed = false;
        main.removeCallbacks(networkChanged);
        if (observing) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
            observing = false;
        }
        cancelCheck();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (notice != null) { notice.dismiss(); notice = null; }
    }

    void close() { closed = true; stop(); main.removeCallbacksAndMessages(null); }
}
