package dev.airbridge;

import android.content.Context;
import android.os.Build;
import dev.airbridge.server.FrameHub;
import dev.airbridge.server.LocalServer;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

public final class AppSession implements LocalServer.Backend {
    private static AppSession instance;
    public static synchronized AppSession get(Context context) {
        if(instance==null) instance=new AppSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    public final TransferStore files;
    public volatile FrameHub frames=new FrameHub();
    public volatile LocalServer server;
    public volatile boolean capturing, starting;
    public volatile String pin="", error="";
    public volatile int maxEdge=1280, fps=12, quality=65;
    private AppSession(Context context) { this.context=context; files=new TransferStore(context); }
    public synchronized void startServer() throws IOException {
        if(server!=null) return;
        pin=String.format(Locale.US,"%06d",new SecureRandom().nextInt(1000000));
        LocalServer next=new LocalServer(8080,pin,this,frames);
        try { next.start(); server=next; } catch(IOException e) { next.close(); throw e; }
    }
    public synchronized void stop() {
        capturing=false; starting=false;
        if(server!=null) { server.close(); server=null; }
        frames.clear();
        // A retiring capture worker must not clear frames from the next session.
        frames=new FrameHub(); pin="";
    }
    public List<String> addresses() {
        List<String> result=new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()) {
                NetworkInterface network=interfaces.nextElement();
                if(!network.isUp() || network.isLoopback()) continue;
                String name=network.getName();
                if(name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")) continue;
                Enumeration<InetAddress> addresses=network.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress ip=addresses.nextElement();
                    if(ip instanceof Inet4Address && ip.isSiteLocalAddress()) result.add("http://"+ip.getHostAddress()+":8080");
                }
            }
        } catch(Exception ignored) { }
        Collections.sort(result); return result;
    }
    @Override public InputStream openAsset(String path) throws IOException { return context.getAssets().open("web/"+path); }
    @Override public String statusJson() {
        JSONObject j=new JSONObject();
        LocalServer currentServer=server;
        try {
            j.put("deviceName",Build.MANUFACTURER+" "+Build.MODEL).put("capturing",capturing)
                .put("width",frames.getWidth()).put("height",frames.getHeight()).put("fps",fps)
                .put("quality",quality).put("viewers",currentServer==null?0:currentServer.getViewerCount())
                .put("version","1.0.0").put("maxUploadBytes",TransferStore.MAX_BYTES);
        } catch(Exception ignored) { }
        return j.toString();
    }
    @Override public String filesJson() { return files.filesJson(); }
    @Override public LocalServer.Download openDownload(String id) throws IOException { return files.openDownload(id); }
    @Override public String receiveUpload(String name,long length,InputStream body) throws IOException {
        return files.receive(name,length,body,"browser").json().toString();
    }
}
