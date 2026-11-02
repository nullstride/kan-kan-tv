package com.bjunicom.iptv.ui;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AlertDialog;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Exports must never replace modified_iptv*.m3u files used by the active source. */
public final class PlaylistExporter {
    private PlaylistExporter() {}

    public static File[] write(Context context, List<IptvChannel> channels, String proxy) throws IOException {
        File directory = new File(ChannelRepository.getInstance(context).getScanRootDir(), "exports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建导出目录");
        File[] files = {new File(directory, "current_channels.m3u"), new File(directory, "results.csv")};
        String[] content = {M3uParser.toM3uContent(channels, false, proxy), M3uParser.toResultsCsvContent(channels)};
        for (int i = 0; i < files.length; i++) {
            android.util.AtomicFile output = new android.util.AtomicFile(files[i]);
            FileOutputStream stream = null;
            try {
                stream = output.startWrite();
                stream.write(content[i].getBytes(StandardCharsets.UTF_8));
                output.finishWrite(stream);
            } catch (IOException error) {
                if (stream != null) output.failWrite(stream);
                throw error;
            }
        }
        return files;
    }

    public static void show(Context context, List<IptvChannel> channels, String proxy) {
        try {
            File[] files = write(context, channels, proxy);
            new MaterialAlertDialogBuilder(context).setTitle("导出成功")
                    .setMessage("已导出 " + channels.size() + " 个频道：\n\nM3U：" + files[0].getAbsolutePath()
                            + "\n\nCSV：" + files[1].getAbsolutePath())
                    .setPositiveButton("完成", null)
                    .setNeutralButton("分享文件", (d, w) -> {
                        ArrayList<android.net.Uri> uris = new ArrayList<>();
                        for (File file : files) uris.add(FileProvider.getUriForFile(context,
                                context.getPackageName() + ".fileprovider", file));
                        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/octet-stream")
                                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try { context.startActivity(Intent.createChooser(share, "导出频道列表")); }
                        catch (android.content.ActivityNotFoundException error) {
                            android.widget.Toast.makeText(context, "文件已保存；当前设备没有可分享的应用", 1).show();
                        }
                    }).show();
        } catch (IOException error) {
            new MaterialAlertDialogBuilder(context).setTitle("导出失败")
                    .setMessage(error.getMessage()).setPositiveButton("返回", null).show();
        }
    }
}
