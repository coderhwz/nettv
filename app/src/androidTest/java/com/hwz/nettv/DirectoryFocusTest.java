package com.hwz.nettv;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class DirectoryFocusTest {
    private static void field(Object owner,String name,Object value) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static GridView grid(View view) {
        if(view instanceof GridView)return (GridView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){GridView found=grid(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private void selected(MainActivity activity,String name) throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        long deadline=System.currentTimeMillis()+10000;
        AtomicReference<String> actual=new AtomicReference<>();
        do {
            instrumentation.runOnMainSync(()->{
                GridView grid=grid(activity.getWindow().getDecorView());
                if(grid!=null && grid.hasFocus() && grid.getSelectedItem() instanceof SmbBrowser.Entry)actual.set(((SmbBrowser.Entry)grid.getSelectedItem()).name);
            });
            if(name.equals(actual.get()))return;
            Thread.sleep(100);
        }while(System.currentTimeMillis()<deadline);
        assertEquals("focus returns to the directory we entered",name,actual.get());
    }
    @Test public void remoteScrollAnimatesAndKeepsSelection() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->{
                try {
                    java.util.List<SmbBrowser.Entry> entries=new java.util.ArrayList<>();
                    for(int i=0;i<100;i++)entries.add(new SmbBrowser.Entry("Folder "+i,"smb://fixture/Media/"+i+"/",true,0));
                    field(activity,"currentUrl","smb://fixture/Media/");field(activity,"entries",entries);
                    java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showFiles");show.setAccessible(true);show.invoke(activity);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                GridView grid=grid(activity.getWindow().getDecorView());assertTrue(grid.hasFocus());
                boolean animated=false;
                for(int i=0;i<15;i++) {
                    int before=grid.getSelectedItemPosition();
                    grid.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_DOWN,new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN));
                    assertEquals(before+grid.getNumColumns(),grid.getSelectedItemPosition());
                    for(int child=0;child<grid.getChildCount();child++)if(grid.getChildAt(child).getTranslationY()!=0)animated=true;
                }
                assertTrue("remote row scrolling animates card positions",animated);
            });
            Thread.sleep(250);
            instrumentation.runOnMainSync(()->{
                GridView grid=grid(activity.getWindow().getDecorView());
                for(int i=0;i<grid.getChildCount();i++)assertEquals(0f,grid.getChildAt(i).getTranslationY(),0.01f);
            });
        }finally {instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void backAndParentRestoreNestedFolderFocus() throws Exception {
        String root=InstrumentationRegistry.getArguments().getString("navigationFixture");assumeNotNull(root);
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();UiDevice device=UiDevice.getInstance(instrumentation);
        SmbBrowser server=new SmbBrowser(new org.json.JSONObject().put("url",root).put("user","nettv").put("password","Test1234"));
        java.util.List<SmbBrowser.Entry> entries=server.list(root);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->{
                try {
                    field(activity,"server",server);field(activity,"currentUrl",root);field(activity,"entries",entries);
                    java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showFiles");show.setAccessible(true);show.invoke(activity);
                    ((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            assertTrue(device.wait(Until.hasObject(By.text("B folder")),10000));device.findObject(By.text("B folder")).click();
            assertTrue(device.wait(Until.hasObject(By.text("Y nested")),10000));device.findObject(By.text("Y nested")).click();
            assertTrue(device.wait(Until.hasObject(By.text("C deep")),10000));
            java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field(activity,"worker");
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1),started=new java.util.concurrent.CountDownLatch(1);
            worker.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            try {
                // Parent contents and focus must restore even while directory refresh cannot run.
                device.pressBack();selected(activity,"Y nested");
            }finally {release.countDown();}
            device.findObject(By.textContains("上一级")).click();selected(activity,"B folder");
            // Open the restored selection using only the remote, then return again.
            device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("Y nested")),10000));
            device.pressBack();selected(activity,"B folder");
            worker.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);instrumentation.waitForIdleSync();
            AtomicReference<GridView> before=new AtomicReference<>();
            instrumentation.runOnMainSync(()->{
                before.set(grid(activity.getWindow().getDecorView()));
                try {java.lang.reflect.Method refresh=MainActivity.class.getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(activity);}
                catch(Exception e){throw new RuntimeException(e);}
            });
            worker.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->assertSame("unchanged refresh keeps cards and preview jobs",before.get(),grid(activity.getWindow().getDecorView())));
        }finally {instrumentation.runOnMainSync(activity::finish);}
    }
}
