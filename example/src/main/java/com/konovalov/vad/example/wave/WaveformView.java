package com.konovalov.vad.example.wave;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WaveformView extends View {
    private final Paint paint;
    private final List<Float> amplitudes = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>(); // 每帧颜色


    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setStrokeWidth(4f);
        paint.setAntiAlias(true);
    }

    // 添加音量值 + 对应颜色
    public void addAmplitude(float value, int color) {
        if (amplitudes.size() > getWidth()) {
            amplitudes.remove(0);
            colors.remove(0);
        }
        amplitudes.add(value);
        colors.add(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float centerY = getHeight() / 2f;

        // 方法B: 自适应缩放（根据最近 N 帧最大值）
        float maxRecent = 1f; // 默认防止除0
        if (!amplitudes.isEmpty()) {
            maxRecent = Collections.max(amplitudes);
        }
        float scale = (getHeight() / 2f) / (maxRecent + 1); // +1防止除零

        // ---------- 2. 绘制波形 ----------
        for (int i = 0; i < amplitudes.size(); i++) {
            float x = i;
            float y = amplitudes.get(i) * scale;

            // 限制 y 最大值，防止超出 View
            if (y > getHeight() / 2f) y = getHeight() / 2f;

            // 单独设置颜色
            paint.setColor(colors.get(i));
            canvas.drawLine(x, centerY - y, x, centerY + y, paint);
        }
    }

}
