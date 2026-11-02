package com.bjunicom.iptv.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Source changes belong to this settings session, never to a nested dialog. */
final class SourceSettingsController {
    private final MainActivity activity;
    private final IptvSettings settings;
    private final ChannelRepository repository;
    private final CustomM3uDialogHelper importer;
    private final Button source;
    private final TextView summary;
    private final TextView networkSource;
    private final Button importButton;
    private final CompoundButton allChannels;
    private final TextView rangeHint;
    private int provider;
    private String pendingContent;
    private String pendingName;
    private Runnable onChange;

    SourceSettingsController(MainActivity activity, View view, IptvSettings settings,
                             ChannelRepository repository, CustomM3uDialogHelper importer) {
        this.activity = activity;
        this.settings = settings;
        this.repository = repository;
        this.importer = importer;
        provider = settings.hasChosenSource() ? settings.getIspProvider() : -1;
        source = view.findViewById(R.id.btn_settings_source);
        summary = view.findViewById(R.id.tv_settings_source_summary);
        networkSource = view.findViewById(R.id.tv_network_source_value);
        importButton = view.findViewById(R.id.btn_settings_import_m3u);
        allChannels = view.findViewById(R.id.cb_settings_all_channels);
        rangeHint = view.findViewById(R.id.tv_settings_channel_range_hint);
        source.setOnClickListener(v -> chooseSource());
        view.findViewById(R.id.btn_settings_import_m3u).setOnClickListener(v -> importM3u());
        allChannels.setOnCheckedChangeListener((button, checked) -> refresh());
        refresh();
    }

    boolean hasChanges() {
        return (provider >= 0 && (!settings.hasChosenSource() || provider != settings.getIspProvider()))
                || (provider == IptvSettings.ISP_CUSTOM_M3U && pendingContent != null)
                || allChannels.isChecked() != settings.isIncludeAllRepoChannels();
    }

    void setOnChangeListener(Runnable listener) {
        onChange = listener;
    }

    private void refresh() {
        source.setText(provider < 0 ? "请选择节目源  ›" : IptvSettings.getIspDisplayName(provider) + "  ›");
        importButton.setText(pendingContent != null || repository.hasCustomUploadedM3u()
                ? "查看检测结果 / 重新导入 M3U" : "导入 M3U");
        int count = provider < 0 ? 0 : provider == IptvSettings.ISP_CUSTOM_M3U && pendingContent != null
                ? M3uParser.inspectAndParse(pendingContent, pendingName).validChannelCount
                : repository.previewChannels(provider, allChannels.isChecked()).size();
        String name = provider == IptvSettings.ISP_CUSTOM_M3U
                ? (pendingContent != null ? pendingName : settings.getCustomM3uName())
                : IptvSettings.getIspDisplayName(provider);
        summary.setText(provider < 0 ? "尚未选择节目源，保存其他设置不会开始播放。"
                : (hasChanges() ? "待应用：" : "当前：") + name + " · " + count + " 个频道");
        if (networkSource != null) {
            networkSource.setText(provider < 0 ? "尚未选择节目源" : name);
        }
        boolean rangeEnabled = provider >= 0 && repository.isBuiltInChannelRangeAvailable(provider);
        allChannels.setEnabled(rangeEnabled);
        allChannels.setAlpha(rangeEnabled ? 1f : 0.45f);
        rangeHint.setText(provider < 0 ? "选择节目源后可设置频道范围。" : rangeEnabled ? "保存后刷新精选或完整频道列表。"
                : provider == IptvSettings.ISP_CUSTOM_M3U ? "自定义源按导入列表加载。" : provider == IptvSettings.ISP_BEIJING_TELECOM ? "北京电信源按完整列表加载。"
                : "当前使用已保存列表，按列表内容加载。");
        if (onChange != null) onChange.run();
    }

    int selectedProvider() { return provider; }

    java.util.List<com.bjunicom.iptv.model.IptvChannel> preview() {
        if (provider < 0) return java.util.Collections.emptyList();
        if (provider == IptvSettings.ISP_CUSTOM_M3U && pendingContent != null)
            return M3uParser.inspectAndParse(pendingContent, pendingName).channels;
        return repository.previewChannels(provider, allChannels.isChecked());
    }

    void stageProvider(int selected) {
        if (selected < 0 || selected > IptvSettings.ISP_CUSTOM_M3U) return;
        provider = selected;
        refresh();
        source.requestFocus();
    }

    private void chooseSource() {
        String[] labels = {"北京联通 IPTV", "北京移动 IPTV", "北京电信 IPTV", "北京三网聚合", "自定义 M3U"};
        android.content.DialogInterface.OnClickListener select = (dialog, which) -> {
            dialog.dismiss();
            if (which == IptvSettings.ISP_CUSTOM_M3U
                    && pendingContent == null && !repository.hasCustomUploadedM3u()) {
                importM3u();
            } else {
                provider = which;
                refresh();
                source.requestFocus();
            }
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle("选择节目源");
        if (DeviceUi.isPhone(activity)) {
            builder.setSingleChoiceItems(new ArrayAdapter<String>(activity,
                    R.layout.item_phone_settings_source, labels) {
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    View row = super.getView(position, convertView, parent);
                    PhoneSettingsUi.roundedBackground(row);
                    return row;
                }
            }, provider, select);
            AlertDialog dialog = builder.create();
            dialog.setOnDismissListener(d -> source.requestFocus());
            dialog.show();
            PhoneSettingsUi.sourcePicker(activity, dialog);
        } else {
            AlertDialog picker = builder.setSingleChoiceItems(labels, provider, select)
                    .setNegativeButton("取消", (d, w) -> source.requestFocus()).create();
            picker.setOnDismissListener(d -> source.requestFocus());
            NetworkDialogUi.show(activity, picker);
        }
    }

    void importM3u() {
        String initial = pendingContent != null ? pendingContent : null;
        String name = pendingContent != null ? pendingName : settings.getCustomM3uName();
        String mergeBase = provider < 0 ? "#EXTM3U\n" : provider == IptvSettings.ISP_CUSTOM_M3U && pendingContent != null
                ? pendingContent : M3uParser.toM3uContent(repository.previewChannels(provider,
                        allChannels.isChecked()), false, null);
        importer.showStagedDialog(initial, name, mergeBase, (content, fileName, report) -> {
            pendingContent = content;
            pendingName = fileName;
            provider = IptvSettings.ISP_CUSTOM_M3U;
            refresh();
        }, () -> activity.findSettingsImportFocus());
    }

    boolean commit() {
        if (provider < 0) {
            settings.setIncludeAllRepoChannels(allChannels.isChecked());
            return true;
        }
        if (!hasChanges()) {
            // Explicitly saving the default source also counts as a user's source choice.
            settings.setIspProvider(provider);
            return true;
        }
        int oldProvider = settings.getIspProvider();
        String oldSubnet = settings.getScanSubnet();
        if (provider == IptvSettings.ISP_CUSTOM_M3U && pendingContent != null) {
            M3uParser.M3uInspectionReport report = repository.importCustomM3uContent(pendingContent, pendingName, true);
            if (!report.isValid()) {
                Toast.makeText(activity, report.toSummaryText(), Toast.LENGTH_LONG).show();
                return false;
            }
        } else {
            settings.setIspProvider(provider);
            if (oldSubnet.equals(IptvSettings.getDefaultScanSubnetForIsp(oldProvider))) {
                settings.setScanSubnet(IptvSettings.getDefaultScanSubnetForIsp(provider));
            }
        }
        settings.setIncludeAllRepoChannels(allChannels.isChecked());
        if (pendingContent == null || provider != IptvSettings.ISP_CUSTOM_M3U) repository.reloadChannels();
        return true;
    }
}
