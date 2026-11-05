package com.hwz.nettv;

import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

final class MediaControls {
    static ImageButton add(LinearLayout row, int icon, String label, Runnable action) {
        ImageButton button = new ImageButton(row.getContext());
        button.setImageResource(icon); button.setContentDescription(label); button.setTooltipText(label);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE); button.setBackgroundResource(R.drawable.focusable);
        int size = Math.round(48 * row.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size); params.setMargins(4, 4, 4, 4);
        row.addView(button, params); button.setOnClickListener(v -> action.run()); return button;
    }
}
