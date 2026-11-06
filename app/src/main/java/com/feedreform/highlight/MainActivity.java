package com.feedreform.highlight;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：小红书入口卡 + B站推荐 / 关注。
 *
 * 所有数据都由手机直连 B站 拿，落在 {@link VideoStore}；判断由 {@link Judge} 逐条做。
 * 推荐与关注都要求本机 B站 凭据：没有凭据时推荐区只显示引导态，不发匿名请求、不跑判断。
 * 关注流 Cookie 失效时提示更新但不清缓存。
 * 发亮先加一圈描边；判为「不想看」的条目不进列表，也不留占位。
 */
public class MainActivity extends Activity {

    private static final String TAG = "HLBASE";
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    /** 关注流单次补取上限：刷新时最多拉 5 页，剩下的保留续取位置。 */
    private static final int FOLLOW_MAX_PAGES = 5;
    /** B站 关注动态接口每页约 20 条；「加载更多」的页码按缓存条数推算（F4-02）。 */
    private static final int FOLLOW_PAGE_SIZE = 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final List<VideoStore.Video> videos = new ArrayList<>();

    private PullFeedListView feedList;
    private CardAdapter adapter;
    private VideoStore store;

    private TextView status;
    private TextView appTitle;
    private TextView searchBar;
    private TextView tabXhs;
    private TextView tabBili;
    private View biliMode;
    private TextView modeRecommend;
    private TextView modeFollow;
    private TextView pullState;
    private View pullBar;
    private View refreshSpin;
    private View settingsGear;
    private View biliEntry;
    private View biliEntryDot;
    private TextView biliEntryState;
    private TextView followHint;
    private View statusRow;
    private TextView pullHint;
    private View footerMore;
    private TextView moreText;
    private ProgressBar moreSpin;
    private TextView feedEmpty;
    /** 推荐区无凭据 / 凭据失效时的引导卡。 */
    private View cookieGuide;
    private TextView cookieGuideTitle;
    private TextView cookieGuideBody;
    private TextView cookieGuideButton;
    /** 推荐区当前是否停在「缺凭据」或「凭据失效」的引导态（不展示卡片）。 */
    private boolean recommendNoCookie = false;
    private boolean recommendExpired = false;

    /** 默认落在 B站：小红书页暂时只有入口卡。 */
    private String source = "bili";
    private String mode = "recommend";
    private volatile boolean busy = false;
    private boolean refreshFailed = false;
    private volatile boolean followLoading = false;
    /** 当前列表结果是在哪个 ruleVersion 下判的；从设置改 focus/avoid 回来时用来触发重判。 */
    private int judgedRuleVersion = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        PaperUi.insets(this);
        store = VideoStore.get(this);
        maybeImportDebugCookie();
        judgedRuleVersion = Prefs.ruleVersion(this);

        feedList = findViewById(R.id.list);
        View header = getLayoutInflater().inflate(R.layout.header_home, feedList, false);
        feedList.addHeaderView(header, null, false);
        footerMore = getLayoutInflater().inflate(R.layout.footer_follow, feedList, false);
        feedList.addFooterView(footerMore, null, false);
        moreText = footerMore.findViewById(R.id.moreText);
        moreSpin = footerMore.findViewById(R.id.moreSpin);
        footerMore.setOnClickListener(v -> loadMoreFollow());

        feedEmpty = new TextView(this);
        feedEmpty.setText("这里暂时很安静。\n下拉更新，打开今天的信息流。");
        feedEmpty.setTextColor(PaperUi.INK);
        feedEmpty.setGravity(android.view.Gravity.CENTER);
        feedEmpty.setPadding(dp(24), dp(36), dp(24), dp(36));
        feedList.addFooterView(feedEmpty, null, false);

        cookieGuide = getLayoutInflater().inflate(R.layout.view_bili_cookie_guide, feedList, false);
        cookieGuideTitle = cookieGuide.findViewById(R.id.cookieGuideTitle);
        cookieGuideBody = cookieGuide.findViewById(R.id.cookieGuideBody);
        cookieGuideButton = cookieGuide.findViewById(R.id.cookieGuideButton);
        cookieGuideButton.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            overridePendingTransition(0, 0);
        });
        feedList.addFooterView(cookieGuide, null, false);

        status = findViewById(R.id.status);
        appTitle = findViewById(R.id.app_title);
        pullState = findViewById(R.id.pullState);
        pullBar = findViewById(R.id.pullBar);
        refreshSpin = findViewById(R.id.refreshSpin);
        searchBar = findViewById(R.id.searchBar);
        biliEntry = findViewById(R.id.biliEntry);
        biliEntryDot = findViewById(R.id.biliEntryDot);
        biliEntryState = findViewById(R.id.biliEntryState);
        if (biliEntry != null) biliEntry.setOnClickListener(v -> openEntry("bili"));
        // B站 入口卡右上角的「!」：clickable 子 View 消费事件，不会触发整张卡的打开。
        View entryInfo = findViewById(R.id.entryInfo);
        if (entryInfo != null) entryInfo.setOnClickListener(v -> showWhatIsHighlight());
        settingsGear = findViewById(R.id.settingsGear);
        if (settingsGear != null) {
            settingsGear.setOnClickListener(v -> {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                overridePendingTransition(0, 0);
            });
        }
        followHint = findViewById(R.id.followHint);
        statusRow = findViewById(R.id.statusRow);
        pullHint = findViewById(R.id.pullHint);
        tabXhs = findViewById(R.id.tab_xhs);
        tabBili = findViewById(R.id.tab_bili);
        biliMode = findViewById(R.id.biliMode);
        modeRecommend = findViewById(R.id.modeRecommend);
        modeFollow = findViewById(R.id.modeFollow);

        pullState.setOnClickListener(v -> {
            if (refreshFailed) load(true);
        });
        feedList.setPullListener(new PullFeedListView.Listener() {
            public void onPull(float progress) {
                float h = getResources().getDisplayMetrics().density * 40f;
                pullState.setTranslationY(0f);
                pullState.setAlpha(1f);
                pullBar.setVisibility(View.VISIBLE);
                refreshSpin.setVisibility(View.VISIBLE);
                pullState.setTranslationY(-h + h * progress);
                pullState.setAlpha(0.25f + 0.75f * progress);
                pullState.setText(progress >= 1f ? "↓ 松手更新" : "↓ 继续下拉，打开今日的窗");
            }

            public void onRelease(boolean ready) {
                if (ready) {
                    pullState.animate().translationY(0).alpha(1f).setDuration(120).start();
                    load(true);
                } else if (!refreshFailed) {
                    pullState.animate().translationY(-getResources().getDisplayMetrics().density * 40f)
                            .alpha(0f).setDuration(160)
                            .withEndAction(() -> {
                                if (!refreshFailed) pullBar.setVisibility(View.GONE);
                            }).start();
                }
            }
        });

        searchBar.setOnClickListener(v -> openPlatformSearch());
        tabXhs.setOnClickListener(v -> switchSource("xhs"));
        tabBili.setOnClickListener(v -> switchSource("bili"));
        modeRecommend.setOnClickListener(v -> switchMode("recommend"));
        modeFollow.setOnClickListener(v -> switchMode("follow"));

        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        adapter = new CardAdapter();
        feedList.setAdapter(adapter);
        BottomNav.bind(this, "home");
        updateTabs();
        recommendNoCookie = SecureStore.getCookie(this) == null;
        recommendExpired = false;
        refreshFromStore();
        load(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopService(new Intent(this, ReturnBubbleService.class));
        SessionNotice.cancel(this);
        // 从设置页回来可能刚保存/清除了凭据：停在引导态且现在有凭据，就重新拉一次。
        if (store != null) {
            boolean hasCookie = SecureStore.getCookie(this) != null;
            if ("bili".equals(source) && "recommend".equals(mode)
                    && (recommendNoCookie || recommendExpired) && hasCookie) {
                recommendNoCookie = false;
                recommendExpired = false;
                refreshFromStore();
                load(true);
            } else {
                if (!"xhs".equals(source)
                        && Prefs.ruleVersion(this) != judgedRuleVersion) {
                    // 在设置里改了「特别关注/不想看到」：同一列表不会重新拉取，必须就地重判。
                    judgeCurrent();
                }
                updateStatus();
            }
        }
        updateEntryCards();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    // ---------- 数据 ----------

    private void refreshFromStore() {
        videos.clear();
        if ("xhs".equals(source)) {
            videos.addAll(store.follow());
        } else if ("follow".equals(mode)) {
            videos.addAll(store.follow());
        } else if (SecureStore.getCookie(this) == null) {
            // 无凭据时连缓存里的推荐也不展示：引导态里不放任何卡片。
            videos.clear();
        } else {
            videos.addAll(store.rcmd());
        }
        render();
    }

    private void load(boolean refresh) {
        if ("xhs".equals(source)) {
            render();
            return;
        }
        if ("follow".equals(mode)) {
            if (refresh) loadFollow(true);
            else if (videos.isEmpty()) loadFollow(true);
            else updateFooter();
            return;
        }
        if (SecureStore.getCookie(this) == null) {
            // 没有凭据就不发请求：匿名推荐不能当用户信息流展示。
            busy = false;
            refreshFailed = false;
            recommendNoCookie = true;
            recommendExpired = false;
            feedList.setRefreshing(false);
            pullBar.setVisibility(View.GONE);
            refreshSpin.setVisibility(View.GONE);
            render();
            return;
        }
        if (busy) return;
        busy = true;
        refreshFailed = false;
        feedList.setRefreshing(true);
        pullState.setTextColor(PaperUi.INK);
        pullBar.setVisibility(View.VISIBLE);
        refreshSpin.setVisibility(View.VISIBLE);
        pullState.setText(refresh ? "正在刷新推荐…" : "正在打开今日的窗…");
        final int freshIdx = refresh ? Prefs.nextRcmdFreshIdx(this) : Prefs.rcmdFreshIdx(this);
        net.execute(() -> {
            try {
                List<VideoStore.Video> batch = BiliApi.rcmd(this, freshIdx);
                List<VideoStore.Video> merged = store.replaceRcmd(batch);
                Prefs.get(this).edit().putLong(Prefs.LAST_SYNC, System.currentTimeMillis()).apply();
                main.post(() -> {
                    busy = false;
                    feedList.setRefreshing(false);
                    if (!"bili".equals(source) || !"recommend".equals(mode)) return;
                    videos.clear();
                    videos.addAll(merged);
                    render();
                    pullBar.setVisibility(View.GONE);
                    refreshSpin.setVisibility(View.GONE);
                    judgeCurrent();
                });
            } catch (Exception e) {
                final boolean needCookie = e instanceof BiliApi.BiliError
                        && ((BiliApi.BiliError) e).needCookie;
                final String err = e.getMessage() == null ? "网络连接异常" : e.getMessage();
                main.post(() -> {
                    busy = false;
                    feedList.setRefreshing(false);
                    refreshSpin.setVisibility(View.GONE);
                    if (needCookie) {
                        // 凭据失效：保留已有缓存，但不展示任何卡片，也不退回匿名推荐。
                        recommendExpired = true;
                        recommendNoCookie = false;
                        pullBar.setVisibility(View.GONE);
                        render();
                        status.setText("B站 凭据已失效，需要更新");
                        return;
                    }
                    refreshFailed = true;
                    pullState.setText("更新失败 · 点击重试");
                    pullState.setTextColor(PaperUi.BRAND);
                    pullBar.setVisibility(View.VISIBLE);
                    status.setText("已保留当前内容 · " + err);
                });
            }
        });
    }

    /**
     * 关注流：
     * - refresh=true 从第一页重新同步（最多 5 页/次，剩下的保留续取位置）；
     * - refresh=false 从保存的 offset 继续取一页（「加载更多」）。
     * 分页失败不清空已有内容。
     */
    private void loadFollow(final boolean refresh) {
        if (followLoading) return;
        followLoading = true;
        refreshFailed = false;
        if (refresh) {
            feedList.setRefreshing(true);
            pullState.setTextColor(PaperUi.INK);
            pullBar.setVisibility(View.VISIBLE);
            refreshSpin.setVisibility(View.VISIBLE);
            pullState.setText("正在拉取关注动态…");
        }
        updateFooter();
        net.execute(() -> {
            String error = "";
            boolean needCookie = false;
            try {
                if (refresh) {
                    // F4-02：记下刷新前的缓存深度与续取游标。刷新只重取头部几页时，
                    // 续取位置不能倒回到这几页，否则「加载更多」会重复请求已取过的 page。
                    final int cachedBefore = store.count("follow");
                    final String prevOffset = store.followOffset();
                    final int prevPage = store.followPage();
                    final boolean prevMore = store.followHasMore();
                    BiliApi.FollowPage first = BiliApi.follow(this, 1, "");
                    store.syncFollowHead(first.items, first.offset, first.hasMore);
                    int fetched = first.items.size();
                    int pages = 1;
                    String offset = first.offset;
                    boolean more = first.hasMore;
                    while (pages < FOLLOW_MAX_PAGES && more && !offset.isEmpty()) {
                        BiliApi.FollowPage next = BiliApi.follow(this, pages + 1, offset);
                        store.appendFollow(next.items, next.offset, next.hasMore);
                        fetched += next.items.size();
                        pages++;
                        offset = next.offset;
                        more = next.hasMore;
                    }
                    if (cachedBefore > fetched) {
                        // 缓存本来就比这次刷新的页更深：续取页码按「缓存已有条数」推算，
                        // 游标沿用旧值，避免再请求一次已经取过的 page（F4-02）。
                        int keepPage = Math.max(prevPage,
                                (cachedBefore + FOLLOW_PAGE_SIZE - 1) / FOLLOW_PAGE_SIZE);
                        store.restoreFollowCursor(prevOffset, keepPage, prevMore);
                        LogI("follow-refresh cursor=keep cachedBefore=" + cachedBefore
                                + " fetched=" + fetched + " keepPage=" + keepPage);
                    } else {
                        LogI("follow-refresh cursor=rewind cachedBefore=" + cachedBefore
                                + " fetched=" + fetched + " page=" + store.followPage());
                    }
                    LogI("follow-sync pages=" + pages + " total=" + store.count("follow")
                            + " hasMore=" + store.followHasMore() + " offsetLen=" + offset.length());
                    logFollowUps();
                } else {
                    String offset = store.followOffset();
                    int page = store.followPage() + 1;
                    BiliApi.FollowPage next = BiliApi.follow(this, page, offset);
                    store.appendFollow(next.items, next.offset, next.hasMore);
                    LogI("follow-more page=" + page + " n=" + next.items.size()
                            + " hasMore=" + next.hasMore + " total=" + store.count("follow"));
                }
                Prefs.get(this).edit().putLong(Prefs.LAST_SYNC, System.currentTimeMillis())
                        .putLong(Prefs.COOKIE_VERIFIED, System.currentTimeMillis()).apply();
            } catch (BiliApi.BiliError e) {
                needCookie = e.needCookie;
                error = e.getMessage();
            } catch (Exception e) {
                error = e.getMessage() == null ? "网络连接异常" : e.getMessage();
            }
            final String fErr = error;
            final boolean fCookie = needCookie;
            main.post(() -> {
                followLoading = false;
                feedList.setRefreshing(false);
                if (!"bili".equals(source) || !"follow".equals(mode)) return;
                videos.clear();
                videos.addAll(store.follow());
                render();
                if (fErr.isEmpty()) {
                    pullBar.setVisibility(View.GONE);
                    refreshSpin.setVisibility(View.GONE);
                    judgeCurrent();
                } else {
                    refreshFailed = true;
                    refreshSpin.setVisibility(View.GONE);
                    pullBar.setVisibility(View.VISIBLE);
                    pullState.setTextColor(PaperUi.BRAND);
                    pullState.setText(fCookie ? "需要更新 Cookie · 去设置填写" : "更新失败 · 点击重试");
                    pullState.setOnClickListener(v -> {
                        if (fCookie) {
                            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                            overridePendingTransition(0, 0);
                        }
                        else load(true);
                    });
                    status.setText(videos.isEmpty()
                            ? (fCookie ? "需要更新 Cookie · 设置里填一个再刷新" : "加载失败 · " + fErr)
                            : "已保留 " + videos.size() + " 条 · " + fErr);
                }
            });
        });
    }

    /** 证据用：同一 UP 多条投稿是否都在；日志只打计数。 */
    private void logFollowUps() {
        java.util.Map<Long, Integer> perUp = new java.util.HashMap<>();
        int max = 0;
        for (VideoStore.Video v : store.follow()) {
            int n = perUp.getOrDefault(v.mid, 0) + 1;
            perUp.put(v.mid, n);
            max = Math.max(max, n);
        }
        int multi = 0;
        for (int n : perUp.values()) if (n > 1) multi++;
        LogI("follow-ups ups=" + perUp.size() + " multiUps=" + multi + " maxPerUp=" + max);
    }

    private void loadMoreFollow() {
        if (!"follow".equals(mode) || followLoading) return;
        if (!store.followHasMore()) {
            toast("没有更多了");
            return;
        }
        loadFollow(false);
    }

    // ---------- 判断 ----------

    private void judgeCurrent() {
        if ("recommend".equals(mode)
                && (SecureStore.getCookie(this) == null || recommendExpired)) return;
        final List<VideoStore.Video> snapshot = new ArrayList<>(videos);
        if (snapshot.isEmpty()) return;
        judgedRuleVersion = Prefs.ruleVersion(this);
        Judge.judgeAsync(this, snapshot, (judged, glow, dim, unknown, ms, error) -> {
            videos.clear();
            videos.addAll("follow".equals(mode) ? store.follow() : store.rcmd());
            if (judged > 0) {
                VideoStore.Video last = null;
                for (VideoStore.Video v : snapshot) {
                    if (v.result != Judge.UNKNOWN) last = v;
                }
                if (last != null) {
                    Prefs.get(this).edit()
                            .putString("lastJudgeTitle", last.title)
                            .putInt("lastJudgeResult", last.result)
                            .putLong("lastJudgeMs", ms)
                            .putLong("lastJudgeAt", System.currentTimeMillis())
                            .apply();
                }
            }
            render();
            if (!error.isEmpty()) {
                status.append(" · 判断失败，未判条目下次刷新重试");
            }
        });
    }

    // ---------- 渲染 ----------

    private void render() {
        boolean xhs = "xhs".equals(source);
        boolean recommend = !xhs && "recommend".equals(mode);
        if (recommend && (recommendNoCookie || recommendExpired)) {
            // 引导态：列表区一张卡都不展示，避免把路人推荐当成用户信息流。
            adapter.setData(new ArrayList<>(), source, mode);
            feedEmpty.setVisibility(View.GONE);
            feedList.setRefreshing(false);
            pullBar.setVisibility(View.GONE);
            refreshSpin.setVisibility(View.GONE);
            cookieGuide.setVisibility(View.VISIBLE);
            if (recommendNoCookie) {
                cookieGuideTitle.setText("先填入 B站 凭据，才会显示你自己的推荐");
                cookieGuideBody.setText("推荐与关注都要求本机 B站 凭据；匿名接口只用于验证网络连通性，不作为产品数据源。");
                cookieGuideButton.setText("去设置填凭据");
            } else {
                cookieGuideTitle.setText("B站 凭据已失效，需要更新");
                cookieGuideBody.setText("已有缓存保留，但不再展示匿名推荐；更新凭据后下拉刷新即可。");
                cookieGuideButton.setText("去设置更新凭据");
            }
            updateFooter();
            updateStatus();
            return;
        }
        cookieGuide.setVisibility(View.GONE);
        List<VideoStore.Video> visible = new ArrayList<>();
        for (VideoStore.Video v : videos) {
            // 「不想看」的直接不进列表：不占位、不留展开区。
            if (v.result == Judge.DIM) continue;
            visible.add(v);
        }
        adapter.setData(visible, source, mode);
        feedEmpty.setVisibility(!xhs && visible.isEmpty() ? View.VISIBLE : View.GONE);
        updateFooter();
        updateStatus();
    }

    private void updateFooter() {
        boolean follow = "bili".equals(source) && "follow".equals(mode);
        footerMore.setVisibility(follow ? View.VISIBLE : View.GONE);
        if (!follow) return;
        boolean more = store.followHasMore();
        moreSpin.setVisibility(followLoading ? View.VISIBLE : View.GONE);
        moreText.setText(followLoading ? "正在加载…" : (more ? "加载更早的更新" : "没有更多了"));
    }

    private void updateStatus() {
        if ("xhs".equals(source)) {
            status.setText("小红书推荐已关闭");
            return;
        }
        if ("recommend".equals(mode)) {
            if (recommendNoCookie) {
                status.setText("未填 B站 凭据 · 不显示推荐");
                return;
            }
            if (recommendExpired) {
                status.setText("B站 凭据已失效 · 需要更新");
                return;
            }
        }
        if ("follow".equals(mode)) {
            // 关注页的状态行按画板 03 不显示（只留一行「按视频判断」说明）。
            status.setText("");
            return;
        }
        // V-08：画板 01 是「已更新 · 12:40」，右侧另有「↓ 下拉更新」。
        long sync = Prefs.lastSyncAt(this);
        StringBuilder sb = new StringBuilder();
        if (sync > 0) {
            sb.append("已更新 · ")
                    .append(LocalDateTime.ofInstant(Instant.ofEpochMilli(sync), ZoneId.systemDefault()).format(HM));
        } else {
            sb.append("还没有更新");
        }
        if (SecureStore.getCookie(this) == null) sb.append(" · 未填 Cookie");
        status.setText(sb.toString());
    }

    private void switchSource(String next) {
        if (next.equals(source)) return;
        source = next;
        updateTabs();
        refreshFromStore();
        if (!"xhs".equals(next)) {
            load("follow".equals(mode) ? store.follow().isEmpty() : store.rcmd().isEmpty());
        }
    }

    private void switchMode(String next) {
        if (next.equals(mode)) return;
        mode = next;
        updateTabs();
        refreshFromStore();
        load("follow".equals(mode) ? store.follow().isEmpty() : store.rcmd().isEmpty());
    }

    private void updateTabs() {
        boolean xhs = "xhs".equals(source);
        boolean follow = !xhs && "follow".equals(mode);
        appTitle.setText("亮点，今天也刚好。");
        searchBar.setText(xhs ? "⌕  搜索小红书内容" : "⌕  搜索B站内容");
        // 画板 03：关注模式没有搜索条、入口卡和条数状态行，只留分段 + 一行说明。
        searchBar.setVisibility(xhs || follow ? View.GONE : View.VISIBLE);
        if (biliEntry != null) biliEntry.setVisibility(xhs || follow ? View.GONE : View.VISIBLE);
        if (followHint != null) followHint.setVisibility(follow ? View.VISIBLE : View.GONE);
        if (statusRow != null) statusRow.setVisibility(xhs || follow ? View.GONE : View.VISIBLE);
        if (pullHint != null) pullHint.setVisibility(xhs || follow ? View.GONE : View.VISIBLE);
        status.setVisibility(xhs || follow ? View.GONE : View.VISIBLE);
        // XHS tab 只有入口卡、不请求推荐：禁掉下拉刷新，并收掉可能残留的 B站刷新态。
        feedList.setPullEnabled(!xhs);
        if (xhs) {
            pullBar.setVisibility(View.GONE);
            refreshSpin.setVisibility(View.GONE);
            feedList.setRefreshing(false);
        }
        if (xhs) {
            PaperUi.chipOutline(tabXhs, true);
            PaperUi.chip(tabBili, false);
        } else {
            PaperUi.chip(tabXhs, false);
            PaperUi.chip(tabBili, true);
        }
        biliMode.setVisibility(xhs ? View.GONE : View.VISIBLE);
        boolean recommend = "recommend".equals(mode);
        if (recommend) {
            modeRecommend.setBackgroundResource(R.drawable.bg_segment_active);
            modeFollow.setBackground(null);
        } else {
            modeRecommend.setBackground(null);
            modeFollow.setBackgroundResource(R.drawable.bg_segment_active);
        }
        // V-05：选中项文字品牌橘，未选中墨绿（画板 01/03）。
        modeRecommend.setTextColor(recommend ? PaperUi.BRAND : PaperUi.INK);
        modeFollow.setTextColor(recommend ? PaperUi.INK : PaperUi.BRAND);
    }

    /** 入口卡状态：无障碍已连接 + Key 已保存才算「就绪」；具体缺项进检查面板看。 */
    private void updateEntryCards() {
        boolean ready = HighlightService.isConnected() && SecureStore.getKey(this) != null;
        if (biliEntryState != null) biliEntryState.setText(ready ? "就绪" : "要配置");
        if (biliEntryDot != null) {
            biliEntryDot.setBackgroundResource(ready ? R.drawable.bg_dot_brand : R.drawable.bg_dot_muted);
        }
        TextView xhsButton = findViewById(R.id.xhsEntryStatus);
        if (xhsButton != null) xhsButton.setText("打开小红书");
        styleEntryButton(xhsButton, ready);
        TextView xhsState = findViewById(R.id.xhsEntryState);
        View xhsDot = findViewById(R.id.xhsEntryDot);
        if (xhsState != null) xhsState.setText(ready ? "就绪 · 可以打开" : "待配置 · 还差 " + missingChecks() + " 项");
        styleXhsDot(xhsDot, ready);
    }

    /** 小红书状态点统一走这里：就绪品牌橘，缺项浅灰（避免 entryRow 与 updateEntryCards 两处分叉）。 */
    private void styleXhsDot(View dot, boolean ready) {
        if (dot != null) {
            dot.setBackgroundResource(ready ? R.drawable.bg_dot_brand : R.drawable.bg_dot_muted);
        }
    }

    /**
     * 入口主按钮的禁用态：缺项时降到 40% 透明度，外观与可用态区分开；
     * 但仍保留点击（进检查面板看缺哪项），不做 setEnabled(false)。
     */
    private void styleEntryButton(TextView button, boolean ready) {
        if (button == null) return;
        button.setAlpha(ready ? 1f : 0.4f);
    }

    /** 入口状态行用：无障碍与 Key 两项里还差几项（安装与规则在检查面板里再查）。 */
    private int missingChecks() {
        int missing = 0;
        if (!HighlightService.isConnected()) missing++;
        if (SecureStore.getKey(this) == null) missing++;
        return Math.max(1, missing);
    }

    private void openEntry(String platform) {
        // 首页入口：四项逐项点亮，全部通过后自动打开目标 App；设置页仍用 start（非自动）。
        EntryCheckActivity.startAuto(this, platform);
    }

    /** 入口卡右上角的小「!」：自定义纸卡弹窗解释亮点是什么。 */
    private void showWhatIsHighlight() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = getLayoutInflater().inflate(R.layout.dialog_what_is_highlight, null, false);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.gravity = Gravity.CENTER;
            attrs.dimAmount = 0.35f;
            window.setAttributes(attrs);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        View close = content.findViewById(R.id.aboutClose);
        if (close != null) close.setOnClickListener(v -> dialog.dismiss());
        View ok = content.findViewById(R.id.aboutOk);
        if (ok != null) ok.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        if (window != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(dp(316), screen - dp(32));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void openPlatformSearch() {
        SessionLaunch.start(this, source);
        if (PlatformSearch.open(this, source, "")) return;
        SessionLaunch.abort(this);
        Toast.makeText(this, "没能直接打开" + PlatformSearch.label(source) + "搜索，先回亮点搜索页",
                Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, SearchActivity.class).putExtra("source", source));
    }

    // ---------- 打开卡片 ----------

    /** 收藏弹层：默认勾上次用过的夹，可新建夹；后续解析/封面落盘都在后台。 */
    private void saveVideo(VideoStore.Video v) {
        if (v == null || v.bvid.isEmpty()) return;
        SavedStore.Item item = new SavedStore.Item();
        item.source = SavedStore.SOURCE_BILI;
        item.id = v.bvid;
        item.title = v.title;
        item.author = v.author;
        item.cover = v.cover;
        item.duration = v.duration;
        item.url = "https://www.bilibili.com/video/" + v.bvid;
        SaveSheet.show(this, item, false, folderIds -> {
            SavedStore store = SavedStore.get(this);
            boolean added = store.save(item, folderIds);
            store.setLastFolder(folderIds.get(0));
            CoverCache.ensure(this, item, null);
            toast(added ? "已收藏" : "已放进选中的收藏夹");
            adapter.notifyDataSetChanged();
        });
    }

    private void openVideo(VideoStore.Video v) {
        if (v == null || v.bvid.isEmpty()) return;
        // 返回气泡已移到 HighlightService 的 accessibility overlay，不再需要悬浮窗权限。
        jump(v);
    }

    private void jump(VideoStore.Video v) {
        SessionLaunch.start(this, "bili");
        if (tryOpen("bilibili://video/" + v.bvid)) return;
        if (tryOpen("https://www.bilibili.com/video/" + v.bvid)) return;
        SessionLaunch.abort(this);
        toast("打不开这条");
    }

    private boolean tryOpen(String uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 仅 debug：电脑把 Cookie 推到 getExternalFilesDir(null)/bili-cookie.txt 后，
     * 启动 App 就会导入本机加密存储（已有凭据则不覆盖），导入成功后删掉文件。
     * 日志只打长度，绝不打内容。
     */
    private void maybeImportDebugCookie() {
        if (!BuildConfig.DEBUG) return;
        try {
            File dir = getExternalFilesDir(null);
            File f = dir == null ? null : new File(dir, "bili-cookie.txt");
            if (f == null || !f.isFile() || f.length() == 0) return;
            if (SecureStore.getCookie(this) != null) return;
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return;
            if (!SecureStore.isAvailable()) {
                android.util.Log.w(TAG, "debug-cookie-import: keystore unavailable");
                return;
            }
            String cookie = SettingsActivity.normalizeCookie(raw);
            SecureStore.setCookie(this, cookie);
            android.util.Log.i(TAG, "cookie len=" + cookie.length());
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "debug-cookie-import failed: " + t.getClass().getSimpleName());
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static void LogI(String msg) {
        android.util.Log.i(TAG, msg);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 列表 ----------

    private class CardAdapter extends BaseAdapter {

        private static final int TYPE_ENTRY = 0;
        private static final int TYPE_GRID = 1;
        private static final int TYPE_VIDEO = 2;

        private final List<VideoStore.Video> visible = new ArrayList<>();
        private boolean xhs;
        private boolean grid;

        void setData(List<VideoStore.Video> visibleItems, String source, String mode) {
            visible.clear();
            visible.addAll(visibleItems);
            xhs = "xhs".equals(source);
            grid = !xhs && !"follow".equals(mode);
            notifyDataSetChanged();
        }

        int rowsOf(List<VideoStore.Video> list) {
            return grid ? (list.size() + 1) / 2 : list.size();
        }

        /** 双列网格：行号 ×2 + 列号 → 数据下标；越界返回 -1。关注流不要用它。 */
        private int indexAt(int position, int column) {
            int index = position * 2 + column;
            return index < visible.size() ? index : -1;
        }

        public int getCount() {
            if (xhs) return 1;
            return rowsOf(visible);
        }

        public Object getItem(int position) {
            return null;
        }

        public long getItemId(int position) {
            return position;
        }

        public int getViewTypeCount() {
            return 3;
        }

        public int getItemViewType(int position) {
            if (xhs) return TYPE_ENTRY;
            return grid ? TYPE_GRID : TYPE_VIDEO;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            int type = getItemViewType(position);
            // convertView 只认「同一 view type、由本适配器 inflate 的行」：ListView 在数据变短/
            // 底部重排时会复用越界位置返回的零高度占位视图，跨类型复用时行里没有 @id/glow，
            // 直接 NPE（F4-01）。类型不符就丢掉重造，宁可少复用也不错绑。
            if (convertView != null && !Integer.valueOf(type).equals(convertView.getTag())) {
                convertView = null;
            }
            if (type == TYPE_ENTRY) return entryRow(convertView);
            if (type == TYPE_VIDEO) return videoRow(position, convertView, parent);
            return gridRow(position, convertView);
        }

        private View entryRow(View convertView) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_xhs_entry, null);
                row.setTag(TYPE_ENTRY);
            }
            TextView button = row.findViewById(R.id.xhsEntryStatus);
            if (button != null) {
                button.setText("打开小红书");
                button.setOnClickListener(v -> openEntry("xhs"));
            }
            boolean ready = HighlightService.isConnected() && SecureStore.getKey(MainActivity.this) != null;
            styleEntryButton(button, ready);
            TextView state = row.findViewById(R.id.xhsEntryState);
            if (state != null) {
                state.setText(ready ? "就绪 · 可以打开" : "待配置 · 还差 " + missingChecks() + " 项");
            }
            View dot = row.findViewById(R.id.xhsEntryDot);
            if (dot != null) {
                MainActivity.this.styleXhsDot(dot, ready);
            }
            View info = row.findViewById(R.id.entryInfo);
            if (info != null) info.setOnClickListener(v -> MainActivity.this.showWhatIsHighlight());
            return row;
        }

        private View gridRow(int position, View convertView) {
            GlowRow row;
            if (convertView == null) {
                row = new GlowRow(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                // 上方/下方留 10–12dp，让行级外发光有地方画；卡片本身随后盖住内侧。
                row.setPadding(dp(22), dp(10), dp(22), dp(12));
                for (int i = 0; i < 2; i++) {
                    View card = getLayoutInflater().inflate(R.layout.item_card, row, false);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (i == 0) lp.rightMargin = dp(6);
                    else lp.leftMargin = dp(6);
                    row.addView(card, lp);
                }
                row.setTag(TYPE_GRID);
            } else {
                row = (GlowRow) convertView;
            }
            int strength = Prefs.glowStrength(MainActivity.this);
            for (int col = 0; col < 2; col++) {
                if (col >= row.getChildCount()) break;
                View card = row.getChildAt(col);
                int index = indexAt(position, col);
                if (index < 0) {
                    card.setVisibility(View.INVISIBLE);
                    card.setOnClickListener(null);
                    row.setGlow(col, false, strength);
                    continue;
                }
                card.setVisibility(View.VISIBLE);
                bindGridCard(card, visible.get(index));
                row.setGlow(col, visible.get(index).result == Judge.GLOW, strength);
            }
            return row;
        }

        private void bindGridCard(View card, VideoStore.Video v) {
            ImageView cover = card.findViewById(R.id.cover);
            TextView title = card.findViewById(R.id.title);
            TextView meta = card.findViewById(R.id.meta);
            // 网格卡的外发光由 GlowRow 在卡片后面统一绘制；卡片内 GlowView 隐藏，避免内侧再叠一层。
            applyGlow(card, card.findViewById(R.id.glow), false);
            card.setAlpha(1f);
            title.setText(v.title);
            StringBuilder m = new StringBuilder(v.author.isEmpty() ? "B站" : v.author);
            m.append(" · ").append(Times.label(v.pubTs, LocalDateTime.now()));
            if (v.result == Judge.GLOW) m.append(" · ✦");
            meta.setText(m.toString());
            TextView duration = card.findViewById(R.id.videoDuration);
            if (duration != null) {
                duration.setText(v.duration);
                duration.setVisibility(v.duration.isEmpty() ? View.GONE : View.VISIBLE);
            }
            View box = card.findViewById(R.id.coverBox);
            ViewGroup.LayoutParams blp = box.getLayoutParams();
            blp.height = dp(90);
            box.setLayoutParams(blp);
            Thumbs.cover(cover, v.cover, dp(170), dp(96));
            bindSave(card.findViewById(R.id.saveBtn), v);
            card.setOnClickListener(x -> openVideo(v));
        }

        private View videoRow(int position, View convertView, ViewGroup parent) {
            // 关注流一行一条，position 就是数据下标。不能再走 indexAt（它是双列的行×2+列）。
            int index = position;
            if (index < 0 || index >= visible.size()) return placeholder(parent);
            View row = convertView;
            // 双保险：即使带了 convertView，只要关注行必备的 id 取不到，也绝不拿它 bind。
            if (row == null || row.findViewById(R.id.followTitle) == null) {
                row = getLayoutInflater().inflate(R.layout.item_follow, parent, false);
                row.setTag(TYPE_VIDEO);
            }
            // V-r2-01：关注卡也是 22dp 页边距（宽 316dp），保持 XML 的 top 0 / bottom 12dp。
            row.setPadding(dp(22), row.getPaddingTop(), dp(22), row.getPaddingBottom());
            VideoStore.Video v = visible.get(index);
            // 关注行不再画辉光（用户决定整卡外发光不好看）；网格卡的外发光由 GlowRow 负责。
            row.setAlpha(1f);

            TextView followTitle = row.findViewById(R.id.followTitle);
            if (followTitle != null) followTitle.setText(v.title);
            TextView followMeta = row.findViewById(R.id.followMeta);
            if (followMeta != null) {
                String author = v.author.isEmpty() ? "B站" : v.author;
                String label = Times.label(v.pubTs, LocalDateTime.now());
                followMeta.setText(author + " · " + label
                        + (v.result == Judge.GLOW ? " · ✦" : ""));
            }
            TextView duration = row.findViewById(R.id.followDuration);
            if (duration != null) {
                duration.setText(v.duration);
                duration.setVisibility(v.duration.isEmpty() ? View.GONE : View.VISIBLE);
            }
            ImageView thumb = row.findViewById(R.id.followThumb);
            if (thumb != null) {
                Thumbs.cover(thumb, v.cover, dp(137), dp(106));
            }
            bindSave(row.findViewById(R.id.followSave), v);
            row.setOnClickListener(x -> openVideo(v));
            return row;
        }

        /**
         * 网格卡的外发光已移到 {@link GlowRow}；这里只把卡内 {@link GlowView} 隐藏，
         * 避免内侧再叠一层。id 缺失/行被回收时 glowView 可能为 null，只跳过、不影响绑定。
         */
        private void applyGlow(View card, View glowView, boolean glow) {
            if (card == null) return;
            boolean on = glow && Prefs.glowEnabled(MainActivity.this);
            if (glowView instanceof GlowView) {
                ((GlowView) glowView).setStrength(Prefs.glowStrength(MainActivity.this));
            }
            if (glowView != null) {
                glowView.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        }

        /** 收藏按钮：已收藏显示实心星，点开是选夹弹层；保存后卡片状态立即更新。 */
        private void bindSave(TextView btn, VideoStore.Video v) {
            if (btn == null) return;
            boolean saved = SavedStore.get(MainActivity.this).has(SavedStore.SOURCE_BILI, v.bvid);
            btn.setText(saved ? "★ 收藏" : "☆ 收藏");
            btn.setTextColor(saved ? PaperUi.INK : PaperUi.BRAND);
            btn.setOnClickListener(x -> saveVideo(v));
        }

        /**
         * 列表在刷新瞬间可能被问到越界位置：给一个零高度占位，别让 get() 抛异常。
         * 占位视图没有 item_follow 的子 view，打上 -1 标记，保证它永远不会被当成 convertView 复用。
         */
        private View placeholder(ViewGroup parent) {
            View v = new View(MainActivity.this);
            v.setTag(-1);
            v.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0));
            return v;
        }

        private String initial(String name) {
            if (name == null || name.isEmpty()) return "·";
            return new String(Character.toChars(name.codePointAt(0)));
        }
    }
}
