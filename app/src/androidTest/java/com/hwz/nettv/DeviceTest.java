package com.hwz.nettv;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceTest {
    @Test public void smbFixtureListsAndReadsImage() throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("smbUrl"); org.junit.Assume.assumeNotNull(url);
        org.json.JSONObject config = new org.json.JSONObject().put("url",url).put("user","nettv").put("password","Test1234");
        try (SmbBrowser server = new SmbBrowser(config)) {
            java.util.List<SmbBrowser.Entry> root = server.list(url); assertEquals(1,root.size());
            java.util.List<SmbBrowser.Entry> files = server.list(root.get(0).url); assertTrue(files.size()>=2);
            for(SmbBrowser.Entry entry : files) if(entry.video()) { android.graphics.Bitmap preview=server.thumbnail(entry); assertNotNull("video frame thumbnail",preview); preview.recycle(); }
            for(SmbBrowser.Entry entry : files) if(entry.image()) {
                java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
                try { server.thumbnail(entry, () -> reads.incrementAndGet() > 2); fail("cancelled preview should stop reading"); }
                catch (java.io.InterruptedIOException expected) { }
                android.graphics.Bitmap image=server.image(entry.url); assertNotNull(image); image.recycle();
                android.graphics.Bitmap preview=server.thumbnail(entry);assertNotNull(preview);
                assertTrue("preview has enough pixels for a 640x360 card",preview.getWidth()>=640 && preview.getHeight()>=360);preview.recycle();
            }
        }
    }
    @Test public void pathsKeepChineseSpacesAndEncodeForVlc() {
        assertEquals("smb://nas/共享/电影 文件/", SmbBrowser.normalizeUrl("smb://nas/共享/电影%20文件"));
        assertEquals("smb://nas/Media/a%20b%23c%25.mkv", SmbBrowser.mediaUri("smb://nas/Media/a b#c%.mkv").toString());
        try { SmbBrowser.normalizeUrl("smb://user:pass@nas/Media"); fail(); } catch (IllegalArgumentException expected) { }
        try { SmbBrowser.normalizeUrl("smb://nas/"); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void vaultEncryptsCredentialsAndLimitsPinAttempts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // This test resets only the disposable test installation.
        context.getSharedPreferences("vault",0).edit().clear().commit();
        Vault vault = new Vault(context); vault.setPin("0123");
        assertTrue(vault.unlock("0123"));
        org.json.JSONObject server = new org.json.JSONObject().put("url","smb://nas/Media/").put("password","secret-value");
        vault.saveServer(server); assertEquals("secret-value",vault.loadServer().getString("password"));
        assertFalse(context.getSharedPreferences("vault",0).getString("server","").contains("secret-value"));
        for (int i=0;i<5;i++) assertFalse(vault.unlock("9999"));
        assertTrue(vault.remainingLockSeconds()>0); assertFalse(vault.unlock("0123"));
        context.getSharedPreferences("vault",0).edit().clear().commit();
    }
}
