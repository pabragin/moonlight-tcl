package com.limelight.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Dictation through the TV's own speech recognizer (Google's on a Google TV), over whichever microphone the TV
 * routes to apps: the far-field one in the set or the remote's. One utterance per {@link #start}: the recognizer
 * stops by itself after the speaker falls silent and reports the text once. Main thread only.
 */
public final class VoiceInput {
    public static final int STATE_IDLE = 0;
    /** startListening() called, microphone not open yet */
    public static final int STATE_STARTING = 1;
    public static final int STATE_LISTENING = 2;
    /** Speech ended, waiting for the result */
    public static final int STATE_PROCESSING = 3;

    public interface Listener {
        void onVoiceState(int state);
        /** Microphone level 0..1 while listening */
        void onVoiceLevel(float level);
        void onVoicePartial(String text);
        void onVoiceResult(String text);
        /** A SpeechRecognizer.ERROR_* code; the recognizer is idle again */
        void onVoiceError(int error);
    }

    private final Context context;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private int state = STATE_IDLE;

    public VoiceInput(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public boolean isActive() {
        return state != STATE_IDLE;
    }

    public int getState() {
        return state;
    }

    public void start(Locale locale) {
        if (state != STATE_IDLE) {
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(callbacks);
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        LimeLog.info("Voice input: start " + locale.toLanguageTag());
        setState(STATE_STARTING);
        recognizer.startListening(intent);
    }

    /** Stops the microphone now and takes whatever was said so far. */
    public void stop() {
        if (state == STATE_STARTING || state == STATE_LISTENING) {
            setState(STATE_PROCESSING);
            recognizer.stopListening();
        }
    }

    /** Drops the utterance without a result. */
    public void cancel() {
        if (recognizer != null && state != STATE_IDLE) {
            recognizer.cancel();
        }
        setState(STATE_IDLE);
    }

    public void destroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        state = STATE_IDLE;
    }

    private void setState(int newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        listener.onVoiceState(newState);
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        ArrayList<String> results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (results == null || results.isEmpty()) {
            return null;
        }
        String text = results.get(0);
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private final RecognitionListener callbacks = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            LimeLog.info("Voice input: ready");
            setState(STATE_LISTENING);
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // The recognizers report roughly -2..10 dB
            float level = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f));
            listener.onVoiceLevel(level);
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            LimeLog.info("Voice input: end of speech");
            setState(STATE_PROCESSING);
        }

        @Override
        public void onError(int error) {
            LimeLog.info("Voice input: error " + error);
            state = STATE_IDLE;
            listener.onVoiceError(error);
        }

        @Override
        public void onResults(Bundle results) {
            String text = firstResult(results);
            LimeLog.info("Voice input: result " + (text == null ? "(empty)" : text.length() + " chars"));
            state = STATE_IDLE;
            if (text != null) {
                listener.onVoiceResult(text);
            }
            else {
                listener.onVoiceError(SpeechRecognizer.ERROR_NO_MATCH);
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (text != null) {
                listener.onVoicePartial(text);
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
