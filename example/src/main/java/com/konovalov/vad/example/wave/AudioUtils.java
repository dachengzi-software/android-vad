package com.konovalov.vad.example.wave;

public class AudioUtils {

    // RMS 音量
    public static float calculateRMS(short[] audioData) {
        double sum = 0;
        for (short sample : audioData) {
            sum += sample * sample;
        }
        double mean = sum / audioData.length;
        return (float) Math.sqrt(mean);
    }

    // Peak 音量
    public static float calculatePeak(short[] audioData) {
        short peak = 0;
        for (short sample : audioData) {
            peak = (short) Math.max(peak, Math.abs(sample));
        }
        return (float) peak;
    }
}

