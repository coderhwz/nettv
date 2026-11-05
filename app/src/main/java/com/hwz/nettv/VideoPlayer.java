package com.hwz.nettv;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import org.videolan.libvlc.interfaces.IMedia;

final class VideoPlayer extends FrameLayout {
    private final Activity activity;
    private final LibVLC vlc;
    private final MediaPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView status;
    private final SeekBar seek;
    private final ImageButton pause, audio, subtitles;
    private boolean dragging, released, navigatingControls;
    private final long opened = SystemClock.elapsedRealtime();
    private boolean started, output;
    private LinearLayout controls;
    private TextView title;
    private final Runnable hideOverlay = () -> {
        if (!released && playerIsPlaying()) { controls.setVisibility(INVISIBLE); title.setVisibility(INVISIBLE); navigatingControls = false; }
    };
    private boolean playerIsPlaying() { return player != null && player.isPlaying(); }
    private void showOverlay() {
        controls.setVisibility(VISIBLE); title.setVisibility(VISIBLE);
        handler.removeCallbacks(hideOverlay); handler.postDelayed(hideOverlay, 5000);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (released) return;
            long duration = player.getLength(), time = Math.max(0, player.getTime());
            if (!dragging) seek.setProgress(duration > 0 ? (int)(time * 1000 / duration) : 0);
            status.setText(clock(time) + "  /  " + clock(duration));
            String action = player.isPlaying() ? "暂停" : "播放";
            if (!action.contentEquals(pause.getContentDescription())) {
                pause.setImageResource(player.isPlaying() ? R.drawable.control_pause : R.drawable.control_play);
                pause.setContentDescription(action); pause.setTooltipText(action);
            }
            handler.postDelayed(this, 500);
        }
    };
    VideoPlayer(Activity activity, SmbBrowser server, SmbBrowser.Entry entry, boolean software, Runnable back) {
        super(activity); this.activity = activity; setBackgroundColor(Color.BLACK);
        ArrayList<String> options = new ArrayList<>(); options.add("--network-caching=1500");
        vlc = new LibVLC(activity, options); player = new MediaPlayer(vlc);
        VLCVideoLayout video = new VLCVideoLayout(activity);
        addView(video, new FrameLayout.LayoutParams(-1, -1));
        player.attachViews(video, null, true, true);
        player.setAspectRatio(null);
        player.setScale(0);
        player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        video.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right-left != oldRight-oldLeft || bottom-top != oldBottom-oldTop) video.post(this::originalAspect);
        });
        title = new TextView(activity); title.setText(entry.name); title.setTextColor(Color.WHITE);
        title.setTextSize(20); title.setPadding(28, 12, 28, 12); title.setBackgroundColor(0x99000000);
        addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        controls = new LinearLayout(activity); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(24, 6, 24, 10); controls.setBackgroundColor(0xCC0C1420);
        status = new TextView(activity); status.setTextColor(Color.WHITE); status.setText("正在连接视频…");
        controls.addView(status); seek = new SeekBar(activity); seek.setMax(1000); controls.addView(seek);
        LinearLayout buttons = new LinearLayout(activity); buttons.setGravity(Gravity.CENTER);
        MediaControls.add(buttons, R.drawable.control_back, "返回", back);
        MediaControls.add(buttons, R.drawable.control_rewind, "后退 10 秒", () -> jump(-10000));
        pause = MediaControls.add(buttons, R.drawable.control_pause, "暂停", this::toggle);
        MediaControls.add(buttons, R.drawable.control_forward, "前进 10 秒", () -> jump(10000));
        audio = MediaControls.add(buttons, R.drawable.control_audio, "音轨", () -> tracks(false));
        subtitles = MediaControls.add(buttons, R.drawable.control_subtitles, "字幕", () -> tracks(true));
        controls.addView(buttons);
        addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { }
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            public void onStopTrackingTouch(SeekBar bar) { player.setPosition(bar.getProgress()/1000f); dragging = false; }
        });
        player.setEventListener(event -> {
            if (released) return;
            if (event.type == MediaPlayer.Event.Playing && !started) {
                started = true; Perf.log("video_playing totalMs=" + (SystemClock.elapsedRealtime()-opened));
            }
            if (event.type == MediaPlayer.Event.Vout && !output && event.getVoutCount() > 0) {
                output = true; Perf.log("video_output totalMs=" + (SystemClock.elapsedRealtime()-opened));
            }
            if (event.type == MediaPlayer.Event.Playing || event.type == MediaPlayer.Event.Vout) originalAspect();
            if (event.type == MediaPlayer.Event.EncounteredError) {
                Perf.log("video_error totalMs=" + (SystemClock.elapsedRealtime()-opened));
                handler.removeCallbacks(tick);
                status.setText("播放失败：请检查 SMB 权限、网络，或返回设置切换软/硬件解码。");
            } else if (event.type == MediaPlayer.Event.EndReached) {
                handler.removeCallbacks(tick); status.setText("播放结束");
                pause.setImageResource(R.drawable.control_play); pause.setContentDescription("重播"); pause.setTooltipText("重播"); showOverlay();
            }
        });
        Media media = new Media(vlc, SmbBrowser.mediaUri(entry.url));
        media.setHWDecoderEnabled(!software, false);
        media.addOption(":smb-user=" + (server.user.isEmpty() ? "guest" : server.user));
        media.addOption(":smb-pwd=" + server.password);
        media.addOption(":smb-domain=" + server.domain);
        player.setMedia(media); media.release(); player.play();
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.post(tick); pause.requestFocus(); showOverlay();
        setOnClickListener(v -> showOverlay());
    }
    private void originalAspect() {
        if (released) return;
        IMedia.VideoTrack track = player.getCurrentVideoTrack();
        if (track == null || track.width <= 0 || track.height <= 0) return;
        long width = track.width, height = track.height;
        if (track.sarNum > 0 && track.sarDen > 0) { width *= track.sarNum; height *= track.sarDen; }
        if (track.orientation >= 4 && track.orientation <= 7) { long swap = width; width = height; height = swap; }
        // Use VLC's effective track dimensions; a rotated phone clip may retain a stale default aspect.
        player.setAspectRatio(width + ":" + height);
    }
    private void jump(long delta) {
        if (!player.isSeekable()) return;
        long from = player.getTime(), target = Math.max(0, Math.min(player.getLength(), from + delta));
        player.setTime(target);
        Perf.log("video_seek fromMs=" + from + " targetMs=" + target + " stepMs=" + delta);
    }
    private void toggle() {
        if (player.isPlaying()) player.pause();
        else { player.play(); handler.removeCallbacks(tick); handler.post(tick); }
    }
    private void tracks(boolean subtitle) {
        MediaPlayer.TrackDescription[] tracks = subtitle ? player.getSpuTracks() : player.getAudioTracks();
        if (tracks == null || tracks.length == 0) { Toast.makeText(getContext(), "没有可选" + (subtitle ? "内嵌字幕" : "音轨"), Toast.LENGTH_SHORT).show(); return; }
        String[] labels = new String[tracks.length]; int selected = -1;
        for (int i = 0; i < tracks.length; i++) { labels[i] = tracks[i].name; if (tracks[i].id == (subtitle ? player.getSpuTrack() : player.getAudioTrack())) selected = i; }
        new android.app.AlertDialog.Builder(activity).setTitle(subtitle ? "选择内嵌字幕" : "选择音轨")
                .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    if (subtitle) player.setSpuTrack(tracks[index].id); else player.setAudioTrack(tracks[index].id);
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                && (!navigatingControls || controls.getVisibility() != VISIBLE || seek.hasFocus())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) { showOverlay(); jump(key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000); }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_CENTER) {
                boolean entering = controls.getVisibility() != VISIBLE || !navigatingControls;
                navigatingControls = true;
                showOverlay();
                if (entering) { pause.requestFocusFromTouch(); return true; }
            }
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: toggle(); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY: player.play(); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: player.pause(); return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: jump(10000); return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND: jump(-10000); return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    void release() {
        released = true; handler.removeCallbacksAndMessages(null);
        player.setEventListener(null); player.stop(); player.detachViews(); player.release(); vlc.release();
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
