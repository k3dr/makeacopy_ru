package de.schliweb.makeacopy.ui.ocr;

import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.makeacopy.utils.RecognizedWord;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewModel class for managing OCR operations.
 * Provides LiveData to observe the state and data of the OCR process.
 * Extends the functionality of BaseViewModel.
 */
public class OCRViewModel extends ViewModel {
    private static final String TAG = "OCRViewModel";

    /**
     * Retrieves the currently selected language from the application state.
     * The returned language is derived from the `mState` LiveData object
     * and defaults to "eng" (English) if the language is not set or if the state is null.
     *
     * @return A LiveData<String> representing the language code. Defaults to "eng" if unavailable.
     */
    public LiveData<String> getLanguage() {
        return androidx.lifecycle.Transformations.map(mState, s -> s != null && s.language() != null ? s.language() : "eng");
    }

    /**
     * @param offsetY letterboxing, else 0
     */ // Transform Original → Target (for example A4)
    public record OcrTransform(int srcW, int srcH, int dstW, int dstH, float scaleX, float scaleY, int offsetX,
                               int offsetY) {
    }

    /**
     * @param durationMs     optional
     * @param meanConfidence optional (0..100)
     * @param transform      optional
     */ // UI-State
    public record OcrUiState(boolean processing, boolean imageProcessed, String language, String ocrText,
                             List<RecognizedWord> words, Long durationMs, Integer meanConfidence,
                             OcrTransform transform) {
        /**
         * Creates a new OcrUiState.
         */
        public OcrUiState {
        }

        public OcrUiState withProcessing(boolean p) {
            return new OcrUiState(p, imageProcessed, language, ocrText, words, durationMs, meanConfidence, transform);
        }

        public OcrUiState withImageProcessed(boolean ip) {
            return new OcrUiState(processing, ip, language, ocrText, words, durationMs, meanConfidence, transform);
        }

        public OcrUiState withText(String t) {
            return new OcrUiState(processing, imageProcessed, language, t, words, durationMs, meanConfidence, transform);
        }

        public OcrUiState withWords(List<RecognizedWord> w) {
            return new OcrUiState(processing, imageProcessed, language, ocrText, w, durationMs, meanConfidence, transform);
        }

        public OcrUiState withLanguage(String lang) {
            return new OcrUiState(processing, imageProcessed, lang, ocrText, words, durationMs, meanConfidence, transform);
        }

        public OcrUiState withDuration(Long ms) {
            return new OcrUiState(processing, imageProcessed, language, ocrText, words, ms, meanConfidence, transform);
        }

        public OcrUiState withMeanConfidence(Integer mc) {
            return new OcrUiState(processing, imageProcessed, language, ocrText, words, durationMs, mc, transform);
        }

        public OcrUiState withTransform(OcrTransform tx) {
            return new OcrUiState(processing, imageProcessed, language, ocrText, words, durationMs, meanConfidence, tx);
        }
    }

    /**
     * Event wrapper for LiveData.
     */
    public static final class Event<T> {
        private final T data;
        private boolean handled = false;

        public Event(T data) {
            this.data = data;
        }

        public T getContentIfNotHandled() {
            if (handled) return null;
            handled = true;
            return data;
        }

        public T peek() {
            return data;
        }
    }

    private final MutableLiveData<OcrUiState> mState = new MutableLiveData<>(
            new OcrUiState(false, false, "eng", "", new ArrayList<>(), null, null, null)
    );
    private final MutableLiveData<Event<String>> mErrorEvents = new MutableLiveData<>();
    private final MutableLiveData<Event<Void>> mNavigateToExport = new MutableLiveData<>();

    public LiveData<OcrUiState> getState() {
        return mState;
    }

    public LiveData<Event<String>> getErrorEvents() {
        return mErrorEvents;
    }

    public LiveData<Event<Void>> getNavigateToExport() {
        return mNavigateToExport;
    }

    /**
     * Resets the UI-State for a new image.
     */
    public void resetForNewImage() {
        OcrUiState s = mState.getValue();
        if (s == null) return;
        mState.setValue(new OcrUiState(false, false, s.language, "", new ArrayList<>(), null, null, null));
        Log.d(TAG, "resetForNewImage");
    }

    /**
     * Sets the language.
     */
    public void setLanguage(String lang) {
        OcrUiState s = mState.getValue();
        if (s == null) return;
        mState.setValue(s.withLanguage(lang));
        Log.d(TAG, "setLanguage: " + lang);
    }

    /**
     * Starts processing.
     */
    public void startProcessing() {
        OcrUiState s = mState.getValue();
        if (s == null) return;
        mState.setValue(new OcrUiState(true, false, s.language, "", new ArrayList<>(), null, null, s.transform));
        Log.d(TAG, "startProcessing");
    }

    /**
     * Sets the OCR text and stops processing.
     */
    public void finishSuccess(String text, List<RecognizedWord> words, long durationMs, Integer meanConf, OcrTransform tx) {
        OcrUiState s = mState.getValue();
        if (s == null) return;
        if (words == null) words = new ArrayList<>();
        mState.setValue(new OcrUiState(false, true, s.language, text != null ? text : "", words, durationMs, meanConf, tx != null ? tx : s.transform));
        Log.d(TAG, "finishSuccess: " + (text != null ? Math.min(text.length(), 100) : 0) + " chars, words=" + words.size() + ", ms=" + durationMs + ", conf=" + meanConf);
    }

    /**
     * Sets an error message and stops processing.
     */
    public void finishError(String msg) {
        OcrUiState s = mState.getValue();
        if (s == null) return;
        mState.setValue(s.withProcessing(false).withImageProcessed(false).withText(""));
        mErrorEvents.setValue(new Event<>(msg));
        Log.d(TAG, "finishError: " + msg);
    }

    public void requestNavigateToExport() {
        mNavigateToExport.setValue(new Event<>(null));
    }

    /**
     * Sets the transform (for example A4)
     */
    public void setTransform(OcrTransform tx) {
        OcrUiState s = mState.getValue();
        if (s == null) return;
        mState.setValue(s.withTransform(tx));
    }

    /**
     * Sets the recognized words
     */
    public void setWords(List<RecognizedWord> words) {
        OcrUiState s = mState.getValue();
        if (s == null) return;
        mState.setValue(s.withWords(words != null ? words : new ArrayList<>()));
    }
}
