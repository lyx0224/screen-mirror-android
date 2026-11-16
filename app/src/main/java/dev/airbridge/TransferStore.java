package dev.airbridge;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import dev.airbridge.server.LocalServer;

/** Only explicitly imported and received files live here, never arbitrary filesystem paths. */
public final class TransferStore {
    public static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_FILES = 200;
    private final File directory;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private int pending;
    private final AtomicLong revision = new AtomicLong();

    public static final class Entry {
        public final String id, name, source;
        public final long size, createdAt;
        final File file;
        Entry(String id, String name, String source, long size, long createdAt, File file) {
            this.id=id; this.name=name; this.source=source; this.size=size; this.createdAt=createdAt; this.file=file;
        }
        JSONObject json() {
            JSONObject j=new JSONObject();
            try { j.put("id",id).put("name",name).put("source",source).put("size",size).put("createdAt",createdAt); }
            catch (Exception ignored) { }
            return j;
        }
    }
    public TransferStore(Context context) {
        directory = new File(context.getFilesDir(), "transfers");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建收发箱");
        File[] files=directory.listFiles();
        if (files != null) for (File file:files) {
            if (file.getName().endsWith(".json")) {
                try (InputStream in=new FileInputStream(file)) {
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    copy(in,out,16*1024);
                    JSONObject j=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                    String id=j.getString("id");
                    if (!id.matches("[0-9a-f-]{36}")) continue;
                    File data=new File(directory,id+".bin");
                    if (data.isFile() && data.length()==j.getLong("size")) entries.put(id,new Entry(id,j.getString("name"),j.getString("source"),data.length(),j.getLong("createdAt"),data));
                } catch (Exception ignored) { file.delete(); }
            }
        }
        // A crashed/interrupted transfer is never published as a complete file.
        if (files != null) for (File file:files) {
            String name=file.getName();
            if (name.endsWith(".part") || (name.endsWith(".bin") && !entries.containsKey(name.substring(0,name.length()-4)))) file.delete();
        }
    }
    public List<Entry> list() {
        List<Entry> result=new ArrayList<>(entries.values());
        result.sort((a,b)->Long.compare(b.createdAt,a.createdAt));
        return result;
    }
    public long revision() { return revision.get(); }
    public String filesJson() {
        JSONArray files=new JSONArray();
        for (Entry e:list()) files.put(e.json());
        return "{\"files\":"+files+"}";
    }
    public Entry importUri(Context context, Uri uri) throws IOException {
        String name="手机文件";
        try (Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (c!=null && c.moveToFirst()) name=c.getString(0);
        }
        try (InputStream in=context.getContentResolver().openInputStream(uri)) {
            if (in==null) throw new IOException("无法读取所选文件");
            return receive(name,-1,in,"phone");
        }
    }
    public Entry receive(String name,long expected,InputStream in,String source) throws IOException {
        if (expected>MAX_BYTES) throw new IOException("单文件不能超过 1 GiB");
        synchronized(this) {
            if (entries.size()+pending>=MAX_FILES) throw new IOException("收发箱已满，请先移除一些文件");
            if (directory.getUsableSpace()<Math.max(expected,0)+16L*1024*1024) throw new IOException("手机存储空间不足");
            pending++;
        }
        String id=UUID.randomUUID().toString();
        File part=new File(directory,id+".part"), data=new File(directory,id+".bin"), meta=new File(directory,id+".json");
        boolean success=false;
        try {
            long size;
            try (FileOutputStream out=new FileOutputStream(part)) {
                size=copy(in,out,MAX_BYTES);
                if (expected>=0 && size!=expected) throw new IOException("传输中断，文件未保存");
                out.getFD().sync();
            }
            if (!part.renameTo(data)) throw new IOException("无法保存文件");
            Entry entry=new Entry(id,safeName(name),source,size,System.currentTimeMillis(),data);
            try (FileOutputStream out=new FileOutputStream(meta)) {
                out.write(entry.json().toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            entries.put(id,entry);
            revision.incrementAndGet();
            success=true;
            return entry;
        } finally {
            synchronized(this) { pending--; }
            if (!success) { part.delete(); data.delete(); meta.delete(); }
        }
    }
    public LocalServer.Download openDownload(String id) throws IOException {
        Entry e=entries.get(id);
        if (e==null) throw new FileNotFoundException("文件不存在");
        return new LocalServer.Download(e.name,"application/octet-stream",e.size,new FileInputStream(e.file));
    }
    public void export(Context context,String id,Uri destination) throws IOException {
        Entry e=entries.get(id);
        if(e==null) throw new FileNotFoundException("文件已移除");
        try (InputStream in=new FileInputStream(e.file); OutputStream out=context.getContentResolver().openOutputStream(destination,"wt")) {
            if (out==null) throw new IOException("无法写入所选位置");
            copy(in,out,MAX_BYTES);
        }
    }
    public void remove(String id) {
        Entry e=entries.remove(id);
        if(e!=null) { new File(directory,id+".json").delete(); e.file.delete(); revision.incrementAndGet(); }
    }
    private static String safeName(String value) {
        if(value==null) return "未命名文件";
        String name=value.replaceAll("[\\\\/\\p{Cntrl}]","_").trim();
        if(name.isEmpty() || name.equals(".") || name.equals("..")) name="未命名文件";
        return name.length()>180 ? name.substring(0,180) : name;
    }
    private static long copy(InputStream in,OutputStream out,long max) throws IOException {
        byte[] buffer=new byte[64*1024]; long total=0; int n;
        while((n=in.read(buffer))!=-1) {
            total+=n; if(total>max) throw new IOException("文件超过大小限制");
            out.write(buffer,0,n);
        }
        return total;
    }
}
