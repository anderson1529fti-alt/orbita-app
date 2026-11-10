package __PACKAGE__;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * 4.2 · La parte del motor que habla con el móvil: el decodificador de Android (MediaCodec) y la salida de sonido
 * (AudioTrack). Es el mismo código de la 4.1 (antes vivía dentro de NovaAudioPlugin.java), sin cambios.
 */
final class NovaAudioIO {
    private NovaAudioIO() { }

    // ---------------------------------------------------------------- decodificar con el propio móvil
    static final class AndroidDecoder implements NovaAudioEngine.Decoder {
        private final MediaExtractor ex = new MediaExtractor();
        private MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int sr, ch, enc = AudioFormat.ENCODING_PCM_16BIT;
        private long dur;
        private boolean inDone, outDone;
        // lo decodificado que espera (muestras intercaladas)
        private float[] pend = new float[0];
        private int pendPos, pendLen;
        private long skipUntil = -1;
        /* MP3: el codificador añade silencio al principio y al final (retardo y relleno). Se quita, para que las
           canciones de un disco vayan pegadas de verdad */
        private int skipStart = 0, holdBack = 0;

        AndroidDecoder(Context ctx, String src) throws Exception {
            // el hilo que decodifica, con prioridad de sonido: que Android no lo frene en segundo plano
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); } catch (Throwable ignored) { }
            try {
                if (src.startsWith("content://")) ex.setDataSource(ctx, Uri.parse(src), null);
                else if (src.startsWith("file://")) ex.setDataSource(Uri.parse(src).getPath());
                else ex.setDataSource(src);
                int track = -1;
                MediaFormat f = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat tf = ex.getTrackFormat(i);
                    String mime = tf.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { track = i; f = tf; break; }
                }
                if (track < 0) throw new Exception("el archivo no tiene sonido");
                ex.selectTrack(track);
                String mime = f.getString(MediaFormat.KEY_MIME);
                sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                dur = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
                if ("audio/mpeg".equals(mime)) {
                    try { if (f.containsKey("encoder-delay")) skipStart = Math.max(0, Math.min(sr, f.getInteger("encoder-delay"))); } catch (Throwable ignored) { }
                    try { if (f.containsKey("encoder-padding")) holdBack = Math.max(0, Math.min(sr, f.getInteger("encoder-padding"))); } catch (Throwable ignored) { }
                }
                codec = MediaCodec.createDecoderByType(mime);
                boolean configured = false;
                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        MediaFormat ff = ex.getTrackFormat(track);
                        ff.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT);
                        codec.configure(ff, null, null, 0);
                        configured = true;
                    } catch (Throwable t) {
                        codec.release();
                        codec = MediaCodec.createDecoderByType(mime);
                    }
                }
                if (!configured) codec.configure(f, null, null, 0);
                codec.start();
                /* lee hasta el primer sonido: así se sabe la frecuencia y los canales reales (algunos AAC los cambian) */
                for (int i = 0; i < 400 && pendLen == 0 && !outDone; i++) step();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        public int sampleRate() { return sr; }
        public int channels() { return ch; }
        public long durationUs() { return dur; }

        public int read(float[] dst, int maxFrames) throws Exception {
            for (int tries = 0; tries < 50; tries++) {
                int c = Math.max(1, ch);
                // el relleno del final no se da nunca: se guarda hasta saber que no queda más
                int usable = pendLen / c - holdBack;
                if (usable > 0) {
                    int frames = Math.min(maxFrames, usable);
                    int n = frames * c;
                    System.arraycopy(pend, pendPos, dst, 0, n);
                    pendPos += n;
                    pendLen -= n;
                    return frames;
                }
                if (outDone) return -1;
                step();
            }
            return 0;
        }

        /** Un paso del decodificador: le da datos y recoge sonido. */
        private void step() throws Exception {
            if (!inDone) {
                int ii = codec.dequeueInputBuffer(4000);
                if (ii >= 0) {
                    ByteBuffer b = codec.getInputBuffer(ii);
                    int n = b == null ? -1 : ex.readSampleData(b, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int oi = codec.dequeueOutputBuffer(info, 4000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                enc = Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                return;
            }
            if (oi < 0) return;
            ByteBuffer ob = codec.getOutputBuffer(oi);
            if (ob != null && info.size > 0) {
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                ob.order(ByteOrder.LITTLE_ENDIAN);
                int c = Math.max(1, ch);
                // lo que aún esperaba se pone al principio y lo nuevo detrás
                if (pendPos > 0 && pendLen > 0) System.arraycopy(pend, pendPos, pend, 0, pendLen);
                pendPos = 0;
                int samples;
                if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                    FloatBuffer fb = ob.asFloatBuffer();
                    samples = fb.remaining();
                    ensure(pendLen + samples);
                    fb.get(pend, pendLen, samples);
                } else {
                    ShortBuffer sb = ob.asShortBuffer();
                    samples = sb.remaining();
                    ensure(pendLen + samples);
                    for (int i = 0; i < samples; i++) pend[pendLen + i] = sb.get() / 32768f;
                }
                samples -= samples % c;
                int newFrom = pendLen;
                pendLen += samples;
                int newFrames = samples / c, drop = 0;
                /* después de saltar: se tira lo que queda antes del punto exacto */
                if (skipUntil >= 0) {
                    long pts = info.presentationTimeUs;
                    if (pts < skipUntil) drop = (int) Math.min(newFrames, (skipUntil - pts) * sr / 1_000_000L);
                    if (newFrames - drop > 0) skipUntil = -1;
                } else if (skipStart > 0) {
                    drop = Math.min(newFrames, skipStart);
                    skipStart -= drop;
                }
                if (drop > 0) {
                    System.arraycopy(pend, newFrom + drop * c, pend, newFrom, (newFrames - drop) * c);
                    pendLen -= drop * c;
                }
            }
            codec.releaseOutputBuffer(oi, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
        }

        private void ensure(int n) { if (pend.length < n) pend = java.util.Arrays.copyOf(pend, Math.max(n, pend.length * 2)); }

        public void seekUs(long us) {
            ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            inDone = false;
            outDone = false;
            pendLen = 0;
            pendPos = 0;
            skipUntil = us;
            skipStart = 0;
        }

        public void close() {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            codec = null;
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- la salida de sonido
    static final class AndroidOutput implements NovaAudioEngine.Output {
        private final AudioTrack track;
        private final int sr;
        /* 4.0b3 · cuánto tarda en oírse lo que se escribe: se pregunta a Android cada 0,2 s qué trama está sonando de
           verdad (incluye el retraso del Bluetooth en los móviles que lo dicen) */
        private long written, lastQ;
        private volatile long lat;
        private final android.media.AudioTimestamp ts = new android.media.AudioTimestamp();

        AndroidOutput(int sr) {
            this.sr = sr;
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat af = new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            track = new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af)
                .setBufferSizeInBytes(Math.max(min * 2, sr / 5 * 2 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build();
            long b = sr / 5; try { b = track.getBufferSizeInFrames(); } catch (Throwable ignored) { }
            lat = b;
        }

        public int sampleRate() { return sr; }
        public void start() {
            // el hilo que da el sonido al altavoz: la prioridad más alta de sonido (sin cortes con la pantalla apagada)
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
            try { track.play(); } catch (Throwable ignored) { }
        }
        public void write(float[] st, int frames) {
            track.write(st, 0, frames * 2, AudioTrack.WRITE_BLOCKING);
            written += frames;
            long now = System.nanoTime();
            if (now - lastQ > 200_000_000L) { lastQ = now; measure(now); }
        }
        private void measure(long now) {
            long l = -1;
            try {
                if (track.getTimestamp(ts)) l = written - (ts.framePosition + Math.round((now - ts.nanoTime) * (double) sr / 1e9));
            } catch (Throwable ignored) { }
            if (l < 0 || l > 2L * sr) { try { l = written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL); } catch (Throwable ignored) { l = -1; } }
            if (l < 0 || l > 2L * sr) return;
            long c = lat; lat = c <= 0 ? l : (c * 3 + l) / 4;
        }
        public long latencyFrames() { return lat; }
        public void pause() { try { track.pause(); } catch (Throwable ignored) { } }
        public void flush() { try { track.flush(); } catch (Throwable ignored) { } }
        public void close() { try { track.stop(); } catch (Throwable ignored) { } track.release(); }
    }
}
