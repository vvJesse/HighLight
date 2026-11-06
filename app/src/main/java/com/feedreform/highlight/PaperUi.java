package com.feedreform.highlight;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
final class PaperUi {
 static final int INK=0xFF173F36, BRAND=0xFFDF5129, SAGE=0xFFE9E8D5;
 /** 描边选中态：小红书页签用（白底 + 1.5dp 品牌橘描边）。 */
 static void chipOutline(TextView view, boolean active) {
  if (!active) { chip(view, false); return; }
  GradientDrawable bg = new GradientDrawable();
  bg.setColor(0xFFFFFCF4);
  bg.setStroke(Math.round(1.5f * view.getResources().getDisplayMetrics().density), 0xFFDF5129);
  bg.setCornerRadius(100 * view.getResources().getDisplayMetrics().density);
  view.setTextColor(0xFFDF5129);
  view.setBackground(bg);
 }
 static void chip(TextView view, boolean active) { view.setTextColor(active?0xFFFFFCF4:INK); GradientDrawable bg=new GradientDrawable(); bg.setColor(active?BRAND:SAGE); bg.setCornerRadius(100*view.getResources().getDisplayMetrics().density); view.setBackground(bg); }
 static void insets(Activity activity) {
  activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  if (android.os.Build.VERSION.SDK_INT >= 30) {
   android.view.View content = activity.findViewById(android.R.id.content);
   content.setOnApplyWindowInsetsListener((view, insets) -> {
    // Use the union, not the sum: the IME already occupies the navigation-bar area.
    android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
      | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
    return insets;
   });
   content.requestApplyInsets();
  }
 }
}
