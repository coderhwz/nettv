package com.hwz.nettv;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Four-digit TV input without an editor or the system keyboard. */
final class PinPad extends LinearLayout {
    private final TextView[] dots = new TextView[4];
    private final ArrayList<Button> buttons = new ArrayList<>();
    private String digits = "";
    private boolean busy;
    private Consumer<String> complete;
    PinPad(Context context) {
        super(context); setOrientation(VERTICAL); setGravity(Gravity.CENTER);
        LinearLayout indicators = new LinearLayout(context); indicators.setGravity(Gravity.CENTER);
        for (int i = 0; i < dots.length; i++) {
            TextView dot = new TextView(context); dot.setTextSize(30); dot.setTextColor(0xFFEAF2F7); dot.setGravity(Gravity.CENTER);
            dots[i] = dot; indicators.addView(dot, new LinearLayout.LayoutParams(dp(52), dp(58)));
        }
        addView(indicators);
        String[] keys = {"1","2","3","4","5","6","7","8","9","清空","0","删除"};
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(context); line.setGravity(Gravity.CENTER);
            for (int column = 0; column < 3; column++) {
                String value = keys[row * 3 + column];
                Button button = new Button(context); button.setText(value); button.setTextSize(value.length() == 1 ? 24 : 16);
                button.setTextColor(0xFFEAF2F7); button.setAllCaps(false); button.setBackgroundResource(R.drawable.focusable);
                button.setContentDescription(value.length() == 1 ? "数字 " + value : value);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(88), dp(46)); params.setMargins(dp(4),dp(4),dp(4),dp(4));
                line.addView(button,params); buttons.add(button);
                button.setOnClickListener(v -> {
                    if (value.equals("清空")) reset();
                    else if (value.equals("删除")) delete();
                    else digit(value);
                });
            }
            addView(line);
        }
        update();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void onComplete(Consumer<String> callback) { complete = callback; }
    void focus() { buttons.get(4).requestFocusFromTouch(); }
    void busy(boolean value) { busy = value; for (Button button : buttons) button.setEnabled(!value); }
    void reset() { digits = ""; update(); }
    private void delete() { if (!busy && !digits.isEmpty()) { digits = digits.substring(0,digits.length()-1); update(); } }
    private void digit(String value) {
        if (busy || digits.length() == 4) return;
        digits += value; update();
        if (digits.length() == 4 && complete != null) complete.accept(digits);
    }
    private void update() {
        for (int i = 0; i < dots.length; i++) { dots[i].setText(i < digits.length() ? "●" : "○"); dots[i].setContentDescription(i < digits.length() ? "已输入" : "未输入"); }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode(), number = -1;
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) number = key-KeyEvent.KEYCODE_0;
        else if (key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_9) number = key-KeyEvent.KEYCODE_NUMPAD_0;
        if (number >= 0 || key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_CLEAR) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !busy) {
                if (number >= 0) digit(Integer.toString(number));
                else if (key == KeyEvent.KEYCODE_DEL) delete(); else reset();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
