package com.hwz.nettv;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class MediaFlowTest {
    @Test public void unlockBrowseImagePlayAndRelock() throws Exception {
        String url=InstrumentationRegistry.getArguments().getString("smbUrl"); assumeNotNull(url);
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.getTargetContext().getSharedPreferences("vault",0).edit().clear().commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiDevice device=UiDevice.getInstance(instrumentation);
        assertTrue(device.wait(Until.hasObject(By.text("设置访问密码")),10000));
        assertTrue("PIN uses a keypad, not an editor",edits(activity.getWindow().getDecorView()).isEmpty());
        enterPin(device,"0123");assertTrue(device.wait(Until.hasObject(By.text("再次输入 PIN")),5000));enterPin(device,"0123");
        assertTrue(device.wait(Until.hasObject(By.text("连接 SMB 服务器")),10000));
        instrumentation.runOnMainSync(() -> {
            java.util.List<android.widget.EditText> fields = edits(activity.getWindow().getDecorView());
            fields.get(0).setText(url); fields.get(1).setText("nettv"); fields.get(2).setText("Test1234");
            click(activity.getWindow().getDecorView(), "保存并连接");
        });
        assertTrue("SMB folder list",device.wait(Until.hasObject(By.textContains("中文 空格")),20000));
        device.findObject(By.textContains("中文 空格")).click();
        assertTrue(device.wait(Until.hasObject(By.textContains("图片 test.jpg")),15000));
        long previewDeadline=System.currentTimeMillis()+20000;
        java.util.concurrent.atomic.AtomicBoolean previewsReady=new java.util.concurrent.atomic.AtomicBoolean();
        while(System.currentTimeMillis()<previewDeadline && !previewsReady.get()) {
            instrumentation.runOnMainSync(()->previewsReady.set(hasPreview(activity.getWindow().getDecorView(), "图片 test.jpg") && hasPreview(activity.getWindow().getDecorView(), "测试 video.mkv")));
            Thread.sleep(200);
        }
        assertTrue("image and video cards have real previews",previewsReady.get());
        device.pressDPadRight(); device.pressDPadLeft();
        device.findObject(By.textContains("图片 test.jpg")).click();
        assertTrue(device.wait(Until.hasObject(By.desc("图片 test.jpg")),10000));
        assertNull("fullscreen image has no buttons",device.findObject(By.clazz(android.widget.Button.class)));
        assertTrue("image loaded",device.wait(Until.gone(By.text("正在加载图片…")),15000));
        assertNull(device.findObject(By.textContains("图片无法显示")));
        android.graphics.Rect imageBounds=device.findObject(By.desc("图片 test.jpg")).getVisibleBounds();
        assertEquals("image uses full screen width",device.getDisplayWidth(),imageBounds.width());
        assertEquals("image uses full screen height",device.getDisplayHeight(),imageBounds.height());
        device.pressDPadCenter();
        assertTrue("image controls use icons",device.wait(Until.hasObject(By.desc("下一张")),5000));
        assertEquals(3,device.findObjects(By.clazz(android.widget.ImageButton.class)).size());
        device.findObject(By.desc("下一张")).click();
        assertTrue(device.wait(Until.hasObject(By.desc("片段02 山间清晨.jpg")),10000));
        assertTrue(device.wait(Until.gone(By.text("正在加载图片…")),15000));
        instrumentation.runOnMainSync(()->activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        instrumentation.waitForIdleSync();Thread.sleep(500);
        device.takeScreenshot(new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null),"image-fullscreen.png"));
        instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.text("共享文件")),10000));
        device.findObject(By.textContains("测试 video.mkv")).click();
        assertTrue("player opens",device.wait(Until.hasObject(By.desc("暂停")),10000));
        assertEquals("six video icon controls",6,device.findObjects(By.clazz(android.widget.ImageButton.class)).size());
        Field vf=MainActivity.class.getDeclaredField("video"); vf.setAccessible(true);
        VideoPlayer view=(VideoPlayer)vf.get(activity); assertNotNull(view);
        Field pf=VideoPlayer.class.getDeclaredField("player"); pf.setAccessible(true);
        MediaPlayer player=(MediaPlayer)pf.get(view);
        long readyDeadline=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<readyDeadline && (!player.isPlaying() || player.getTime()<500))Thread.sleep(100);
        assertTrue("wait for playback before clicking pause",player.isPlaying());device.waitForIdle();
        device.findObject(By.desc("暂停")).click();
        assertTrue("pause switches to play icon",device.wait(Until.hasObject(By.desc("播放")),5000));
        device.findObject(By.desc("播放")).click();
        instrumentation.waitForIdleSync();
        long deadline=System.currentTimeMillis()+20000;
        while(System.currentTimeMillis()<deadline && player.getTime()<1500) Thread.sleep(200);
        assertTrue("VLC advances over SMB",player.getTime()>=1500);
        assertTrue("video surface attached",player.getVLCVout().areViewsAttached());
        org.videolan.libvlc.interfaces.IMedia playing=player.getMedia();
        assertTrue("video frames decoded",playing.getStats().decodedVideo>0); playing.release();
        assertEquals(MediaPlayer.ScaleType.SURFACE_BEST_FIT,player.getVideoScale());
        assertNotNull("effective video aspect is set",player.getAspectRatio());
        java.util.concurrent.atomic.AtomicReference<android.view.TextureView> surface = new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(() -> surface.set(texture(view)));
        assertNotNull("texture video output",surface.get());
        VideoAspectTest.assertCircleNotStretched(instrumentation,surface.get());
        device.pressDPadUp();
        instrumentation.runOnMainSync(()->activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        instrumentation.waitForIdleSync();
        device.takeScreenshot(new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null),"video-controls.png"));
        instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        player.setTime(7000);
        Thread.sleep(1200); assertTrue("SMB seek",player.getTime()>=6000);
        device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.text("共享文件")),10000));
        device.pressDPadRight(); instrumentation.waitForIdleSync();
        java.util.concurrent.atomic.AtomicBoolean focusedCard = new java.util.concurrent.atomic.AtomicBoolean();
        instrumentation.runOnMainSync(() -> {
            android.widget.GridView grid = grid(activity.getWindow().getDecorView());
            focusedCard.set(grid != null && grid.hasFocus() && grid.getSelectedView() != null && grid.getSelectedView().isSelected());
        });
        assertTrue("D-pad highlights the selected card",focusedCard.get());
        // Capture only synthetic fixture content, with the production privacy flag temporarily cleared.
        instrumentation.runOnMainSync(()->activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        instrumentation.waitForIdleSync(); Thread.sleep(700);
        device.takeScreenshot(new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null),"browser.png"));
        device.pressHome();
        instrumentation.getTargetContext().startActivity(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("locks after background",device.wait(Until.hasObject(By.text("欢迎回来")),10000));
        enterPin(device,"0123");
        assertTrue(device.wait(Until.hasObject(By.text("共享文件")),10000));
        instrumentation.runOnMainSync(activity::finish);
    }

    private static void enterPin(UiDevice device,String pin) {
        for(char digit:pin.toCharArray())device.pressKeyCode(android.view.KeyEvent.KEYCODE_0+digit-'0');
    }

    private static android.view.TextureView texture(android.view.View view) {
        if(view instanceof android.view.TextureView)return (android.view.TextureView)view;
        if(view instanceof android.view.ViewGroup) { android.view.ViewGroup group=(android.view.ViewGroup)view; for(int i=0;i<group.getChildCount();i++){ android.view.TextureView found=texture(group.getChildAt(i));if(found!=null)return found; } }
        return null;
    }
    private static android.widget.GridView grid(android.view.View view) {
        if(view instanceof android.widget.GridView)return (android.widget.GridView)view;
        if(view instanceof android.view.ViewGroup) { android.view.ViewGroup group=(android.view.ViewGroup)view; for(int i=0;i<group.getChildCount();i++) { android.widget.GridView found=grid(group.getChildAt(i)); if(found!=null)return found; } }
        return null;
    }
    private static boolean hasPreview(android.view.View view, String name) {
        if(view instanceof android.widget.ImageView && view.getTag() instanceof String && ((String)view.getTag()).endsWith(name))
            return ((android.widget.ImageView)view).getDrawable() instanceof android.graphics.drawable.BitmapDrawable;
        if(view instanceof android.view.ViewGroup) { android.view.ViewGroup group=(android.view.ViewGroup)view; for(int i=0;i<group.getChildCount();i++) if(hasPreview(group.getChildAt(i),name))return true; }
        return false;
    }
    private static java.util.List<android.widget.EditText> edits(android.view.View view) {
        java.util.List<android.widget.EditText> found = new java.util.ArrayList<>();
        if (view instanceof android.widget.EditText) found.add((android.widget.EditText)view);
        if (view instanceof android.view.ViewGroup) { android.view.ViewGroup group = (android.view.ViewGroup)view; for(int i=0;i<group.getChildCount();i++)found.addAll(edits(group.getChildAt(i))); }
        return found;
    }
    private static boolean click(android.view.View view, String label) {
        if (view instanceof android.widget.Button && ((android.widget.Button)view).getText().toString().equals(label)) return view.performClick();
        if (view instanceof android.view.ViewGroup) { android.view.ViewGroup group = (android.view.ViewGroup)view; for(int i=0;i<group.getChildCount();i++)if(click(group.getChildAt(i),label))return true; }
        return false;
    }
}
