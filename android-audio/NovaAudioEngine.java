package __PACKAGE__;

import java.util.ArrayDeque;

/**
 * El motor de sonido de Órbita fuera del WebView (3.5, beta).
 * Dos «platos» (como en una cabina de DJ) que se mezclan, y después el estudio de sonido: ecualizador de 10 bandas,
 * volumen igualado, limitador y volumen general. Cada plato decodifica en su propio hilo y convierte su canción a la
 * frecuencia de la salida con un remuestreador de calidad. Las canciones de un mismo disco pueden ir pegadas, sin
 * hueco. Este archivo no usa nada de Android: así se puede probar en un ordenador.
 */
public final class NovaAudioEngine {

    /** Lee una canción como sonido: muestras intercaladas en coma flotante. */
    public interface Decoder {
        int sampleRate();
        int channels();
        long durationUs();
        /** Rellena dst; devuelve las tramas leídas, 0 si aún no hay nada (vuelve a intentarlo) o -1 al terminar. */
        int read(float[] dst, int maxFrames) throws Exception;
        void seekUs(long us) throws Exception;
        void close();
    }

    public interface DecoderFactory { Decoder open(String src) throws Exception; }

    /** La salida de sonido (estéreo). write() espera hasta que cabe: es lo que marca el ritmo. */
    public interface Output {
        int sampleRate();
        void start();
        void write(float[] stereo, int frames);
        void pause();
        void flush();
        void close();
    }

    public interface Listener {
        void onEvent(String type, int slot, String src, double pos, double dur, String msg);
        void onSpectrum(byte[] bins);
    }

    public static final int SLOTS = 2;
    private static final int BLOCK = 512;

    private final DecoderFactory factory;
    private final Output out;
    private final Listener listener;
    private final int rate;
    private final Slot[] slots = new Slot[SLOTS];
    private final Object lock = new Object();
    private volatile boolean running = true;
    private Thread thread;

    // estudio de sonido
    private final Biquad[] eq = new Biquad[10];
    private volatile float[] eqGains = new float[10];
    private volatile boolean eqOn = false, direct = false;
    private volatile float pre = 1f;
    private float masterNow = 1f;
    private volatile float master = 1f;
    private final Limiter limiter;
    private volatile boolean viz = false;
    private final Spectrum spectrum;
    private boolean outPlaying = false;

    public static final double[] EQ_FREQS = { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };

    public NovaAudioEngine(DecoderFactory factory, Output out, Listener listener) {
        this.factory = factory;
        this.out = out;
        this.listener = listener;
        this.rate = out.sampleRate();
        for (int i = 0; i < SLOTS; i++) slots[i] = new Slot(i);
        for (int i = 0; i < 10; i++) eq[i] = new Biquad();
        setEq(new float[10], false);
        limiter = new Limiter(rate);
        spectrum = new Spectrum(rate);
    }

    public int rate() { return rate; }

    public void start() {
        thread = new Thread(this::loop, "orbita-audio");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void release() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        for (Slot s : slots) s.unload();
        try { if (thread != null) thread.join(1500); } catch (InterruptedException ignored) { }
        try { out.close(); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- órdenes
    public void load(int slot, String src, double startAt, boolean play) { slots[slot].load(src, startAt, play); wake(); }
    public void play(int slot) { slots[slot].setPlaying(true); wake(); }
    public void pause(int slot) { slots[slot].setPlaying(false); }
    public void stop(int slot) { slots[slot].unload(); }
    public void seek(int slot, double pos) { slots[slot].seek(pos); wake(); }
    public void setVolume(int slot, float v) { slots[slot].volume = clamp(v, 0f, 1f); }
    public void setNorm(int slot, float g) { slots[slot].norm = clamp(g, 0f, 4f); }
    public void setNext(int slot, String src) { slots[slot].setNext(src); }
    /** 3.5b2: las siguientes canciones en orden; el motor las encadena solo aunque la app esté dormida. */
    public void setQueue(int slot, java.util.List<String> srcs) { slots[slot].setQueue(srcs); }
    public void setMaster(float v) { master = clamp(v, 0f, 1f); }
    public void setDirect(boolean d) { direct = d; }
    public void setViz(boolean on) { viz = on; }
    public boolean anyPlaying() { for (Slot s : slots) if (s.playing) return true; return false; }
    public double position(int slot) { return slots[slot].position(); }
    public double duration(int slot) { return slots[slot].durUs / 1e6; }
    public boolean playing(int slot) { return slots[slot].playing; }

    /** Ganancias en dB por banda; el volumen se baja antes lo justo para que subir bandas nunca distorsione. */
    public void setEq(float[] gains, boolean on) {
        float[] g = new float[10];
        for (int i = 0; i < 10 && gains != null && i < gains.length; i++) g[i] = clamp(gains[i], -12f, 12f);
        for (int i = 0; i < 10; i++) eq[i].target(i == 0 ? Biquad.LOWSHELF : i == 9 ? Biquad.HIGHSHELF : Biquad.PEAKING, EQ_FREQS[i], 1.41, g[i], rate);
        eqGains = g;
        eqOn = on;
        double peak = on ? peakDb(g) : 0;
        pre = (float) Math.pow(10, -peak * 0.85 / 20);
    }

    /** El punto más alto de la curva del ecualizador (en dB), mirando de 20 Hz a 20 kHz. */
    public double peakDb(float[] g) {
        Biquad[] probe = new Biquad[10];
        for (int i = 0; i < 10; i++) { probe[i] = new Biquad(); probe[i].set(i == 0 ? Biquad.LOWSHELF : i == 9 ? Biquad.HIGHSHELF : Biquad.PEAKING, EQ_FREQS[i], 1.41, g[i], rate); }
        double mx = 0;
        for (int k = 0; k < 96; k++) {
            double f = 20 * Math.pow(1000, k / 95.0), m = 1;
            for (Biquad b : probe) m *= b.magnitude(f, rate);
            mx = Math.max(mx, m);
        }
        return Math.max(0, 20 * Math.log10(mx));
    }

    private void wake() { synchronized (lock) { lock.notifyAll(); } }

    // ---------------------------------------------------------------- el hilo de sonido
    private void loop() {
        final float[] mix = new float[BLOCK * 2], tmp = new float[BLOCK * 2];
        long lastSpec = 0;
        while (running) {
            boolean any = false;
            for (Slot s : slots) if (s.playing) { any = true; break; }
            if (!any) {
                if (outPlaying) { out.pause(); outPlaying = false; }
                synchronized (lock) {
                    boolean still = true;
                    for (Slot s : slots) if (s.playing) still = false;
                    if (still && running) { try { lock.wait(250); } catch (InterruptedException e) { return; } }
                }
                continue;
            }
            if (!outPlaying) { out.start(); outPlaying = true; }
            java.util.Arrays.fill(mix, 0f);
            for (Slot s : slots) if (s.playing) s.render(mix, tmp, BLOCK);
            process(mix, BLOCK);
            out.write(mix, BLOCK);
            for (Slot s : slots) s.afterBlock();
            if (viz) {
                long now = System.nanoTime();
                spectrum.push(mix, BLOCK);
                if (now - lastSpec > 33_000_000L) { lastSpec = now; try { listener.onSpectrum(spectrum.bins()); } catch (Throwable ignored) { } }
            }
        }
    }

    private void process(float[] buf, int frames) {
        final float target = master;
        final boolean useEq = eqOn && !direct;
        final float p = useEq ? pre : 1f;
        for (int i = 0; i < frames; i++) {
            masterNow += (target - masterNow) * 0.0015f;
            float l = buf[2 * i] * p, r = buf[2 * i + 1] * p;
            if (useEq) for (Biquad b : eq) { l = b.l(l); r = b.r(r); }
            if (!direct) { float g = limiter.gain(l, r); l *= g; r *= g; }
            l *= masterNow; r *= masterNow;
            buf[2 * i] = l > 1f ? 1f : (l < -1f ? -1f : l);
            buf[2 * i + 1] = r > 1f ? 1f : (r < -1f ? -1f : r);
        }
        if (useEq) for (Biquad b : eq) b.glide();
    }

    private void emit(String type, int slot, String src, double pos, double dur, String msg) {
        try { listener.onEvent(type, slot, src, pos, dur, msg); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- un plato
    private final class Slot {
        final int id;
        volatile String src;
        volatile boolean playing;
        volatile float volume = 1f, norm = 1f;
        float volNow = 0f;
        volatile long durUs;
        // lo que el hilo de sonido ya ha sacado (tramas a la frecuencia de salida) desde el principio de la canción actual
        volatile long playedFrames;
        volatile double baseSec;
        Ring ring;
        Thread feeder;
        volatile int gen;
        volatile boolean eof, failed;
        volatile int seekReq, seekDone;
        volatile double seekTo;
        volatile String nextSrc;
        // las que vienen después de nextSrc (cola de segundo plano)
        final ArrayDeque<String> upcoming = new ArrayDeque<>();
        // cambios de canción sin hueco: en qué trama del anillo empieza la siguiente
        final ArrayDeque<long[]> marks = new ArrayDeque<>();
        final ArrayDeque<Object[]> markInfo = new ArrayDeque<>();
        volatile boolean endedSent;

        Slot(int id) { this.id = id; }

        synchronized void load(String s, double startAt, boolean play) {
            stopFeeder();
            src = s; durUs = 0; playedFrames = 0; baseSec = Math.max(0, startAt);
            eof = false; failed = false; endedSent = false; nextSrc = null;
            synchronized (upcoming) { upcoming.clear(); }
            synchronized (marks) { marks.clear(); markInfo.clear(); }
            ring = new Ring(rate * 2);
            seekTo = startAt; seekDone = seekReq; if (startAt > 0) seekReq++;
            playing = play; volNow = 0f;
            final int my = ++gen;
            feeder = new Thread(() -> feed(my, s), "orbita-plato-" + id);
            feeder.start();
        }

        synchronized void unload() {
            stopFeeder();
            playing = false; src = null; ring = null; eof = false;
        }

        void stopFeeder() {
            gen++;
            Thread f = feeder; feeder = null;
            if (f != null) { f.interrupt(); try { f.join(800); } catch (InterruptedException ignored) { } }
        }

        void setPlaying(boolean p) {
            if (p && (src == null || failed)) return;
            if (p && eof && ring != null && ring.available() == 0) return;
            playing = p;
        }

        void seek(double pos) {
            if (src == null) return;
            /* si ya se estaba preparando la siguiente del disco, se vuelve a abrir esta */
            /* si ya se estaban preparando las siguientes, se vuelve a abrir esta y se conservan todas, en orden */
            ArrayDeque<String> rest = new ArrayDeque<>();
            synchronized (marks) { for (Object[] info : markInfo) rest.add((String) info[0]); }
            if (!rest.isEmpty()) {
                boolean p = playing; String s = src;
                synchronized (upcoming) { if (nextSrc != null) rest.add(nextSrc); rest.addAll(upcoming); }
                load(s, pos, p);
                synchronized (upcoming) { nextSrc = rest.poll(); upcoming.addAll(rest); }
                return;
            }
            synchronized (this) {
                seekTo = Math.max(0, pos); seekReq++;
                baseSec = seekTo; playedFrames = 0; eof = false; endedSent = false;
                synchronized (marks) { marks.clear(); markInfo.clear(); }
                if (ring != null) ring.clear();
            }
        }

        void setNext(String s) { setQueue(s == null ? null : java.util.Collections.singletonList(s)); }

        /** Las siguientes. Las que ya están preparadas en el anillo no se repiten. */
        void setQueue(java.util.List<String> list) {
            ArrayDeque<String> q = new ArrayDeque<>();
            if (list != null) for (String x : list) if (x != null && !x.isEmpty()) q.add(x);
            synchronized (marks) { for (Object[] info : markInfo) { if (!q.isEmpty() && q.peek().equals(info[0])) q.poll(); } }
            synchronized (upcoming) { upcoming.clear(); nextSrc = q.poll(); upcoming.addAll(q); }
        }

        private String pollNext() { synchronized (upcoming) { String x = nextSrc; nextSrc = upcoming.poll(); return x; } }

        double position() { return baseSec + playedFrames / (double) rate; }

        /** El hilo que decodifica: llena el anillo por delante de lo que suena. */
        private void feed(int my, String first) {
            Decoder d = null;
            Resampler rs = null;
            float[] in = new float[4096 * 8];
            String cur = first;
            try {
                d = factory.open(first);
                durUs = d.durationUs();
                rs = new Resampler(d.sampleRate(), rate);
                emit("loaded", id, first, baseSec, durUs / 1e6, null);
                while (my == gen && running) {
                    int sr = seekReq;
                    if (sr != seekDone) {
                        double to = seekTo;
                        d.seekUs((long) (to * 1e6));
                        rs.reset();
                        Ring rr = ring; if (rr != null) rr.clear();
                        seekDone = sr;
                        if (my == gen) emit("seeked", id, cur, to, durUs / 1e6, null);
                        continue;
                    }
                    Ring r = ring; if (r == null) return;
                    if (rs.rate() != d.sampleRate()) rs = new Resampler(d.sampleRate(), rate);
                    int ch = Math.max(1, d.channels());
                    int want = Math.min(4096, in.length / ch);
                    if (r.space() < (int) (want * (double) rate / d.sampleRate()) + 64) { Thread.sleep(8); continue; }
                    int n = d.read(in, want);
                    if (n == 0) { Thread.sleep(2); continue; }
                    if (n < 0) {
                        // la siguiente (del disco o de la cola), pegada: sin hueco. Si una no se abre, la de después
                        Decoder d2 = null; String nx = null;
                        while (my == gen && d2 == null) {
                            nx = pollNext();
                            if (nx == null) break;
                            try { d2 = factory.open(nx); } catch (Throwable t) { d2 = null; }
                        }
                        if (nx != null) {
                            if (d2 != null && my == gen) {
                                d.close(); d = d2; cur = nx;
                                rs = rs.rate() == d.sampleRate() ? rs : new Resampler(d.sampleRate(), rate);
                                synchronized (marks) { marks.add(new long[] { r.written(), d.durationUs() }); markInfo.add(new Object[] { nx }); }
                                continue;
                            }
                            if (d2 != null) try { d2.close(); } catch (Throwable ignored) { }
                        }
                        eof = true;
                        return;
                    }
                    float[] st = toStereo(in, n, ch);
                    float[] o = rs.process(st, n);
                    int frames = rs.outFrames();
                    if (my != gen) return;
                    r.write(o, frames);
                }
            } catch (InterruptedException e) {
                // cambio de canción
            } catch (Throwable t) {
                if (my == gen) { failed = true; playing = false; emit("error", id, cur, position(), durUs / 1e6, String.valueOf(t.getMessage())); }
            } finally {
                if (d != null) try { d.close(); } catch (Throwable ignored) { }
            }
        }

        private float[] stereoBuf = new float[0];
        private float[] toStereo(float[] in, int frames, int ch) {
            if (stereoBuf.length < frames * 2) stereoBuf = new float[frames * 2];
            if (ch == 2) { System.arraycopy(in, 0, stereoBuf, 0, frames * 2); return stereoBuf; }
            for (int i = 0; i < frames; i++) {
                float l, r;
                if (ch == 1) { l = r = in[i]; }
                else { l = in[i * ch]; r = in[i * ch + 1]; }
                stereoBuf[2 * i] = l; stereoBuf[2 * i + 1] = r;
            }
            return stereoBuf;
        }

        /** Suma este plato al bloque (con su volumen y el volumen igualado). */
        void render(float[] mix, float[] tmp, int frames) {
            Ring r = ring; if (r == null) return;
            if (seekReq != seekDone) return;
            int got = r.read(tmp, frames);
            final float tv = volume * norm;
            for (int i = 0; i < got; i++) {
                volNow += (tv - volNow) * 0.004f;
                mix[2 * i] += tmp[2 * i] * volNow;
                mix[2 * i + 1] += tmp[2 * i + 1] * volNow;
            }
            if (got > 0) advance(r, got);
            pendingEnd = got < frames && eof && r.available() == 0;
        }
        private boolean pendingEnd;

        private void advance(Ring r, int got) {
            long readPos = r.readTotal();
            synchronized (marks) {
                while (!marks.isEmpty() && readPos >= marks.peek()[0]) {
                    long[] m = marks.poll(); Object[] info = markInfo.poll();
                    long over = readPos - m[0];
                    src = (String) info[0]; durUs = m[1]; baseSec = 0; playedFrames = over;
                    emit("gapless", id, src, position(), durUs / 1e6, null);
                    return;
                }
            }
            playedFrames += got;
        }

        void afterBlock() {
            if (pendingEnd && !endedSent) {
                endedSent = true; pendingEnd = false; playing = false;
                emit("ended", id, src, position(), durUs / 1e6, null);
            }
        }
    }

    // ---------------------------------------------------------------- piezas de sonido
    /** Un anillo de muestras estéreo (un escritor y un lector). */
    static final class Ring {
        private final float[] buf;
        private final int cap;
        private volatile long w, r;
        Ring(int frames) { cap = frames; buf = new float[frames * 2]; }
        int available() { return (int) (w - r); }
        int space() { return cap - available(); }
        long written() { return w; }
        long readTotal() { return r; }
        synchronized void clear() { r = w; }
        void write(float[] src, int frames) {
            for (int i = 0; i < frames; i++) { int p = (int) ((w + i) % cap) * 2; buf[p] = src[2 * i]; buf[p + 1] = src[2 * i + 1]; }
            w += frames;
        }
        synchronized int read(float[] dst, int frames) {
            int n = Math.min(frames, available());
            for (int i = 0; i < n; i++) { int p = (int) ((r + i) % cap) * 2; dst[2 * i] = buf[p]; dst[2 * i + 1] = buf[p + 1]; }
            r += n;
            return n;
        }
    }

    /** Cambio de frecuencia con un filtro de seno cardinal con ventana (32 puntos): limpio, sin «metal» ni pitidos. */
    static final class Resampler {
        private static final int TAPS = 32, PHASES = 512;
        private static final java.util.Map<Integer, float[][]> TABLES = new java.util.HashMap<>();
        private final float[][] table;
        private final int inRate, outRate;
        private final double step;
        private double pos;          // posición en la entrada, relativa al historial
        private float[] hist = new float[TAPS * 2];   // las últimas TAPS tramas (estéreo)
        private float[] outBuf = new float[0];
        private int outFrames;
        private float[] work = new float[0];

        Resampler(int inRate, int outRate) {
            this.inRate = inRate; this.outRate = outRate;
            this.step = inRate / (double) outRate;
            /* al bajar la frecuencia, el filtro corta antes (si no, aparecerían pitidos) */
            double cutoff = 0.95 * Math.min(1.0, outRate / (double) inRate);
            int key = (int) Math.round(cutoff * 10000);
            synchronized (TABLES) { float[][] t = TABLES.get(key); if (t == null) { t = makeTable(cutoff); TABLES.put(key, t); } table = t; }
            reset();
        }
        int rate() { return inRate; }
        int outFrames() { return outFrames; }
        void reset() { java.util.Arrays.fill(hist, 0f); pos = TAPS / 2.0; }

        private static float[][] makeTable(double cutoff) {
            float[][] t = new float[PHASES + 1][TAPS];
            for (int ph = 0; ph <= PHASES; ph++) {
                double frac = ph / (double) PHASES;
                double sum = 0;
                for (int k = 0; k < TAPS; k++) {
                    double x = k - (TAPS / 2 - 1) - frac;
                    double sinc = x == 0 ? 1 : Math.sin(Math.PI * x * cutoff) / (Math.PI * x * cutoff);
                    double wpos = (k - frac + 0.5) / TAPS;           // ventana de Blackman
                    double win = 0.42 - 0.5 * Math.cos(2 * Math.PI * wpos) + 0.08 * Math.cos(4 * Math.PI * wpos);
                    t[ph][k] = (float) (sinc * win);
                    sum += t[ph][k];
                }
                for (int k = 0; k < TAPS; k++) t[ph][k] /= (float) sum;
            }
            return t;
        }

        /** in: tramas estéreo; devuelve el búfer de salida (outFrames() tramas). */
        float[] process(float[] in, int frames) {
            if (inRate == outRate) {
                if (outBuf.length < frames * 2) outBuf = new float[frames * 2];
                System.arraycopy(in, 0, outBuf, 0, frames * 2); outFrames = frames; return outBuf;
            }
            int total = TAPS + frames;
            if (work.length < total * 2) work = new float[total * 2];
            System.arraycopy(hist, 0, work, 0, TAPS * 2);
            System.arraycopy(in, 0, work, TAPS * 2, frames * 2);
            int maxOut = (int) Math.ceil(frames / step) + 4;
            if (outBuf.length < maxOut * 2) outBuf = new float[maxOut * 2];
            int n = 0;
            final double limit = total - TAPS / 2.0;
            while (pos < limit) {
                int ip = (int) Math.floor(pos);
                double frac = pos - ip;
                float[] co = table[(int) Math.round(frac * PHASES)];
                int base = ip - (TAPS / 2 - 1);
                float l = 0, r = 0;
                for (int k = 0; k < TAPS; k++) {
                    int j = base + k;
                    if (j < 0 || j >= total) continue;
                    float c = co[k];
                    l += work[2 * j] * c; r += work[2 * j + 1] * c;
                }
                outBuf[2 * n] = l; outBuf[2 * n + 1] = r; n++;
                pos += step;
            }
            pos -= frames;
            System.arraycopy(work, frames * 2, hist, 0, TAPS * 2);
            outFrames = n;
            return outBuf;
        }
    }

    /** Un filtro del ecualizador (fórmulas de Robert Bristow-Johnson, las mismas que usa el navegador). */
    static final class Biquad {
        static final int PEAKING = 0, LOWSHELF = 1, HIGHSHELF = 2;
        double b0 = 1, b1, b2, a1, a2;
        double tb0 = 1, tb1, tb2, ta1, ta2;
        double l1, l2, r1, r2, lo1, lo2, ro1, ro2;
        boolean flat = true;

        void set(int type, double f, double q, double gainDb, int fs) {
            double[] c = coefs(type, f, q, gainDb, fs);
            b0 = tb0 = c[0]; b1 = tb1 = c[1]; b2 = tb2 = c[2]; a1 = ta1 = c[3]; a2 = ta2 = c[4];
            flat = gainDb == 0;
        }
        /** Cambia hacia los nuevos valores poco a poco (sin chasquidos al mover una banda). */
        void target(int type, double f, double q, double gainDb, int fs) {
            double[] c = coefs(type, f, q, gainDb, fs);
            tb0 = c[0]; tb1 = c[1]; tb2 = c[2]; ta1 = c[3]; ta2 = c[4];
            if (flat && gainDb != 0) { b0 = tb0; b1 = tb1; b2 = tb2; a1 = ta1; a2 = ta2; }
            flat = false;
        }
        void glide() {
            final double k = 0.2;
            b0 += (tb0 - b0) * k; b1 += (tb1 - b1) * k; b2 += (tb2 - b2) * k; a1 += (ta1 - a1) * k; a2 += (ta2 - a2) * k;
        }
        static double[] coefs(int type, double f, double q, double gainDb, int fs) {
            double A = Math.pow(10, gainDb / 40), w0 = 2 * Math.PI * Math.min(f, fs * 0.49) / fs, cs = Math.cos(w0), sn = Math.sin(w0);
            double b0, b1, b2, a0, a1, a2;
            if (type == PEAKING) {
                double alpha = sn / (2 * q);
                b0 = 1 + alpha * A; b1 = -2 * cs; b2 = 1 - alpha * A; a0 = 1 + alpha / A; a1 = -2 * cs; a2 = 1 - alpha / A;
            } else {
                double alpha = sn / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * alpha;
                if (type == LOWSHELF) {
                    b0 = A * ((A + 1) - (A - 1) * cs + sa); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - sa);
                    a0 = (A + 1) + (A - 1) * cs + sa; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - sa;
                } else {
                    b0 = A * ((A + 1) + (A - 1) * cs + sa); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - sa);
                    a0 = (A + 1) - (A - 1) * cs + sa; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - sa;
                }
            }
            return new double[] { b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0 };
        }
        double magnitude(double f, int fs) {
            double w = 2 * Math.PI * f / fs, c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2 * w), s2 = Math.sin(2 * w);
            double nr = b0 + b1 * c1 + b2 * c2, ni = -(b1 * s1 + b2 * s2), dr = 1 + a1 * c1 + a2 * c2, di = -(a1 * s1 + a2 * s2);
            return Math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
        }
        float l(float x) { double y = b0 * x + b1 * l1 + b2 * l2 - a1 * lo1 - a2 * lo2; l2 = l1; l1 = x; lo2 = lo1; lo1 = y; return (float) y; }
        float r(float x) { double y = b0 * x + b1 * r1 + b2 * r2 - a1 * ro1 - a2 * ro2; r2 = r1; r1 = x; ro2 = ro1; ro1 = y; return (float) y; }
    }

    /** Limitador de seguridad: si algo pasa de -1 dB lo baja rápido (3 ms) y lo devuelve despacio (220 ms). */
    static final class Limiter {
        private final float thr = (float) Math.pow(10, -1 / 20.0), att, rel;
        private float g = 1f;
        Limiter(int fs) { att = (float) (1 - Math.exp(-1 / (0.003 * fs))); rel = (float) (1 - Math.exp(-1 / (0.22 * fs))); }
        float gain(float l, float r) {
            float pk = Math.max(Math.abs(l), Math.abs(r));
            float want = pk > thr ? thr / pk : 1f;
            g += (want - g) * (want < g ? att : rel);
            return g;
        }
    }

    /** Lo que ve el visualizador: 256 bandas como las del navegador (de -100 a -30 dB, suavizadas) y la forma de la onda. */
    static final class Spectrum {
        private final float[] win = new float[512], hist = new float[512], smooth = new float[256];
        private int n;
        Spectrum(int fs) { for (int i = 0; i < 512; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / 511)); }
        void push(float[] st, int frames) {
            for (int i = 0; i < frames; i++) { hist[n] = (st[2 * i] + st[2 * i + 1]) * 0.5f; n = (n + 1) & 511; }
        }
        byte[] bins() {
            double[] re = new double[512], im = new double[512];
            for (int i = 0; i < 512; i++) re[i] = hist[(n + i) & 511] * win[i];
            fft(re, im);
            byte[] out = new byte[768];
            for (int k = 0; k < 256; k++) {
                double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]) / 512;
                smooth[k] = (float) (0.8 * smooth[k] + 0.2 * mag);
                double db = 20 * Math.log10(smooth[k] + 1e-12);
                int v = (int) Math.round((db + 100) / 70 * 255);
                out[k] = (byte) Math.max(0, Math.min(255, v));
            }
            /* y la forma de la onda (para el estilo «Ondas»): 512 muestras, 128 = silencio */
            for (int i = 0; i < 512; i++) { int v = (int) Math.round(128 + hist[(n + i) & 511] * 127); out[256 + i] = (byte) Math.max(0, Math.min(255, v)); }
            return out;
        }
        private static void fft(double[] re, double[] im) {
            int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
                for (int i = 0; i < n; i += len) {
                    double cr = 1, ci = 0;
                    for (int k = 0; k < len / 2; k++) {
                        int a = i + k, b = a + len / 2;
                        double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                        re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                        double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                    }
                }
            }
        }
    }

    private static float clamp(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }
}
