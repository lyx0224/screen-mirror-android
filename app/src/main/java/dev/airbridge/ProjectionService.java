package dev.airbridge;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.os.*;

public final class ProjectionService extends Service {
    public static final String STOP="dev.airbridge.STOP";
    private ScreenCapture capture;
    private AppSession session;
    private boolean shuttingDown;
    @Override public void onCreate() { super.onCreate(); session=AppSession.get(this); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(capture!=null) return START_NOT_STICKY;
        try {
            NotificationManager notifications=getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("projection","屏幕共享",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ProjectionService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(this,"projection")
                .setContentTitle("轻投 · 正在共享屏幕")
                .setContentText("同一网络中的已配对浏览器可观看和传文件")
                .setSmallIcon(R.drawable.ic_airbridge).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"停止共享",stop).build()).build();
            if(Build.VERSION.SDK_INT>=29) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(1,notification);
            Intent permission=intent.getParcelableExtra("permission");
            if(permission==null) throw new IllegalStateException("请重新授权屏幕共享");
            MediaProjection projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,permission);
            if(projection==null) throw new IllegalStateException("屏幕共享授权已失效");
            capture=new ScreenCapture(this,projection,session.frames,session.maxEdge,session.fps,session.quality,new ScreenCapture.Listener() {
                @Override public void onStopped() { if(!shuttingDown) stopSelf(); }
                @Override public void onError(String message) {
                    if(!shuttingDown) { session.error=message; stopSelf(); }
                }
            });
            session.startServer();
            session.capturing=true; session.starting=false; session.error="";
            capture.start();
        } catch(Exception e) {
            session.error="启动失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
            session.starting=false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        shuttingDown=true;
        if(capture!=null) { capture.close(); capture=null; }
        session.stop(); stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
