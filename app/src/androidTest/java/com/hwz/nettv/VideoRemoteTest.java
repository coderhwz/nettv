package com.hwz.nettv;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class VideoRemoteTest {
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private void at(MediaPlayer player,long target) throws Exception {
        long deadline=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<deadline) {
            if(Math.abs(player.getTime()-target)<1500)return;
            Thread.sleep(50);
        }
        assertEquals("remote seeks to requested time",target,player.getTime(),1500);
    }
    @Test public void leftRightSeekWithHiddenAndVisibleOverlayAndControlsRemainReachable() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("smbUrl");assumeNotNull(root);
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();UiDevice device=UiDevice.getInstance(instrumentation);
        SmbBrowser server=new SmbBrowser(new org.json.JSONObject().put("url",root).put("user","nettv").put("password","Test1234"));
        SmbBrowser.Entry entry=server.list(server.list(root).get(0).url).stream().filter(item->item.name.equals("测试 video.mkv")).findFirst().get();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->{
                try {
                    Field saved=MainActivity.class.getDeclaredField("server");saved.setAccessible(true);saved.set(activity,server);
                    java.lang.reflect.Method play=MainActivity.class.getDeclaredMethod("play",SmbBrowser.Entry.class);play.setAccessible(true);play.invoke(activity,entry);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            VideoPlayer video=(VideoPlayer)field(activity,"video");MediaPlayer player=(MediaPlayer)field(video,"player");
            long deadline=System.currentTimeMillis()+15000;
            while(System.currentTimeMillis()<deadline && (!player.isSeekable() || player.getTime()<500))Thread.sleep(100);
            assertTrue(player.isSeekable());device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE);
            player.setTime(2000);at(player,2000);
            View controls=(View)field(video,"controls");
            instrumentation.runOnMainSync(()->controls.setVisibility(View.INVISIBLE));
            device.pressDPadRight();at(player,12000);
            instrumentation.runOnMainSync(()->assertEquals("seek also reveals progress",View.VISIBLE,controls.getVisibility()));
            device.pressDPadLeft();at(player,2000);
            // Explicitly enter the controls: left/right can still reach audio/subtitles buttons.
            device.pressDPadUp();device.pressDPadDown();
            device.pressDPadRight();at(player,2000);
            instrumentation.runOnMainSync(()->assertEquals("前进 10 秒",video.findFocus().getContentDescription()));
            device.pressDPadCenter();at(player,12000);
        }finally {instrumentation.runOnMainSync(activity::finish);}
    }
}
