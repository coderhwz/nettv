package com.hwz.nettv;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ThumbnailCacheTest {
    private Context context(String name) {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(target.getCacheDir(),name);directory.mkdirs();
        return new ContextWrapper(target) { @Override public File getCacheDir() {return directory;} };
    }
    private void clean(Context context) {
        File directory=new File(context.getCacheDir(),"thumbnails");File[] files=directory.listFiles();
        if(files!=null)for(File file:files)file.delete();directory.delete();context.getCacheDir().delete();
    }
    @Test public void encryptedPreviewSurvivesRestartAndRejectsTampering() throws Exception {
        Context context=context("thumbnail-test-crypto");Bitmap original=Bitmap.createBitmap(64,36,Bitmap.Config.ARGB_8888);original.eraseColor(Color.MAGENTA);
        try {
            ThumbnailCache cache=new ThumbnailCache(context);cache.write("preview-a",original);
            File file=new File(context.getCacheDir(),"thumbnails/preview-a");byte[] first=Files.readAllBytes(file.toPath());
            assertFalse("no PNG header on disk",first[0]==(byte)137 && first[1]==80 && first[2]==78 && first[3]==71);
            cache.write("preview-a",original);byte[] second=Files.readAllBytes(file.toPath());
            assertFalse("fresh random IV per write",Arrays.equals(Arrays.copyOf(first,12),Arrays.copyOf(second,12)));
            Bitmap restored=new ThumbnailCache(context).read("preview-a");assertNotNull(restored);assertEquals(Color.MAGENTA,restored.getPixel(0,0));restored.recycle();
            Files.write(new File(context.getCacheDir(),"thumbnails/preview-b").toPath(),second);
            assertNull("ciphertext is bound to its cache identity",cache.read("preview-b"));
            second[second.length-1]^=1;Files.write(file.toPath(),second);
            assertNull("corrupt ciphertext becomes a cache miss",cache.read("preview-a"));assertFalse(file.exists());
        } finally {original.recycle();clean(context);}
    }
    @Test public void diskCacheEvictsOldEntriesWithin64MiB() throws Exception {
        Context context=context("thumbnail-test-limit");Bitmap bitmap=Bitmap.createBitmap(64,36,Bitmap.Config.ARGB_8888);
        try {
            File directory=new File(context.getCacheDir(),"thumbnails");directory.mkdirs();
            for(int i=0;i<5;i++) {
                File file=new File(directory,"old-"+i);
                try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(16L*1024*1024);}
                file.setLastModified(1000L*(i+1));
            }
            ThumbnailCache cache=new ThumbnailCache(context);cache.write("latest",bitmap);
            long bytes=0;for(File file:directory.listFiles())bytes+=file.length();
            assertTrue(bytes<=64L*1024*1024);assertFalse(new File(directory,"old-0").exists());
            Bitmap latest=cache.read("latest");assertNotNull(latest);latest.recycle();
        } finally {bitmap.recycle();clean(context);}
    }
    @Test public void largeEncryptedPreviewSurvivesRestart() throws Exception {
        Context context=context("thumbnail-test-large");Bitmap original=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);
        java.util.Random random=new java.util.Random(42);int[] pixels=new int[640*360];
        for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0x1000000);original.setPixels(pixels,0,640,0,0,640,360);
        try {
            long start=android.os.SystemClock.elapsedRealtime();
            new ThumbnailCache(context).write("large-preview",original);
            long written=android.os.SystemClock.elapsedRealtime();
            Bitmap restored=new ThumbnailCache(context).read("large-preview");assertNotNull("large encrypted PNG decrypts",restored);
            android.os.Bundle report=new android.os.Bundle();report.putString("stream","Large encrypted preview write_ms="+(written-start)+" read_ms="+(android.os.SystemClock.elapsedRealtime()-written)+"\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
            try {assertTrue("preview pixels survive encryption",original.sameAs(restored));}finally {restored.recycle();}
        }finally {original.recycle();clean(context);}
    }
    @Test public void identityTracksFileChangesAndAccounts() throws Exception {
        try(SmbBrowser server=new SmbBrowser(new org.json.JSONObject().put("url","smb://nas/Media/").put("user","one"));
            SmbBrowser other=new SmbBrowser(new org.json.JSONObject().put("url","smb://nas/Media/").put("user","two"))) {
            SmbBrowser.Entry entry=new SmbBrowser.Entry("photo.jpg","smb://nas/Media/photo.jpg",false,100,1);
            String id=ThumbnailCache.id(server,entry);assertEquals(64,id.length());
            assertNotEquals(id,ThumbnailCache.id(other,entry));
            assertNotEquals(id,ThumbnailCache.id(server,new SmbBrowser.Entry(entry.name,entry.url,false,101,1)));
            assertNotEquals(id,ThumbnailCache.id(server,new SmbBrowser.Entry(entry.name,entry.url,false,100,2)));
        }
    }
}
