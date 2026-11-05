package com.hwz.nettv;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Disposable emulator installation only: these tests reset the test PIN and server settings. */
@RunWith(AndroidJUnit4.class)
public class PinPadTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final UiDevice device=UiDevice.getInstance(instrumentation);
    private MainActivity start(boolean savedPin) throws Exception {
        instrumentation.getTargetContext().getSharedPreferences("vault",0).edit().clear().commit();
        if(savedPin)new Vault(instrumentation.getTargetContext()).setPin("0123");
        return (MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void enter(String digits) { for(char digit:digits.toCharArray())device.pressKeyCode(KeyEvent.KEYCODE_0+digit-'0'); }
    private void message(MainActivity activity,String expected) throws Exception {
        if(device.wait(Until.hasObject(By.text(expected)),5000))return;
        instrumentation.runOnMainSync(()->activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        instrumentation.waitForIdleSync();device.takeScreenshot(new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null),"pin-failure.png"));
        StringBuilder actual=new StringBuilder();instrumentation.runOnMainSync(()->texts(activity.getWindow().getDecorView(),actual));
        fail("Expected "+expected+"; actual "+actual);
    }
    private static void texts(View view,StringBuilder out) {
        if(view instanceof android.widget.TextView)out.append(((android.widget.TextView)view).getText()).append("|");
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)texts(group.getChildAt(i),out);}
    }
    private void assertWaitingForFourth(MainActivity activity) {
        instrumentation.runOnMainSync(()->{
            PinPad input=pad(activity.getWindow().getDecorView());assertNotNull("three digits must stay on the PIN screen",input);
            try {java.lang.reflect.Field digits=PinPad.class.getDeclaredField("digits");digits.setAccessible(true);assertEquals(3,((String)digits.get(input)).length());}
            catch(ReflectiveOperationException e){throw new RuntimeException(e);}
        });
    }
    private static PinPad pad(View view) {
        if(view instanceof PinPad)return (PinPad)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){PinPad found=pad(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    @Test public void centeredKeypadAutoUnlocksOnlyAfterFourDigits() throws Exception {
        MainActivity activity=start(true);
        try {
            assertTrue(device.wait(Until.hasObject(By.text("欢迎回来")),5000));
            assertNull("no text input or system keyboard",device.findObject(By.clazz(android.widget.EditText.class)));
            instrumentation.runOnMainSync(()->{
                View panel=(View)pad(activity.getWindow().getDecorView()).getParent();int[] position=new int[2];panel.getLocationOnScreen(position);
                assertEquals(device.getDisplayWidth()/2,position[0]+panel.getWidth()/2,4);
                assertEquals(device.getDisplayHeight()/2,position[1]+panel.getHeight()/2,4);
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            });
            instrumentation.waitForIdleSync();device.takeScreenshot(new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null),"pin-pad.png"));
            instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
            device.findObject(By.desc("数字 9")).click();enter("999");
            message(activity,"密码不正确，请重试");
            enter("012");Thread.sleep(200);assertWaitingForFourth(activity);
            device.pressKeyCode(KeyEvent.KEYCODE_DEL);enter("2");
            assertWaitingForFourth(activity);enter("3");
            assertTrue("no unlock button needed",device.wait(Until.hasObject(By.text("连接 SMB 服务器")),5000));
            assertEquals(0,instrumentation.getTargetContext().getSharedPreferences("vault",0).getInt("attempts",0));
        }finally {instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void setupRequiresTwoMatchingFourDigitEntries() throws Exception {
        MainActivity activity=start(false);
        try {
            assertTrue(device.wait(Until.hasObject(By.text("设置访问密码")),5000));enter("0123");
            assertTrue(device.wait(Until.hasObject(By.text("再次输入 PIN")),5000));enter("0124");
            message(activity,"两次输入不一致，请重新设置");
            assertFalse(new Vault(instrumentation.getTargetContext()).hasPin());
            enter("0123");enter("0123");
            assertTrue(device.wait(Until.hasObject(By.text("连接 SMB 服务器")),5000));
            assertTrue(new Vault(instrumentation.getTargetContext()).unlock("0123"));
        }finally {instrumentation.runOnMainSync(activity::finish);}
    }
}
