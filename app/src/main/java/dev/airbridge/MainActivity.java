package dev.airbridge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int CAPTURE=10, PICK=11, SAVE=12, NOTIFICATIONS=13;
    private static final int BG=0xff0c151c, CARD=0xff15242d, INK=0xffedf6f4, MUTED=0xff91a9b2, MINT=0xff66e0bd;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService fileWorker=Executors.newSingleThreadExecutor();
    private AppSession session;
    private TextView status, address, code, detail, fileHint;
    private Button toggle, copy, share;
    private Spinner profile;
    private LinearLayout fileRows;
    private String exportId;
    private long shownRevision=-1;
    private boolean resumed, working, requestingNotifications;
    private final Runnable update=new Runnable() {
        @Override public void run() { if(resumed) { refresh(); handler.postDelayed(this,1000); } }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session=AppSession.get(this);
        if(state!=null) {
            exportId=state.getString("exportId");
            requestingNotifications=state.getBoolean("requestingNotifications");
        }
        buildUi();
        if(state!=null) profile.setSelection(state.getInt("profile",0));
    }
    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page=column(); page.setPadding(dp(22),dp(22),dp(22),dp(30)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        TextView brand=text("◈  AIRBRIDGE",13,MINT); brand.setLetterSpacing(.14f); page.addView(brand);
        page.addView(text("轻投",38,INK));
        TextView sub=text("让手机桌面，来到更大的屏幕。",15,MUTED); padBottom(sub,20); page.addView(sub);

        LinearLayout connection=card(page);
        status=text("●  准备连接",14,MINT); connection.addView(status);
        TextView intro=text("01  电脑浏览器打开",12,MUTED); padTop(intro,18); connection.addView(intro);
        address=text("开始投屏后显示连接地址",20,INK); address.setTextIsSelectable(true); connection.addView(address);
        copy=button("复制浏览器地址",false); connection.addView(copy);
        copy.setOnClickListener(v->{
            List<String> addresses=session.addresses();
            if(!addresses.isEmpty()) { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("AirBridge",addresses.get(0))); toast("地址已复制"); }
        });
        TextView pair=text("02  输入本次配对码",12,MUTED); padTop(pair,12); connection.addView(pair);
        code=text("— — — — — —",30,MINT); code.setTypeface(Typeface.MONOSPACE); code.setLetterSpacing(.13f); connection.addView(code);
        connection.addView(text("手机与电脑连接同一 Wi-Fi，或让电脑连接手机热点。",13,MUTED));

        LinearLayout controls=card(page);
        controls.addView(text("屏幕共享",19,INK));
        profile=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"均衡 · 长边 1280 / 12 FPS","省流 · 长边 960 / 8 FPS","清晰 · 长边 1920 / 15 FPS"});
        profile.setAdapter(adapter);
        profile.setSelection(session.maxEdge==960?1:session.maxEdge==1920?2:0);
        controls.addView(profile);
        toggle=button("开始投屏",true); controls.addView(toggle);
        toggle.setOnClickListener(v->{
            if(session.capturing) { stopService(new Intent(this,ProjectionService.class)); refresh(); }
            else if(!session.starting) {
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                    if(requestingNotifications) return;
                    requestingNotifications=true; refresh();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
                }
                else askProjection();
            }
        });
        detail=text("无需数据线。开始后请在系统弹窗中允许共享屏幕。",13,MUTED); controls.addView(detail);
        controls.addView(text("仅传输画面，不含声音或远程操控。保持手机解锁；安全窗口可能显示黑屏。",12,MUTED));

        LinearLayout transfer=card(page);
        transfer.addView(text("文件收发箱",20,INK));
        transfer.addView(text("手机选文件 → 电脑下载\n电脑拖入网页 → 手机另存到所选位置",13,MUTED));
        share=button("＋  选择手机文件",false); transfer.addView(share);
        share.setOnClickListener(v->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            startActivityForResult(pick,PICK);
        });
        fileHint=text("单文件最大 1 GiB。收发箱保存在 App 内，卸载会删除。",12,MUTED); transfer.addView(fileHint);
        fileRows=column(); transfer.addView(fileRows);
        TextView footer=text("LOCAL FIRST  /  局域网直连\n配对码仅向可信的人提供。请在可信网络使用。",11,MUTED); padTop(footer,8); page.addView(footer);
    }
    private void askProjection() {
        int selection=profile.getSelectedItemPosition();
        session.maxEdge=selection==1?960:selection==2?1920:1280;
        session.fps=selection==1?8:selection==2?15:12;
        session.quality=selection==1?50:selection==2?80:65;
        session.error=""; session.starting=true; refresh();
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        try {
            Intent capture=Build.VERSION.SDK_INT>=34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
            startActivityForResult(capture,CAPTURE);
        } catch(Exception e) { session.starting=false; session.error="无法打开屏幕授权："+e.getMessage(); refresh(); }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTIFICATIONS) {
            requestingNotifications=false;
            if(!session.capturing && !session.starting) askProjection();
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==CAPTURE) {
            if(result==RESULT_OK && data!=null) {
                try { startForegroundService(new Intent(this,ProjectionService.class).putExtra("permission",data)); }
                catch(Exception e) { session.starting=false; session.error="启动失败："+e.getMessage(); }
            } else { session.starting=false; toast("已取消屏幕共享"); }
            refresh();
        } else if(request==PICK && result==RESULT_OK && data!=null) {
            List<Uri> selected=new ArrayList<>();
            if(data.getClipData()!=null) for(int i=0;i<data.getClipData().getItemCount();i++) selected.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null) selected.add(data.getData());
            runFiles("正在添加文件…",()->{
                int count=0;
                for(Uri uri:selected) { session.files.importUri(getApplicationContext(),uri); count++; }
                return "已添加 "+count+" 个文件，可在浏览器下载";
            });
        } else if(request==SAVE) {
            String id=exportId; exportId=null;
            if(result==RESULT_OK && data!=null && data.getData()!=null && id!=null) {
                Uri uri=data.getData();
                runFiles("正在另存文件…",()->{ session.files.export(getApplicationContext(),id,uri); return "已保存到所选位置"; });
            }
        }
    }
    private interface FileJob { String run() throws Exception; }
    private void runFiles(String progress,FileJob job) {
        working=true; fileHint.setText(progress); share.setEnabled(false);
        fileWorker.execute(()->{
            String result;
            try { result=job.run(); } catch(Exception e) { result="文件操作失败："+(e.getMessage()==null?"请重试":e.getMessage()); }
            String message=result;
            handler.post(()->{
                if(isDestroyed()) return;
                working=false; share.setEnabled(true); fileHint.setText(message); shownRevision=-1; refresh(); toast(message);
            });
        });
    }
    private void refresh() {
        boolean active=session.capturing;
        status.setText(active?"●  正在投屏 · "+(session.server==null?0:session.server.getViewerCount())+" 个观看连接":session.starting?"●  等待启动":"○  尚未共享屏幕");
        List<String> ips=session.addresses();
        address.setText(active?(ips.isEmpty()?"未找到局域网地址，请连接 Wi-Fi 或开启热点":String.join("\n",ips)):"开始投屏后显示连接地址");
        code.setText(active?session.pin:"— — — — — —");
        copy.setEnabled(active&&!ips.isEmpty());
        toggle.setEnabled(!session.starting&&!requestingNotifications); toggle.setText(active?"停止投屏与连接":session.starting?"正在启动…":"开始投屏");
        profile.setEnabled(!active&&!session.starting&&!requestingNotifications);
        if(!session.error.isEmpty()) detail.setText(session.error);
        else detail.setText(active?"浏览器输入上方地址，再使用配对码连接。停止会断开所有连接。":"无需数据线。开始后请在系统弹窗中允许共享屏幕。");
        if(active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(shownRevision!=session.files.revision()) { shownRevision=session.files.revision(); renderFiles(); }
    }
    private void renderFiles() {
        fileRows.removeAllViews();
        List<TransferStore.Entry> entries=session.files.list();
        if(entries.isEmpty()) { TextView empty=text("还没有文件\n选一个文件，或从浏览器拖入。",14,MUTED); padTop(empty,20); fileRows.addView(empty); }
        for(TransferStore.Entry entry:entries) {
            LinearLayout row=column(); row.setPadding(0,dp(18),0,dp(8)); fileRows.addView(row);
            row.addView(text(entry.name,15,INK));
            row.addView(text((entry.source.equals("phone")?"手机已分享":"从浏览器收到")+"  ·  "+size(entry.size),12,MUTED));
            LinearLayout actions=new LinearLayout(this); row.addView(actions);
            Button save=button("另存到手机",false);
            actions.addView(save,new LinearLayout.LayoutParams(0,dp(44),1));
            save.setOnClickListener(v->{
                exportId=entry.id;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,entry.name),SAVE);
            });
            Button remove=button("移除",false); actions.addView(remove,new LinearLayout.LayoutParams(dp(80),dp(44)));
            remove.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("从收发箱移除？").setMessage("仅删除 App 中的副本，已另存的文件不受影响。")
                .setNegativeButton("取消",null).setPositiveButton("移除",(dialog,which)->{session.files.remove(entry.id); refresh();}).show());
        }
    }
    private String size(long value) {
        if(value<1024) return value+" B";
        if(value<1024*1024) return String.format(Locale.CHINA,"%.1f KiB",value/1024.0);
        return String.format(Locale.CHINA,"%.1f MiB",value/(1024.0*1024));
    }
    @Override protected void onResume() { super.onResume(); resumed=true; handler.removeCallbacks(update); update.run(); }
    @Override protected void onPause() { resumed=false; handler.removeCallbacks(update); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("exportId",exportId);
        out.putBoolean("requestingNotifications",requestingNotifications);
        out.putInt("profile",profile.getSelectedItemPosition());
    }
    @Override protected void onDestroy() { handler.removeCallbacks(update); fileWorker.shutdown(); super.onDestroy(); }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String content,int size,int color) { TextView t=new TextView(this); t.setText(content); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(5),0,dp(5)); t.setLineSpacing(dp(3),1); return t; }
    private void padTop(View v,int n) { v.setPadding(v.getPaddingLeft(),dp(n),v.getPaddingRight(),v.getPaddingBottom()); }
    private void padBottom(View v,int n) { v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),dp(n)); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c=column(); c.setPadding(dp(18),dp(17),dp(18),dp(17));
        GradientDrawable background=new GradientDrawable(); background.setColor(CARD); background.setCornerRadius(dp(20)); c.setBackground(background);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(16); parent.addView(c,p); return c;
    }
    private Button button(String label,boolean primary) {
        Button b=new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(primary?BG:MINT);
        GradientDrawable shape=new GradientDrawable(); shape.setColor(primary?MINT:0xff20363f); shape.setCornerRadius(dp(12)); b.setBackground(shape);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48)); p.topMargin=dp(10); p.bottomMargin=dp(10); b.setLayoutParams(p); return b;
    }
}
