package com.bjunicom.iptv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.network.StreamUrlResolver;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.player.PlaybackFailure;
import com.bjunicom.iptv.network.TransportRouteCache;
import com.bjunicom.iptv.player.MeasureHelper;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.report.DataReportManager;
import com.bjunicom.iptv.ui.adapter.CategoryAdapter;
import com.bjunicom.iptv.ui.adapter.ChannelAdapter;
import com.bjunicom.iptv.update.AppUpdateManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/**
 * 北京联通 IPTV 主播放界面
 *
 * 核心能力：
 * 1. 基于 bilibili/ijkplayer 播放全部北京联通 IPTV 频道 (4K HDR/SDR、高清央视/卫视/北京、少儿、标清)
 * 2. 支持路由器 udpxy 单播代理、Android 内置本地 udpxy 网关、RTP 原生组播三种播放模式
 * 3. 实时探测并回填与 check_iptv_m3u.sh 一致的视频轨道、音频轨道及实测分辨率标签
 * 4. 完整适配【电视遥控器】与【手机/平板触屏】双交互模式：
 *    - 遥控器：上下键/频道键换台、0-9数字键选台(含频道名实时预览与OK立即确认)、左右键呼出频道列表并自动定位当前频道焦点、
 *      OK键呼出信息页面、列表内左右键直接切换分类、长按OK/红键收藏、绿键打开动态组播扫描、黄键切换画面比例、菜单/蓝键打开设置、双击返回退出
 *    - 触屏：单击显隐OSD/收起抽屉、双击切换画面比例、右滑呼出频道抽屉、左滑收起抽屉、全屏上下滑动快速换台
 */
public class MainActivity extends AppCompatActivity {

    private interface IntSettingValue { int getAsInt(); }
    private interface BooleanSettingValue { boolean getAsBoolean(); }

    private static final long HUD_AUTO_HIDE_MS = 6000L;
    private static final long NUMBER_INPUT_COMMIT_MS = 1800L;
    private static final long DOUBLE_BACK_EXIT_MS = 2000L;

    private IjkVideoView videoView;
    private VideoGlass videoGlass;
    private boolean playbackFailed;
    private AlertDialog diagnosticsDialog;
    private TextView diagnosticsText;
    private LinearLayout loadingOverlay;
    private TextView tvLoadingHint;
    private TextView tvNumberInputOsd;
    private RelativeLayout hudOverlay;
    private LinearLayout drawerPanel;

    private TextView tvTopChannelTitle;
    private TextView tvTopStreamSpeed;
    private TextView tvOsdChannelNum;
    private TextView tvOsdChannelName;
    private TextView tvOsdResolutionBadge;
    private TextView tvOsdGroupName;
    private TextView tvOsdStreamUrl;
    private TextView tvOsdTrackInfo;
    private Button btnSwitchIsp;
    private Button btnFavoriteChannel;
    private Button btnPrevChannel;
    private Button phoneMoreButton;
    private View[] hudButtons;
    private int currentHudFocusIndex = 0;
    private boolean longPressCenterHandled = false;
    private boolean drawerFocusOnCategories = false;
    private CustomM3uDialogHelper customM3uDialogHelper;
    private AlertDialog activeSettingsDialog;
    private SourceSettingsController sourceSettingsController;
    private SourceNetworkController sourceNetworkController;

    private TextView tvDrawerTitle;
    private CheckBox cbOnlyAvailable;
    private EditText etSearchChannel;
    private RecyclerView rvCategories;
    private RecyclerView rvChannels;

    private ChannelRepository repository;
    private IptvSettings settings;
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private GestureDetector touchGestureDetector;

    private IptvChannel currentChannel;
    private int channelSwitchDirection = 1;
    private boolean fallbackToLocalProxyAttempted = false;
    private long lastBackPressTimeMs = 0L;
    private final StringBuilder remoteNumberBuffer = new StringBuilder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable hideHudRunnable = () -> {
        if (drawerPanel.getVisibility() == View.VISIBLE || playbackFailed) return;
        hudOverlay.setVisibility(View.GONE);
        updateHudButtonsVisualState();
    };

    private final Runnable commitNumberInputRunnable = () -> {
        if (remoteNumberBuffer.length() > 0) {
            try {
                int targetNum = Integer.parseInt(remoteNumberBuffer.toString());
                IptvChannel target = repository.findByChannelNumber(targetNum);
                if (target != null) {
                    playChannel(target);
                } else {
                    Toast.makeText(this, "未找到台号: " + targetNum, Toast.LENGTH_SHORT).show();
                }
            } catch (NumberFormatException ignored) {
            }
            remoteNumberBuffer.setLength(0);
            tvNumberInputOsd.setVisibility(View.GONE);
        }
    };

    private final Runnable telemetryTickRunnable = new Runnable() {
        @Override
        public void run() {
            updateLiveTelemetry();
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private final ChannelRepository.OnChannelsChangedListener channelsChangedListener =
            new ChannelRepository.OnChannelsChangedListener() {
                @Override
                public void onChannelsChanged() {
                    mainHandler.post(() -> {
                        if (categoryAdapter != null) {
                            categoryAdapter.setCategories(repository.getCategoryGroups());
                        }
                        refreshChannelList();
                    });
                }

                @Override
                public void onChannelsImportedAndPlayFirst() {
                    mainHandler.post(() -> {
                        if (categoryAdapter != null) {
                            categoryAdapter.setCategories(repository.getCategoryGroups());
                            categoryAdapter.setSelectedCategory(ChannelRepository.GROUP_ALL);
                        }
                        updateIspButtonsVisualState();
                        refreshChannelList();
                        playFirstChannel();
                    });
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DeviceUi.isPhone(this)) PhoneWindow.configure(getWindow());
        setContentView(DeviceUi.isPhone(this) ? R.layout.activity_main_phone : R.layout.activity_main);

        repository = ChannelRepository.getInstance(this);
        repository.addOnChannelsChangedListener(channelsChangedListener);
        settings = new IptvSettings(this);
        customM3uDialogHelper = new CustomM3uDialogHelper(this, this::onCustomM3uApplied);

        bindViews();
        if (DeviceUi.isPhone(this)) PhoneWindow.player(findViewById(R.id.root_container));
        setupTouchGestures();
        setupCategoryList();
        setupChannelList();
        setupPlayerListeners();
        refreshChannelList();

        sourceNetworkController = new SourceNetworkController(this, settings, repository);
        // A fresh installation must choose a source before playing the inherited Unicom default.
        if (settings.hasChosenSource()) {
            playInitialChannel();
        } else {
            loadingOverlay.setVisibility(View.GONE);
            showPreviewBackdrop(true);
            ((TextView) findViewById(R.id.tv_playback_status)).setText("请先选择适合当前网络的节目源");
            if (getIntent() == null || !Intent.ACTION_VIEW.equals(getIntent().getAction())) {
                sourceNetworkController.showWelcome();
            }
        }

        // 启动时如果抽屉可见，自动将遥控器焦点定位到当前播放的频道项；否则聚焦播放页 HUD 按钮
        if (drawerPanel.getVisibility() == View.VISIBLE) {
            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
        } else {
            focusHudButtonAt(currentHudFocusIndex);
        }

        handleIncomingM3uIntent(getIntent());
        AppUpdateManager.getInstance(this).checkForUpdatesOnStartup(this);
        DataReportManager.getInstance(this).reportDeviceProfileInitOnStartup();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingM3uIntent(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && DeviceUi.isPhone(this)) PhoneWindow.configure(getWindow());
    }

    private void handleIncomingM3uIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            showCustomM3uDialog();
            customM3uDialogHelper.loadFromUri(intent.getData());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (customM3uDialogHelper != null) {
            customM3uDialogHelper.handleActivityResult(requestCode, resultCode, data);
        }
        AppUpdateManager.getInstance(this).handleActivityResult(this, requestCode);
    }

    public void showCustomM3uDialog() {
        showSettingsDialog(R.id.settings_tab_channels);
        sourceSettingsController.importM3u();
    }

    void openSourceChoice(boolean importCustom) {
        showSettingsDialog(R.id.settings_tab_channels);
        if (importCustom) sourceSettingsController.importM3u();
        else activeSettingsDialog.findViewById(R.id.btn_settings_source).performClick();
    }

    void stageNetworkSource(int provider) {
        showSettingsDialog(R.id.settings_tab_channels);
        sourceSettingsController.stageProvider(provider);
    }

    SourceNetworkController.CheckConfig networkCheckConfig() {
        if (activeSettingsDialog != null && activeSettingsDialog.isShowing()) {
            RadioGroup mode = activeSettingsDialog.findViewById(R.id.rg_stream_mode);
            int selected = mode.getCheckedRadioButtonId();
            int transport = selected == R.id.rb_stream_udpxy ? IptvSettings.STREAM_MODE_UDPXY
                    : selected == R.id.rb_stream_local_proxy ? IptvSettings.STREAM_MODE_LOCAL_PROXY
                    : IptvSettings.STREAM_MODE_DIRECT_RTP;
            String address = ((EditText) activeSettingsDialog.findViewById(R.id.et_settings_udpxy)).getText().toString().trim();
            boolean changed = sourceSettingsController.hasChanges() || transport != settings.getStreamMode()
                    || !address.equals(settings.getUdpxyAddress());
            return new SourceNetworkController.CheckConfig(sourceSettingsController.selectedProvider(), transport,
                    address, sourceSettingsController.preview(), changed);
        }
        int provider = settings.hasChosenSource() ? settings.getIspProvider() : -1;
        return new SourceNetworkController.CheckConfig(provider, settings.getStreamMode(), settings.getUdpxyAddress(),
                provider < 0 ? java.util.Collections.emptyList()
                        : repository.previewChannels(provider, settings.isIncludeAllRepoChannels()), false);
    }

    void findSettingsImportFocus() {
        if (activeSettingsDialog != null && activeSettingsDialog.isShowing()) {
            View button = activeSettingsDialog.findViewById(R.id.btn_settings_import_m3u);
            if (button != null) button.requestFocus();
        }
    }

    private void onCustomM3uApplied(M3uParser.M3uInspectionReport report, boolean switchedToCustomIsp) {
        if (categoryAdapter != null) {
            categoryAdapter.setCategories(repository.getCategoryGroups());
            categoryAdapter.setSelectedCategory(ChannelRepository.GROUP_ALL);
        }
        updateIspButtonsVisualState();
        refreshChannelList();
        if (switchedToCustomIsp || currentChannel == null) {
            playFirstChannel();
        }
        String modeDesc = switchedToCustomIsp ? "已切换为自定义源并加载" : "已合并到当前播放列表";
        Toast.makeText(this,
                String.format(Locale.US, "%s: %s (%d 个有效频道)",
                        modeDesc, report.sourceName, report.validChannelCount),
                Toast.LENGTH_LONG).show();
    }

    private void bindViews() {
        videoView = findViewById(R.id.ijk_video_view);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvLoadingHint = findViewById(R.id.tv_loading_hint);
        tvNumberInputOsd = findViewById(R.id.tv_number_input_osd);
        hudOverlay = findViewById(R.id.player_hud_overlay);
        drawerPanel = findViewById(R.id.channel_drawer_panel);

        tvTopChannelTitle = findViewById(R.id.tv_top_channel_title);
        tvTopStreamSpeed = findViewById(R.id.tv_top_stream_speed);
        tvOsdChannelNum = findViewById(R.id.tv_osd_channel_num);
        tvOsdChannelName = findViewById(R.id.tv_osd_channel_name);
        tvOsdResolutionBadge = findViewById(R.id.tv_osd_resolution_badge);
        tvOsdGroupName = findViewById(R.id.tv_osd_group_name);
        tvOsdStreamUrl = findViewById(R.id.tv_osd_stream_url);
        tvOsdTrackInfo = findViewById(R.id.tv_osd_track_info);
        btnFavoriteChannel = findViewById(R.id.btn_favorite_channel);
        btnPrevChannel = findViewById(R.id.btn_prev_channel);

        tvDrawerTitle = findViewById(R.id.tv_drawer_title);
        cbOnlyAvailable = findViewById(R.id.cb_only_available);
        etSearchChannel = findViewById(R.id.et_search_channel);
        rvCategories = findViewById(R.id.rv_categories);
        rvChannels = findViewById(R.id.rv_channels);

        cbOnlyAvailable.setChecked(settings.isOnlyShowAvailable());
        cbOnlyAvailable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setOnlyShowAvailable(isChecked);
            refreshChannelList();
        });

        etSearchChannel.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshChannelList();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btn_toggle_drawer).setOnClickListener(v -> toggleChannelDrawer());

        findViewById(R.id.btn_open_scanner).setOnClickListener(v -> openScannerActivity());

        findViewById(R.id.btn_open_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btn_open_settings).setOnLongClickListener(v -> {
            showSmartRemoteQuickMenu();
            return true;
        });

        btnPrevChannel.setOnClickListener(v -> switchAdjacentChannel(-1));
        findViewById(R.id.btn_next_channel).setOnClickListener(v -> switchAdjacentChannel(1));

        findViewById(R.id.btn_numpad_dialog).setOnClickListener(v -> showVirtualNumpadDialog());
        findViewById(R.id.btn_drawer_numpad).setOnClickListener(v -> showVirtualNumpadDialog());

        btnFavoriteChannel.setOnClickListener(v -> toggleCurrentChannelFavorite());

        findViewById(R.id.btn_aspect_ratio).setOnClickListener(v -> cycleAspectRatio());

        findViewById(R.id.btn_reload_stream).setOnClickListener(v -> {
            TransportRouteCache.clear();
            if (currentChannel != null) {
                playChannel(currentChannel);
            } else if (sourceNetworkController != null) {
                sourceNetworkController.showWelcome();
            }
        });

        findViewById(R.id.btn_close_drawer).setOnClickListener(v -> closeChannelDrawer());
        findViewById(R.id.btn_diagnostics).setOnClickListener(v -> showDiagnostics());
        phoneMoreButton = AdaptivePlayerLayout.install(findViewById(R.id.root_container), v -> showPhoneMoreMenu());
        findViewById(R.id.root_container).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = AdaptivePlayerLayout.drawerWidth(v, r - l);
            if (drawerPanel.getLayoutParams().width != width) {
                drawerPanel.getLayoutParams().width = width;
                drawerPanel.requestLayout();
            }
        });
        boolean television = DeviceUi.isTelevision(this);
        ((TextView) findViewById(R.id.tv_remote_help_hint)).setText(television
                ? "左右选择操作 · 上下换台 · OK 确认 · 长按 OK 打开快捷菜单"
                : "轻点画面收起控制 · 上下滑动换台 · 双击切换比例");
        ((TextView) findViewById(R.id.tv_drawer_help)).setText(television
                ? "上下选台 · 左右分类 · OK 播放 · 长按 OK 收藏 · 返回收起" : "点击播放 · 长按收藏");
        if (DeviceUi.isPhone(this)) {
            videoGlass = new VideoGlass(videoView, findViewById(R.id.preview_backdrop),
                    findViewById(R.id.glass_top_bar), findViewById(R.id.phone_favorite_surface),
                    findViewById(R.id.phone_more_surface), findViewById(R.id.glass_player_bar),
                    drawerPanel, findViewById(R.id.phone_more_menu));
        } else {
            videoGlass = new VideoGlass(videoView, findViewById(R.id.preview_backdrop), findViewById(R.id.glass_top_bar),
                    findViewById(R.id.glass_player_bar), drawerPanel);
        }
        setupButtonFocusEffects();
    }

    void bindDialogGlass(View panel) {
        if (videoGlass == null) return;
        videoGlass.addPanel(panel, true);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                videoGlass.removePanel(view);
                view.removeOnAttachStateChangeListener(this);
            }
        });
    }

    private void setupButtonFocusEffects() {
        hudButtons = new View[]{
                findViewById(R.id.btn_toggle_drawer),
                findViewById(R.id.btn_open_scanner),
                findViewById(R.id.btn_open_settings),
                findViewById(R.id.btn_prev_channel),
                findViewById(R.id.btn_next_channel),
                findViewById(R.id.btn_numpad_dialog),
                findViewById(R.id.btn_favorite_channel),
                findViewById(R.id.btn_aspect_ratio),
                findViewById(R.id.btn_reload_stream),
                findViewById(R.id.btn_diagnostics)
        };
        if (DeviceUi.isPhone(this)) {
            hudButtons = new View[]{findViewById(R.id.btn_toggle_drawer),
                    btnFavoriteChannel, phoneMoreButton,
                    findViewById(R.id.btn_prev_channel), findViewById(R.id.btn_next_channel),
                    findViewById(R.id.btn_numpad_dialog)};
        }

        for (int i = 0; i < hudButtons.length; i++) {
            final int btnIndex = i;
            View btn = hudButtons[i];
            if (btn != null) {
                btn.setFocusable(true);
                GlassMotion.install(btn, !DeviceUi.isPhone(this) || btn.getId() != R.id.btn_toggle_drawer);
                btn.setBackgroundTintList(null);
                btn.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && drawerPanel.getVisibility() != View.VISIBLE) {
                        currentHudFocusIndex = btnIndex;
                        updateHudButtonsVisualState();
                    } else if (!hasFocus) {
                        v.setFocusableInTouchMode(false);
                    }
                });
            }
        }

        int[] drawerBtnIds = {
                R.id.btn_drawer_numpad,
                R.id.cb_only_available,
                R.id.btn_close_drawer
        };
        View.OnFocusChangeListener drawerFocusListener = (v, hasFocus) -> {
            GlassMotion.focus(v, hasFocus);
            v.setSelected(hasFocus);
            if (!hasFocus) {
                v.setFocusableInTouchMode(false);
            }
        };
        for (int id : drawerBtnIds) {
            View btn = findViewById(id);
            if (btn != null) {
                btn.setFocusable(true);
                btn.setBackgroundTintList(null);
                btn.setOnFocusChangeListener(drawerFocusListener);
                GlassMotion.install(btn);
                btn.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        drawerFocusOnCategories = true;
                        focusSelectedCategoryTab();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (v.getId() == R.id.btn_drawer_numpad) {
                            requestFocusCompat(cbOnlyAvailable);
                        } else {
                            requestFocusCompat(findViewById(R.id.btn_drawer_numpad));
                        }
                        return true;
                    }
                    return false;
                });
            }
        }

        if (etSearchChannel != null) {
            etSearchChannel.setOnClickListener(v -> {
                etSearchChannel.setFocusableInTouchMode(true);
                etSearchChannel.requestFocus();
            });
            etSearchChannel.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    v.setFocusableInTouchMode(false);
                }
            });
            etSearchChannel.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusSelectedCategoryTab();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        requestFocusCompat(findViewById(R.id.btn_drawer_numpad));
                        return true;
                    }
                }
                return false;
            });
        }
    }

    private void requestFocusCompat(View target) {
        if (target == null) {
            return;
        }
        if (target.isInTouchMode()) {
            target.setFocusableInTouchMode(true);
        }
        target.requestFocus();
    }

    public int getCurrentHudFocusIndex() {
        return currentHudFocusIndex;
    }

    public View getFocusedHudButton() {
        if (hudButtons == null || hudButtons.length == 0) {
            return null;
        }
        int idx = (currentHudFocusIndex % hudButtons.length + hudButtons.length) % hudButtons.length;
        return hudButtons[idx];
    }

    private void updateHudButtonsVisualState() {
        if (hudButtons == null) {
            return;
        }
        boolean showFocus = drawerPanel.getVisibility() != View.VISIBLE
                && hudOverlay.getVisibility() == View.VISIBLE;
        for (int i = 0; i < hudButtons.length; i++) {
            View btn = hudButtons[i];
            if (btn == null) {
                continue;
            }
            boolean isTarget = showFocus && (i == currentHudFocusIndex)
                    && (!DeviceUi.isPhone(this) || !btn.isInTouchMode());
            boolean phoneChannelButton = DeviceUi.isPhone(this) && btn.getId() == R.id.btn_toggle_drawer;
            // Keep the shared filled action visible on touch devices as well as with remote focus.
            boolean filled = isTarget || phoneChannelButton;
            btn.setSelected(filled);
            btn.setBackgroundResource(filled ? R.drawable.bg_md3_primary_button
                    : DeviceUi.isPhone(this) ? android.R.color.transparent : R.drawable.bg_group_tab);
            btn.setBackgroundTintList(null);
            // A downward cast shadow makes the centered phone action look top-heavy inside the glass.
            btn.setElevation(phoneChannelButton ? 0f : isTarget ? 12f : 0f);
            // Preserve the equal inset around the phone's white action while it remains selected.
            GlassMotion.focus(btn, filled, !phoneChannelButton);
        }
        if (videoGlass != null && showFocus) videoGlass.focus(getFocusedHudButton());
    }

    private void focusHudButtonAt(int index) {
        if (hudButtons == null || hudButtons.length == 0) {
            return;
        }
        currentHudFocusIndex = (index % hudButtons.length + hudButtons.length) % hudButtons.length;
        updateHudButtonsVisualState();
        View target = hudButtons[currentHudFocusIndex];
        if (target != null && drawerPanel.getVisibility() != View.VISIBLE && hudOverlay.getVisibility() == View.VISIBLE) {
            requestFocusCompat(target);
        }
    }

    private void moveHudButtonFocus(int delta) {
        focusHudButtonAt(currentHudFocusIndex + delta);
    }

    /**
     * 配置触屏手势：单击显隐HUD/关闭抽屉、双击切换画面比例、左右滑动开关频道抽屉、上下滑动换台
     */
    private void setupTouchGestures() {
        touchGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (DeviceUi.isPhone(MainActivity.this)
                        && findViewById(R.id.phone_more_menu).getVisibility() == View.VISIBLE) {
                    findViewById(R.id.phone_more_menu).setVisibility(View.GONE);
                    scheduleHideHud();
                } else if (drawerPanel.getVisibility() == View.VISIBLE) {
                    closeChannelDrawer();
                } else if (hudOverlay.getVisibility() == View.VISIBLE) {
                    hudOverlay.setVisibility(View.GONE);
                } else {
                    showHudTemporarily();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                cycleAspectRatio();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();

                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 90 && Math.abs(velocityX) > 200) {
                    if (dx > 0 && drawerPanel.getVisibility() != View.VISIBLE) {
                        openChannelDrawerAndFocus();
                        return true;
                    } else if (dx < 0 && drawerPanel.getVisibility() == View.VISIBLE) {
                        closeChannelDrawer();
                        return true;
                    }
                } else if (drawerPanel.getVisibility() != View.VISIBLE
                        && Math.abs(dy) > Math.abs(dx)
                        && Math.abs(dy) > 90
                        && Math.abs(velocityY) > 200) {
                    // 全屏上下滑动切换频道
                    if (dy < 0) {
                        switchAdjacentChannel(1);
                    } else {
                        switchAdjacentChannel(-1);
                    }
                    return true;
                }
                return false;
            }
        });

        View rootContainer = findViewById(R.id.root_container);
        rootContainer.setOnTouchListener((v, event) -> touchGestureDetector.onTouchEvent(event));
    }

    private void setupCategoryList() {
        rvCategories.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvCategories.setItemAnimator(null);
        categoryAdapter = new CategoryAdapter(repository.getCategoryGroups(),
                new CategoryAdapter.OnCategoryInteractionListener() {
                    @Override
                    public void onCategorySelected(String category, int position) {
                        refreshChannelList();
                    }

                    @Override
                    public void onRequestChannelListFocus() {
                        if (channelAdapter != null && channelAdapter.getItemCount() > 0) {
                            drawerFocusOnCategories = false;
                            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
                        }
                    }

                    @Override
                    public void onRequestHeaderFocus() {
                        drawerFocusOnCategories = false;
                        if (etSearchChannel != null) {
                            etSearchChannel.setFocusable(true);
                            requestFocusCompat(etSearchChannel);
                        }
                    }

                    @Override
                    public void onSwitchCategory(int delta) {
                        drawerFocusOnCategories = true;
                        switchCategoryInDrawer(delta);
                    }
                });
        rvCategories.setAdapter(categoryAdapter);
    }

    private void setupChannelList() {
        rvChannels.setLayoutManager(new LinearLayoutManager(this));
        rvChannels.setItemAnimator(null);
        channelAdapter = new ChannelAdapter(new ChannelAdapter.OnChannelActionListener() {
            @Override
            public void onChannelClicked(IptvChannel channel, int position) {
                closeChannelDrawer();
                playChannel(channel);
            }

            @Override
            public void onChannelLongClicked(IptvChannel channel, int position) {
                repository.toggleFavorite(channel);
                refreshChannelList();
                updateFavoriteButtonText();
                Toast.makeText(MainActivity.this,
                        channel.isFavorite() ? "★ 已加入收藏: " + channel.getDisplayName() : "☆ 已取消收藏: " + channel.getDisplayName(),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSwitchCategory(int delta) {
                drawerFocusOnCategories = false;
                switchCategoryInDrawer(delta);
            }

            @Override
            public void onNavigateUpFromTop() {
                drawerFocusOnCategories = true;
                focusSelectedCategoryTab();
            }

            @Override
            public void onWrapToTop() {
                drawerFocusOnCategories = false;
                focusChannelListAt(0);
            }

            @Override
            public void onNavigateToPosition(int targetPosition) {
                drawerFocusOnCategories = false;
                focusChannelListAt(targetPosition);
            }
        });
        rvChannels.setAdapter(channelAdapter);
    }

    private void switchCategoryInDrawer(int delta) {
        if (categoryAdapter == null) {
            return;
        }
        boolean targetEmpty = isAdjacentCategoryEmpty(delta);
        if (targetEmpty) {
            // 即将切换到空的频道分类（如尚未收藏频道的「★ 我的收藏」）：
            // 在清空旧频道列表前先将焦点转移到分类栏标签上，防止旧频道项移除时系统默认焦点乱飘，
            // 但保留原 drawerFocusOnCategories 状态，以便再切到下一个非空分类时能自动把焦点落回频道列表
            if (rvCategories != null && rvCategories.getChildCount() > 0) {
                int curPos = categoryAdapter.getSelectedPosition();
                RecyclerView.ViewHolder curVh = rvCategories.findViewHolderForAdapterPosition(curPos);
                if (curVh != null && curVh.itemView != null) {
                    requestFocusCompat(curVh.itemView);
                } else {
                    requestFocusCompat(rvCategories.getChildAt(0));
                }
            } else if (rvCategories != null) {
                requestFocusCompat(rvCategories);
            }
        }
        int newCatPos = categoryAdapter.selectAdjacentCategory(delta);
        centerCategoryTabAt(newCatPos);
        if (channelAdapter == null || channelAdapter.getItemCount() == 0 || drawerFocusOnCategories) {
            // 当前分类为空或用户原本就在分类栏上移动时，焦点保持在当前频道类型图标上
            focusSelectedCategoryTab();
        } else {
            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
        }
    }

    private boolean isAdjacentCategoryEmpty(int delta) {
        if (categoryAdapter == null || categoryAdapter.getItemCount() == 0 || repository == null) {
            return false;
        }
        List<String> groups = repository.getCategoryGroups();
        if (groups == null || groups.isEmpty()) {
            return false;
        }
        int curIdx = categoryAdapter.getSelectedPosition();
        int size = groups.size();
        int nextIdx = ((curIdx + delta) % size + size) % size;
        String nextCategory = groups.get(nextIdx);
        String query = etSearchChannel != null && etSearchChannel.getText() != null
                ? etSearchChannel.getText().toString() : "";
        return repository.filterChannels(nextCategory, query, false).isEmpty();
    }

    private void setupPlayerListeners() {
        videoView.setOnPreparedListener(mp -> {
            playbackFailed = false;
            ((TextView) findViewById(R.id.tv_playback_status)).setText("正在连接频道…");
            if (currentChannel != null) {
                String vTrack = videoView.getFormattedVideoTrackInfo();
                String aTrack = videoView.getFormattedAudioTrackInfo();
                String detectedRes = videoView.getDetectedResolutionFlag();
                if (!TextUtils.isEmpty(vTrack)) {
                    currentChannel.setVideoTrackInfo(vTrack);
                }
                if (!TextUtils.isEmpty(aTrack)) {
                    currentChannel.setAudioTrackInfo(aTrack);
                }
                if (!TextUtils.isEmpty(detectedRes)) {
                    currentChannel.setActualResolutionFlag(detectedRes);
                }
                updateOsdWithChannel(currentChannel);
                channelAdapter.notifyDataSetChanged();
            }
            scheduleHideHud();
        });

        videoView.setOnInfoListener((mp, what, extra) -> {
            if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                loadingOverlay.setVisibility(View.VISIBLE);
                tvLoadingHint.setText("正在缓冲视频流...");
                ((TextView) findViewById(R.id.tv_playback_status)).setText("正在缓冲…");
            } else if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                    || (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END && videoView.getFirstFrameCostMs() >= 0)) {
                loadingOverlay.setVisibility(View.GONE);
                playbackFailed = false;
                if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) showPreviewBackdrop(false);
                ((TextView) findViewById(R.id.tv_playback_status)).setText("正在播放");
                if (currentChannel != null) {
                    currentChannel.setConnected(true);
                    currentChannel.setLatencyMs(videoView.getFirstFrameCostMs());
                    updateOsdWithChannel(currentChannel);
                }
            }
            return true;
        });

        videoView.setOnCompletionListener(mp -> handlePlaybackError(mp, 1, 0));
        videoView.setOnErrorListener(this::handlePlaybackError);
    }

    private boolean handlePlaybackError(IMediaPlayer mp, int frameworkErr, int implErr) {
            if (videoView.getLastFailure() == PlaybackFailure.NETWORK
                    && currentChannel != null && currentChannel.isMulticastStream()
                    && TransportRouteCache.isExternalProxy(videoView.getPlaybackUri())
                    && !fallbackToLocalProxyAttempted) {
                fallbackToLocalProxyAttempted = true;
                int localProxyPort = (getApplication() instanceof IptvApplication)
                        ? ((IptvApplication) getApplication()).getLocalProxyPort()
                        : -1;
                if (localProxyPort > 0) {
                    TransportRouteCache.recordNetworkFailure(videoView.getPlaybackUri());
                    String localUrl = StreamUrlResolver.toUdpxyUrl(currentChannel.getRawUrl(), "127.0.0.1:" + localProxyPort);
                    tvLoadingHint.setText("正在通过网卡 IGMP 组播直连 " + currentChannel.getDisplayName() + "...");
                    tvOsdStreamUrl.setText("RTP 组播直连: " + currentChannel.getRawUrl() + "  ➔  " + localUrl);
                    videoView.setRawChannelUrl(currentChannel.getRawUrl());
                    videoView.setVideoPath(localUrl);
                    videoView.start();
                    return true;
                }
            }
            loadingOverlay.setVisibility(View.GONE);
            if (currentChannel != null) {
                currentChannel.setConnected(false);
                channelAdapter.notifyDataSetChanged();
            }
            playbackFailed = true;
            PlaybackFailure failure = videoView.getLastFailure();
            videoView.stopPlayback();
            showPreviewBackdrop(true);
            boolean decoderFailure = failure == PlaybackFailure.AUDIO_DECODER || failure == PlaybackFailure.VIDEO_DECODER;
            ((TextView) findViewById(R.id.tv_playback_status)).setText(decoderFailure ? "设备解码失败 · 请检查播放设置"
                    : failure == PlaybackFailure.FORMAT ? "节目源格式无法解析 · 请检查播放设置或 M3U"
                    : "连接失败 · 可检测节目源，或重新连接");
            tvOsdTrackInfo.setText(decoderFailure ? "设备解码失败，可在播放设置中更换内核或关闭硬件解码。"
                    : failure == PlaybackFailure.FORMAT ? "节目源格式无法解析，可更换播放内核或检查 M3U 内容。"
                    : "当前节目源：" + settings.getIspDisplayName() + "。"
                    + (currentChannel != null && !currentChannel.isMulticastStream()
                    ? "播放列表或媒体分片可能不可访问，请检测节目源及地区、授权限制。"
                    : "请确认节目源与宽带运营商匹配，并检查 IPTV 接入或代理配置。"));
            showHudTemporarily();
            if (sourceNetworkController != null) sourceNetworkController.playbackFailed(!decoderFailure && failure != PlaybackFailure.FORMAT);
            return true;
    }

    public void refreshChannelList() {
        String category = categoryAdapter != null ? categoryAdapter.getSelectedCategory() : ChannelRepository.GROUP_ALL;
        String query = etSearchChannel != null ? etSearchChannel.getText().toString() : "";

        List<IptvChannel> filtered = repository.filterChannels(category, query, settings.isOnlyShowAvailable());
        channelAdapter.submitList(filtered);
        findViewById(R.id.tv_channel_empty).setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);

        List<IptvChannel> all = repository.getAllChannels();
        int availableCount = 0;
        for (IptvChannel c : all) {
            if (Boolean.TRUE.equals(c.getConnected())) {
                availableCount++;
            }
        }
        String statsSuffix = availableCount > 0
                ? String.format(Locale.US, " (%d/%d 可用)", availableCount, all.size())
                : String.format(Locale.US, " (共%d频道)", all.size());
        if (DeviceUi.isPhone(this)) {
            tvDrawerTitle.setText("频道");
            ((TextView) findViewById(R.id.phone_channel_count)).setText(all.size() + " 个频道");
        } else tvDrawerTitle.setText("看看电视\n" + statsSuffix.trim());
    }

    private void playInitialChannel() {
        String lastUrl = settings.getLastChannelUrl();
        List<IptvChannel> all = repository.getAllChannels();
        IptvChannel target = null;
        for (IptvChannel ch : all) {
            if (lastUrl.equals(ch.getRawUrl())) {
                target = ch;
                break;
            }
        }
        if (target == null && !all.isEmpty()) {
            target = all.get(0);
        }
        if (target != null) {
            // 初始启动仅创建当前频道播放器，不创建后台播放器
            playChannel(target, false);
        }
    }

    /**
     * 立即跳转到当前频道列表的第一个频道并开始播放
     */
    public void playFirstChannel() {
        List<IptvChannel> all = repository.getAllChannels();
        if (!all.isEmpty()) {
            playChannel(all.get(0), false);
            scrollChannelListToCenter(0);
        }
    }

    public IptvChannel getCurrentChannel() {
        return currentChannel;
    }

    public void playChannel(IptvChannel channel) {
        boolean isChannelSwitch = currentChannel != null
                && channel != null
                && !TextUtils.equals(currentChannel.getRawUrl(), channel.getRawUrl());
        playChannel(channel, isChannelSwitch);
    }

    public void playChannel(IptvChannel channel, boolean isChannelSwitch) {
        if (channel == null) {
            return;
        }
        currentChannel = channel;
        playbackFailed = false;
        showPreviewBackdrop(true);
        ((TextView) findViewById(R.id.tv_playback_status)).setText("正在连接频道…");
        fallbackToLocalProxyAttempted = false;
        settings.setLastChannelUrl(channel.getRawUrl());
        channelAdapter.setCurrentPlayingUrl(channel.getRawUrl());

        int localProxyPort = -1;
        if (getApplication() instanceof IptvApplication) {
            localProxyPort = ((IptvApplication) getApplication()).getLocalProxyPort();
        }

        String resolvedUrl = resolveChannelUrl(channel, localProxyPort);
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingHint.setText(String.format(Locale.US, "正在连接 [%s] %s...",
                channel.getChannelNumberDisplay(), channel.getDisplayName()));

        updateOsdWithChannel(channel);
        showHudTemporarily();

        videoView.setRawChannelUrl(channel.getRawUrl());
        videoView.setAudioCodecHint(channel.getAudioTrackInfo());
        videoView.setVideoPath(resolvedUrl);
        videoView.start();
        if (isChannelSwitch) updateBackgroundPlayer(channel, localProxyPort);
        else videoView.setBackgroundChannelPath(null, null);
    }

    private String resolveChannelUrl(IptvChannel ch, int localProxyPort) {
        if (ch == null) {
            return "";
        }
        if (settings.getStreamMode() == IptvSettings.STREAM_MODE_UDPXY
                && TransportRouteCache.shouldBypass(android.net.Uri.parse(
                StreamUrlResolver.toUdpxyUrl(ch.getRawUrl(), settings.getUdpxyAddress())))
                && localProxyPort > 0
                && ch.isMulticastStream()) {
            return StreamUrlResolver.toUdpxyUrl(ch.getRawUrl(), "127.0.0.1:" + localProxyPort);
        }
        return StreamUrlResolver.resolvePlaybackUrl(ch, settings, localProxyPort);
    }

    /**
     * 短时预收切台方向上的一个邻台，仅缓存 TS 数据，不创建后台播放器或解码器。
     */
    private void updateBackgroundPlayer(IptvChannel activeChannel, int localProxyPort) {
        if (activeChannel == null || videoView == null) {
            return;
        }
        List<IptvChannel> list = channelAdapter != null ? channelAdapter.getChannels() : null;
        if (list == null || list.isEmpty()) {
            list = repository.getAllChannels();
        }
        if (list == null || list.size() <= 1) {
            videoView.setBackgroundChannelPath(null, null);
            return;
        }
        int curIdx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getRawUrl().equals(activeChannel.getRawUrl())) {
                curIdx = i;
                break;
            }
        }
        if (curIdx < 0) {
            videoView.setBackgroundChannelPath(null, null);
            return;
        }
        int direction = channelSwitchDirection >= 0 ? 1 : -1;
        int nextIdx = (curIdx + direction + list.size()) % list.size();
        IptvChannel nextCh = list.get(nextIdx);

        String nextResolved = (nextCh != null && !nextCh.getRawUrl().equals(activeChannel.getRawUrl()))
                ? resolveChannelUrl(nextCh, localProxyPort)
                : null;
        String nextRaw = (nextCh != null && !nextCh.getRawUrl().equals(activeChannel.getRawUrl()))
                ? nextCh.getRawUrl()
                : null;

        videoView.setBackgroundChannelPath(nextResolved, nextRaw);
    }

    private void updateOsdWithChannel(IptvChannel ch) {
        if (ch == null) {
            return;
        }
        int localProxyPort = (getApplication() instanceof IptvApplication)
                ? ((IptvApplication) getApplication()).getLocalProxyPort()
                : -1;
        String resolvedUrl = StreamUrlResolver.resolvePlaybackUrl(ch, settings, localProxyPort);

        tvTopChannelTitle.setText(String.format(Locale.US, "%s  %s [%s]",
                ch.getChannelNumberDisplay(), ch.getDisplayName(), ch.getResolutionTag()));
        tvOsdChannelNum.setText(ch.getChannelNumberDisplay());
        if (DeviceUi.isPhone(this)) {
            ((Button) findViewById(R.id.btn_numpad_dialog)).setText(ch.getChannelNumberDisplay());
            findViewById(R.id.btn_numpad_dialog).setContentDescription("数字选台，当前频道 " + ch.getChannelNumberDisplay());
        }
        tvOsdChannelName.setText(ch.getDisplayName());
        String resTag = ch.getResolutionTag();
        tvOsdResolutionBadge.setText(resTag);
        if ("4K".equals(resTag)) {
            tvOsdResolutionBadge.setBackgroundResource(R.drawable.bg_res_badge_4k);
        } else if ("高清".equals(resTag)) {
            tvOsdResolutionBadge.setBackgroundResource(R.drawable.bg_res_badge_hd);
        } else {
            tvOsdResolutionBadge.setBackgroundResource(R.drawable.bg_res_badge_sd);
        }
        tvOsdGroupName.setText(TextUtils.join(" / ", ch.getGroups()));

        if (!ch.isMulticastStream()) {
            tvOsdStreamUrl.setText("HTTP/HLS 单播源: " + ch.getRawUrl());
        } else if (settings.getStreamMode() == IptvSettings.STREAM_MODE_DIRECT_RTP) {
            tvOsdStreamUrl.setText("RTP 组播直连 (网卡 IGMP): " + ch.getRawUrl());
        } else if (resolvedUrl.equals(ch.getRawUrl())) {
            tvOsdStreamUrl.setText("组播直连: " + ch.getRawUrl());
        } else {
            tvOsdStreamUrl.setText("组播源: " + ch.getRawUrl() + "  ➔  " + resolvedUrl);
        }

        String vTrack = !TextUtils.isEmpty(ch.getVideoTrackInfo())
                ? ch.getVideoTrackInfo()
                : videoView.getFormattedVideoTrackInfo();
        String aTrack = !TextUtils.isEmpty(ch.getAudioTrackInfo())
                ? ch.getAudioTrackInfo()
                : videoView.getFormattedAudioTrackInfo();

        if (!TextUtils.isEmpty(vTrack)) {
            tvOsdTrackInfo.setText(String.format(Locale.US, "Video: %s | Audio: %s | %s",
                    vTrack, aTrack, videoView.getDecoderName()));
        } else {
            tvOsdTrackInfo.setText("解码器: " + videoView.getDecoderName() + " | 等待流媒体轨道信息...");
        }

        updateFavoriteButtonText();
    }

    private void toggleCurrentChannelFavorite() {
        if (currentChannel != null) {
            repository.toggleFavorite(currentChannel);
            updateFavoriteButtonText();
            refreshChannelList();
            Toast.makeText(this,
                    currentChannel.isFavorite()
                            ? "★ 已收藏: " + currentChannel.getDisplayName()
                            : "☆ 已取消收藏: " + currentChannel.getDisplayName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void cycleAspectRatio() {
        int ratio = videoView.toggleAspectRatio();
        String ratioText = MeasureHelper.getAspectRatioText(this, ratio);
        Toast.makeText(this, "画面比例: " + ratioText, Toast.LENGTH_SHORT).show();
        showHudTemporarily();
    }

    private void openScannerActivity() {
        Intent intent = new Intent(MainActivity.this, ScannerActivity.class);
        startActivity(intent);
    }

    private void updateFavoriteButtonText() {
        if (DeviceUi.isPhone(this)) {
            boolean favorite = currentChannel != null && currentChannel.isFavorite();
            btnFavoriteChannel.setText(favorite ? "★" : "☆");
            btnFavoriteChannel.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            btnFavoriteChannel.setContentDescription(favorite ? "取消收藏当前频道" : "收藏当前频道");
            return;
        }
        if (currentChannel != null && currentChannel.isFavorite()) {
            btnFavoriteChannel.setText("已收藏");
            btnFavoriteChannel.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_flat_star_filled, 0, 0, 0);
        } else {
            btnFavoriteChannel.setText("收藏");
            btnFavoriteChannel.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_flat_star_outline, 0, 0, 0);
        }
    }

    private void updateLiveTelemetry() {
        if (videoView == null) {
            return;
        }
        long speedBytes = videoView.getTcpSpeed();
        float fps = videoView.getVideoOutputFramesPerSecond();
        String speedStr;
        if (speedBytes >= 1024 * 1024) {
            speedStr = String.format(Locale.US, "%.2f MB/s", speedBytes / (1024f * 1024f));
        } else if (speedBytes > 0) {
            speedStr = String.format(Locale.US, "%d KB/s", speedBytes / 1024);
        } else {
            speedStr = "直播流";
        }
        int w = videoView.getVideoWidth();
        int h = videoView.getVideoHeight();
        String dimStr = (w > 0 && h > 0) ? (w + "x" + h + " | ") : "";
        String fpsStr = fps > 0 ? String.format(Locale.US, "%.1f fps", fps) : "帧率待检测";
        tvTopStreamSpeed.setText(dimStr + speedStr + " | " + fpsStr);
        if (diagnosticsDialog != null && diagnosticsDialog.isShowing()) updateDiagnosticsText();
    }

    private void switchAdjacentChannel(int delta) {
        List<IptvChannel> list = channelAdapter.getChannels();
        if (list.isEmpty()) {
            list = repository.getAllChannels();
        }
        if (list.isEmpty()) {
            return;
        }
        int curIdx = 0;
        if (currentChannel != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getRawUrl().equals(currentChannel.getRawUrl())) {
                    curIdx = i;
                    break;
                }
            }
        }
        channelSwitchDirection = delta >= 0 ? 1 : -1;
        int nextIdx = (curIdx + channelSwitchDirection + list.size()) % list.size();
        playChannel(list.get(nextIdx), true);
        scrollChannelListToCenter(nextIdx);
    }

    private void toggleChannelDrawer() {
        if (drawerPanel.getVisibility() == View.VISIBLE) {
            closeChannelDrawer();
        } else {
            openChannelDrawerAndFocus();
        }
    }

    private void openChannelDrawerAndFocus() {
        if (DeviceUi.isPhone(this)) {
            findViewById(R.id.phone_more_menu).setVisibility(View.GONE);
            drawerPanel.setVisibility(View.VISIBLE);
            hudOverlay.setVisibility(View.VISIBLE);
            AdaptivePlayerLayout.updateDrawer(findViewById(R.id.root_container));
            scrollChannelListToCenter(channelAdapter.getPlayingChannelIndex());
            scheduleHideHud();
            return;
        }
        if (hudOverlay.getVisibility() == View.VISIBLE) {
            hudOverlay.setVisibility(View.GONE);
        }
        drawerPanel.setVisibility(View.VISIBLE);
        drawerPanel.setTranslationX(-dp(24));
        drawerPanel.setAlpha(0f);
        drawerPanel.animate().translationX(0).alpha(1f).setDuration(220).start();
        hudOverlay.setVisibility(View.GONE);
        drawerFocusOnCategories = false;
        updateHudButtonsVisualState();
        if (categoryAdapter != null) {
            centerCategoryTabAt(categoryAdapter.getSelectedPosition());
        }
        if (channelAdapter != null && channelAdapter.getItemCount() > 0) {
            drawerFocusOnCategories = false;
            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
        } else {
            drawerFocusOnCategories = true;
            focusSelectedCategoryTab();
        }
        scheduleHideHud();
    }

    private void closeChannelDrawer() {
        drawerPanel.animate().cancel();
        drawerPanel.setVisibility(View.GONE);
        AdaptivePlayerLayout.updateDrawer(findViewById(R.id.root_container));
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(etSearchChannel.getWindowToken(), 0);
        etSearchChannel.clearFocus();
        showHudTemporarily();
        updateHudButtonsVisualState();
    }

    private void scrollChannelListToCenter(int position) {
        if (rvChannels == null || channelAdapter == null || channelAdapter.getItemCount() == 0) {
            return;
        }
        int safePos = Math.max(0, Math.min(position, channelAdapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = rvChannels.findViewHolderForAdapterPosition(safePos);
        int rvHeight = rvChannels.getHeight();
        if (vh != null && vh.itemView != null && rvHeight > 0) {
            int itemCenter = (vh.itemView.getTop() + vh.itemView.getBottom()) / 2;
            int dy = itemCenter - (rvHeight / 2);
            if (Math.abs(dy) > 2 && !rvChannels.isComputingLayout()) {
                rvChannels.scrollBy(0, dy);
            }
            return;
        }
        RecyclerView.LayoutManager lm = rvChannels.getLayoutManager();
        if (lm instanceof LinearLayoutManager && rvHeight > 0) {
            int itemHeight = rvChannels.getChildCount() > 0
                    ? rvChannels.getChildAt(0).getHeight()
                    : Math.round(64f * getResources().getDisplayMetrics().density);
            int centerOffset = Math.max(0, (rvHeight - itemHeight) / 2);
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(safePos, centerOffset);
        } else {
            rvChannels.scrollToPosition(safePos);
        }
    }

    public void scrollCategoryListToCenter(int position) {
        if (rvCategories == null || categoryAdapter == null || categoryAdapter.getItemCount() == 0) {
            return;
        }
        int safePos = Math.max(0, Math.min(position, categoryAdapter.getItemCount() - 1));
        int rvWidth = rvCategories.getWidth();
        RecyclerView.LayoutManager lm = rvCategories.getLayoutManager();
        RecyclerView.ViewHolder vh = rvCategories.findViewHolderForAdapterPosition(safePos);
        if (vh != null && vh.itemView != null && rvWidth > 0) {
            int itemWidth = vh.itemView.getWidth();
            if (itemWidth > 0 && lm instanceof LinearLayoutManager) {
                int centerOffset = (rvWidth - itemWidth) / 2;
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(safePos, centerOffset);
                return;
            }
            int tabCenter = (vh.itemView.getLeft() + vh.itemView.getRight()) / 2;
            int dx = tabCenter - (rvWidth / 2);
            if (Math.abs(dx) > 1 && !rvCategories.isComputingLayout()) {
                rvCategories.scrollBy(dx, 0);
            }
            return;
        }
        if (lm instanceof LinearLayoutManager && rvWidth > 0) {
            int tabWidth = estimateCategoryTabWidth(safePos);
            int centerOffset = (rvWidth - tabWidth) / 2;
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(safePos, centerOffset);
        } else {
            rvCategories.scrollToPosition(safePos);
        }
    }

    /**
     * 将指定位置的频道类型标签移动到分类栏中间（无论焦点在分类栏还是频道列表均确保居中）
     */
    private void centerCategoryTabAt(final int position) {
        if (rvCategories == null || categoryAdapter == null || categoryAdapter.getItemCount() == 0) {
            return;
        }
        final int safePos = Math.max(0, Math.min(position, categoryAdapter.getItemCount() - 1));
        scrollCategoryListToCenter(safePos);
        rvCategories.post(() -> {
            scrollCategoryListToCenter(safePos);
            RecyclerView.ViewHolder vh = rvCategories.findViewHolderForAdapterPosition(safePos);
            int rvWidth = rvCategories.getWidth();
            if (vh != null && vh.itemView != null && rvWidth > 0) {
                int tabCenter = (vh.itemView.getLeft() + vh.itemView.getRight()) / 2;
                int dx = tabCenter - (rvWidth / 2);
                if (Math.abs(dx) > 1 && !rvCategories.isComputingLayout()) {
                    rvCategories.scrollBy(dx, 0);
                }
            }
        });
    }

    private int estimateCategoryTabWidth(int position) {
        if (rvCategories != null && rvCategories.getChildCount() > 0) {
            int sum = 0;
            int count = 0;
            for (int i = 0; i < rvCategories.getChildCount(); i++) {
                int w = rvCategories.getChildAt(i).getWidth();
                if (w > 0) {
                    sum += w;
                    count++;
                }
            }
            if (count > 0) {
                return sum / count;
            }
        }
        return Math.round(92f * getResources().getDisplayMetrics().density);
    }

    private void focusChannelListAt(int position) {
        if (channelAdapter == null || channelAdapter.getItemCount() == 0) {
            return;
        }
        int safePos = Math.max(0, Math.min(position, channelAdapter.getItemCount() - 1));
        scrollChannelListToCenter(safePos);
        RecyclerView.ViewHolder immediateVh = rvChannels.findViewHolderForAdapterPosition(safePos);
        if (immediateVh != null && immediateVh.itemView != null) {
            requestFocusCompat(immediateVh.itemView);
            scrollChannelListToCenter(safePos);
        }
        rvChannels.post(() -> {
            scrollChannelListToCenter(safePos);
            RecyclerView.ViewHolder vh = rvChannels.findViewHolderForAdapterPosition(safePos);
            if (vh != null && vh.itemView != null) {
                requestFocusCompat(vh.itemView);
                scrollChannelListToCenter(safePos);
            } else {
                rvChannels.post(() -> {
                    scrollChannelListToCenter(safePos);
                    RecyclerView.ViewHolder delayedVh = rvChannels.findViewHolderForAdapterPosition(safePos);
                    if (delayedVh != null && delayedVh.itemView != null) {
                        requestFocusCompat(delayedVh.itemView);
                        scrollChannelListToCenter(safePos);
                    } else {
                        requestFocusCompat(rvChannels);
                    }
                });
            }
        });
    }

    private void focusSelectedCategoryTab() {
        if (categoryAdapter == null || categoryAdapter.getItemCount() == 0) {
            return;
        }
        int pos = categoryAdapter.getSelectedPosition();
        centerCategoryTabAt(pos);
        RecyclerView.ViewHolder immediateVh = rvCategories.findViewHolderForAdapterPosition(pos);
        if (immediateVh != null && immediateVh.itemView != null) {
            requestFocusCompat(immediateVh.itemView);
            scrollCategoryListToCenter(pos);
        } else if (rvCategories.getChildCount() > 0) {
            // 若目标分类标签尚未完成绑定，先临时挂靠在分类栏可见标签上，避免焦点逃逸到顶部搜索框
            requestFocusCompat(rvCategories.getChildAt(0));
        }
        rvCategories.post(() -> {
            int targetPos = categoryAdapter.getSelectedPosition();
            scrollCategoryListToCenter(targetPos);
            RecyclerView.ViewHolder vh = rvCategories.findViewHolderForAdapterPosition(targetPos);
            if (vh != null && vh.itemView != null) {
                requestFocusCompat(vh.itemView);
                scrollCategoryListToCenter(targetPos);
            } else {
                rvCategories.post(() -> {
                    int delayedPos = categoryAdapter.getSelectedPosition();
                    scrollCategoryListToCenter(delayedPos);
                    RecyclerView.ViewHolder delayedVh = rvCategories.findViewHolderForAdapterPosition(delayedPos);
                    if (delayedVh != null && delayedVh.itemView != null) {
                        requestFocusCompat(delayedVh.itemView);
                        scrollCategoryListToCenter(delayedPos);
                    }
                });
            }
        });
    }

    private boolean isHudButtonFocused() {
        if (hudOverlay.getVisibility() != View.VISIBLE || drawerPanel.getVisibility() == View.VISIBLE) {
            return false;
        }
        View focused = getCurrentFocus();
        if (focused != null) {
            int id = focused.getId();
            if (id == R.id.btn_toggle_drawer
                    || id == R.id.btn_open_scanner
                    || id == R.id.btn_open_settings
                    || id == R.id.btn_prev_channel
                    || id == R.id.btn_next_channel
                    || id == R.id.btn_numpad_dialog
                    || id == R.id.btn_favorite_channel
                    || id == R.id.btn_aspect_ratio
                    || id == R.id.btn_reload_stream) {
                return true;
            }
        }
        View target = getFocusedHudButton();
        return target != null && target.isSelected();
    }

    private void updateIspButtonsVisualState() {
        if (settings == null) {
            return;
        }
        if (btnSwitchIsp != null) {
            btnSwitchIsp.setText(settings.getIspShortName());
        }
    }

    public void switchIspProvider(int newIsp) {
        if (settings == null) {
            return;
        }
        int oldIsp = settings.getIspProvider();
        String oldDefaultSubnet = IptvSettings.getDefaultScanSubnetForIsp(oldIsp);
        String currentSubnet = settings.getScanSubnet();

        settings.setIspProvider(newIsp);
        if (TextUtils.isEmpty(currentSubnet) || oldDefaultSubnet.equals(currentSubnet)) {
            settings.setScanSubnet(IptvSettings.getDefaultScanSubnetForIsp(newIsp));
        }

        repository.reloadChannels();
        if (categoryAdapter != null) {
            categoryAdapter.setCategories(repository.getCategoryGroups());
            categoryAdapter.setSelectedCategory(ChannelRepository.GROUP_ALL);
        }
        updateIspButtonsVisualState();
        refreshChannelList();
        playInitialChannel();

        Toast.makeText(this,
                "已切换至: " + settings.getIspDisplayName() + " (" + repository.getAllChannels().size() + " 频道)",
                Toast.LENGTH_SHORT).show();
    }

    private void showIspSwitchDialog() {
        showSettingsDialog(R.id.settings_tab_channels);
    }

    private void showHudTemporarily() {
        hudOverlay.setVisibility(drawerPanel.getVisibility() == View.VISIBLE && !DeviceUi.isPhone(this)
                ? View.GONE : View.VISIBLE);
        AdaptivePlayerLayout.updateDrawer(findViewById(R.id.root_container));
        if (drawerPanel.getVisibility() != View.VISIBLE) {
            focusHudButtonAt(currentHudFocusIndex);
        }
        scheduleHideHud();
    }

    private void scheduleHideHud() {
        mainHandler.removeCallbacks(hideHudRunnable);
        if (drawerPanel.getVisibility() != View.VISIBLE && !playbackFailed)
            if (!DeviceUi.isPhone(this) || findViewById(R.id.phone_more_menu).getVisibility() != View.VISIBLE)
                mainHandler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_MS);
    }

    /**
     * 针对无数字按键、无红绿黄蓝四色键的智能电视极简遥控器：
     * 提供方向键可操控的虚拟数字选台键盘 (3x4 网格，默认聚焦数字 5 居中位)
     */
    private void showPreviewBackdrop(boolean visible) {
        findViewById(R.id.preview_backdrop).setVisibility(visible ? View.VISIBLE : View.GONE);
        findViewById(R.id.preview_label).setVisibility(visible ? View.VISIBLE : View.GONE);
        if (videoGlass != null) videoGlass.clear();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateDiagnosticsText() {
        diagnosticsText.setText(tvTopChannelTitle.getText() + "\n\n" + tvTopStreamSpeed.getText()
                + "\n\n" + tvOsdStreamUrl.getText() + "\n\n" + tvOsdTrackInfo.getText());
    }

    private void showDiagnostics() {
        diagnosticsText = new TextView(this);
        diagnosticsText.setTextSize(16);
        diagnosticsText.setTextColor(getResources().getColor(R.color.text_primary));
        diagnosticsText.setTextIsSelectable(true);
        diagnosticsText.setPadding(dp(24), dp(16), dp(24), dp(16));
        updateDiagnosticsText();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(diagnosticsText);
        diagnosticsDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("播放信息")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .setNeutralButton("播放与网络设置", (d, which) -> showSettingsDialog())
                .create();
        diagnosticsDialog.show();
    }

    private void showVirtualNumpadDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_virtual_numpad, null);
        Button compactClose = AdaptiveNumpad.install(view);
        TextView tvDigits = view.findViewById(R.id.tv_numpad_digits);
        TextView tvPreview = view.findViewById(R.id.tv_numpad_preview);
        StringBuilder inputBuf = new StringBuilder();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .setNegativeButton(compactClose == null ? "关闭" : null, null)
                .create();
        if (compactClose != null) compactClose.setOnClickListener(v -> dialog.dismiss());

        Runnable updatePreview = () -> {
            if (inputBuf.length() == 0) {
                tvDigits.setText("---");
                tvPreview.setText("输入台号，预览频道");
                return;
            }
            String numStr = inputBuf.toString();
            tvDigits.setText(numStr);
            try {
                int num = Integer.parseInt(numStr);
                IptvChannel match = repository.findByChannelNumber(num);
                if (match != null) {
                    tvPreview.setText(String.format(Locale.US, "匹配频道: [%s] %s (%s)",
                            match.getChannelNumberDisplay(), match.getDisplayName(), match.getResolutionTag()));
                } else {
                    tvPreview.setText("未找到台号 " + num + " 对应的频道");
                }
            } catch (NumberFormatException ignored) {
            }
        };

        View clear = view.findViewById(R.id.btn_numpad_clear);
        if (clear != null) clear.setOnClickListener(v -> {
            inputBuf.setLength(0);
            updatePreview.run();
        });

        int[] btnIds = {
                R.id.btn_numpad_0, R.id.btn_numpad_1, R.id.btn_numpad_2, R.id.btn_numpad_3,
                R.id.btn_numpad_4, R.id.btn_numpad_5, R.id.btn_numpad_6,
                R.id.btn_numpad_7, R.id.btn_numpad_8, R.id.btn_numpad_9
        };
        for (int i = 0; i <= 9; i++) {
            final int digit = i;
            Button b = view.findViewById(btnIds[i]);
            b.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.04f : 1.0f).scaleY(hasFocus ? 1.04f : 1.0f).setDuration(100).start();
                v.setSelected(hasFocus);
            });
            b.setOnClickListener(v -> {
                if (inputBuf.length() >= 4) {
                    inputBuf.setLength(0);
                }
                inputBuf.append(digit);
                updatePreview.run();
            });
        }

        View btnDel = view.findViewById(R.id.btn_numpad_del);
        btnDel.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.04f : 1.0f).scaleY(hasFocus ? 1.04f : 1.0f).setDuration(100).start();
            v.setSelected(hasFocus);
        });
        btnDel.setOnClickListener(v -> {
            if (inputBuf.length() > 0) {
                inputBuf.deleteCharAt(inputBuf.length() - 1);
                updatePreview.run();
            }
        });
        btnDel.setOnLongClickListener(v -> {
            inputBuf.setLength(0);
            updatePreview.run();
            return true;
        });

        View btnGo = view.findViewById(R.id.btn_numpad_go);
        btnGo.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.04f : 1.0f).scaleY(hasFocus ? 1.04f : 1.0f).setDuration(100).start();
            v.setSelected(hasFocus);
        });
        btnGo.setOnClickListener(v -> {
            if (inputBuf.length() == 0) {
                Toast.makeText(this, "请先输入台号", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int targetNum = Integer.parseInt(inputBuf.toString());
                IptvChannel target = repository.findByChannelNumber(targetNum);
                if (target != null) {
                    dialog.dismiss();
                    closeChannelDrawer();
                    playChannel(target);
                } else {
                    Toast.makeText(this, "未找到台号: " + targetNum, Toast.LENGTH_SHORT).show();
                }
            } catch (NumberFormatException ignored) {
            }
        });

        dialog.show();
        if (compactClose != null) {
            int width = Math.min(dp(500), getResources().getDisplayMetrics().widthPixels - dp(72));
            int height = Math.min(dp(266), getResources().getDisplayMetrics().heightPixels - dp(16));
            PhonePages.panel(this, dialog, view, width, height);
        }
        // 默认将遥控器焦点放在正中央的数字 "5" 上，方便上下左右一步触达任意数字
        View centerBtn = view.findViewById(R.id.btn_numpad_5);
        if (centerBtn != null) {
            centerBtn.post(centerBtn::requestFocus);
        }
    }

    /**
     * 针对智能电视极简遥控器（无数字键、无四色键）的全功能快捷操作菜单：
     * 全屏下长按【OK键】或按【菜单键】呼出，所有高频功能均可通过上下键 + OK 一键完成。
     */
    private void showSmartRemoteQuickMenu() {
        String favText = (currentChannel != null && currentChannel.isFavorite())
                ? "取消收藏当前频道 (" + currentChannel.getDisplayName() + ")"
                : "收藏当前频道 (" + (currentChannel != null ? currentChannel.getDisplayName() : "") + ")";

        String[] items = {
                "切换运营商节目源: 当前 [" + settings.getIspShortName() + "] (联通 / 移动 / 电信 / 自定义)",
                "上传并检测自定义 M3U 播放列表文件",
                "虚拟数字键盘选台 (按台号直达)",
                "打开频道分类列表 (左右键切换分类)",
                favText,
                "切换画面比例 (16:9 / 4:3 / 铺满 / 适应)",
                "重新连接当前频道流",
                "播放与网络转发设置 (udpxy / RTP 组播直连)",
                "组播地址动态扫描与巡检 (check_iptv_m3u.sh)"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("智能遥控器快捷操作面板")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showIspSwitchDialog();
                            break;
                        case 1:
                            showCustomM3uDialog();
                            break;
                        case 2:
                            showVirtualNumpadDialog();
                            break;
                        case 3:
                            openChannelDrawerAndFocus();
                            break;
                        case 4:
                            toggleCurrentChannelFavorite();
                            break;
                        case 5:
                            cycleAspectRatio();
                            break;
                        case 6:
                            if (currentChannel != null) {
                                playChannel(currentChannel);
                            }
                            break;
                        case 7:
                            showSettingsDialog();
                            break;
                        case 8:
                            openScannerActivity();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPhoneMoreMenu() {
        View menu = findViewById(R.id.phone_more_menu);
        if (menu.getVisibility() == View.VISIBLE) {
            menu.setVisibility(View.GONE);
            scheduleHideHud();
            return;
        }
        LinearLayout items = findViewById(R.id.phone_more_items);
        items.removeAllViews();
        String[] titles = {"设置", "节目源", "导入 M3U", "数字选台", "画面比例", "重新连接", "播放信息", "频道扫描"};
        String[] hints = {"网络与播放", IptvSettings.getIspDisplayName(settings.getIspProvider()),
                "文件与内容检测", "输入台号", MeasureHelper.getAspectRatioText(this, settings.getAspectRatio()),
                "刷新当前频道", "连接与解码", "扫描与巡检"};
        for (int i = 0; i < titles.length; i++) {
            final int action = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(12), 0);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            row.setContentDescription(titles[i]);
            row.setFocusable(true);
            row.setClickable(true);
            GlassMotion.install(row);
            for (int part = 0; part < 2; part++) {
                TextView label = new TextView(this);
                label.setText(part == 0 ? titles[i] : hints[i]);
                label.setTextSize(part == 0 ? 13 : 11);
                label.setTextColor(getResources().getColor(part == 0 ? R.color.text_primary : R.color.text_secondary));
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                GlassForeground.protect(label, false);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(part == 0 ? 0
                        : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, part == 0 ? 1 : 0);
                if (part == 1) labelParams.leftMargin = dp(8);
                row.addView(label, labelParams);
            }
            row.setOnClickListener(v -> {
                menu.setVisibility(View.GONE);
                switch (action) {
                    case 0: showSettingsDialog(); break;
                    case 1:
                        showSettingsDialog(R.id.settings_tab_channels);
                        activeSettingsDialog.findViewById(R.id.btn_settings_source).performClick();
                        break;
                    case 2: showCustomM3uDialog(); break;
                    case 3: showVirtualNumpadDialog(); break;
                    case 4: cycleAspectRatio(); break;
                    case 5: findViewById(R.id.btn_reload_stream).performClick(); break;
                    case 6: showDiagnostics(); break;
                    case 7: openScannerActivity(); break;
                }
                scheduleHideHud();
            });
            items.addView(row);
        }
        ViewGroup.LayoutParams params = menu.getLayoutParams();
        params.height = Math.min(dp(364), Math.max(dp(88), hudOverlay.getHeight() - dp(83)));
        menu.setLayoutParams(params);
        menu.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideHudRunnable);
    }

    private void configurePhoneSettings(View view) {
        if (!DeviceUi.isPhone(this)) return;
        Button back = view.findViewById(R.id.btn_phone_settings_back);
        PhoneSettingsUi.back(back, "返回播放，放弃未保存设置");
        back.setOnClickListener(v -> view.findViewById(R.id.btn_settings_cancel).performClick());
    }

    private void fitDialogWindow(AlertDialog dialog, int marginDp, int maxWidthDp, int maxHeightDp) {
        if (dialog.getWindow() == null) return;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        android.graphics.Rect bounds = new android.graphics.Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(bounds);
        int availableWidth = bounds.width() > 0 ? bounds.width() : metrics.widthPixels;
        int availableHeight = bounds.height() > 0 ? bounds.height() : metrics.heightPixels;
        int marginPx = dp(marginDp);
        int widthPx = Math.min(dp(maxWidthDp), Math.max(1, availableWidth - marginPx * 2));
        int heightPx = Math.min(dp(maxHeightDp), Math.max(1, availableHeight - marginPx * 2));
        dialog.getWindow().setLayout(widthPx, heightPx);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void showSettingsDialog() {
        showSettingsDialog(R.id.settings_tab_network);
    }

    private void showSettingsDialog(int initialTab) {
        if (activeSettingsDialog != null && activeSettingsDialog.isShowing()) {
            activeSettingsDialog.findViewById(initialTab).performClick();
            return;
        }
        View view = LayoutInflater.from(this).inflate(DeviceUi.isPhone(this)
                ? R.layout.dialog_player_settings_phone : R.layout.dialog_player_settings, null);
        configurePhoneSettings(view);
        if (sourceNetworkController != null) sourceNetworkController.bindSettings(view);
        RadioGroup rgStreamMode = view.findViewById(R.id.rg_stream_mode);
        Button streamModeButton = view.findViewById(R.id.btn_settings_stream_mode);
        EditText etUdpxy = view.findViewById(R.id.et_settings_udpxy);
        RadioGroup rgPlayerCore = view.findViewById(R.id.rg_player_core);
        Button playerCoreButton = view.findViewById(R.id.btn_settings_player_core);
        CompoundButton cbMediaCodec = view.findViewById(R.id.cb_settings_mediacodec);
        CompoundButton cbHevc = view.findViewById(R.id.cb_settings_hevc);
        CompoundButton cbOpenSlEs = view.findViewById(R.id.cb_settings_opensles);
        CompoundButton cbTextureView = view.findViewById(R.id.cb_settings_textureview);
        CompoundButton cbAllChannels = view.findViewById(R.id.cb_settings_all_channels);

        int mode = settings.getStreamMode();
        if (mode == IptvSettings.STREAM_MODE_UDPXY) {
            rgStreamMode.check(R.id.rb_stream_udpxy);
        } else if (mode == IptvSettings.STREAM_MODE_LOCAL_PROXY) {
            rgStreamMode.check(R.id.rb_stream_local_proxy);
        } else {
            rgStreamMode.check(R.id.rb_stream_direct_rtp);
        }

        etUdpxy.setText(settings.getUdpxyAddress());

        int playerCore = settings.getPlayerType();
        if (playerCore == IptvSettings.PLAYER_UTV) {
            rgPlayerCore.check(R.id.rb_core_exo);
        } else if (playerCore == IptvSettings.PLAYER_ANDROID_MEDIA) {
            rgPlayerCore.check(R.id.rb_core_android);
        } else {
            rgPlayerCore.check(R.id.rb_core_ijk);
        }

        cbMediaCodec.setChecked(settings.isMediaCodecEnabled());
        cbHevc.setChecked(settings.isMediaCodecHevcEnabled());
        cbOpenSlEs.setChecked(settings.isOpenSlEsEnabled());
        cbTextureView.setChecked(settings.getRenderViewType() == IjkVideoView.RENDER_TEXTURE_VIEW);
        cbAllChannels.setChecked(settings.isIncludeAllRepoChannels());
        sourceSettingsController = new SourceSettingsController(this, view, settings, repository, customM3uDialogHelper);
        final SourceSettingsController sourceDraft = sourceSettingsController;

        View proxyAddressGroup = view.findViewById(R.id.proxy_address_group);
        TextView streamHint = view.findViewById(R.id.tv_stream_mode_hint);
        Runnable updateNetworkOptions = () -> {
            int checked = rgStreamMode.getCheckedRadioButtonId();
            streamModeButton.setText(checked == R.id.rb_stream_udpxy ? "路由器代理"
                    : checked == R.id.rb_stream_local_proxy ? "内置代理" : "组播直连");
            proxyAddressGroup.setVisibility(checked == R.id.rb_stream_udpxy ? View.VISIBLE : View.GONE);
            streamHint.setText((checked == R.id.rb_stream_udpxy
                    ? "通过路由器的 udpxy 服务，将组播转换为 HTTP 播放。"
                    : checked == R.id.rb_stream_local_proxy
                    ? "由应用在本机转换组播流，无需路由器代理。"
                    : "直接接收组播流，需要当前网络支持 IGMP。")
                    + " HTTP / HLS 节目源按原地址播放。");
        };
        rgStreamMode.setOnCheckedChangeListener((group, checkedId) -> updateNetworkOptions.run());
        updateNetworkOptions.run();
        streamModeButton.setOnClickListener(v -> {
            int selected = rgStreamMode.getCheckedRadioButtonId() == R.id.rb_stream_local_proxy ? 1
                    : rgStreamMode.getCheckedRadioButtonId() == R.id.rb_stream_direct_rtp ? 2 : 0;
            int[] ids = {R.id.rb_stream_udpxy, R.id.rb_stream_local_proxy, R.id.rb_stream_direct_rtp};
            String[] labels = {"路由器代理 · udpxy", "内置代理 · 本机组播转 HTTP", "组播直连 · RTP / IGMP"};
            AlertDialog picker = new MaterialAlertDialogBuilder(this)
                    .setTitle("连接方式")
                    .setSingleChoiceItems(labels, selected, (d, which) -> {
                        rgStreamMode.check(ids[which]);
                        d.dismiss();
                        streamModeButton.requestFocus();
                    })
                    .setNegativeButton("取消", (d, which) -> streamModeButton.requestFocus())
                    .create();
            NetworkDialogUi.show(this, picker);
        });

        CompoundButton[] ijkOptions = {cbMediaCodec, cbHevc, cbOpenSlEs};
        Runnable updateDecoderOptions = () -> {
            boolean ijk = rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_ijk;
            playerCoreButton.setText(ijk ? "IjkMediaPlayer"
                    : rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_exo
                    ? "UTVPlayer" : "AndroidMediaPlayer");
            ((TextView) view.findViewById(R.id.tv_decoder_options_hint)).setText(ijk
                    ? "硬件解码和 OpenSL ES 音频输出适用于当前 Ijk 内核。"
                    : rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_exo
                    ? "硬件解码适用于当前 UTV 内核；OpenSL ES 仅适用于 Ijk。"
                    : "系统内核自行选择解码器，以下三项不适用。");
            for (CompoundButton option : ijkOptions) {
                boolean enabled = ijk || (rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_exo
                        && option != cbOpenSlEs);
                option.setEnabled(enabled);
                option.setAlpha(enabled ? 1f : 0.45f);
            }
        };
        rgPlayerCore.setOnCheckedChangeListener((group, checkedId) -> updateDecoderOptions.run());
        updateDecoderOptions.run();
        playerCoreButton.setOnClickListener(v -> {
            int selected = rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_exo ? 1
                    : rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_android ? 2 : 0;
            int[] ids = {R.id.rb_core_ijk, R.id.rb_core_exo, R.id.rb_core_android};
            String[] labels = {"IjkMediaPlayer", "UTVPlayer", "AndroidMediaPlayer"};
            AlertDialog picker = new MaterialAlertDialogBuilder(this)
                    .setTitle("播放内核")
                    .setSingleChoiceItems(labels, selected, (d, which) -> {
                        rgPlayerCore.check(ids[which]);
                        d.dismiss();
                        playerCoreButton.requestFocus();
                    })
                    .setNegativeButton("取消", (d, which) -> playerCoreButton.requestFocus())
                    .create();
            NetworkDialogUi.show(this, picker);
        });

        AlertDialog settingsDialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .create();
        int[] tabIds = {R.id.settings_tab_network, R.id.settings_tab_playback, R.id.settings_tab_channels};
        int[] panelIds = {R.id.settings_panel_network, R.id.settings_panel_playback, R.id.settings_panel_channels};
        int[] firstControlIds = {R.id.btn_check_network_sources, R.id.btn_settings_player_core, R.id.btn_settings_source};
        boolean[] switchingTab = {false};
        for (int i = 0; i < tabIds.length; i++) {
            final int index = i;
            View tab = view.findViewById(tabIds[i]);
            Runnable selectTab = () -> {
                if (switchingTab[0]) return;
                switchingTab[0] = true;
                try {
                    for (int p = 0; p < panelIds.length; p++) {
                        view.findViewById(tabIds[p]).setSelected(p == index);
                        view.findViewById(panelIds[p]).setVisibility(p == index ? View.VISIBLE : View.GONE);
                    }
                } finally {
                    switchingTab[0] = false;
                }
            };
            tab.setOnClickListener(v -> {
                selectTab.run();
                // Touch selection must not make the tab consume its next tap only to gain focus.
                if (!tab.isInTouchMode()) tab.requestFocus();
            });
            tab.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int next = index + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if (next >= 0 && next < tabIds.length) {
                        requestFocusCompat(view.findViewById(tabIds[next]));
                        return true;
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    selectTab.run();
                    requestFocusCompat(view.findViewById(firstControlIds[index]));
                    return true;
                }
                return false;
            });
        }
        // 所有设置行均可向左返回当前分类，隐藏面板不参与焦点搜索。
        int[][] panelControls = {
                {R.id.btn_settings_stream_mode, R.id.btn_check_network_sources, R.id.btn_network_advanced,
                        R.id.et_settings_udpxy},
                {R.id.btn_settings_player_core, R.id.cb_settings_mediacodec, R.id.cb_settings_hevc},
                {R.id.btn_settings_source, R.id.cb_settings_all_channels}
        };
        for (int p = 0; p < panelControls.length; p++) {
            for (int controlId : panelControls[p]) view.findViewById(controlId).setNextFocusLeftId(tabIds[p]);
        }
        IntSettingValue draftMode = () -> rgStreamMode.getCheckedRadioButtonId() == R.id.rb_stream_udpxy
                ? IptvSettings.STREAM_MODE_UDPXY : rgStreamMode.getCheckedRadioButtonId() == R.id.rb_stream_local_proxy
                ? IptvSettings.STREAM_MODE_LOCAL_PROXY : IptvSettings.STREAM_MODE_DIRECT_RTP;
        IntSettingValue draftCore = () -> rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_exo
                ? IptvSettings.PLAYER_UTV : rgPlayerCore.getCheckedRadioButtonId() == R.id.rb_core_android
                ? IptvSettings.PLAYER_ANDROID_MEDIA : IptvSettings.PLAYER_IJK;
        BooleanSettingValue playbackChanges = () -> draftMode.getAsInt() != settings.getStreamMode()
                || !etUdpxy.getText().toString().trim().equals(settings.getUdpxyAddress())
                || draftCore.getAsInt() != settings.getPlayerType()
                || cbMediaCodec.isChecked() != settings.isMediaCodecEnabled()
                || cbHevc.isChecked() != settings.isMediaCodecHevcEnabled()
                || cbOpenSlEs.isChecked() != settings.isOpenSlEsEnabled()
                || cbTextureView.isChecked() != (settings.getRenderViewType() == IjkVideoView.RENDER_TEXTURE_VIEW);
        TextView draftStatus = view.findViewById(R.id.tv_phone_settings_status);
        view.findViewById(R.id.btn_settings_licenses).setOnClickListener(v -> OpenSourceLicenses.show(this));
        Button saveButton = view.findViewById(R.id.btn_settings_save);
        BooleanSettingValue hasChanges = () -> sourceDraft.hasChanges() || playbackChanges.getAsBoolean();
        Runnable updateDraftStatus = () -> {
            boolean dirty = hasChanges.getAsBoolean();
            draftStatus.setText(dirty ? "• 有修改 · 待应用" : "全部已保存");
            draftStatus.setContentDescription(dirty ? "有修改，待应用" : "全部已保存");
            draftStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    dirty ? R.color.settings_warning : R.color.settings_secondary_label));
            saveButton.setEnabled(dirty);
            if (!"正在应用".contentEquals(saveButton.getText())) saveButton.setText("保存并应用");
        };
        sourceDraft.setOnChangeListener(updateDraftStatus);
        rgStreamMode.setOnCheckedChangeListener((group, checkedId) -> {
            updateNetworkOptions.run();
            updateDraftStatus.run();
        });
        rgPlayerCore.setOnCheckedChangeListener((group, checkedId) -> {
            updateDecoderOptions.run();
            updateDraftStatus.run();
        });
        for (CompoundButton option : new CompoundButton[]{cbMediaCodec, cbHevc, cbOpenSlEs, cbTextureView}) {
            option.setOnCheckedChangeListener((button, checked) -> updateDraftStatus.run());
        }
        etUdpxy.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateDraftStatus.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        updateDraftStatus.run();
        Runnable closeSettings = () -> {
            if (!hasChanges.getAsBoolean()) {
                settingsDialog.dismiss();
                return;
            }
            AlertDialog confirm = new MaterialAlertDialogBuilder(this)
                    .setTitle("有修改尚未应用")
                    .setMessage("继续编辑、放弃本次修改，或保存并应用后返回播放。")
                    .setPositiveButton("保存并应用", (d, which) -> saveButton.performClick())
                    .setNeutralButton("放弃修改", (d, which) -> settingsDialog.dismiss())
                    .setNegativeButton("继续编辑", (d, which) -> view.findViewById(R.id.btn_settings_cancel).requestFocus())
                    .create();
            confirm.setOnShowListener(d -> confirm.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus());
            confirm.show();
        };
        view.findViewById(R.id.btn_settings_cancel).setOnClickListener(v -> closeSettings.run());
        saveButton.setOnClickListener(v -> {
            if (!hasChanges.getAsBoolean()) return;
            saveButton.setEnabled(false);
            saveButton.setText("正在应用");
            boolean reloadPlayback = currentChannel == null || hasChanges.getAsBoolean();
            if (!sourceDraft.commit()) {
                saveButton.setText("保存并应用");
                updateDraftStatus.run();
                return;
            }
            settings.setStreamMode(draftMode.getAsInt());
            settings.setUdpxyAddress(etUdpxy.getText().toString());
            settings.setPlayerType(draftCore.getAsInt());
            settings.setMediaCodecEnabled(cbMediaCodec.isChecked());
            settings.setMediaCodecHevcEnabled(cbHevc.isChecked());
            settings.setOpenSlEsEnabled(cbOpenSlEs.isChecked());
            int renderType = cbTextureView.isChecked() ? IjkVideoView.RENDER_TEXTURE_VIEW : IjkVideoView.RENDER_SURFACE_VIEW;
            int previousRender = settings.getRenderViewType();
            settings.setRenderViewType(renderType);
            if (renderType != previousRender) videoView.setRender(renderType);
            if (reloadPlayback && settings.hasChosenSource()) {
                TransportRouteCache.clear();
                IptvChannel matching = null;
                for (IptvChannel channel : repository.getAllChannels()) {
                    if (currentChannel != null && TextUtils.equals(channel.getRawUrl(), currentChannel.getRawUrl())) {
                        matching = channel;
                        break;
                    }
                }
                categoryAdapter.setCategories(repository.getCategoryGroups());
                categoryAdapter.setSelectedCategory(ChannelRepository.GROUP_ALL);
                updateIspButtonsVisualState();
                refreshChannelList();
                if (matching != null) playChannel(matching, false);
                else playFirstChannel();
            }
            settingsDialog.dismiss();
        });
        settingsDialog.setCanceledOnTouchOutside(false);
        settingsDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) closeSettings.run();
            return true;
        });
        view.findViewById(R.id.btn_settings_scan).setOnClickListener(v -> {
            Runnable open = () -> { settingsDialog.dismiss(); openScannerActivity(); };
            if (sourceDraft.hasChanges() || playbackChanges.getAsBoolean()) {
                new MaterialAlertDialogBuilder(this).setTitle("设置尚未保存")
                        .setMessage("前往扫描前，是否保存并应用当前修改？")
                        .setPositiveButton("保存并继续", (d, w) -> {
                            view.findViewById(R.id.btn_settings_save).performClick();
                            if (!settingsDialog.isShowing()) openScannerActivity();
                        })
                        .setNeutralButton("放弃并继续", (d, w) -> open.run())
                        .setNegativeButton("留在设置", (d, w) -> view.findViewById(R.id.btn_settings_scan).requestFocus()).show();
            } else open.run();
        });
        view.findViewById(R.id.btn_settings_export).setOnClickListener(v ->
                PlaylistExporter.show(this, repository.getAllChannels(), settings.getUdpxyAddress()));
        view.findViewById(initialTab).performClick();
        boolean[] initialFocusPending = {true};
        view.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus && initialFocusPending[0]) {
                initialFocusPending[0] = false;
                view.findViewById(initialTab).performClick();
            }
        });
        activeSettingsDialog = settingsDialog;
        settingsDialog.setOnDismissListener(d -> {
            if (activeSettingsDialog == settingsDialog) activeSettingsDialog = null;
            if (sourceSettingsController == sourceDraft) sourceSettingsController = null;
        });
        settingsDialog.show();
        if (DeviceUi.isPhone(this)) {
            PhonePages.fill(this, settingsDialog, view);
        } else if (settingsDialog.getWindow() != null) {
            settingsDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            fitDialogWindow(settingsDialog, 16, DeviceUi.isTelevision(this) ? 920 : 780,
                    DeviceUi.isTelevision(this) ? 620 : 590);
        }
        if (DeviceUi.isTelevision(this)) requestFocusCompat(view.findViewById(initialTab));
    }

    /**
     * 全局遥控器按键分发拦截（优先于子控件焦点处理）：
     * - 数字键 0~9 / 小键盘 0~9：输入台号选台，OSD 实时预览台名，按 OK 键立即跳转
     * - 频道加减键 (CHANNEL_UP / CHANNEL_DOWN / PAGE_UP / PAGE_DOWN)：随时上下换台
     * - 全屏状态下（抽屉关闭）：
     *   - 上/下方向键：直接切换上/下一频道
     *   - 左/右方向键：若信息页面(HUD)未显示则调出左侧频道列表并定位当前频道；若信息页面已显示则切换 HUD 按钮焦点
     *   - OK / Enter 键：若信息页面(HUD)未显示则调出信息页面并聚焦功能按钮；若信息页面已显示则触发当前聚焦按钮
     * - 机顶盒四色功能键与快捷键：
     *   - 菜单键 / 蓝键 (PROG_BLUE)：打开播放与 udpxy 设置
     *   - 绿键 (PROG_GREEN) / GUIDE：打开组播地址动态扫描器
     *   - 黄键 (PROG_YELLOW)：切换画面比例
     *   - 红键 (PROG_RED)：收藏/取消收藏频道
     *   - INFO 键：显隐流媒体信息栏
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (videoGlass != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN)
            videoGlass.illuminate(ev.getRawX());
        if (hudOverlay.getVisibility() == View.VISIBLE || drawerPanel.getVisibility() == View.VISIBLE) {
            scheduleHideHud();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A modal settings window owns remote input; playback shortcuts must not leak through it.
        if (activeSettingsDialog != null && activeSettingsDialog.isShowing()) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            if (hudOverlay.getVisibility() == View.VISIBLE || drawerPanel.getVisibility() == View.VISIBLE) {
                scheduleHideHud();
            }

            // 1. 搜索输入框未处于焦点时，拦截 0-9 及小键盘数字键用于遥控器数字选台
            boolean searchFocused = etSearchChannel != null && etSearchChannel.hasFocus();
            if (!searchFocused) {
                int digit = extractDigitFromKeyCode(keyCode);
                if (digit >= 0) {
                    handleRemoteNumberDigit(digit);
                    return true;
                }
                // 当正在输入台号时，按 OK/Enter 立即确认切台，按返回/删除键取消输入
                if (remoteNumberBuffer.length() > 0) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        mainHandler.removeCallbacks(commitNumberInputRunnable);
                        commitNumberInputRunnable.run();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DEL) {
                        mainHandler.removeCallbacks(commitNumberInputRunnable);
                        remoteNumberBuffer.setLength(0);
                        tvNumberInputOsd.setVisibility(View.GONE);
                        return true;
                    }
                }
            }

            // 2. 遥控器专用频道加减键 / 翻页键随时换台 (上=下一台/+1，下=上一台/-1)
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                switchAdjacentChannel(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                switchAdjacentChannel(-1);
                return true;
            }

            // 3. 遥控器菜单键与四色快捷键（针对无四色键的智能遥控器，按菜单键打开全功能快捷菜单）
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                showSmartRemoteQuickMenu();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_BLUE) {
                showSettingsDialog();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_GREEN || keyCode == KeyEvent.KEYCODE_GUIDE) {
                openScannerActivity();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW || keyCode == KeyEvent.KEYCODE_CAPTIONS) {
                cycleAspectRatio();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (hudOverlay.getVisibility() == View.VISIBLE && drawerPanel.getVisibility() != View.VISIBLE) {
                    hudOverlay.setVisibility(View.GONE);
                } else {
                    showHudTemporarily();
                }
                return true;
            }

            // 4. 当左侧频道列表抽屉打开时的遥控器方向键循环切换逻辑
            if (drawerPanel.getVisibility() == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View focused = getCurrentFocus();
                    int focusedId = focused != null ? focused.getId() : View.NO_ID;
                    if (focusedId == R.id.btn_drawer_numpad) {
                        requestFocusCompat(cbOnlyAvailable);
                        return true;
                    } else if (focusedId == R.id.cb_only_available) {
                        requestFocusCompat(findViewById(R.id.btn_drawer_numpad));
                        return true;
                    } else if (focusedId == R.id.et_search_channel
                            && etSearchChannel.getText() != null
                            && etSearchChannel.getText().length() > 0) {
                        return super.dispatchKeyEvent(event);
                    }
                    int delta = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ? -1 : 1;
                    switchCategoryInDrawer(delta);
                    return true;
                }
            } else {
                // 当左侧频道列表抽屉关闭时的全屏遥控器方向键与长按 OK 逻辑
                if (keyCode == KeyEvent.KEYCODE_PROG_RED || keyCode == KeyEvent.KEYCODE_BOOKMARK) {
                    toggleCurrentChannelFavorite();
                    return true;
                }

                // 播放时方向键逻辑：
                // - 左/右方向键：切换页面上所有 HUD 按钮的焦点（若 HUD 已隐藏则先唤出 HUD 并聚焦按钮）
                // - 上/下方向键：直接切换上/下一频道
                // - 短按 OK：若 HUD 已显示则触发当前聚焦的按钮点击；若 HUD 未显示则打开频道抽屉
                // - 长按 OK 键：弹出「智能遥控器快捷操作面板」
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (hudOverlay.getVisibility() != View.VISIBLE) {
                        showHudTemporarily();
                    } else {
                        scheduleHideHud();
                        moveHudButtonFocus(-1);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (hudOverlay.getVisibility() != View.VISIBLE) {
                        showHudTemporarily();
                    } else {
                        scheduleHideHud();
                        moveHudButtonFocus(1);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    switchAdjacentChannel(1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    switchAdjacentChannel(-1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getRepeatCount() > 0) {
                        longPressCenterHandled = true;
                        showSmartRemoteQuickMenu();
                    } else {
                        longPressCenterHandled = false;
                    }
                    return true;
                }
            }

            // 5. 返回键分层处理：先关抽屉 -> 再关HUD操控栏 -> 双击退出应用
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (DeviceUi.isPhone(this) && findViewById(R.id.phone_more_menu).getVisibility() == View.VISIBLE) {
                    findViewById(R.id.phone_more_menu).setVisibility(View.GONE);
                    scheduleHideHud();
                    return true;
                }
                if (drawerPanel.getVisibility() == View.VISIBLE) {
                    closeChannelDrawer();
                    return true;
                }
                if (hudOverlay.getVisibility() == View.VISIBLE) {
                    hudOverlay.setVisibility(View.GONE);
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPressTimeMs > DOUBLE_BACK_EXIT_MS) {
                    lastBackPressTimeMs = now;
                    Toast.makeText(this, "再按一次返回键退出 看看电视", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            int keyCode = event.getKeyCode();
            // 全屏播放时，短按松开 OK 键：若 HUD 显示则执行当前聚焦按钮功能，否则打开频道列表
            if (drawerPanel.getVisibility() != View.VISIBLE
                    && remoteNumberBuffer.length() == 0
                    && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                boolean wasLongPress = longPressCenterHandled;
                longPressCenterHandled = false;
                if (!wasLongPress && !event.isCanceled() && (event.getEventTime() - event.getDownTime() < 500)) {
                    if (hudOverlay.getVisibility() == View.VISIBLE) {
                        View focusedBtn = getFocusedHudButton();
                        if (focusedBtn != null) {
                            scheduleHideHud();
                            focusedBtn.performClick();
                            return true;
                        }
                    }
                    openChannelDrawerAndFocus();
                    return true;
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int extractDigitFromKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }

    private void handleRemoteNumberDigit(int digit) {
        if (remoteNumberBuffer.length() >= 4) {
            remoteNumberBuffer.setLength(0);
        }
        remoteNumberBuffer.append(digit);
        String numStr = remoteNumberBuffer.toString();
        String previewText = numStr;
        try {
            int num = Integer.parseInt(numStr);
            IptvChannel match = repository.findByChannelNumber(num);
            if (match != null) {
                previewText = String.format(Locale.US, "%s  %s", numStr, match.getDisplayName());
            } else {
                previewText = numStr + "  (未匹配)";
            }
        } catch (NumberFormatException ignored) {
        }
        tvNumberInputOsd.setText(previewText);
        tvNumberInputOsd.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(commitNumberInputRunnable);
        mainHandler.postDelayed(commitNumberInputRunnable, NUMBER_INPUT_COMMIT_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sourceNetworkController != null) sourceNetworkController.start();
        AppUpdateManager.getInstance(this).attachActivity(this);
        if (categoryAdapter != null) {
            categoryAdapter.setCategories(repository.getCategoryGroups());
        }
        refreshChannelList();
        mainHandler.post(telemetryTickRunnable);
        if (videoGlass != null) videoGlass.start();
        if (currentChannel != null && !videoView.isPlaying()) {
            if (playbackFailed) {
                // An explicit return after failure retries the configured route.
                TransportRouteCache.clear();
                playChannel(currentChannel);
            } else {
                videoView.start();
            }
        }
    }

    @Override
    protected void onPause() {
        if (sourceNetworkController != null) sourceNetworkController.stop();
        mainHandler.removeCallbacks(telemetryTickRunnable);
        if (videoGlass != null) videoGlass.stop();
        if (videoView != null) {
            videoView.stopOffscreenPlayers();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (sourceNetworkController != null) sourceNetworkController.close();
        if (isFinishing()) {
            DataReportManager.getInstance(this).onAppExit();
        }
        if (repository != null) {
            repository.removeOnChannelsChangedListener(channelsChangedListener);
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (videoGlass != null) videoGlass.release();
        if (diagnosticsDialog != null) diagnosticsDialog.dismiss();
        if (customM3uDialogHelper != null) customM3uDialogHelper.close();
        if (activeSettingsDialog != null) activeSettingsDialog.dismiss();
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
