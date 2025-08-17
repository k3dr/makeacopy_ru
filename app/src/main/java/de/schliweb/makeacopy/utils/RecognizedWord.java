package de.schliweb.makeacopy.utils;

import android.graphics.RectF;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents a recognized word in an OCR (Optical Character Recognition) process.
 * Each recognized word contains text, its bounding box coordinates within the image,
 * and a confidence score indicating the reliability of the recognition.
 */
@Getter
public class RecognizedWord {
    /**
     * The text content of a recognized word from an OCR process.
     * This variable stores the word's textual representation as identified
     * during the recognition step.
     */
    @Setter
    private String text;

    /**
     * The rectangular bounding box that defines the spatial boundaries of the recognized word
     * within the source image. This box is represented using a {@link RectF} object, which
     * provides the coordinates of the box's left, top, right, and bottom edges in a float format.
     * <p>
     * It represents the region in the image where the word is located and is used for tasks
     * such as visualization, transformations, or clipping operations.
     */
    private final RectF boundingBox;

    /**
     * The confidence score of the OCR recognition for the current word.
     * This variable represents the reliability of the OCR process in recognizing
     * the word, typically ranging between 0.0 and 1.0, where higher values indicate
     * greater confidence in the recognition.
     */
    private final float confidence;

    /**
     * Constructs a new instance of a recognized word with the specified text, bounding box,
     * and confidence score.
     *
     * @param text        The text content of the recognized word.
     * @param boundingBox The rectangular bounding box enclosing the word in the source image.
     *                    This defines its spatial location and dimensions.
     * @param confidence  The confidence score of the OCR recognition, typically a value
     *                    between 0.0 and 1.0 indicating the accuracy of the recognition.
     */
    public RecognizedWord(String text, RectF boundingBox, float confidence) {
        this.text = text;
        this.boundingBox = boundingBox;
        this.confidence = confidence;
    }

    /**
     * Transforms the bounding box of this recognized word by applying scaling factors and offsets
     * in the X and Y dimensions.
     *
     * @param scaleX  The scaling factor to apply along the X axis.
     * @param scaleY  The scaling factor to apply along the Y axis.
     * @param offsetX The offset to apply along the X axis.
     * @param offsetY The offset to apply along the Y axis.
     * @return A new RecognizedWord instance with the transformed bounding box and the same text
     * and confidence as the original instance.
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
     * Transforms the bounding box of this recognized word by applying a uniform scaling factor
     * and offsets in the X and Y dimensions.
     *
     * @param scale   The uniform scaling factor to apply along both the X and Y axes.
     * @param offsetX The offset to apply along the X axis.
     * @param offsetY The offset to apply along the Y axis.
     * @return A new RecognizedWord instance with the transformed bounding box and the same text
     * and confidence as the original instance.
     */
    public RecognizedWord transform(float scale, float offsetX, float offsetY) {
        return transform(scale, scale, offsetX, offsetY);
    }

    /**
     * Clips the bounding box of this recognized word to a maximum width and height.
     * If the bounding box dimensions exceed the specified limits, they will be adjusted
     * to fit within the constraints while maintaining their spatial position.
     *
     * @param maxW The maximum width to which the bounding box can be clipped.
     * @param maxH The maximum height to which the bounding box can be clipped.
     * @return A new RecognizedWord instance with the clipped bounding box, retaining
     * the same text and confidence as the original instance.
     */
    public RecognizedWord clipTo(float maxW, float maxH) {
        RectF r = new RectF(
                Math.max(0, Math.min(boundingBox.left, maxW)),
                Math.max(0, Math.min(boundingBox.top, maxH)),
                Math.max(0, Math.min(boundingBox.right, maxW)),
                Math.max(0, Math.min(boundingBox.bottom, maxH))
        );
        return new RecognizedWord(text, r, confidence);
    }

    public float width() {
        return boundingBox.width();
    }

    public float height() {
        return boundingBox.height();
    }

    @Override
    public String toString() {
        return "RecognizedWord{" +
                "text='" + text + '\'' +
                ", boundingBox=" + boundingBox +
                ", confidence=" + confidence +
                '}';
    }
}
