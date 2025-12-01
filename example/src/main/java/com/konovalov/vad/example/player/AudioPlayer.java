package com.konovalov.vad.example.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;


public class AudioPlayer {

    public enum VoiceEffect {
        NORMAL,
        PITCH_SHIFT,
        ROBOT,
        REVERB,
        ROBOT_REVERB
    }

    private final int sampleRate = 16000;
    private float pitchFactor = 1.0f;
    private VoiceEffect voiceEffect = VoiceEffect.NORMAL;

    public void setPitchFactor(float pitch) {
        this.pitchFactor = Math.max(0.5f, Math.min(2.0f, pitch));
    }

    public void setVoiceEffect(VoiceEffect effect) {
        this.voiceEffect = effect;
    }

    public void playNow(short[] clip) {
        playInternal(clip);
    }

    public void playDelayed(short[] clip, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> playInternal(clip), delayMs);
    }

    public void playLoop(short[] clip, int loopCount) {
        new Thread(() -> {
            int cnt = 0;
            while (loopCount == -1 || cnt < loopCount) {
                playInternal(clip);
                cnt++;
            }
        }).start();
    }

    private void playInternal(short[] clip) {
        short[] processed = applyEffects(clip);

        int bufSize = processed.length * 2;
        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
                AudioTrack.MODE_STATIC
        );

        track.write(processed, 0, processed.length);
        track.play();

        // block until done (approx)
        try {
            Thread.sleep((long) (processed.length * 1000L / sampleRate) + 50);
        } catch (InterruptedException ignored) {}

        track.release();
    }

    private short[] applyEffects(short[] pcm) {
        if (voiceEffect == VoiceEffect.NORMAL && pitchFactor == 1.0f) {
            return pcm;
        }

        // Convert to float array
        float[] samples = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            samples[i] = pcm[i] / 32768.0f;
        }

        // Apply pitch shift first if needed
        float currentPitch = pitchFactor;
        if (voiceEffect == VoiceEffect.ROBOT || voiceEffect == VoiceEffect.ROBOT_REVERB) {
            currentPitch = 1.5f; // Robot voice uses fixed pitch
        }
        
        if (currentPitch != 1.0f) {
            samples = applyPitchShift(samples, currentPitch);
        }

        // Apply other effects using TarsosDSP
        if (voiceEffect == VoiceEffect.ROBOT || voiceEffect == VoiceEffect.ROBOT_REVERB) {
            samples = applyBitCrush(samples, 4);
        }
        
        if (voiceEffect == VoiceEffect.REVERB || voiceEffect == VoiceEffect.ROBOT_REVERB) {
            samples = applyReverbSimple(samples);
        }

        // Convert back to short array
        short[] output = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float sample = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            output[i] = (short) (sample * 32767.0f);
        }

        return output;
    }

    // Simple pitch shift using resampling
    private float[] applyPitchShift(float[] input, float pitchFactor) {
        if (pitchFactor == 1.0f) return input;
        
        int outputLength = (int) (input.length / pitchFactor);
        float[] output = new float[outputLength];
        
        for (int i = 0; i < outputLength; i++) {
            float srcIndex = i * pitchFactor;
            int idx0 = (int) srcIndex;
            int idx1 = Math.min(idx0 + 1, input.length - 1);
            float frac = srcIndex - idx0;
            output[i] = input[idx0] * (1 - frac) + input[idx1] * frac;
        }
        
        return output;
    }

    // Bit crushing for robot voice
    private float[] applyBitCrush(float[] input, int bits) {
        float[] output = new float[input.length];
        float step = 2.0f / (1 << bits);
        for (int i = 0; i < input.length; i++) {
            output[i] = Math.round(input[i] / step) * step;
        }
        return output;
    }

    // Simple reverb using delay lines
    private float[] applyReverbSimple(float[] input) {
        float[] output = new float[input.length];
        int delay1 = (int) (sampleRate * 0.03f);
        int delay2 = (int) (sampleRate * 0.05f);
        int delay3 = (int) (sampleRate * 0.07f);
        float decay = 0.4f;
        
        float[] delayLine1 = new float[delay1];
        float[] delayLine2 = new float[delay2];
        float[] delayLine3 = new float[delay3];
        int pos1 = 0, pos2 = 0, pos3 = 0;
        
        for (int i = 0; i < input.length; i++) {
            float sample = input[i];
            sample += delayLine1[pos1] * decay;
            sample += delayLine2[pos2] * decay * 0.7f;
            sample += delayLine3[pos3] * decay * 0.5f;
            
            delayLine1[pos1] = sample;
            delayLine2[pos2] = sample;
            delayLine3[pos3] = sample;
            
            pos1 = (pos1 + 1) % delay1;
            pos2 = (pos2 + 1) % delay2;
            pos3 = (pos3 + 1) % delay3;
            
            output[i] = sample * 0.6f;
        }
        
        return output;
    }
}
