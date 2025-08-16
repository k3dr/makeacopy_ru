package de.schliweb.makeacopy.utils;

import android.graphics.RectF;
import lombok.Getter;
import lombok.Setter;

/**
 * Repräsentiert ein erkanntes Wort mit Box & Confidence.
 * - boundingBox/confidence immutable; Text setzbar
 * - Transform/Clip/Width/Height Helfer
 */
@Getter
public class RecognizedWord {
    /** Text ist änderbar (z.B. Korrekturen) */
    @Setter
    private String text;

    /** Bounding-Box im (OCR-)Bildkoordinatensystem */
    private final RectF boundingBox;

    /** Confidence (0-100) */
    private final float confidence;

    public RecognizedWord(String text, RectF boundingBox, float confidence) {
        this.text = text;
        this.boundingBox = boundingBox;
        this.confidence = confidence;
    }

    public RecognizedWord transform(float scaleX, float scaleY, float offsetX, float offsetY) {
        RectF r = new RectF(
                boundingBox.left * scaleX + offsetX,
                boundingBox.top  * scaleY + offsetY,
                boundingBox.right* scaleX + offsetX,
                boundingBox.bottom*scaleY + offsetY
        );
        return new RecognizedWord(text, r, confidence);
    }

    public RecognizedWord transform(float scale, float offsetX, float offsetY) {
        return transform(scale, scale, offsetX, offsetY);
    }

    public RecognizedWord clipTo(float maxW, float maxH) {
        RectF r = new RectF(
                Math.max(0, Math.min(boundingBox.left,   maxW)),
                Math.max(0, Math.min(boundingBox.top,    maxH)),
                Math.max(0, Math.min(boundingBox.right,  maxW)),
                Math.max(0, Math.min(boundingBox.bottom, maxH))
        );
        return new RecognizedWord(text, r, confidence);
    }

    public float width()  { return boundingBox.width(); }
    public float height() { return boundingBox.height(); }

    @Override public String toString() {
        return "RecognizedWord{" +
                "text='" + text + '\'' +
                ", boundingBox=" + boundingBox +
                ", confidence=" + confidence +
                '}';
    }
}
