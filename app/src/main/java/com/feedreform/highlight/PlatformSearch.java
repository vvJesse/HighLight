package com.feedreform.highlight;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import java.util.List;

/**
 * 直接跳到小红书 / B站 的搜索界面（不再先回亮点搜索页）。
 *
 * 真机（PLG110）实测，无关键词时都能落到对方 App 的搜索页，键盘同时弹出：
 * - 小红书 {@code xhsdiscover://search/result} → com.xingin.alioth.search.GlobalSearchActivity
 * - B站   {@code bilibili://search}            → com.bilibili.search2.main.BiliMainSearchActivity
 * 带 keyword 时两边都直接出结果页，行为与各自 App 内搜索一致。
 *
 * 启动前用 queryIntentActivities 确认目标 App 接得住这个 scheme（AndroidManifest 里已声明
 * queries）；接不住就返回 false，由调用方给出可见的回退，不静默失败。
 */
final class PlatformSearch {

    static final String BILI = "bili";

    private static final String TAG = "PlatformSearch";

    private PlatformSearch() {
    }

    static String label(String source) {
        return BILI.equals(source) ? "B站" : "小红书";
    }

    /** 组装跳转意图：keyword 为空 → 对方搜索页（等输入）；否则 → 搜索结果页。 */
    static Intent intent(String source, String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        String encoded = trimmed.isEmpty() ? "" : Uri.encode(trimmed);
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SearchTargets.uri(source, encoded)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * 启动对方搜索页。搜索跳转没有 HighlightSession，不挂计时气泡（避免无 session 的
     * 气泡只能等 30 分钟）；计时气泡只属于入口会话。
     *
     * @return false 表示这台设备接不住该 scheme（没装 App / 该版本没有搜索入口），
     *         调用方必须回退并且让用户看得见。
     */
    static boolean open(Activity activity, String source, String keyword) {
        Intent intent = intent(source, keyword);
        if (!canResolve(activity, intent)) {
            Log.w(TAG, "no search target for " + intent.getData());
            return false;
        }
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "startActivity failed for " + intent.getData(), e);
            return false;
        }
    }

    private static boolean canResolve(Context context, Intent intent) {
        try {
            List<ResolveInfo> targets = context.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (targets == null || targets.isEmpty()) return false;
            for (ResolveInfo info : targets) {
                if (info.activityInfo != null) {
                    Log.i(TAG, "search target: " + info.activityInfo.packageName + "/" + info.activityInfo.name);
                }
            }
            return true;
        } catch (Exception e) {
            // 查询本身不可用（如包可见性限制）时不能据此断言「没装」，交给 startActivity 判断。
            Log.w(TAG, "resolve failed, launching anyway", e);
            return true;
        }
    }
}
