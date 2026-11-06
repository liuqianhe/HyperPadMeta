package pub.chara.hyperpadmeta;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「关于」页：应用图标 / 名称 / 版本号 / 仓库与文档入口 / 说明文案 / 两位作者。
 *
 * 布局全部用代码搭（与 SettingsActivity 保持一套风格，不额外引 XML layout）：
 *   返回箭头 → 大标题 → 居中图标 + 名称 + 版本 → 两个胶囊按钮（GitHub / 文档）
 *   → 说明卡片（原设置页页脚，已移到这里）→ 两张作者卡片（点击跳 GitHub）。
 * 仓库地址不再单独显示一行文本，避免与「GitHub」按钮重复。
 */
public class AboutActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String URL_REPO = "https://github.com/liuqianhe/HyperPadMeta";
    private static final String URL_README = "https://github.com/liuqianhe/HyperPadMeta/blob/main/README.md";
    private static final String URL_ME = "https://github.com/liuqianhe";
    private static final String URL_UPSTREAM = "https://github.com/CwithW";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getColor(R.color.window_bg));
        getWindow().setNavigationBarColor(getColor(R.color.window_bg));
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(
                night ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(12), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        // ---- 返回 ----
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(getColor(R.color.text_primary));
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), dp(4), dp(8), dp(4));
        back.setContentDescription(getString(R.string.about_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(back, new LinearLayout.LayoutParams(WRAP, WRAP));

        // ---- 标题 ----
        TextView title = new TextView(this);
        title.setText(R.string.about_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = wrapLp();
        titleLp.topMargin = dp(10);
        root.addView(title, titleLp);

        // ---- 图标 ----
        FrameLayout logoWrap = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setShape(GradientDrawable.OVAL);
        logoBg.setColor(0xFFE8F1FF);
        logoWrap.setBackground(logoBg);
        int logoSize = dp(104);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.topMargin = dp(28);
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logoWrap, logoLp);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int iconSize = dp(80);
        // 把方形自适应图标裁成圆形，和参考图一致（圆形底 + 圆形图标）
        GradientDrawable logoMask = new GradientDrawable();
        logoMask.setShape(GradientDrawable.OVAL);
        logoMask.setColor(0xFFFFFFFF);
        logo.setBackground(logoMask);
        logo.setClipToOutline(true);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER;
        logoWrap.addView(logo, iconLp);

        // ---- 名称 / 版本 ----
        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        name.setTextColor(getColor(R.color.text_primary));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = wrapLp();
        nameLp.topMargin = dp(16);
        root.addView(name, nameLp);

        TextView version = new TextView(this);
        version.setText(getString(R.string.about_version, versionName(), versionCode()));
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        version.setTextColor(getColor(R.color.text_secondary));
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = wrapLp();
        verLp.topMargin = dp(6);
        root.addView(version, verLp);

        // ---- 仓库 / 文档 ----
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = wrapLp();
        btnRowLp.topMargin = dp(24);
        root.addView(buttons, btnRowLp);

        buttons.addView(pillButton(R.drawable.ic_code, R.string.about_github, true, URL_REPO),
                weightedLp(dp(6)));
        buttons.addView(pillButton(R.drawable.ic_doc, R.string.about_docs, false, URL_README),
                weightedLp(dp(6)));

        // 仓库地址不再单独显示一行文本：与上方的「GitHub」按钮功能重复

        // ---- 说明（原设置页页脚） ----
        LinearLayout note = new LinearLayout(this);
        note.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable noteBg = new GradientDrawable();
        noteBg.setColor(getColor(R.color.card_bg));
        noteBg.setCornerRadius(dp(18));
        note.setBackground(noteBg);
        note.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams noteLp = wrapLp();
        noteLp.topMargin = dp(24);
        root.addView(note, noteLp);

        TextView noteText = new TextView(this);
        noteText.setText(R.string.about_note);
        noteText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        noteText.setTextColor(getColor(R.color.text_secondary));
        noteText.setLineSpacing(dp(3), 1f);
        note.addView(noteText, wrapLp());

        // ---- 作者卡片 ----
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardsLp = wrapLp();
        cardsLp.topMargin = dp(18);
        root.addView(cards, cardsLp);

        cards.addView(authorCard(R.drawable.avatar_jingyue, "jingyue", URL_ME), cardLp());
        cards.addView(authorCard(R.drawable.avatar_charawhite, "Chara White", URL_UPSTREAM), cardLp());

        return scroll;
    }

    /** 胶囊按钮：filled=true 为浅蓝底实心，否则描边。 */
    private LinearLayout pillButton(int iconRes, int textRes, boolean filled, final String url) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        if (filled) {
            bg.setColor(0x1F3482FF);
            bg.setStroke(Math.max(1, dp(0.5f)), 0x333482FF);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(Math.max(1, dp(1f)), 0x333482FF);
        }
        pill.setBackground(bg);
        pill.setClickable(true);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(url);
            }
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        labelLp.leftMargin = dp(8);
        pill.addView(label, labelLp);

        return pill;
    }

    /** 作者卡片：圆形头像 + 昵称，整行可点跳 GitHub。 */
    private LinearLayout authorCard(int avatarRes, String displayName, final String url) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(url);
            }
        });

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(avatarRes);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size = dp(46);
        // 圆形裁切：用 oval 背景 + clipToOutline
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        avatar.setBackground(mask);
        avatar.setClipToOutline(true);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(size, size);
        card.addView(avatar, avatarLp);

        TextView name = new TextView(this);
        name.setText(displayName);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        nameLp.leftMargin = dp(14);
        card.addView(name, nameLp);

        return card;
    }

    private void openUrl(String url) {
        try {
            // NEW_TASK：让浏览器在自己的任务里打开，避免它压在本页之上导致返回键先退浏览器历史
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException t) {
            Toast.makeText(this, R.string.about_link_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.getLongVersionCode();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(MATCH, WRAP);
    }

    private LinearLayout.LayoutParams weightedLp(int sideMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.leftMargin = sideMargin;
        lp.rightMargin = sideMargin;
        return lp;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = wrapLp();
        lp.topMargin = dp(10);
        return lp;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
