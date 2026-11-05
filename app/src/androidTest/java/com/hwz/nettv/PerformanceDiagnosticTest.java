package com.hwz.nettv;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import javax.crypto.SecretKeyFactory;
import static org.junit.Assert.*;

/** Diagnostics use saved SMB settings read-only and separate disposable PIN preferences. */
@RunWith(AndroidJUnit4.class)
public class PerformanceDiagnosticTest {
    @Test public void readExistingThumbnailCache() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("savedCache", "false").equals("true"));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();Perf.init(context);
        java.io.File[] files=new java.io.File(context.getCacheDir(),"thumbnails").listFiles();org.junit.Assume.assumeNotNull(files);
        ThumbnailCache cache=new ThumbnailCache(context);int count=0,misses=0;long total=0,maximum=0;
        for(java.io.File file:files) {
            if(!file.getName().matches("[0-9a-f]{64}"))continue;
            long start=android.os.SystemClock.elapsedRealtime();android.graphics.Bitmap bitmap=cache.read(file.getName());
            long elapsed=android.os.SystemClock.elapsedRealtime()-start;
            if(bitmap==null)misses++;else bitmap.recycle();total+=elapsed;maximum=Math.max(maximum,elapsed);
            if(++count==12)break;
        }
        assertTrue("device has cached previews",count>0);
        report("Existing encrypted previews="+count+" misses="+misses+" read_total_ms="+total+" average_ms="+(total/count)+" maximum_ms="+maximum);
        assertTrue("existing cache has usable previews",misses<count);
    }
    @Test public void readSavedServerImage() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("savedServer", "false").equals("true"));
        org.json.JSONObject config=new Vault(InstrumentationRegistry.getInstrumentation().getTargetContext()).loadServer();
        org.junit.Assume.assumeNotNull(config);
        try(SmbBrowser browser=new SmbBrowser(config)) {
            java.util.ArrayDeque<String> folders=new java.util.ArrayDeque<>(); folders.add(browser.root);
            int visited=0;
            while(!folders.isEmpty() && visited++<12) {
                for(SmbBrowser.Entry entry:browser.list(folders.remove())) {
                    if(entry.directory && folders.size()<20)folders.add(entry.url);
                    else if(entry.image()) {
                        long start=android.os.SystemClock.elapsedRealtime();
                        android.graphics.Bitmap preview=browser.thumbnail(entry);
                        long thumbnail=android.os.SystemClock.elapsedRealtime()-start; assertNotNull(preview);preview.recycle();
                        start=android.os.SystemClock.elapsedRealtime(); android.graphics.Bitmap full=browser.image(entry.url); assertNotNull(full);
                        report("SMB image bytes="+entry.size+" thumbnail_ms="+thumbnail+" full_ms="+(android.os.SystemClock.elapsedRealtime()-start)+" dimensions="+full.getWidth()+"x"+full.getHeight()); full.recycle();return;
                    }
                }
            }
            org.junit.Assert.fail("No image found within bounded server scan");
        }
    }
    private static void report(String value) { android.os.Bundle report=new android.os.Bundle();report.putString("stream",value+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(0,report); }
    @Test public void deviceBoundPinUnlockIsFast() throws Exception {
        android.content.Context context=new android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {return super.getSharedPreferences("nettv-diagnostic-vault",mode);}
        };
        android.content.SharedPreferences prefs=context.getSharedPreferences("vault",0);prefs.edit().clear().commit();
        try {
            Vault vault=new Vault(context);vault.setPin("0123");
            long start=android.os.SystemClock.elapsedRealtime();assertTrue(vault.unlock("0123"));
            long fast=android.os.SystemClock.elapsedRealtime()-start;report("Device-bound PIN verification ms="+fast);assertTrue("fast PIN unlock",fast<1000);
            assertFalse(vault.unlock("9999"));
            prefs.edit().remove("pin_check").remove("pin_iv").commit();
            assertTrue("old PIN migrates",vault.unlock("0123"));assertTrue(prefs.contains("pin_check"));
            assertTrue("migrated PIN unlocks",vault.unlock("0123"));
            byte[] damaged=android.util.Base64.decode(prefs.getString("pin_check",""),android.util.Base64.NO_WRAP);damaged[0]^=1;
            prefs.edit().putString("pin_check",android.util.Base64.encodeToString(damaged,android.util.Base64.NO_WRAP)).commit();
            assertTrue("damaged quick verifier recovers through original hash",vault.unlock("0123"));
            vault.setPin("4567");assertFalse(vault.unlock("0123"));assertTrue(vault.unlock("4567"));
        } finally {prefs.edit().clear().commit();}
    }
    @Test public void comparePinImplementations() throws Exception {
        long start=android.os.SystemClock.elapsedRealtime();
        byte[] expected=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new javax.crypto.spec.PBEKeySpec("0123".toCharArray(),new byte[16],120000,256)).getEncoded();
        long framework=android.os.SystemClock.elapsedRealtime()-start;
        start=android.os.SystemClock.elapsedRealtime();
        byte[] actual=PinHash.derive("0123",new byte[16]);
        assertArrayEquals(expected,actual);
        android.os.Bundle report=new android.os.Bundle(); report.putString("stream","PIN framework_ms="+framework+" lightweight_ms="+(android.os.SystemClock.elapsedRealtime()-start)+" provider="+SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").getProvider().getName()+"\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
    }
}
