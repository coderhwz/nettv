package com.hwz.nettv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class ThumbnailLoadingTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private static void field(Object owner,String name,Object value) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private MainActivity show(SmbBrowser server,List<SmbBrowser.Entry> entries) throws Exception {
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()-> {
            try {
                field(activity,"server",server);field(activity,"currentUrl",server.root);field(activity,"entries",entries);
                java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showFiles");show.setAccessible(true);show.invoke(activity);
                ((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);
            } catch(Exception e){throw new RuntimeException(e);}
        });return activity;
    }
    private static GridView grid(View view) {
        if(view instanceof GridView)return (GridView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){GridView found=grid(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private Map<Integer,ImageView> visible(MainActivity activity) {
        Map<Integer,ImageView> result=new LinkedHashMap<>();GridView grid=grid(activity.getWindow().getDecorView());
        if(grid==null)return result;
        for(int i=0;i<grid.getChildCount();i++) {
            View card=grid.getChildAt(i);if(!card.getGlobalVisibleRect(new Rect()))continue;
            result.put(grid.getFirstVisiblePosition()+i,(ImageView)((FrameLayout)((LinearLayout)card).getChildAt(0)).getChildAt(0));
        }return result;
    }
    private int waitForPreviews(MainActivity activity) throws Exception {
        long deadline=System.currentTimeMillis()+25000;int maximum=0;
        while(System.currentTimeMillis()<deadline) {
            java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger();
            instrumentation.runOnMainSync(()-> {
                Map<Integer,ImageView> visible=visible(activity);ready.set(!visible.isEmpty() && visible.values().stream().allMatch(view->view.getDrawable() instanceof BitmapDrawable));
                try {Object thumbnails=field(activity,"thumbnails");active.set(((ThreadPoolExecutor)field(thumbnails,"images")).getActiveCount()+((ThreadPoolExecutor)field(thumbnails,"videos")).getActiveCount());}catch(Exception e){throw new RuntimeException(e);}
            });maximum=Math.max(maximum,active.get());if(ready.get())return maximum;Thread.sleep(20);
        }fail("Visible previews did not load");return maximum;
    }
    private void waitForDisk(File directory,Set<String> ids) throws Exception {
        long deadline=System.currentTimeMillis()+10000;
        do {
            boolean ready=true;for(String id:ids)ready &= new File(directory,id).isFile();
            if(ready)return;Thread.sleep(20);
        }while(System.currentTimeMillis()<deadline);
        fail("Background cache writes did not finish");
    }
    @Test public void onlyVisibleCardsGenerateAndEncryptedCacheWorksOffline() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("thumbnailFixture");assumeNotNull(root);
        File directory=new File(instrumentation.getTargetContext().getCacheDir(),"thumbnails");
        File[] old=directory.listFiles();if(old!=null)for(File file:old)file.delete();
        org.json.JSONObject config=new org.json.JSONObject().put("url",root).put("user","nettv").put("password","Test1234");
        SmbBrowser server=new SmbBrowser(config);List<SmbBrowser.Entry> entries=server.list(root);assertTrue(entries.size()>24);
        MainActivity activity=show(server,entries);
        try {
            int parallel=waitForPreviews(activity);assertTrue("multiple previews generate concurrently",parallel>=2);
            AtomicReference<Map<Integer,ImageView>> first=new AtomicReference<>();instrumentation.runOnMainSync(()->first.set(visible(activity)));
            Set<String> seen=new HashSet<>();for(int position:first.get().keySet())seen.add(ThumbnailCache.id(server,entries.get(position)));
            waitForDisk(directory,seen);for(File file:directory.listFiles())assertTrue("offscreen card did not generate: "+file.getName(),seen.contains(file.getName()));
            assertEquals("all visible cards cached",seen.size(),directory.listFiles().length);
            instrumentation.runOnMainSync(()->grid(activity.getWindow().getDecorView()).setSelection(entries.size()-1));
            instrumentation.waitForIdleSync();
            waitForPreviews(activity);
            AtomicReference<Map<Integer,ImageView>> last=new AtomicReference<>();instrumentation.runOnMainSync(()->last.set(visible(activity)));
            for(int position:last.get().keySet())seen.add(ThumbnailCache.id(server,entries.get(position)));
            waitForDisk(directory,seen);for(File file:directory.listFiles())assertTrue("unseen middle cards did not generate",seen.contains(file.getName()));
            android.os.Bundle report=new android.os.Bundle();report.putString("stream","Visible-only cache: "+directory.listFiles().length+" / "+entries.size()+" media; concurrent jobs="+parallel+"\n");instrumentation.sendStatus(0,report);
        } finally {instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();}
        // A new activity has no memory previews; wrong SMB credentials make a network fallback fail.
        SmbBrowser offline=new SmbBrowser(new org.json.JSONObject(config.toString()).put("password","intentionally-wrong"));
        long start=System.currentTimeMillis();MainActivity restored=show(offline,entries);
        try {
            waitForPreviews(restored);long millis=System.currentTimeMillis()-start;
            assertTrue("disk cache restores without SMB generation",millis<3000);
            android.os.Bundle report=new android.os.Bundle();report.putString("stream","Fresh activity with unavailable SMB credentials restored previews in "+millis+" ms\n");instrumentation.sendStatus(0,report);
        } finally {instrumentation.runOnMainSync(restored::finish);}
    }

    private android.content.Context isolated(String name) {
        android.content.Context target=instrumentation.getTargetContext();File directory=new File(target.getCacheDir(),name);directory.mkdirs();
        return new android.content.ContextWrapper(target) { @Override public File getCacheDir(){return directory;} };
    }
    private void clean(android.content.Context context) {
        File directory=new File(context.getCacheDir(),"thumbnails");File[] files=directory.listFiles();
        if(files!=null)for(File file:files)file.delete();directory.delete();context.getCacheDir().delete();
    }
    private void awaitViews(Collection<ImageView> views) throws Exception {
        long deadline=System.currentTimeMillis()+8000;
        do {
            java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(()->ready.set(views.stream().allMatch(view->view.getDrawable() instanceof BitmapDrawable)));
            if(ready.get())return;Thread.sleep(20);
        }while(System.currentTimeMillis()<deadline);
        fail("Previews were blocked by another stage");
    }
    @Test public void cachedPreviewsDoNotWaitForBusyImageOrVideoWorkers() throws Exception {
        android.content.Context context=isolated("thumbnail-test-priority");
        CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(4);
        MediaThumbnails thumbnails=new MediaThumbnails(context);
        try(SmbBrowser server=new SmbBrowser(new org.json.JSONObject().put("url","smb://127.0.0.1/NoNetwork/"))) {
            for(String pool:Arrays.asList("images","videos"))for(int i=0;i<2;i++)((ThreadPoolExecutor)field(thumbnails,pool)).execute(()->{
                started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            assertTrue(started.await(3,TimeUnit.SECONDS));
            Map<ImageView,SmbBrowser.Entry> visible=new LinkedHashMap<>();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888);
            try {
                for(String name:Arrays.asList("cached.jpg","cached.mp4")) {
                    SmbBrowser.Entry entry=new SmbBrowser.Entry(name,server.root+name,false,100,1);
                    new ThumbnailCache(context).write(ThumbnailCache.id(server,entry),bitmap);
                    ImageView view=new ImageView(context);view.setTag(entry.url);visible.put(view,entry);
                }
            }finally {bitmap.recycle();}
            long start=System.currentTimeMillis();instrumentation.runOnMainSync(()->thumbnails.visible(server,visible));
            awaitViews(visible.keySet());assertTrue("cached cards bypass all busy network workers",System.currentTimeMillis()-start<2000);
        } finally {release.countDown();instrumentation.runOnMainSync(thumbnails::close);clean(context);}
    }
    @Test public void generatedPreviewDisplaysWhileCacheWriterIsBlocked() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("thumbnailFixture");assumeNotNull(root);
        android.content.Context context=isolated("thumbnail-test-writeback");
        MediaThumbnails thumbnails=new MediaThumbnails(context);CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(1);
        try(SmbBrowser server=new SmbBrowser(new org.json.JSONObject().put("url",root).put("user","nettv").put("password","Test1234"))) {
            SmbBrowser.Entry entry=server.list(root).stream().filter(SmbBrowser.Entry::image).findFirst().get();
            ((ThreadPoolExecutor)field(thumbnails,"writers")).execute(()->{
                started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            });assertTrue(started.await(3,TimeUnit.SECONDS));
            ImageView view=new ImageView(context);view.setTag(entry.url);
            instrumentation.runOnMainSync(()->thumbnails.visible(server,Collections.singletonMap(view,entry)));
            awaitViews(Collections.singleton(view));
            File directory=new File(context.getCacheDir(),"thumbnails");
            assertFalse("preview displays before any disk commit",new File(directory,ThumbnailCache.id(server,entry)).exists());
            instrumentation.runOnMainSync(()->{
                android.graphics.Bitmap preview=((BitmapDrawable)view.getDrawable()).getBitmap();
                assertEquals(640,preview.getWidth());assertEquals(360,preview.getHeight());
            });
            release.countDown();waitForDisk(directory,Collections.singleton(ThumbnailCache.id(server,entry)));
        } finally {release.countDown();instrumentation.runOnMainSync(thumbnails::close);clean(context);}
    }
}
