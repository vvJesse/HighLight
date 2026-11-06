package com.feedreform.highlight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 收藏页：多收藏夹、置顶、四种排序、标题搜索、打开计数。
 *
 * 数据只在 {@link SavedStore}（filesDir/saved.json + covers/），不请求任何服务器。
 * 收藏页也接剪贴板链接：从外面复制小红书/B站 链接再切回来，同样弹预览层。
 */
public class CollectionActivity extends Activity {

    private ListView list;
    private LinearLayout folderBar;
    private LinearLayout sortBar;
    private TextView newFolder;
    private TextView empty;
    private EditText search;
    private WindowGardenView garden;
    private Adapter adapter;

    private final List<SavedStore.Item> items = new ArrayList<>();
    private final List<SavedStore.Item> shown = new ArrayList<>();
    private SavedStore store;

    /** null = 全部。 */
    private String folder = null;
    private String sort = "recent";
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collection);
        PaperUi.insets(this);
        store = SavedStore.get(this);

        list = findViewById(R.id.list);
        View header = getLayoutInflater().inflate(R.layout.header_collection, list, false);
        list.addHeaderView(header, null, false);
        folderBar = header.findViewById(R.id.folderBar);
        sortBar = header.findViewById(R.id.sortBar);
        newFolder = header.findViewById(R.id.newFolder);
        empty = header.findViewById(R.id.empty);
        search = header.findViewById(R.id.search);
        garden = header.findViewById(R.id.collectionGarden);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            int index = pos - list.getHeaderViewsCount();
            if (index >= 0 && index < shown.size()) openItem(shown.get(index));
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            int index = pos - list.getHeaderViewsCount();
            if (index < 0 || index >= shown.size()) return false;
            itemMenu(shown.get(index));
            return true;
        });

        newFolder.setOnClickListener(v -> createFolder());
        // 键盘弹出时把插画收起来，否则小屏上第一条收藏会被挤出屏幕（pitfalls #28）。
        search.setOnFocusChangeListener((v, hasFocus) ->
                garden.setVisibility(hasFocus ? View.GONE : View.VISIBLE));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim();
                render();
            }

            public void afterTextChanged(Editable s) {
            }
        });

        BottomNav.bind(this, "collection");
        renderSortBar();
        renderFolders();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopService(new Intent(this, ReturnBubbleService.class));
        reload();
        LinkCapture.onResume(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LinkCapture.onPause(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        LinkCapture.onWindowFocusChanged(this, hasFocus);
    }

    // ---------- 数据 ----------

    private void reload() {
        if (store == null) return;
        items.clear();
        items.addAll(store.items());
        renderFolders();
        render();
    }

    private void renderFolders() {
        folderBar.removeAllViews();
        addChip(null, "全部 " + store.items().size());
        for (SavedStore.Folder f : store.folders()) {
            addChip(f.id, f.name + " " + store.countIn(f.id));
        }
    }

    private void addChip(String id, String label) {
        boolean active = (folder == null && id == null) || (folder != null && folder.equals(id));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        PaperUi.chip(tv, active);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            folder = id;
            renderFolders();
            render();
        });
        folderBar.addView(tv);
    }

    private void renderSortBar() {
        sortBar.removeAllViews();
        addSort("recent", "最近收藏");
        addSort("opens_desc", "看得多");
        addSort("opens_asc", "没看过");
        addSort("last_open", "最近打开");
    }

    private void addSort(String key, String label) {
        boolean active = key.equals(sort);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setTextColor(active ? PaperUi.BRAND : PaperUi.INK);
        if (active) tv.setBackgroundResource(R.drawable.bg_sage);
        tv.setOnClickListener(v -> {
            sort = key;
            renderSortBar();
            render();
        });
        sortBar.addView(tv);
    }

    private void render() {
        shown.clear();
        for (SavedStore.Item it : items) {
            if (folder != null && !it.folders.contains(folder)) continue;
            if (!query.isEmpty()
                    && !it.title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                continue;
            }
            shown.add(it);
        }
        Collections.sort(shown, comparator());
        String caption = "全部收藏";
        if (folder != null) {
            SavedStore.Folder f = store.folder(folder);
            if (f != null) caption = f.name;
        }
        if (caption.length() > 7) caption = caption.substring(0, 6) + "…";
        garden.setCaption(caption, shown.size() + " 篇可以再读");
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private Comparator<SavedStore.Item> comparator() {
        Comparator<SavedStore.Item> byPin = (a, b) -> {
            boolean pa = folder != null ? a.pinned.contains(folder) : !a.pinned.isEmpty();
            boolean pb = folder != null ? b.pinned.contains(folder) : !b.pinned.isEmpty();
            return Boolean.compare(pb, pa);
        };
        Comparator<SavedStore.Item> main;
        switch (sort) {
            case "opens_desc":
                main = (a, b) -> Integer.compare(b.opens, a.opens);
                break;
            case "opens_asc":
                main = (a, b) -> Integer.compare(a.opens, b.opens);
                break;
            case "last_open":
                main = (a, b) -> Long.compare(b.lastOpenAt, a.lastOpenAt);
                break;
            default:
                main = (a, b) -> Long.compare(b.savedAt, a.savedAt);
        }
        return byPin.thenComparing(main);
    }

    // ---------- 操作 ----------

    private void createFolder() {
        EditText input = new EditText(this);
        input.setHint("夹的名字，比如：编程");
        input.setSingleLine(true);
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("新建收藏夹")
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    store.newFolder(name);
                    renderFolders();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void itemMenu(SavedStore.Item it) {
        boolean pinned = folder != null ? it.pinned.contains(folder) : !it.pinned.isEmpty();
        List<String> actions = new ArrayList<>();
        if (folder != null) actions.add(pinned ? "取消置顶" : "置顶");
        actions.add("移出收藏");
        new AlertDialog.Builder(this)
                .setTitle(it.title)
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    String action = actions.get(which);
                    if ("移出收藏".equals(action)) {
                        removeItem(it);
                    } else if (folder != null) {
                        store.setPinned(it.source, it.id, folder, !pinned);
                        reload();
                    }
                })
                .show();
    }

    private void removeItem(SavedStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("移出收藏？")
                .setMessage(it.title)
                .setPositiveButton("移出", (d, w) -> {
                    store.remove(it.source, it.id);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openItem(SavedStore.Item it) {
        String platform = SavedStore.SOURCE_XHS.equals(it.source) ? "xhs" : "bili";
        SessionLaunch.start(this, platform);
        boolean opened = false;
        if (SavedStore.SOURCE_BILI.equals(it.source)) {
            opened = tryOpen("bilibili://video/" + it.id)
                    || tryOpen("https://www.bilibili.com/video/" + it.id);
        } else if (!it.url.isEmpty()) {
            opened = tryOpen(it.url);
        }
        if (!opened) {
            SessionLaunch.abort(this);
            toast("打不开这条");
            return;
        }
        store.touchOpen(it.source, it.id);
        reload();
    }

    private boolean tryOpen(String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 适配器 ----------

    private class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_saved, parent, false);
            if (position < 0 || position >= shown.size()) return v;
            SavedStore.Item it = shown.get(position);

            TextView title = v.findViewById(R.id.title);
            TextView meta = v.findViewById(R.id.meta);
            ImageView cover = v.findViewById(R.id.cover);

            title.setText(it.title.isEmpty() ? "未解析链接" : it.title);
            meta.setText(metaOf(it));
            v.findViewById(R.id.itemMenu).setOnClickListener(x -> itemMenu(it));

            // 本地有封面文件优先读文件；没有就先用网络图，同时后台落盘。
            java.io.File file = store.coverFile(it.source, it.id);
            Thumbs.coverWithFile(cover, file, it.cover, dp(64), dp(84));
            CoverCache.ensure(CollectionActivity.this, it, () -> {
                if (!isFinishing()) adapter.notifyDataSetChanged();
            });

            v.setOnClickListener(x -> openItem(it));
            return v;
        }

        private String metaOf(SavedStore.Item it) {
            StringBuilder sb = new StringBuilder();
            if (folder != null && it.pinned.contains(folder)) sb.append("置顶 · ");
            sb.append(SavedStore.SOURCE_BILI.equals(it.source) ? "B站" : "小红书");
            if (it.opens > 0) {
                sb.append(" · 打开 ").append(it.opens).append(" 次");
            } else {
                sb.append(" · 还没打开");
            }
            long stamp = it.lastOpenAt > 0 ? it.lastOpenAt : it.savedAt;
            if (stamp > 0) {
                sb.append(" · ").append(new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
                        .format(new Date(stamp)));
            }
            return sb.toString();
        }
    }
}
