package com.hwz.nettv;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Plays only a supplied synthetic clip, without changing saved settings. */
@RunWith(AndroidJUnit4.class)
public class VideoAspectTest {
    @Test public void syntheticVideoKeepsAspect() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("aspectFixture");org.junit.Assume.assumeNotNull(root);
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SmbBrowser server=new SmbBrowser(new org.json.JSONObject().put("url",root).put("user","nettv").put("password","Test1234"));
        java.util.concurrent.atomic.AtomicReference<VideoPlayer> view=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            instrumentation.runOnMainSync(()-> {view.set(new VideoPlayer(activity,server,new SmbBrowser.Entry("Synthetic aspect clip",root+"中文 空格/"+InstrumentationRegistry.getArguments().getString("videoFile","测试 video.mkv"),false,0),false,()->{}));activity.setContentView(view.get());((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.get().getWindowToken(),0);});
            Thread.sleep(8000);
            java.util.concurrent.atomic.AtomicReference<TextureView> surface=new java.util.concurrent.atomic.AtomicReference<>();
            instrumentation.runOnMainSync(()->surface.set(texture(view.get())));
            assertNotNull(surface.get());assertTrue(surface.get().isAvailable());
            java.lang.reflect.Field field=VideoPlayer.class.getDeclaredField("player");field.setAccessible(true);
            org.videolan.libvlc.MediaPlayer player=(org.videolan.libvlc.MediaPlayer)field.get(view.get());
            org.videolan.libvlc.interfaces.IMedia.VideoTrack track=player.getCurrentVideoTrack();
            android.os.Bundle details=new android.os.Bundle();details.putString("stream","time="+player.getTime()+" duration="+player.getLength()+" track="+(track==null?"none":track.width+"x"+track.height+" sar="+track.sarNum+":"+track.sarDen+" orientation="+track.orientation)+"\n");instrumentation.sendStatus(0,details);

            org.videolan.libvlc.interfaces.IMedia media=player.getMedia();assertTrue(media.getStats().decodedVideo>0);media.release();
            instrumentation.runOnMainSync(()->activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE));
            instrumentation.waitForIdleSync(); Thread.sleep(500);
            android.graphics.Bitmap display=instrumentation.getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null),"video-aspect.png"))) {display.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            assertCircleNotStretched(instrumentation,display);
            android.os.Bundle report=new android.os.Bundle();report.putString("stream","Video output="+surface.get().getWidth()+"x"+surface.get().getHeight()+" decoded and playing\n");instrumentation.sendStatus(0,report);
        } finally {
            instrumentation.runOnMainSync(()-> {if(view.get()!=null)view.get().release();activity.finish();});server.close();
        }
    }
    static void assertCircleNotStretched(Instrumentation instrumentation, TextureView view) {
        java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> snapshot=new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(()->snapshot.set(view.getBitmap()));
        assertCircleNotStretched(instrumentation,snapshot.get());
    }
    static void assertCircleNotStretched(Instrumentation instrumentation, android.graphics.Bitmap bitmap) {
        assertNotNull(bitmap);
        int left=bitmap.getWidth(),right=-1,top=bitmap.getHeight(),bottom=-1;
        int[] row=new int[bitmap.getWidth()];
        for(int y=0;y<bitmap.getHeight();y+=2) {
            bitmap.getPixels(row,0,row.length,0,y,row.length,1);
            for(int x=0;x<row.length;x+=2) {
                int color=row[x];int r=android.graphics.Color.red(color),g=android.graphics.Color.green(color),b=android.graphics.Color.blue(color);
                if(r>50 && r<150 && g>170 && b>140 && g-r>80 && g-b>10) {left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
            }
        }
        bitmap.recycle();assertTrue("synthetic circle is visible",right>left && bottom>top);
        float ratio=(right-left)/(float)(bottom-top);
        android.os.Bundle report=new android.os.Bundle();report.putString("stream","Rendered circle aspect="+ratio+" (expected 1.0)\n");instrumentation.sendStatus(0,report);
        assertEquals("video circle remains round",1f,ratio,0.06f);
    }
    private static TextureView texture(View view) {
        if(view instanceof TextureView)return (TextureView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextureView found=texture(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
}
