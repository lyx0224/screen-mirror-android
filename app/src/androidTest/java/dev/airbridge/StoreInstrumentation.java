package dev.airbridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import dev.airbridge.server.FrameHub;
import dev.airbridge.server.LocalServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Device-side integration tests. Never request or start screen capture. No third-party runner. */
public final class StoreInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle status=new Bundle();
        status.putString("id","InstrumentationTestRunner"); status.putInt("numtests",1); status.putInt("current",1);
        status.putString("class",getClass().getName()); status.putString("test","storageAndHttpOnAndroid"); sendStatus(1,status);
        File sandbox=new File(getTargetContext().getCacheDir(),"test-"+UUID.randomUUID());
        Bundle result=new Bundle();
        try {
            if(!sandbox.mkdirs()) throw new IOException("Cannot create test directory");
            verify(sandbox);
            status.putString("stream","Passed "+checks+" Android storage / HTTP checks.\n"); sendStatus(0,status);
            result.putString("stream","\nOK (1 test, "+checks+" checks)\n");
        } catch(Throwable failure) {
            StringWriter trace=new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            status.putString("stack",trace.toString()); sendStatus(-2,status); result.putString("stream",trace.toString());
        } finally { erase(sandbox); }
        finish(Activity.RESULT_OK,result);
    }
    private void verify(File directory) throws Exception {
        Context context=new ContextWrapper(getTargetContext()) { @Override public File getFilesDir() { return directory; } };
        TransferStore store=new TransferStore(context);
        byte[] bytes=new byte[2*1024*1024]; new Random(7).nextBytes(bytes);
        TransferStore.Entry entry=store.receive("../中文\nfile.bin",bytes.length,new ByteArrayInputStream(bytes),"browser");
        check(entry.name.equals(".._中文_file.bin"),"safe file name");
        try(InputStream in=store.openDownload(entry.id).stream) { check(Arrays.equals(read(in),bytes),"byte-exact download"); }
        TransferStore reload=new TransferStore(context);
        check(reload.list().size()==1 && reload.list().get(0).name.equals(entry.name),"persistent metadata");
        try { reload.receive("truncated",100,new ByteArrayInputStream(new byte[4]),"browser"); throw new AssertionError("Truncation accepted"); }
        catch(IOException expected) { check(reload.list().size()==1,"truncated transfer unpublished"); }
        check(new File(directory,"transfers").list().length==2,"partial files cleaned");
        try { reload.receive("oversize",TransferStore.MAX_BYTES+1,new ByteArrayInputStream(new byte[0]),"browser"); throw new AssertionError("Oversize accepted"); }
        catch(IOException expected) { check(reload.list().size()==1,"oversized body rejected"); }
        check(reload.receive("empty",0,new ByteArrayInputStream(new byte[0]),"browser").size==0,"empty file supported");
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<16;i++) tasks.add(pool.submit(()->{try{reload.receive("parallel",4,new ByteArrayInputStream(new byte[4]),"browser");}catch(IOException e){throw new RuntimeException(e);}}));
            for(Future<?> task:tasks) task.get(10,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        check(reload.list().size()==18 && reload.revision()==17,"concurrent publication");
        reload.remove(entry.id);
        check(new TransferStore(context).list().size()==17,"persistent removal");
        File box=new File(directory,"transfers");
        new File(box,"orphan.bin").createNewFile(); new File(box,"unfinished.part").createNewFile();
        new TransferStore(context);
        check(!new File(box,"orphan.bin").exists()&&!new File(box,"unfinished.part").exists(),"crash cleanup");
        LocalServer.Backend backend=new LocalServer.Backend() {
            public InputStream openAsset(String path) throws IOException { return getTargetContext().getAssets().open("web/"+path); }
            public String statusJson() { return "{\"capturing\":false}"; }
            public String filesJson() { return reload.filesJson(); }
            public LocalServer.Download openDownload(String id) throws IOException { return reload.openDownload(id); }
            public String receiveUpload(String name,long length,InputStream body) throws IOException { return reload.receive(name,length,body,"browser").json().toString(); }
        };
        try(LocalServer server=new LocalServer(0,"123456",backend,new FrameHub())) {
            server.start(); int port=server.getPort();
            Reply page=request(port,"GET","/",null,null);
            check(page.status==200 && new String(page.body,StandardCharsets.UTF_8).contains("AirBridge"),"bundled web assets");
            check(request(port,"GET","/api/status",null,null).status==401,"unauthorized denied");
            Reply pair=request(port,"POST","/api/pair",null,"123456".getBytes(StandardCharsets.US_ASCII));
            check(pair.status==200,"pair on Android runtime");
            String cookie=pair.headers.get("set-cookie").split(";",2)[0];
            Reply upload=request(port,"POST","/api/upload?name="+URLEncoder.encode("设备测试.bin","UTF-8"),cookie,bytes);
            check(upload.status==201,"HTTP upload on Android");
            String id=new org.json.JSONObject(new String(upload.body,StandardCharsets.UTF_8)).getString("id");
            Reply download=request(port,"GET","/api/files/"+id,cookie,null);
            check(download.status==200 && Arrays.equals(download.body,bytes),"HTTP binary roundtrip on Android");
        }
    }
    private static final class Reply {
        int status; byte[] body; Map<String,String> headers=new HashMap<>();
    }
    // Raw HTTP tests the inbound server without changing the shipping app's outbound cleartext policy.
    private static Reply request(int port,String method,String path,String cookie,byte[] body) throws Exception {
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",port),5000); socket.setSoTimeout(5000);
            OutputStream out=socket.getOutputStream();
            String head=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nX-AirBridge: 1\r\n"
                +(cookie==null?"":"Cookie: "+cookie+"\r\n")
                +(body==null?"":"Content-Length: "+body.length+"\r\n")+"\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII)); if(body!=null)out.write(body); out.flush();
            byte[] response=read(socket.getInputStream()); int boundary=-1;
            for(int i=0;i<response.length-3;i++) if(response[i]==13&&response[i+1]==10&&response[i+2]==13&&response[i+3]==10){boundary=i;break;}
            if(boundary<0) throw new IOException("Missing HTTP header");
            String[] lines=new String(response,0,boundary,StandardCharsets.ISO_8859_1).split("\r\n");
            Reply reply=new Reply(); reply.status=Integer.parseInt(lines[0].split(" ")[1]);
            for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');reply.headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim());}
            reply.body=Arrays.copyOfRange(response,boundary+4,response.length);return reply;
        }
    }
    private static byte[] read(InputStream in) throws IOException {
        try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[65536];int n;while((n=source.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();
        }
    }
    private void check(boolean value,String name) { if(!value)throw new AssertionError(name);checks++; }
    private static void erase(File path) { File[] children=path.listFiles();if(children!=null)for(File child:children)erase(child);path.delete(); }
}
