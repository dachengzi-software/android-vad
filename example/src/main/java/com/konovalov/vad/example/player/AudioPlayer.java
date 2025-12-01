package com.konovalov.vad.example.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;

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

    private final HandlerThread playThread;
    private final Handler playHandler;

    private AudioTrack currentTrack;

    public AudioPlayer() {
        playThread = new HandlerThread("AudioPlayThread");
        playThread.start();
        playHandler = new Handler(playThread.getLooper());
    }

    public void setPitchFactor(float pitch) {
        this.pitchFactor = Math.max(0.5f, Math.min(2.0f, pitch));
    }

    public void setVoiceEffect(VoiceEffect effect) {
        this.voiceEffect = effect;
    }

    /** ----------------------------
     * 立即播放（强制中断旧播放）
     * ---------------------------- */
    public void playNow(short[] clip) {
        stopCurrentPlayback();

        playHandler.post(() -> playInternal(clip));
    }

    /** 延迟播放（仍然可以被 playNow() 打断） */
    public void playDelayed(short[] clip, long delayMs) {
        playHandler.postDelayed(() -> playNow(clip), delayMs);
    }

    /** 循环播放（也会被 playNow() 打断） */
    public void playLoop(short[] clip, int loopCount) {
        playHandler.post(() -> {
            for (int i = 0; i < loopCount || loopCount == -1; i++) {
                playNow(clip);

                // 等待当前播放结束
                synchronized (this) {
                    if (currentTrack != null) {
                        try { currentTrack.wait(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    /** ----------------------------
     * 停止当前播放
     * ---------------------------- */
    private synchronized void stopCurrentPlayback() {
        if (currentTrack != null) {

            try {
                if (currentTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    currentTrack.pause(); // 比 stop() 安全
                }
            } catch (Exception ignored) {}

            try {
                currentTrack.flush();
                currentTrack.release();
            } catch (Exception ignored) {}

            currentTrack = null;
        }

        // ⚠ 不能 removeAllCallback，会删掉接下来要执行的 playInternal()
        // 只清除延迟任务（不会影响立刻播放）
        playHandler.removeMessages(0);
    }

    // ----------------------------
    // 核心播放逻辑，不使用 sleep()
    // ----------------------------
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

        synchronized (this) {
            currentTrack = track;
        }

        track.write(processed, 0, processed.length);

        // ✔ 使用 marker 回调判断播放结束
        track.setNotificationMarkerPosition(processed.length);
        track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {

            @Override
            public void onMarkerReached(AudioTrack audioTrack) {
                synchronized (AudioPlayer.this) {
                    if (currentTrack == track) {
                        try { track.release(); } catch (Exception ignored) {}
                        currentTrack = null;

                        AudioPlayer.this.notifyAll();
                    }
                }
            }

            @Override
            public void onPeriodicNotification(AudioTrack audioTrack) {}
        });

        track.play();
    }

    // ----------------------------
    // 以下音效逻辑保持不变
    // ----------------------------

    private short[] applyEffects(short[] pcm) {
        if (voiceEffect == VoiceEffect.NORMAL && pitchFactor == 1.0f) {
            return pcm;
        }

        float[] samples = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) samples[i] = pcm[i] / 32768f;

        float curPitch = pitchFactor;
        if (voiceEffect == VoiceEffect.ROBOT || voiceEffect == VoiceEffect.ROBOT_REVERB) {
            curPitch = 1.5f;
        }

        if (curPitch != 1.0f) samples = applyPitchShift(samples, curPitch);

        if (voiceEffect == VoiceEffect.ROBOT || voiceEffect == VoiceEffect.ROBOT_REVERB)
            samples = applyBitCrush(samples, 4);

        if (voiceEffect == VoiceEffect.REVERB || voiceEffect == VoiceEffect.ROBOT_REVERB)
            samples = applyReverbSimple(samples);

        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = Math.max(-1f, Math.min(1f, samples[i]));
            out[i] = (short) (s * 32767f);
        }
        return out;
    }

    private float[] applyPitchShift(float[] in, float pitch) {
        if (pitch == 1.0f) return in;

        int outLen = (int) (in.length / pitch);
        float[] out = new float[outLen];

        for (int i = 0; i < outLen; i++) {
            float src = i * pitch;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, in.length - 1);
            float t = src - i0;
            out[i] = in[i0] * (1 - t) + in[i1] * t;
        }
        return out;
    }

    private float[] applyBitCrush(float[] in, int bits) {
        float[] out = new float[in.length];
        float step = 2f / (1 << bits);
        for (int i = 0; i < in.length; i++)
            out[i] = Math.round(in[i] / step) * step;
        return out;
    }

    private float[] applyReverbSimple(float[] in) {
        float[] out = new float[in.length];
        int d1 = (int) (sampleRate * 0.03f);
        int d2 = (int) (sampleRate * 0.05f);
        int d3 = (int) (sampleRate * 0.07f);

        float decay = 0.4f;

        float[] dl1 = new float[d1], dl2 = new float[d2], dl3 = new float[d3];
        int p1 = 0, p2 = 0, p3 = 0;

        for (int i = 0; i < in.length; i++) {
            float s = in[i];
            s += dl1[p1] * decay;
            s += dl2[p2] * decay * 0.7f;
            s += dl3[p3] * decay * 0.5f;

            dl1[p1] = dl2[p2] = dl3[p3] = s;

            p1 = (p1 + 1) % d1;
            p2 = (p2 + 1) % d2;
            p3 = (p3 + 1) % d3;

            out[i] = s * 0.6f;
        }
        return out;
    }
}
