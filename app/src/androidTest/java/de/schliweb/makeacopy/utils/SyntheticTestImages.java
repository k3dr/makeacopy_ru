package de.schliweb.makeacopy.utils;

import android.graphics.*;

public class SyntheticTestImages {
    public static Bitmap create(int skew) {
        int width = 600;
        int height = 800;
        int margin = 60;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);

        Path path = new Path();
        path.moveTo(margin + skew, margin);
        path.lineTo(width - margin, margin);
        path.lineTo(width - margin - skew, height - margin);
        path.lineTo(margin, height - margin);
        path.close();

        canvas.drawColor(Color.BLACK);
        canvas.drawPath(path, paint);

        return bitmap;
    }
}