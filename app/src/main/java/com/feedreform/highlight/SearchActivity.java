package com.feedreform.highlight;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 搜索入口页：上面切换平台，中间点一下搜索框就直接进对应平台 App 的搜索界面；
 * 跳转前由 {@link SessionLaunch} 启动会话并挂「← 返回亮点」气泡，和入口/卡片一致。
 * 本页不再输入关键词，输入留给平台 App 自己。
 */
public class SearchActivity extends Activity {

    private String source = "xhs";
    private TextView chip;
    private TextView biliChip;
    private TextView entry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        PaperUi.insets(this);
        BottomNav.bind(this, "search");

        if (getIntent() != null && "bili".equals(getIntent().getStringExtra("source"))) {
            source = "bili";
        }
        chip = findViewById(R.id.srcChip);
        biliChip = findViewById(R.id.searchBili);
        entry = findViewById(R.id.searchEntry);
        paint();

        chip.setOnClickListener(v -> { source="xhs";paint(); });
        biliChip.setOnClickListener(v -> { source="bili";paint(); });
        entry.setOnClickListener(v -> openPlatformSearch());
    }

    @Override protected void onResume(){
        super.onResume();
        stopService(new Intent(this,ReturnBubbleService.class));
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        String next=intent.getStringExtra("source");
        if(next!=null){source="bili".equals(next)?"bili":"xhs";paint();}
    }

    private void paint() {
        // V-11：与首页同一套选中样式 —— 小红书选中用描边，B站选中用实心橘。
        if ("xhs".equals(source)) {
            PaperUi.chipOutline(chip, true);
            PaperUi.chip(biliChip, false);
        } else {
            PaperUi.chip(chip, false);
            PaperUi.chip(biliChip, true);
        }
        entry.setText("bili".equals(source) ? "⌕  去B站搜点什么…" : "⌕  去小红书搜点什么…");
    }

    /** 点搜索框 → 按当前平台直达对方搜索页；接不住时只提示，不回退（本页就是入口）。 */
    private void openPlatformSearch() {
        SessionLaunch.start(this, source);
        if (PlatformSearch.open(this, source, "")) return;
        SessionLaunch.abort(this);
        Toast.makeText(this, "没能直接打开" + PlatformSearch.label(source) + "搜索，确认一下是否已安装",
                Toast.LENGTH_LONG).show();
    }
}
