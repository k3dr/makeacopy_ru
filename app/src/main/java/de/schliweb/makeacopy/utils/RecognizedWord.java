package de.schliweb.makeacopy.utils;

import android.graphics.RectF;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents a single word that has been recognized, typically in the context of image
 * or text analysis. Each recognized word contains the text, a bounding box specifying its
 * location and size, and a confidence level indicating the likelihood of its correctness.
 */
@Getter
public class RecognizedWord {

    /**
     * The recognized textual content.
     * Represents the text detected and recognized in an image or document.
     * This variable stores the value of the recognized word or phrase.
     */
    @Setter
    private String text;

    /**
     * The bounding box of the recognized word, represented as a normalized rectangle.
     * The coordinates of the rectangle are guaranteed to follow normalization constraints:
     * the left coordinate is always less than or equal to the right, and the top coordinate
     * is always less than or equal to the bottom.
     * <p>
     * This variable is immutable and encapsulated for integrity and consistency.
     * Access to the bounding box must be performed through the `getBoundingBox()` method,
     * which provides a defensive copy to prevent external modification.
     */
    private final RectF boundingBox;   // always normalized: left<=right, top<=bottom


    /**
     * Represents the confidence level of recognition for a recognized word.
     * The value is typically a float between 0 and 1, where higher values
     * indicate greater confidence in the recognition result.
     */
    private final float confidence;

    /**
     * Constructs a new {@code RecognizedWord} instance with the given text, bounding box,
     * and confidence score.
     *
     * @param text        The recognized text. Cannot be null.
     * @param boundingBox The bounding box of the recognized text, represented as a {@code RectF}.
     *                    The box is normalized within this constructor to ensure consistency.
     * @param confidence  The confidence score of the recognition, ranging from 0.0 to 1.0.
     */
    public RecognizedWord(String text, RectF boundingBox, float confidence) {
        this.text = text;
        this.boundingBox = normalize(boundingBox); // defensive copy + normalize
        this.confidence = confidence;
    }

    /**
     * Retrieves the bounding box of the recognized text.
     *
     * @return A copy of the {@code RectF} representing the bounding box of the recognized text.
     */
    public RectF getBoundingBox() {
        return new RectF(boundingBox);
    }

    /**
     * Transforms the bounding box of the current {@code RecognizedWord} by applying scaling and
     * translation transformations based on the provided parameters.
     *
     * @param scaleX  The horizontal scale factor to apply to the bounding box.
     * @param scaleY  The vertical scale factor to apply to the bounding box.
     * @param offsetX The horizontal translation to apply to the bounding box.
     * @param offsetY The vertical translation to apply to the bounding box.
     * @return A new {@code RecognizedWord} instance with the transformed bounding box
     * and the same text and confidence score as the original.
     */
    public RecognizedWord transform(float scaleX, float scaleY, float offsetX, float offsetY) {
        RectF r = new RectF(
                boundingBox.left * scaleX + offsetX,
                boundingBox.top * scaleY + offsetY,
                boundingBox.right * scaleX + offsetX,
                boundingBox.bottom * scaleY + offsetY
        );
        return new RecognizedWord(text, r, confidence);
    }

    /**
     * Transforms the bounding box of the current {@code RecognizedWord} instance by applying
     * uniform scaling and translation transformations based on the provided parameters.
     *
     * @param scale   The uniform scale factor to apply to both the horizontal and vertical dimensions
     *                of the bounding box.
     * @param offsetX The horizontal translation to apply to the bounding box.
     * @param offsetY The vertical translation to apply to the bounding box.
     * @return A new {@code RecognizedWord} instance with the transformed bounding box,
     * while retaining the same text and confidence score.
     */
    public RecognizedWord transform(float scale, float offsetX, float offsetY) {
        return transform(scale, scale, offsetX, offsetY);
    }

    /**
     * Clips the bounding box of this {@code RecognizedWord} instance to fit within the specified maximum width and height.
     * If the bounding box extends beyond these dimensions, it is adjusted to remain within the specified limits.
     *
     * @param maxW The maximum allowable width. The left and right edges of the bounding box will be clamped between 0 and this value.
     * @param maxH The maximum allowable height. The top and bottom edges of the bounding box will be clamped between 0 and this value.
     * @return A new {@code RecognizedWord} instance with the text and confidence score unchanged, but with the bounding box adjusted
     * to fit within the specified limits.
     */
    public RecognizedWord clipTo(float maxW, float maxH) {
        RectF r = new RectF(
                clamp(boundingBox.left, 0f, maxW),
                clamp(boundingBox.top, 0f, maxH),
                clamp(boundingBox.right, 0f, maxW),
                clamp(boundingBox.bottom, 0f, maxH)
        );
        return new RecognizedWord(text, r, confidence);
    }

    public float width() {
        return boundingBox.width();
    }

    public float height() {
        return boundingBox.height();
    }

    /**
     * Useful helpers for line clustering/alignment
     */
    public float midY() {
        return 0.5f * (boundingBox.top + boundingBox.bottom);
    }

    public float centerX() {
        return 0.5f * (boundingBox.left + boundingBox.right);
    }

    @Override
    public String toString() {
        return "RecognizedWord{" +
                "text='" + text + '\'' +
                ", boundingBox=" + boundingBox +
                ", confidence=" + confidence +
                '}';
    }

    // ---- private helpers ----
    private static RectF normalize(RectF in) {
        if (in == null) return new RectF();
        float left = Math.min(in.left, in.right);
        float right = Math.max(in.left, in.right);
        float top = Math.min(in.top, in.bottom);
        float bottom = Math.max(in.top, in.bottom);
        return new RectF(left, top, right, bottom); // defensive copy
    }

    /**
     * Constrains a given value to lie within a specified range.
     * If the value is less than the minimum, the method returns the minimum.
     * If the value is greater than the maximum, the method returns the maximum.
     * Otherwise, the original value is returned.
     *
     * @param v   The value to be clamped.
     * @param min The minimum allowable value for the range.
     * @param max The maximum allowable value for the range.
     * @return The clamped value, which will be within the range [min, max].
     */
    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
