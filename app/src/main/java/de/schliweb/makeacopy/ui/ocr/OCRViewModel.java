package de.schliweb.makeacopy.ui.ocr;

import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.makeacopy.utils.RecognizedWord;

import java.util.ArrayList;
import java.util.List;

/**
 * The OCRViewModel class acts as a ViewModel for managing data related to OCR (Optical Character Recognition)
 * operations. It provides LiveData objects to observe changes to OCR results, processing status,
 * recognized words, and image processing state.
 */
public class OCRViewModel extends ViewModel {
    private static final String TAG = "OCRViewModel";

    private final MutableLiveData<String> mText;
    private final MutableLiveData<String> mOcrResult;
    private final MutableLiveData<Boolean> mImageProcessed;
    private final MutableLiveData<Boolean> mIsProcessing;
    private final MutableLiveData<List<RecognizedWord>> mRecognizedWords;

    public OCRViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("OCR Fragment");

        mOcrResult = new MutableLiveData<>();
        mOcrResult.setValue("");

        mImageProcessed = new MutableLiveData<>();
        mImageProcessed.setValue(false);

        mIsProcessing = new MutableLiveData<>();
        mIsProcessing.setValue(false);

        mRecognizedWords = new MutableLiveData<>();
        mRecognizedWords.setValue(new ArrayList<>());
    }

    public LiveData<String> getText() {
        return mText;
    }

    public LiveData<String> getOcrResult() {
        return mOcrResult;
    }

    public void setOcrResult(String result) {
        Log.d(TAG, "setOcrResult: " + (result != null ? (result.length() > 100 ? result.substring(0, 100) + "..." : result) : "null"));
        mOcrResult.setValue(result);
    }

    public LiveData<Boolean> isImageProcessed() {
        return mImageProcessed;
    }

    public void setImageProcessed(boolean processed) {
        Log.d(TAG, "setImageProcessed: " + processed);
        mImageProcessed.setValue(processed);
    }

    public LiveData<Boolean> isProcessing() {
        return mIsProcessing;
    }

    public void setProcessing(boolean processing) {
        Log.d(TAG, "setProcessing: " + processing);
        mIsProcessing.setValue(processing);
    }

    /**
     * Gets the list of recognized words with their positions
     *
     * @return LiveData containing the list of recognized words
     */
    public LiveData<List<RecognizedWord>> getRecognizedWords() {
        return mRecognizedWords;
    }

    /**
     * Sets the list of recognized words with their positions
     *
     * @param words List of recognized words
     */
    public void setRecognizedWords(List<RecognizedWord> words) {
        Log.d(TAG, "setRecognizedWords: " + (words != null ? words.size() : "null") + " words");
        mRecognizedWords.setValue(words != null ? words : new ArrayList<>());
    }
}