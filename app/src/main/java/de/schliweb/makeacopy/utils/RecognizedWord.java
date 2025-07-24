package de.schliweb.makeacopy.utils;

import android.graphics.RectF;
import lombok.Getter;

/**
 * RecognizedWord represents a word recognized by OCR, including its text content
 * and position (bounding box) in the original image.
 * This class is used for creating searchable PDFs by positioning text at the exact
 * location where it appears in the image.
 */
@Getter
public class RecognizedWord {
    /**
     * -- GETTER --
     * Gets the recognized text
     *
     * @return The recognized text
     */
    private String text;
    /**
     * -- GETTER --
     * Gets the bounding box of the text in the original image
     *
     * @return The bounding box as a RectF
     */
    private RectF boundingBox;
    /**
     * -- GETTER --
     * Gets the confidence level of the recognition
     *
     * @return The confidence level (0-100)
     */
    private float confidence;

    /**
     * Constructor
     *
     * @param text        The recognized text
     * @param boundingBox The bounding box of the text in the original image
     * @param confidence  The confidence level of the recognition (0-100)
     */
    public RecognizedWord(String text, RectF boundingBox, float confidence) {
        this.text = text;
        this.boundingBox = boundingBox;
        this.confidence = confidence;
    }

    /**
     * Constructor with default confidence
     *
     * @param text        The recognized text
     * @param boundingBox The bounding box of the text in the original image
     */
    public RecognizedWord(String text, RectF boundingBox) {
        this(text, boundingBox, 0);
    }

    /**
     * Sets the recognized text
     *
     * @param text The recognized text
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * Sets the bounding box of the text
     *
     * @param boundingBox The bounding box as a RectF
     */
    public void setBoundingBox(RectF boundingBox) {
        this.boundingBox = boundingBox;
    }

    /**
     * Sets the confidence level of the recognition
     *
     * @param confidence The confidence level (0-100)
     */
    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    /**
     * Transforms the bounding box coordinates based on a scale factor and offset
     * This is useful when embedding the text in a PDF with a different scale
     *
     * @param scaleX  The scale factor for the X coordinate
     * @param scaleY  The scale factor for the Y coordinate
     * @param offsetX The X offset to apply after scaling
     * @param offsetY The Y offset to apply after scaling
     * @return A new RecognizedWord with transformed coordinates
     */
    public RecognizedWord transform(float scaleX, float scaleY, float offsetX, float offsetY) {
        RectF transformedBox = new RectF(boundingBox.left * scaleX + offsetX, boundingBox.top * scaleY + offsetY, boundingBox.right * scaleX + offsetX, boundingBox.bottom * scaleY + offsetY);
        return new RecognizedWord(text, transformedBox, confidence);
    }

    /**
     * Transforms the bounding box coordinates based on a uniform scale factor and offset
     *
     * @param scale   The scale factor for both X and Y coordinates
     * @param offsetX The X offset to apply after scaling
     * @param offsetY The Y offset to apply after scaling
     * @return A new RecognizedWord with transformed coordinates
     */
    public RecognizedWord transform(float scale, float offsetX, float offsetY) {
        return transform(scale, scale, offsetX, offsetY);
    }

    /**
     * Returns a string representation of the recognized word
     *
     * @return A string representation
     */
    @Override
    public String toString() {
        return "RecognizedWord{" + "text='" + text + '\'' + ", boundingBox=" + boundingBox + ", confidence=" + confidence + '}';
    }
}