/*
 * Copyright 2026 Pegion Fish
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.anotherviewer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * Dressing for the Surface Duo reading simulation: fills the area outside
 * the simulated content with black (letterbox) and paints the hinge gap
 * between the two panels opaque black, like the real device's dead zone.
 * The reader viewport spans both panels plus the gap; dual-page layout
 * keeps each page inside its own half, so the gap only ever covers
 * background, never page content.
 */
public class DuoSimOverlay extends View {

    private final Paint mPaint = new Paint();
    private Rect mContent;
    private Rect mHinge;

    public DuoSimOverlay(Context context) {
        super(context);
        mPaint.setColor(Color.BLACK);
    }

    public void configure(Rect content, Rect hinge) {
        mContent = content;
        mHinge = hinge;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mContent == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        // letterbox outside the simulated area
        canvas.drawRect(0, 0, mContent.left, h, mPaint);
        canvas.drawRect(mContent.right, 0, w, h, mPaint);
        canvas.drawRect(mContent.left, 0, mContent.right, mContent.top, mPaint);
        canvas.drawRect(mContent.left, mContent.bottom, mContent.right, h, mPaint);
        // the hinge dead zone between the two panels
        if (mHinge != null && !mHinge.isEmpty()) {
            canvas.drawRect(mHinge, mPaint);
        }
    }
}
