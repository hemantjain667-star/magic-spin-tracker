package com.hemantjain.magicspintracker;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class SirenPlayer {
    public static final String TYPE_NONE = "none";
    public static final String TYPE_8X = "8x";
    public static final String TYPE_32X = "32x";

    private static final Object LOCK = new Object();
    private static volatile boolean running = false;
    private static volatile String activeType = TYPE_NONE;
    private static Thread sirenThread = null;
    private static AudioTrack sirenTrack = null;

    public static boolean isPlaying() { return running; }
    public static String getActiveType() { return running ? activeType : TYPE_NONE; }
    public static void start8x(Context ctx) { startInternal(ctx, TYPE_8X); }
    public static void start32x(Context ctx) { startInternal(ctx, TYPE_32X); }

    private static void startInternal(Context ctx, final String type) {
        synchronized (LOCK) {
            if (running && type.equals(activeType)) return;
            stopLocked();
            running = true;
            activeType = type;

            sirenThread = new Thread(() -> {
                final int sr = 22050, n = 1024;
                short[] buf = new short[n];
                int mb = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack tr = null;
                try {
                    tr = new AudioTrack(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build(),
                            new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sr)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build(),
                            Math.max(mb, n * 4),
                            AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE
                    );
                    synchronized (LOCK) { sirenTrack = tr; }
                    tr.play();

                    double ph = 0, lfo = 0;
                    final boolean is32x = TYPE_32X.equals(type);
                    final double lfoStep = 2.0 * Math.PI * (is32x ? 1.35 : 0.85) / sr;

                    while (running) {
                        for (int i = 0; i < n; i++) {
                            double freq, wave;
                            if (is32x) {
                                boolean high = Math.sin(lfo) >= 0;
                                freq = high ? 540.0 : 380.0;
                                lfo = (lfo + lfoStep) % (2.0 * Math.PI);
                                ph = (ph + 2.0 * Math.PI * freq / sr) % (2.0 * Math.PI);
                                wave = 0.65 * Math.sin(ph) + 0.25 * Math.sin(2.0 * ph) + 0.15 * Math.sin(3.0 * ph);
                            } else {
                                freq = 1015.0 + 365.0 * Math.sin(lfo);
                                lfo = (lfo + lfoStep) % (2.0 * Math.PI);
                                ph = (ph + 2.0 * Math.PI * freq / sr) % (2.0 * Math.PI);
                                wave = 0.75 * Math.sin(ph) + 0.25 * Math.sin(3.0 * ph);
                            }
                            buf[i] = (short) (wave * 24000.0);
                        }
                        if (!running) break;
                        tr.write(buf, 0, n);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (tr != null) { try { tr.stop(); tr.release(); } catch (Exception ignored) {} }
                    synchronized (LOCK) {
                        if (sirenTrack == tr) sirenTrack = null;
                        if (type.equals(activeType)) { running = false; activeType = TYPE_NONE; }
                    }
                }
            }, "MagicSiren_" + type);
            sirenThread.start();
        }
        broadcastStatus(ctx, true, type);
    }

    public static void stop(Context ctx) {
        synchronized (LOCK) { stopLocked(); }
        broadcastStatus(ctx, false, TYPE_NONE);
    }

    private static void stopLocked() {
        running = false;
        activeType = TYPE_NONE;
        if (sirenTrack != null) { try { sirenTrack.pause(); sirenTrack.flush(); sirenTrack.stop(); } catch (Exception ignored) {} }
        if (sirenThread != null) { sirenThread.interrupt(); sirenThread = null; }
    }

    private static void broadcastStatus(Context ctx, boolean playing, String type) {
        if (ctx == null) return;
        try {
            ctx.sendBroadcast(new Intent(FloatingOverlayService.ACTION_SIREN_STATUS)
                    .setPackage(ctx.getPackageName())
                    .putExtra(FloatingOverlayService.EXTRA_SIREN_PLAYING, playing)
                    .putExtra(FloatingOverlayService.EXTRA_SIREN_TYPE, type));
        } catch (Exception ignored) {}
    }

    // Loud unmistakable MEDIA speaker alarm for Target Profit & Stop-Loss!
    public static void playGoalChime(final boolean isProfit) {
        final double[] notes = isProfit
                ? new double[]{523.25, 659.25, 783.99, 1046.5, 0, 783.99, 1046.5, 1318.5}
                : new double[]{880.0, 440.0, 0, 880.0, 440.0, 0, 880.0, 440.0, 0, 780.0, 390.0};
        playMelodyAsync(notes, isProfit ? 180 : 190, AudioAttributes.USAGE_MEDIA, 26000.0);
    }

    public static void playBestWishSong() {
        final double[] notes = new double[]{523.25, 659.25, 783.99, 880.0, 783.99, 659.25, 783.99, 1046.5};
        playMelodyAsync(notes, 210, AudioAttributes.USAGE_NOTIFICATION, 20000.0);
    }

    private static void playMelodyAsync(final double[] freqs, final int noteMs, final int usage, final double amp) {
        new Thread(() -> {
            final int sr = 22050;
            final int noteSamples = (sr * noteMs) / 1000;
            short[] buf = new short[noteSamples];
            int mb = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack tr = null;
            try {
                tr = new AudioTrack(
                        new AudioAttributes.Builder()
                                .setUsage(usage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sr)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        Math.max(mb, noteSamples * 2),
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                );
                tr.play();
                for (double f : freqs) {
                    for (int i = 0; i < noteSamples; i++) {
                        if (f <= 0) {
                            buf[i] = 0;
                        } else {
                            double env = Math.min(1.0, i / 250.0) * Math.min(1.0, (noteSamples - i) / 500.0);
                            double ph = 2.0 * Math.PI * f * i / sr;
                            double w = 0.72 * Math.sin(ph) + 0.28 * Math.sin(3.0 * ph);
                            buf[i] = (short) (w * env * amp);
                        }
                    }
                    tr.write(buf, 0, noteSamples);
                }
            } catch (Exception ignored) {
            } finally {
                if (tr != null) { try { tr.stop(); tr.release(); } catch (Exception ignored) {} }
            }
        }, "MagicGoalTone").start();
    }
}
