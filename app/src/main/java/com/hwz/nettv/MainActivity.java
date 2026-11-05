package com.hwz.nettv;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.InputType;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.FutureTask;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int INK = 0xFFEAF2F7, MUTED = 0xFF94AABC, ACCENT = 0xFF65DEC5;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService authentication = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor imageWorker = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
    private ImageLoad pictureLoad, prefetchLoad;
    private FrameLayout pictureViewer;
    private ImageView pictureView, outgoingPicture;
    private TextView pictureStatus;
    private int pictureDirection;
    private long pictureRequested;
    private final class ImageLoad {
        final String id;
        final FutureTask<Void> task;
        volatile boolean cancelled;
        ImageLoad(SmbBrowser.Entry item) {
            SmbBrowser active = server;
            id = ThumbnailCache.id(active,item);
            int width = getResources().getDisplayMetrics().widthPixels, height = getResources().getDisplayMetrics().heightPixels;
            task = new FutureTask<>(() -> {
                long start = SystemClock.elapsedRealtime();
                try {
                    Bitmap loaded = active.image(item.url,width,height,()->cancelled);
                    runOnUiThread(() -> {
                        if (cancelled) { loaded.recycle(); return; }
                        fullImages.put(id,loaded);
                        if (pictureLoad == this) {
                            pictureLoad = null; presentPicture(loaded,"smb"); prefetchPicture();
                        } else if (prefetchLoad == this) {
                            prefetchLoad = null;
                            Perf.log("image_prefetch ready totalMs=" + (SystemClock.elapsedRealtime()-start));
                        }
                    });
                } catch (Exception error) {
                    Perf.log("image_load failed=" + error.getClass().getSimpleName() + " cancelled=" + cancelled
                            + " totalMs=" + (SystemClock.elapsedRealtime()-start));
                    runOnUiThread(() -> {
                        if (!cancelled && pictureLoad == this) { pictureLoad = null; pictureStatus.setText("图片无法显示，请检查格式、网络或共享权限。"); }
                        if (prefetchLoad == this) prefetchLoad = null;
                    });
                }
                return null;
            });
        }
        void cancel() { cancelled = true; task.cancel(true); imageWorker.remove(task); }
    }
    private final LruCache<String, Bitmap> fullImages = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private MediaThumbnails thumbnails;
    private Vault vault;
    private LinearLayout page;
    private SmbBrowser server;
    private VideoPlayer video;
    private Bitmap bitmap;
    private String screen = "lock", currentUrl, restoreFolder;
    private LinearLayout pictureControls;
    private int pictureRevealKey;
    private final android.os.Handler pictureHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hidePictureControls = () -> { if (pictureControls != null) pictureControls.setVisibility(View.GONE); };
    private List<SmbBrowser.Entry> entries = new ArrayList<>(), pictures = new ArrayList<>();
    private final Map<String, List<SmbBrowser.Entry>> directories = new LinkedHashMap<String, List<SmbBrowser.Entry>>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<SmbBrowser.Entry>> eldest) { return size() > 8; }
    };
    private final Deque<String> parents = new ArrayDeque<>();
    private volatile int generation;
    private int pictureIndex, selectedIndex;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Perf.init(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        thumbnails = new MediaThumbnails(this); vault = new Vault(this); lockScreen(false);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(5), 0, dp(5)); return view;
    }
    private LinearLayout base(String title, String hint) {
        generation++; thumbnails.cancel(); clearMedia();
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(42), dp(20), dp(42), dp(16)); page.setBackgroundColor(0xFF0C1420);
        setContentView(page);
        TextView brand = text("N E T T V     /     私 人 媒 体 库", 13, ACCENT); page.addView(brand);
        page.addView(text(title, 28, INK));
        if (hint != null) page.addView(text(hint, 14, MUTED));
        return page;
    }
    private Button button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(16); button.setTextColor(INK); button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.focusable); button.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48)); lp.setMargins(0, dp(8), dp(10), dp(4));
        row.addView(button, lp); button.setOnClickListener(v -> action.run()); return button;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); parent.addView(row); return row;
    }
    private EditText field(LinearLayout parent, String label, String value, boolean secret, boolean numeric) {
        parent.addView(text(label, 14, MUTED));
        EditText input = new EditText(this); input.setTextColor(INK); input.setTextSize(18); input.setSingleLine(true);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        input.setText(value); input.setPadding(dp(12), dp(6), dp(12), dp(6)); input.setBackgroundResource(R.drawable.focusable);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46)); lp.bottomMargin = dp(4); parent.addView(input, lp); return input;
    }
    private void hideKeyboard() { ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(page.getWindowToken(), 0); }
    private void lockScreen(boolean change) {
        thumbnails.clear(); fullImages.evictAll();
        screen = change ? "pin" : "lock";
        boolean setup = !vault.hasPin() || change;
        generation++; thumbnails.cancel(); clearMedia();
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER);
        page.setPadding(dp(24),dp(16),dp(24),dp(16)); page.setBackgroundResource(R.drawable.browser_background); setContentView(page);
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER);
        page.addView(panel,new LinearLayout.LayoutParams(dp(360),-2));
        TextView brand = text("N E T T V",16,ACCENT); brand.setGravity(Gravity.CENTER); panel.addView(brand);
        TextView title = text(setup ? "设置访问密码" : "欢迎回来",28,INK); title.setGravity(Gravity.CENTER); panel.addView(title);
        TextView hint = text(setup ? "设置 4 位数字 PIN" : "输入 4 位 PIN 自动解锁",14,MUTED); hint.setGravity(Gravity.CENTER); panel.addView(hint);
        PinPad pad = new PinPad(this); panel.addView(pad);
        TextView message = text("",14,MUTED); message.setGravity(Gravity.CENTER); message.setMinHeight(dp(32)); panel.addView(message);
        String[] first = {null};
        pad.onComplete(value -> {
            if (setup && first[0] == null) {
                first[0] = value; title.setText("再次输入 PIN"); hint.setText("输入相同的 4 位 PIN 以确认"); message.setText(""); pad.reset(); return;
            }
            if (setup && !value.equals(first[0])) {
                first[0] = null; title.setText("设置访问密码"); hint.setText("设置 4 位数字 PIN"); message.setText("两次输入不一致，请重新设置"); pad.reset(); return;
            }
            long wait = vault.remainingLockSeconds();
            if (!setup && wait > 0) { message.setText("请等待 " + wait + " 秒后再试"); pad.reset(); return; }
            pad.busy(true); message.setText("正在验证…"); final int token = generation;
            long requested = SystemClock.elapsedRealtime();
            authentication.execute(() -> {
                long start = SystemClock.elapsedRealtime();
                try {
                    boolean ok;
                    if (setup) { vault.setPin(value); ok = true; } else ok = vault.unlock(value);
                    Perf.log("pin_verify queueMs=" + (start-requested) + " verifyMs=" + (SystemClock.elapsedRealtime()-start) + " ok=" + ok);
                    runOnUiThread(() -> {
                        if (token != generation) return;
                        if (!ok) {
                            message.setText(vault.remainingLockSeconds() > 0 ? "连续输错 5 次，请等待 30 秒" : "密码不正确，请重试");
                            pad.busy(false); pad.reset(); pad.focus(); return;
                        }
                        hideKeyboard();
                        if (server != null) browse(); else loadServer();
                    });
                } catch (Exception error) { runOnUiThread(() -> {
                    if (token == generation) { pad.busy(false); pad.reset(); pad.focus(); message.setText("无法访问本机安全存储，请重试"); }
                }); }
            });
        });
        pad.focus();
    }
    private void loadServer() {
        int token = generation;
        long requested = SystemClock.elapsedRealtime();
        worker.execute(() -> {
            long start = SystemClock.elapsedRealtime();
            try {
                JSONObject config = vault.loadServer();
                SmbBrowser loaded = config == null ? null : new SmbBrowser(config);
                Perf.log("server_init queueMs=" + (start-requested) + " loadMs=" + (SystemClock.elapsedRealtime()-start));
                runOnUiThread(() -> {
                    if (token != generation) { closeServer(loaded); return; }
                    if (loaded == null) settings();
                    else { server = loaded; currentUrl = server.root; parents.clear(); restoreFolder = null; browse(); }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (token == generation) { settings(); toast("读取服务器配置失败，请重新填写"); } }); }
        });
    }
    private void settings() {
        screen = "settings"; base("连接 SMB 服务器", "填写共享地址。电视与服务器需在同一网络，或已配置 VPN。");
        JSONObject config = null; try { config = vault.loadServer(); } catch (Exception ignored) { }
        if (config == null) config = new JSONObject();
        ScrollView scroll = new ScrollView(this); LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(form); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        EditText url = field(form, "共享地址 · smb://192.168.1.10/Movies/", config.optString("url"), false, false);
        LinearLayout columns = row(form); LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL); right.setOrientation(LinearLayout.VERTICAL); right.setPadding(dp(16),0,0,0);
        columns.addView(left, new LinearLayout.LayoutParams(0,-2,1)); columns.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        EditText user = field(left, "SMB 用户名 · 留空为访客", config.optString("user"), false, false);
        EditText password = field(right, "SMB 密码", config.optString("password"), true, false);
        EditText domain = field(form, "域 · 通常留空", config.optString("domain"), false, false);
        CheckBox software = new CheckBox(this); software.setText("使用软件解码（硬件解码花屏或无画面时开启）"); software.setTextColor(INK);
        software.setChecked(getPreferences(0).getBoolean("software", false)); form.addView(software);
        TextView message = text("账号密码在本机加密保存；访问 PIN 与 SMB 密码相互独立。",14,MUTED); form.addView(message);
        LinearLayout actions = row(form);
        Button connect = button(actions,"保存并连接", () -> {});
        button(actions,"修改 PIN", () -> lockScreen(true));
        if (server != null) button(actions,"返回",this::browse);
        connect.setOnClickListener(v -> {
            JSONObject next = new JSONObject();
            try {
                next.put("url", SmbBrowser.normalizeUrl(url.getText().toString())); next.put("user",user.getText().toString().trim());
                next.put("password",password.getText().toString()); next.put("domain",domain.getText().toString().trim());
            } catch (Exception error) { message.setText(error.getMessage()); return; }
            hideKeyboard(); connect.setEnabled(false); message.setText("正在测试连接…"); int token = generation;
            worker.execute(() -> {
                SmbBrowser candidate = null;
                try {
                    candidate = new SmbBrowser(next); List<SmbBrowser.Entry> found = candidate.list(candidate.root);
                    if (token != generation) { candidate.close(); return; }
                    vault.saveServer(next); SmbBrowser ready = candidate;
                    runOnUiThread(() -> {
                        if (token != generation) { closeServer(ready); return; }
                        thumbnails.clear(); fullImages.evictAll(); closeServer(server); server = ready; currentUrl = server.root; entries = found; directories.clear(); directories.put(currentUrl, found); parents.clear(); restoreFolder = null; selectedIndex = 0;
                        getPreferences(0).edit().putBoolean("software",software.isChecked()).apply(); showFiles();
                    });
                } catch (Exception error) { closeServer(candidate); runOnUiThread(() -> { if (token == generation) { connect.setEnabled(true); message.setText("连接失败：检查地址、共享名、账号权限，以及服务器是否启用 SMB2/3。"); } }); }
            });
        });
        url.requestFocus();
    }
    private void browse() {
        if (server == null) { settings(); return; }
        List<SmbBrowser.Entry> cached = directories.get(currentUrl);
        if (cached != null) { entries = cached; restoreSelection(); }
        Perf.log("browse directoryCache=" + (cached != null) + " entries=" + entries.size());
        showFiles(); refresh();
    }
    private void restoreSelection() {
        if (restoreFolder == null) return;
        selectedIndex = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).url.equals(restoreFolder)) { selectedIndex = i; break; }
        restoreFolder = null;
    }
    private Button navigation(LinearLayout sidebar, String label, boolean selected, Runnable action) {
        Button item = new Button(this); item.setText(label); item.setTextSize(15); item.setAllCaps(false);
        item.setTextColor(selected ? INK : MUTED); item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14),0,dp(10),0); item.setBackgroundResource(R.drawable.navigation_item); item.setActivated(selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,dp(44)); params.bottomMargin = dp(7);
        sidebar.addView(item,params); item.setOnClickListener(v -> action.run()); return item;
    }
    private void showFiles() {
        long start = SystemClock.elapsedRealtime();
        int requestedSelection = selectedIndex;
        screen = "browse"; generation++; thumbnails.cancel(); clearMedia();
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setPadding(dp(24),dp(22),dp(24),dp(22)); shell.setBackgroundResource(R.drawable.browser_background);
        setContentView(shell);
        LinearLayout sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(8),0,dp(18),0); shell.addView(sidebar,new LinearLayout.LayoutParams(dp(182),-1));
        sidebar.addView(text("NetTV",25,INK)); sidebar.addView(text("你的私人媒体库",11,MUTED));
        TextView section = text("网络位置",11,MUTED); section.setPadding(dp(12),dp(23),0,dp(12)); sidebar.addView(section);
        navigation(sidebar,"▦   共享文件",true,this::showFiles);
        navigation(sidebar,"↑   上一级",false,this::up);
        navigation(sidebar,"↻   刷新",false,this::refresh);
        sidebar.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        navigation(sidebar,"⚙   服务器设置",false,this::settings);
        navigation(sidebar,"⌑   锁定媒体库",false,()->lockScreen(false));
        sidebar.addView(text("SMB  ·  只读访问",11,MUTED));
        View divider = new View(this); divider.setBackgroundColor(0x25FFFFFF); shell.addView(divider,new LinearLayout.LayoutParams(dp(1),-1));
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24),0,0,0);
        shell.addView(page,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout header = row(page);
        header.addView(text("共享文件",25,INK),new LinearLayout.LayoutParams(0,-2,1));
        TextView connection = text("●  SMB",12,ACCENT); header.addView(connection);
        TextView path = text(currentUrl,12,MUTED); path.setSingleLine(true); path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setPadding(0,dp(5),0,dp(12)); page.addView(path);
        TextView status = text(entries.size() + " 个项目   ·   方向键选择 / 确认打开 / 返回上一级",11,MUTED);
        GridView grid = new GridView(this) {
            private boolean animateScroll;
            @Override public boolean onKeyDown(int key, KeyEvent event) {
                animateScroll = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
                return super.onKeyDown(key,event);
            }
            @Override protected void layoutChildren() {
                int first = getFirstVisiblePosition();
                View anchor = getChildAt(0);
                float top = anchor == null ? 0 : anchor.getY();
                int row = anchor == null ? 0 : anchor.getHeight()+getVerticalSpacing();
                super.layoutChildren();
                if (animateScroll && row > 0 && getChildCount() > 0) {
                    float distance = top-getChildAt(0).getTop()
                            + (getFirstVisiblePosition()-first)/Math.max(1,getNumColumns())*row;
                    if (distance != 0) {
                        for (int i=0;i<getChildCount();i++) {
                            View card = getChildAt(i); card.animate().cancel(); card.setTranslationY(distance);
                            card.animate().translationY(0).setDuration(180).start();
                        }
                        postDelayed(()->loadVisibleThumbnails(this),190);
                    }
                }
                animateScroll = false;
            }
        };
        grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(dp(185));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setHorizontalSpacing(dp(12)); grid.setVerticalSpacing(dp(12));
        grid.setSelector(android.R.color.transparent); grid.setClipToPadding(false); grid.setPadding(dp(3),dp(3),dp(3),dp(6));
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return entries.size(); }
            public Object getItem(int position) { return entries.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                SmbBrowser.Entry item = entries.get(position);
                LinearLayout card;
                if (convertView == null) {
                    card = new LinearLayout(MainActivity.this); card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(4),dp(4),dp(4),dp(8)); card.setBackgroundResource(R.drawable.focusable);
                    card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    FrameLayout cover = new FrameLayout(MainActivity.this);
                    cover.setBackgroundColor(0xFF203449);
                    ImageView preview = new ImageView(MainActivity.this); preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cover.addView(preview, new FrameLayout.LayoutParams(-1,-1));
                    TextView badge = text("",12,INK); badge.setPadding(dp(8),dp(3),dp(8),dp(3)); badge.setBackgroundColor(0xD90C1420);
                    FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);
                    badgeParams.setMargins(dp(6),dp(6),dp(6),dp(6)); cover.addView(badge,badgeParams);
                    card.addView(cover,new LinearLayout.LayoutParams(-1,dp(112)));
                    TextView name = text("",16,INK); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    name.setPadding(dp(8),dp(8),dp(8),dp(2)); card.addView(name);
                    TextView detail = text("",12,MUTED); detail.setPadding(dp(8),0,dp(8),0); card.addView(detail);
                } else card = (LinearLayout) convertView;
                FrameLayout cover = (FrameLayout)card.getChildAt(0);
                cover.getLayoutParams().height = Math.max(dp(100), Math.round((((GridView)parent).getColumnWidth()-dp(8))*9f/16));
                ImageView preview = (ImageView)cover.getChildAt(0);
                TextView badge = (TextView)cover.getChildAt(1), name = (TextView)card.getChildAt(1), detail = (TextView)card.getChildAt(2);
                String kind = item.directory ? "文件夹" : item.image() ? "图片" : item.video() ? "视频" : "文件";
                if (!item.url.equals(preview.getTag())) {
                    preview.setTag(item.url); preview.setImageResource(item.directory ? R.drawable.folder : item.image() ? R.drawable.photo : R.drawable.movie);
                }
                badge.setText(item.video() ? "▶  视频" : kind); name.setText(item.name);
                detail.setText(item.directory ? "打开文件夹" : size(item.size)); card.setContentDescription(item.name + "，" + kind);
                return card;
            }
        });
        page.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        page.addView(status);
        grid.setOnItemClickListener((parent,view,position,id) -> { selectedIndex = position; open(entries.get(position)); });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selectedIndex = position; }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) { }
            public void onScroll(AbsListView view, int first, int visible, int total) { loadVisibleThumbnails(grid); }
        });
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> loadVisibleThumbnails(grid));
        grid.post(() -> loadVisibleThumbnails(grid));
        int selection = Math.min(requestedSelection,Math.max(0, entries.size()-1));
        if (!entries.isEmpty()) grid.requestFocus();
        grid.setSelection(selection);
        grid.post(() -> {
            if (grid.isAttachedToWindow()) {
                if (!entries.isEmpty()) { grid.requestFocusFromTouch(); grid.setSelection(selection); }
                Perf.log("browse_layout entries=" + entries.size() + " layoutMs=" + (SystemClock.elapsedRealtime()-start));
            }
        });
        if (entries.isEmpty()) status.setText("目录为空，或尚未加载。可按“刷新”重新加载。");
    }
    private void loadVisibleThumbnails(GridView grid) {
        if (!screen.equals("browse") || !grid.isAttachedToWindow()) return;
        Map<ImageView, SmbBrowser.Entry> visible = new LinkedHashMap<>();
        android.graphics.Rect bounds = new android.graphics.Rect();
        for (int i = 0; i < grid.getChildCount(); i++) {
            int position = grid.getFirstVisiblePosition() + i;
            View card = grid.getChildAt(i);
            if (position >= entries.size() || !card.getGlobalVisibleRect(bounds)) continue;
            SmbBrowser.Entry entry = entries.get(position);
            if (entry.directory || (!entry.image() && !entry.video())) continue;
            ImageView preview = (ImageView)((FrameLayout)((LinearLayout)card).getChildAt(0)).getChildAt(0);
            visible.put(preview, entry);
        }
        thumbnails.visible(server, visible);
    }
    private void refresh() {
        if (!screen.equals("browse")) return;
        int token = generation; SmbBrowser active = server; String url = currentUrl;
        TextView status = text("正在读取目录…",14,ACCENT); page.addView(status);
        long requested = SystemClock.elapsedRealtime();
        worker.execute(() -> {
            long start = SystemClock.elapsedRealtime();
            try {
                List<SmbBrowser.Entry> found = active.list(url);
                long listed = SystemClock.elapsedRealtime();
                Perf.log("directory_list entries=" + found.size() + " queueMs=" + (start-requested) + " listMs=" + (listed-start));
                runOnUiThread(() -> {
                    if (token != generation) return;
                    directories.put(url, found);
                    boolean unchanged = entries.size() == found.size();
                    for (int i = 0; unchanged && i < found.size(); i++) {
                        SmbBrowser.Entry before = entries.get(i), after = found.get(i);
                        unchanged = before.url.equals(after.url) && before.name.equals(after.name) && before.directory == after.directory
                                && before.size == after.size && before.modified == after.modified;
                    }
                    Perf.log("directory_ready unchanged=" + unchanged + " totalMs=" + (SystemClock.elapsedRealtime()-requested));
                    if (unchanged) { page.removeView(status); return; }
                    entries = found; restoreSelection();
                    showFiles();
                });
            } catch (Exception e) {
                Perf.log("directory_list failed=" + e.getClass().getSimpleName() + " totalMs=" + (SystemClock.elapsedRealtime()-requested));
                runOnUiThread(() -> { if (token == generation) { status.setText("读取失败：检查网络和共享权限，按刷新重试。"); } });
            }
        });
    }
    private void open(SmbBrowser.Entry item) {
        if (item.directory) { parents.push(currentUrl); currentUrl = item.url; restoreFolder = null; selectedIndex = 0; entries = new ArrayList<>(); browse(); }
        else if (item.image()) {
            pictures = new ArrayList<>(); for (SmbBrowser.Entry e : entries) if (e.image()) pictures.add(e);
            pictureIndex = pictures.indexOf(item); showPicture();
        } else if (item.video()) play(item);
        else new android.app.AlertDialog.Builder(this).setTitle(item.name).setMessage("此文件未识别为媒体，是否尝试用 VLC 打开？")
                .setPositiveButton("尝试播放",(dialog,which)->play(item)).setNegativeButton("取消",null).show();
    }
    private void play(SmbBrowser.Entry item) {
        generation++; thumbnails.cancel(); clearMedia(); screen = "video";
        video = new VideoPlayer(this,server,item,getPreferences(0).getBoolean("software",false),this::showFiles); setContentView(video);
    }
    private void showPicture() {
        pictureRequested = SystemClock.elapsedRealtime();
        SmbBrowser.Entry item = pictures.get(pictureIndex);
        String id = ThumbnailCache.id(server,item);
        ImageLoad promoted = prefetchLoad != null && prefetchLoad.id.equals(id) ? prefetchLoad : null;
        if (pictureLoad != null) pictureLoad.cancel();
        if (prefetchLoad != null && prefetchLoad != promoted) prefetchLoad.cancel();
        pictureLoad = promoted; prefetchLoad = null;
        generation++; thumbnails.cancel();
        if (pictureViewer == null) {
            clearMedia(); screen = "image";
            pictureViewer = new FrameLayout(this); pictureViewer.setBackgroundColor(Color.BLACK);
            pictureView = new ImageView(this); pictureView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pictureViewer.addView(pictureView,new FrameLayout.LayoutParams(-1,-1));
            pictureStatus = text("",14,MUTED); pictureStatus.setGravity(Gravity.CENTER);
            pictureViewer.addView(pictureStatus,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
            pictureControls = new LinearLayout(this); pictureControls.setGravity(Gravity.CENTER); pictureControls.setBackgroundColor(0xCC0C1420);
            MediaControls.add(pictureControls,R.drawable.control_previous,"上一张",()->changePicture(-1));
            MediaControls.add(pictureControls,R.drawable.control_back,"返回",this::showFiles);
            MediaControls.add(pictureControls,R.drawable.control_next,"下一张",()->changePicture(1));
            pictureViewer.addView(pictureControls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            pictureViewer.setOnClickListener(v -> showPictureControls()); setContentView(pictureViewer);
        }
        screen = "image"; bitmap = null;
        pictureStatus.setText("正在加载图片…"); pictureStatus.setVisibility(View.VISIBLE);
        pictureControls.setVisibility(View.GONE); pictureHandler.removeCallbacks(hidePictureControls);
        pictureViewer.requestFocusFromTouch();
        Bitmap cached = fullImages.get(id);
        if (cached != null) { presentPicture(cached,"memory"); prefetchPicture(); return; }
        if (promoted != null) { Perf.log("image_prefetch promoted"); return; }
        pictureLoad = new ImageLoad(item); imageWorker.execute(pictureLoad.task);
    }
    private void presentPicture(Bitmap loaded, String source) {
        pictureView.animate().cancel(); pictureView.setTranslationX(0); pictureView.setAlpha(1f);
        if (outgoingPicture != null) {
            outgoingPicture.animate().cancel(); pictureViewer.removeView(outgoingPicture); outgoingPicture = null;
        }
        if (pictureView.getDrawable() != null && pictureDirection != 0) {
            ImageView previous = new ImageView(this); previous.setScaleType(ImageView.ScaleType.FIT_CENTER);
            previous.setImageDrawable(pictureView.getDrawable());
            pictureViewer.addView(previous,0,new FrameLayout.LayoutParams(-1,-1)); outgoingPicture = previous;
            previous.animate().translationX(-pictureDirection*dp(70)).alpha(0).setDuration(200)
                    .withEndAction(()->{
                        previous.setImageDrawable(null);
                        if (previous.getParent() instanceof ViewGroup) ((ViewGroup)previous.getParent()).removeView(previous);
                        if (outgoingPicture == previous) outgoingPicture = null;
                    }).start();
            pictureView.setTranslationX(pictureDirection*dp(70)); pictureView.setAlpha(0f);
            pictureView.animate().translationX(0).alpha(1).setDuration(200).start();
        }
        bitmap = loaded; pictureView.setImageBitmap(loaded);
        pictureView.setContentDescription(pictures.get(pictureIndex).name); pictureStatus.setVisibility(View.GONE);
        Perf.log("image_shown source=" + source + " totalMs=" + (SystemClock.elapsedRealtime()-pictureRequested));
    }
    private void prefetchPicture() {
        if (pictures.size() < 2) return;
        SmbBrowser.Entry next = pictures.get((pictureIndex+1)%pictures.size());
        if (fullImages.get(ThumbnailCache.id(server,next)) != null) return;
        prefetchLoad = new ImageLoad(next); imageWorker.execute(prefetchLoad.task);
    }
    private void showPictureControls() {
        pictureControls.setVisibility(View.VISIBLE); pictureControls.getChildAt(0).requestFocus();
        pictureHandler.removeCallbacks(hidePictureControls); pictureHandler.postDelayed(hidePictureControls,5000);
    }
    private void changePicture(int delta) { pictureDirection = delta; pictureIndex = (pictureIndex + delta + pictures.size()) % pictures.size(); showPicture(); }
    private void up() {
        if (!parents.isEmpty()) { restoreFolder = currentUrl; currentUrl = parents.pop(); selectedIndex = 0; entries = new ArrayList<>(); browse(); }
        else toast("已到共享根目录");
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (pictureRevealKey == event.getKeyCode() && event.getAction() == KeyEvent.ACTION_UP) { pictureRevealKey = 0; return true; }
        if (screen.equals("image") && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (pictureControls.getVisibility() != View.VISIBLE) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) { changePicture(-1); return true; }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) { changePicture(1); return true; }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) { pictureRevealKey = event.getKeyCode(); showPictureControls(); return true; }
            } else { pictureHandler.removeCallbacks(hidePictureControls); pictureHandler.postDelayed(hidePictureControls,5000); }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        switch(screen) {
            case "video": case "image": showFiles(); break;
            case "settings": case "pin": if (server != null) browse(); else finish(); break;
            case "browse": if (!parents.isEmpty()) up(); else finish(); break;
            default: finish();
        }
    }
    @Override protected void onPause() {
        super.onPause(); generation++; thumbnails.clear(); fullImages.evictAll(); clearMedia();
        if (!isFinishing()) lockScreen(false);
    }
    @Override protected void onDestroy() { generation++; thumbnails.close(); clearMedia(); closeServer(server); worker.shutdown(); authentication.shutdown(); imageWorker.shutdownNow(); super.onDestroy(); }
    private void clearMedia() {
        pictureHandler.removeCallbacksAndMessages(null); pictureControls = null;
        if (pictureLoad != null) { pictureLoad.cancel(); pictureLoad = null; }
        if (prefetchLoad != null) { prefetchLoad.cancel(); prefetchLoad = null; }
        imageWorker.getQueue().clear();
        if (pictureView != null) pictureView.animate().cancel();
        if (outgoingPicture != null) outgoingPicture.animate().cancel();
        pictureViewer = null; pictureView = null; outgoingPicture = null; pictureStatus = null; pictureDirection = 0;
        if (video != null) { long start = SystemClock.elapsedRealtime(); video.release(); video = null; Perf.log("video_release totalMs=" + (SystemClock.elapsedRealtime()-start)); }
        // The old ImageView may still be attached during a transition; let GC reclaim its bitmap.
        bitmap = null;
    }
    private void closeServer(SmbBrowser value) {
        if (value != null && !worker.isShutdown()) worker.execute(() -> { try { value.close(); } catch (Exception ignored) { } });
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }
    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);
        if (bytes < 1073741824) return String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0);
        return String.format(Locale.ROOT,"%.1f GB",bytes/1073741824.0);
    }
}
