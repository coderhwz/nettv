package com.hwz.nettv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class ImageViewingTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void field(Object owner,String name,Object value) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private Bitmap waitForImage(MainActivity activity) throws Exception {
        long deadline=System.currentTimeMillis()+10000;AtomicReference<Bitmap> loaded=new AtomicReference<>();
        do {
            instrumentation.runOnMainSync(()->{try{loaded.set((Bitmap)field(activity,"bitmap"));}catch(Exception e){throw new RuntimeException(e);}});
            if(loaded.get()!=null)return loaded.get();Thread.sleep(20);
        }while(System.currentTimeMillis()<deadline);
        fail("full image did not load");return null;
    }
    private org.json.JSONObject config(String root) throws Exception { return new org.json.JSONObject().put("url",root).put("user","nettv").put("password","Test1234"); }
    @Test public void fullImageFitsScreenAndCancelsSourceReads() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("thumbnailFixture");assumeNotNull(root);
        try(SmbBrowser server=new SmbBrowser(config(root))) {
            SmbBrowser.Entry entry=server.list(root).stream().filter(SmbBrowser.Entry::image).findFirst().get();
            Bitmap image=server.image(entry.url,960,540,()->false);
            try {assertEquals(960,image.getWidth());assertEquals(540,image.getHeight());}finally {image.recycle();}
            java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
            try {server.image(entry.url,960,540,()->reads.incrementAndGet()>3);fail("cancelled full image should stop SMB reads");}
            catch(java.io.InterruptedIOException expected){ }
        }
    }
    @Test public void sharedHandleSupportsFormatsAndLargeHeaders() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("imageFormatsFixture");assumeNotNull(root);
        try(SmbBrowser server=new SmbBrowser(config(root))) {
            List<SmbBrowser.Entry> cases=server.list(root);assertEquals(6,cases.size());
            for(SmbBrowser.Entry entry:cases) {
                Bitmap loaded=server.image(entry.url,320,180,()->false);
                try {assertEquals(entry.name,320,loaded.getWidth());assertEquals(entry.name,180,loaded.getHeight());}
                finally {loaded.recycle();}
            }
        }
    }
    @Test public void nextImagePrefetchIsPromotedAndDoesNotScanDirectory() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("thumbnailFixture");assumeNotNull(root);
        try(SmbBrowser server=new SmbBrowser(config(root))) {
            List<SmbBrowser.Entry> pictures=server.list(root).stream().filter(SmbBrowser.Entry::image).collect(java.util.stream.Collectors.toList());
            MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1),started=new java.util.concurrent.CountDownLatch(2);
            try {
                Bitmap first=server.image(pictures.get(0).url,960,540,()->false);
                for(int i=0;i<2;i++)((java.util.concurrent.ExecutorService)field(activity,"imageWorker")).execute(()->{
                    started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                });
                assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
                instrumentation.runOnMainSync(()->{
                    try {
                        field(activity,"server",server);field(activity,"pictures",pictures);field(activity,"pictureIndex",0);
                        ((LruCache<String,Bitmap>)field(activity,"fullImages")).put(ThumbnailCache.id(server,pictures.get(0)),first);
                        java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showPicture");show.setAccessible(true);show.invoke(activity);
                        Object prefetch=field(activity,"prefetchLoad");assertNotNull(prefetch);
                        java.lang.reflect.Method change=MainActivity.class.getDeclaredMethod("changePicture",int.class);change.setAccessible(true);change.invoke(activity,1);
                        assertSame("next image reuses queued prefetch",prefetch,field(activity,"pictureLoad"));
                        assertNull(field(activity,"prefetchLoad"));
                    }catch(Exception e){throw new RuntimeException(e);}
                });
                release.countDown();Bitmap second=waitForImage(activity);assertNotSame(first,second);
                long deadline=System.currentTimeMillis()+10000;AtomicReference<Bitmap> next=new AtomicReference<>();
                do {
                    instrumentation.runOnMainSync(()->{
                        try {
                            LruCache<String,Bitmap> cache=(LruCache<String,Bitmap>)field(activity,"fullImages");
                            next.set(cache.get(ThumbnailCache.id(server,pictures.get(2))));
                            assertNull("prefetch stops after the next image",cache.get(ThumbnailCache.id(server,pictures.get(3))));
                        }catch(Exception e){throw new RuntimeException(e);}
                    });
                    if(next.get()!=null)break;Thread.sleep(20);
                }while(System.currentTimeMillis()<deadline);
                assertNotNull("next image is prefetched",next.get());
                instrumentation.runOnMainSync(()->{
                    try {
                        java.lang.reflect.Method change=MainActivity.class.getDeclaredMethod("changePicture",int.class);change.setAccessible(true);change.invoke(activity,1);
                        assertSame(next.get(),field(activity,"bitmap"));
                        android.widget.ImageView view=(android.widget.ImageView)field(activity,"pictureView");
                        assertEquals(pictures.get(2).name,view.getContentDescription());
                        assertNotNull("transition retains outgoing image, direction="+field(activity,"pictureDirection"),field(activity,"outgoingPicture"));
                        assertEquals("transition starts transparent",0f,view.getAlpha(),0.01f);
                        assertTrue("transition starts offset",view.getTranslationX()!=0);
                        assertEquals("image pixels keep full opacity",255,view.getImageAlpha());
                    }catch(Exception e){throw new RuntimeException(e);}
                });
                Thread.sleep(250);instrumentation.runOnMainSync(()->{
                    try{assertEquals(1f,((android.view.View)field(activity,"pictureView")).getAlpha(),0.01f);}
                    catch(Exception e){throw new RuntimeException(e);}
                });
                UiDevice.getInstance(instrumentation).pressHome();instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(()->{
                    try{assertNull(field(activity,"prefetchLoad"));assertEquals(0,((LruCache<?,?>)field(activity,"fullImages")).size());}
                    catch(Exception e){throw new RuntimeException(e);}
                });
            }finally {release.countDown();instrumentation.runOnMainSync(activity::finish);}
        }
    }
    @Test public void viewedImagesSwitchOfflineAndLockClearsMemoryCache() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("thumbnailFixture");assumeNotNull(root);
        try(SmbBrowser server=new SmbBrowser(config(root))) {
            List<SmbBrowser.Entry> pictures=server.list(root).stream().filter(SmbBrowser.Entry::image).limit(2).collect(java.util.stream.Collectors.toList());assertEquals(2,pictures.size());
            MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                instrumentation.runOnMainSync(()->{
                    try {
                        field(activity,"server",server);field(activity,"pictures",pictures);field(activity,"pictureIndex",0);
                        java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showPicture");show.setAccessible(true);show.invoke(activity);
                    }catch(Exception e){throw new RuntimeException(e);}
                });
                Bitmap first=waitForImage(activity);UiDevice device=UiDevice.getInstance(instrumentation);
                device.pressDPadRight();Bitmap second=waitForImage(activity);assertNotSame(first,second);
                SmbBrowser offline=new SmbBrowser(new org.json.JSONObject(config(root).toString()).put("password","intentionally-wrong"));
                instrumentation.runOnMainSync(()->{try{field(activity,"server",offline);}catch(Exception e){throw new RuntimeException(e);}});
                long start=System.currentTimeMillis();device.pressDPadLeft();assertSame("previous image reuses the decoded bitmap",first,waitForImage(activity));
                long elapsed=System.currentTimeMillis()-start;
                device.pressDPadRight();assertSame("next viewed image also reuses memory",second,waitForImage(activity));
                instrumentation.runOnMainSync(()->{
                    try {
                        LruCache<String,Bitmap> cache=(LruCache<String,Bitmap>)field(activity,"fullImages");
                        assertTrue(cache.size()<=16*1024*1024);
                    }catch(Exception e){throw new RuntimeException(e);}
                });
                android.os.Bundle report=new android.os.Bundle();report.putString("stream","Previously viewed full image restored offline in "+elapsed+" ms\n");instrumentation.sendStatus(0,report);
                device.pressHome();instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(()->{
                    try {assertEquals("background locks and clears full images",0,((LruCache<?,?>)field(activity,"fullImages")).size());}
                    catch(Exception e){throw new RuntimeException(e);}
                });
            }finally {instrumentation.runOnMainSync(activity::finish);}
        }
    }
}
