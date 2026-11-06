package com.feedreform.highlight;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 贴底的「保存到收藏夹」弹层：多选夹、新建夹、默认勾选上次用过的夹。
 *
 * 卡片收藏和链接预览共用同一个弹层；链接预览多一块「封面 + 标题 + 作者 + 状态」头部，
 * 解析仍在后台跑，结果通过 {@link Sheet#setPreview} 补进来，保存动作不被解析阻塞。
 */
final class SaveSheet {

    interface Callback {
        void onSave(List<String> folderIds);
    }

    private SaveSheet() {
    }

    static final class Sheet {
        final Dialog dialog;
        private final Activity activity;
        private final List<String> selected = new ArrayList<>();
        private final LinearLayout list;
        private final TextView subtitle;
        private final TextView previewTitle;
        private final TextView previewAuthor;
        private final TextView previewStatus;
        private final ImageView previewCover;
        private final View previewBox;

        Sheet(Activity activity, Dialog dialog, LinearLayout list, TextView subtitle,
              TextView previewTitle, TextView previewAuthor, TextView previewStatus,
              ImageView previewCover, View previewBox, List<String> initial) {
            this.activity = activity;
            this.dialog = dialog;
            this.list = list;
            this.subtitle = subtitle;
            this.previewTitle = previewTitle;
            this.previewAuthor = previewAuthor;
            this.previewStatus = previewStatus;
            this.previewCover = previewCover;
            this.previewBox = previewBox;
            selected.addAll(initial);
        }

        /** 链接解析完成后补全预览头部；封面按本地/网络规则加载。 */
        void setPreview(String title, String author, String cover, String status) {
            if (title != null && !title.isEmpty()) previewTitle.setText(title);
            if (author != null && !author.isEmpty()) previewAuthor.setText(author);
            if (status != null) previewStatus.setText(status);
            if (cover != null && !cover.isEmpty()) {
                Thumbs.cover(previewCover, cover,
                        activity.getResources().getDisplayMetrics().widthPixels / 3,
                        dp(112));
            }
        }

        void dismiss() {
            if (dialog.isShowing()) dialog.dismiss();
        }

        private int dp(int v) {
            return Math.round(v * activity.getResources().getDisplayMetrics().density);
        }
    }

    /**
     * @param item       要保存/补夹的条目（标题、作者、封面用于预览）
     * @param showPreview 是否显示预览头部（卡片收藏不需要）
     */
    static Sheet show(Activity activity, SavedStore.Item item, boolean showPreview, Callback cb) {
        if (activity == null || activity.isFinishing()) return null;
        SavedStore store = SavedStore.get(activity);
        List<String> initial = store.foldersOf(item.source, item.id);
        if (initial.isEmpty()) {
            String last = store.lastFolder();
            if (last.isEmpty() && !store.folders().isEmpty()) last = store.folders().get(0).id;
            if (!last.isEmpty()) initial.add(last);
        }

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
        }
        dialog.setCanceledOnTouchOutside(true);

        View root = dialog.findViewById(R.id.sheetRoot);
        View panel = dialog.findViewById(R.id.sheetPanel);
        LinearLayout list = dialog.findViewById(R.id.sheetList);
        TextView titleView = dialog.findViewById(R.id.sheetTitle);
        TextView subtitle = dialog.findViewById(R.id.sheetSubtitle);
        TextView newFolder = dialog.findViewById(R.id.sheetNewFolder);
        TextView cancel = dialog.findViewById(R.id.sheetCancel);
        TextView confirm = dialog.findViewById(R.id.sheetConfirm);
        View previewBox = dialog.findViewById(R.id.previewBox);
        ImageView previewCover = dialog.findViewById(R.id.previewCover);
        TextView previewTitle = dialog.findViewById(R.id.previewTitle);
        TextView previewAuthor = dialog.findViewById(R.id.previewAuthor);
        TextView previewStatus = dialog.findViewById(R.id.previewStatus);

        Sheet sheet = new Sheet(activity, dialog, list, subtitle,
                previewTitle, previewAuthor, previewStatus, previewCover, previewBox, initial);

        boolean already = store.has(item.source, item.id);
        titleView.setText(showPreview ? "这条链接" : (already ? "已收藏" : "保存到"));
        subtitle.setText(already ? "已在收藏夹里，可再放进其他夹" : "可同时放进多个收藏夹");

        if (showPreview) {
            previewBox.setVisibility(View.VISIBLE);
            previewTitle.setText(item.title.isEmpty() ? "正在读取这条链接…" : item.title);
            previewAuthor.setText(item.author);
            previewStatus.setText(item.cover.isEmpty() && item.title.isEmpty()
                    ? "正在读取标题和封面…" : "");
            if (!item.cover.isEmpty()) {
                Thumbs.cover(previewCover, item.cover,
                        activity.getResources().getDisplayMetrics().widthPixels / 3, dp(activity, 112));
            }
        } else {
            previewBox.setVisibility(View.GONE);
        }

        // 贴底面板要给系统导航栏/键盘留位置，否则「保存」会被手势条压住（pitfalls #28）。
        final int baseBottom = panel.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.ime());
            v.setPadding(0, 0, 0, 0);
            panel.setPadding(panel.getPaddingLeft(), panel.getPaddingTop(),
                    panel.getPaddingRight(), baseBottom + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();

        // 点空白关闭，点面板不穿透。
        root.setOnClickListener(v -> dialog.dismiss());
        panel.setOnClickListener(v -> {
        });
        cancel.setOnClickListener(v -> dialog.dismiss());

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            for (SavedStore.Folder f : store.folders()) {
                TextView row = new TextView(activity);
                row.setText((sheet.selected.contains(f.id) ? "✓  " : "") + f.name
                        + "   " + store.countIn(f.id));
                row.setTextSize(15);
                row.setTextColor(sheet.selected.contains(f.id) ? PaperUi.BRAND : PaperUi.INK);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
                row.setBackgroundResource(sheet.selected.contains(f.id)
                        ? R.drawable.bg_save_row_on : R.drawable.bg_save_row);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
                lp.bottomMargin = dp(activity, 10);
                row.setLayoutParams(lp);
                row.setOnClickListener(v -> {
                    if (sheet.selected.contains(f.id)) sheet.selected.remove(f.id);
                    else sheet.selected.add(f.id);
                    rebuild[0].run();
                });
                list.addView(row);
            }
            confirm.setAlpha(sheet.selected.isEmpty() ? 0.5f : 1f);
        };
        rebuild[0].run();

        newFolder.setOnClickListener(v -> {
            EditText input = new EditText(activity);
            input.setHint("夹的名字，比如：编程");
            input.setSingleLine(true);
            int pad = dp(activity, 16);
            input.setPadding(pad, pad, pad, pad);
            new AlertDialog.Builder(activity)
                    .setTitle("新建收藏夹")
                    .setView(input)
                    .setPositiveButton("创建", (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) return;
                        String id = store.newFolder(name);
                        if (!sheet.selected.contains(id)) sheet.selected.add(id);
                        rebuild[0].run();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        confirm.setOnClickListener(v -> {
            if (sheet.selected.isEmpty()) {
                Toast.makeText(activity, "先选一个收藏夹", Toast.LENGTH_SHORT).show();
                return;
            }
            if (cb != null) cb.onSave(new ArrayList<>(sheet.selected));
            dialog.dismiss();
        });

        dialog.show();
        return sheet;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
