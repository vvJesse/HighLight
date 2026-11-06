package com.feedreform.highlight;

import android.app.Activity;
import android.content.Intent;
import android.widget.TextView;

/** 三个页面共用的底部导航：首页 / 搜索 / 收藏（设置从首页齿轮进入）。 */
final class BottomNav {

    private BottomNav() {}

    static void bind(Activity activity, String current) {
        TextView home = activity.findViewById(R.id.navHome);
        TextView search = activity.findViewById(R.id.navSearch);
        TextView collection = activity.findViewById(R.id.navCollection);
        if (home == null) return;

        style(home, "home".equals(current), R.drawable.nav_home);
        style(search, "search".equals(current), R.drawable.nav_search);
        if (collection != null) style(collection, "collection".equals(current), R.drawable.nav_collection);

        home.setOnClickListener(v -> go(activity, MainActivity.class, "home".equals(current)));
        search.setOnClickListener(v -> go(activity, SearchActivity.class, "search".equals(current)));
        if (collection != null) {
            collection.setOnClickListener(v -> go(activity, CollectionActivity.class, "collection".equals(current)));
        }
    }

    private static void style(TextView tv, boolean active, int icon) {
        tv.setTextColor(active ? PaperUi.BRAND : PaperUi.INK);
        android.graphics.drawable.Drawable d = tv.getContext().getDrawable(icon).mutate();
        d.setTint(active ? PaperUi.BRAND : PaperUi.INK);
        tv.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null);
        if (active) tv.setBackgroundResource(R.drawable.bg_sage);
        else tv.setBackground(null);
    }

    private static void go(Activity activity, Class<?> target, boolean current) {
        if (current) return;
        Intent i = new Intent(activity, target);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(i);
        // 底部四个页签之间直接切换，不做转场动画。
        activity.overridePendingTransition(0, 0);
    }
}
