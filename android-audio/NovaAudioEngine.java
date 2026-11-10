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
        /** 4.0b3 · Cuántas tramas de las ya escritas aún no se oyen (lo que espera en la salida + el altavoz o el Bluetooth). */
        long latencyFrames();
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
    // 3.6: el temporizador para dormir baja todo poco a poco (aunque la app esté dormida)
    private volatile float sleepGain = 1f;
    /* 3.9: fundido para pausar (otra app suena, se desconectan los auriculares) o para volver: lo hace el motor solo,
       aunque la app esté dormida. fadeNow va de 0 a 1 en línea recta y se aplica al cuadrado (al oído baja parejo). */
    private volatile float fadeGoal = 1f, fadeStep = 0f;
    private float fadeNow = 1f;
    private volatile Runnable fadeDone;
    private volatile boolean fadeReset;
    private volatile float fadeResetTo = 1f;
    private final Limiter limiter;
    private volatile boolean viz = false;
    private final Spectrum spectrum;
    private final Bands bands;
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
        bands = new Bands(rate);
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
    /** 3.5b4: el volumen igualado de la canción que suena, aplicado dentro del plato (en un fundido, cada canción el suyo). */
    public void setSongGain(int slot, float g) { if (!Float.isNaN(g)) slots[slot].songGain = clamp(g, 0f, 4f); }
    public void setNext(int slot, String src) { slots[slot].setNext(src); }
    /** 3.5b2: las siguientes canciones en orden; el motor las encadena solo aunque la app esté dormida. */
    public void setQueue(int slot, java.util.List<String> srcs) { slots[slot].setQueue(srcs); }
    /** 3.5b3: la cola con sus fundidos (cuánto dura cada uno, dónde empieza y el volumen igualado de cada canción). */
    public void setItems(int slot, java.util.List<Item> items) { slots[slot].setItems(items); }
    public String source(int slot) { return slots[slot].src; }
    public float normOf(int slot) { return slots[slot].norm; }
    public void setMaster(float v) { master = clamp(v, 0f, 1f); }
    public void setSleepGain(float v) { sleepGain = clamp(v, 0f, 1f); }
    /** 3.9 · Lleva el volumen del fundido a goal (0…1) en ms milisegundos; al llegar, done (en otro hilo). */
    public void fadeTo(float goal, int ms, Runnable done) {
        goal = clamp(goal, 0f, 1f);
        final float from = fadeReset ? fadeResetTo : fadeNow;   // si hay un «ya» pendiente, se parte de él
        fadeStep = Math.abs(goal - from) / Math.max(1f, ms * rate / 1000f);
        fadeDone = done; fadeGoal = goal;
    }
    /** El fundido, ya en v (sin transición): cancela uno a medias. */
    public void fadeSet(float v) { fadeDone = null; v = clamp(v, 0f, 1f); fadeResetTo = v; fadeGoal = v; fadeReset = true; }
    public float fadeLevel() { return fadeReset ? fadeResetTo : fadeNow; }
    /** 4.0b2 · ¿Está bajando para pausar? (un fundido hacia 0 con algo pendiente al acabar) */
    public boolean fadingOut() { return fadeDone != null && fadeGoal < 0.01f; }
    public void setDirect(boolean d) { direct = d; }
    /** 4.0b3 · Con Bluetooth, el sonido se vuelve a comprimir (SBC, AAC, LDAC…) y esa compresión puede pasarse un poco de
     *  los picos: el techo baja a -2 dB para que los auriculares nunca reciban algo que tengan que recortar. */
    private volatile boolean bt;
    public void setBluetooth(boolean on) { if (on != bt) { bt = on; limiter.ceiling(on ? -2f : -1f); } }
    public boolean bluetooth() { return bt; }
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
        // 3.5b3: se baja la mitad de lo que sube (antes 0,85): el limitador cuida los picos y el sonido no queda flojo
        pre = (float) Math.pow(10, -peak * 0.5 / 20);
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
                if (outPlaying) { out.pause(); outPlaying = false; limiter.reset(); specQ.clear(); }
                synchronized (lock) {
                    boolean still = true;
                    for (Slot s : slots) if (s.playing) still = false;
                    if (still && running) { try { lock.wait(250); } catch (InterruptedException e) { return; } }
                }
                continue;
            }
            if (!outPlaying) { out.start(); outPlaying = true; }
            java.util.Arrays.fill(mix, 0f);
            int nPlay = 0; boolean ovl = false;
            for (Slot s : slots) if (s.playing) { nPlay++; ovl |= s.overlapping; s.render(mix, tmp, BLOCK); }
            /* 3.7 · dos canciones a la vez (un fundido): el limitador cuida aunque esté puesto el sonido directo */
            mixingNow = nPlay > 1 || ovl;
            if (viz) bands.push(mix, BLOCK);   // 3.9: antes del volumen general: las luces no dependen de lo alto que lo tengas
            process(mix, BLOCK);
            out.write(mix, BLOCK);
            outFrames += BLOCK;
            try { long lt = out.latencyFrames(); latNow = lt < 0 ? 0 : Math.min(lt, 2L * rate); } catch (Throwable ignored) { }
            for (Slot s : slots) s.afterBlock();
            if (viz) {
                long now = System.nanoTime();
                spectrum.push(mix, BLOCK);
                /* 4.0b3 · lo que ve el visualizador se guarda con «cuándo se va a oír» y se entrega en ese momento: antes
                   llegaba en cuanto se escribía, entre 0,2 s (altavoz) y 0,5 s (Bluetooth) antes de oírse. Un golpe de
                   bombo se apunta en el momento justo (no espera a la siguiente foto). */
                boolean hit = (bands.flags & 1) != 0 && now - lastSpec > 8_000_000L;
                if (hit || now - lastSpec > 33_000_000L) {
                    lastSpec = now;
                    try { byte[] b = spectrum.bins(); bands.fill(b, 768); specQ.add(new Object[] { outFrames, b }); } catch (Throwable ignored) { }
                    while (specQ.size() > 120) { Object[] o = specQ.poll(); Object[] nx = specQ.peek(); if (nx != null) ((byte[]) nx[1])[774] |= ((byte[]) o[1])[774]; }
                }
                long heard = outFrames - latNow; byte[] due = null; int fl = 0;
                while (!specQ.isEmpty() && (Long) specQ.peek()[0] <= heard) { byte[] b = (byte[]) specQ.poll()[1]; fl |= b[774]; due = b; }
                if (due != null) { due[774] = (byte) fl; try { listener.onSpectrum(due); } catch (Throwable ignored) { } }
            } else if (!specQ.isEmpty()) specQ.clear();
        }
    }

    /* 4.0b3 · lo que lleva escrito la salida, lo que tarda en oírse y las fotos del visualizador esperando a su momento */
    private long outFrames;
    private volatile long latNow;
    private final ArrayDeque<Object[]> specQ = new ArrayDeque<>();
    /** Cuánto tarda en oírse lo que sale del motor ahora mismo (segundos). */
    public double latency() { return latNow / (double) rate; }
    /** 4.0b3 · Por dónde va lo que SE OYE (lo de position() menos lo que aún espera en la salida): para la barra, la letra
     *  y el ritmo de las luces. */
    public double heardPosition(int slot) { Slot s = slots[slot]; long pf = s.playedFrames - latNow; return s.baseSec + Math.max(0, pf) / (double) rate; }

    private volatile boolean mixingNow;
    private void process(float[] buf, int frames) {
        final float target = master * sleepGain;
        final boolean useEq = eqOn && !direct;
        final float p = useEq ? pre : 1f;
        final boolean limit = !direct || mixingNow;
        if (fadeReset) { fadeReset = false; fadeNow = fadeResetTo; }
        final float fg = fadeGoal, fs = fadeStep;
        for (int i = 0; i < frames; i++) {
            masterNow += (target - masterNow) * 0.0015f;
            if (fadeNow != fg) fadeNow = fadeNow < fg ? Math.min(fg, fadeNow + fs) : Math.max(fg, fadeNow - fs);
            float l = buf[2 * i] * p, r = buf[2 * i + 1] * p;
            if (useEq) for (Biquad b : eq) { l = b.l(l); r = b.r(r); }
            /* 3.7 · el limitador mira 3 ms por delante (el sonido sale con ese retraso, siempre el mismo): baja el volumen
               ANTES de que llegue el pico, así ningún pico se recorta en seco (antes, en los fundidos, crujía) */
            limiter.push(l, r, limit); l = limiter.outL; r = limiter.outR;
            final float fq = fadeNow * fadeNow;
            l *= masterNow * fq; r *= masterNow * fq;
            buf[2 * i] = l > 1f ? 1f : (l < -1f ? -1f : l);
            buf[2 * i + 1] = r > 1f ? 1f : (r < -1f ? -1f : r);
        }
        if (useEq) for (Biquad b : eq) b.glide();
        if (fadeNow == fadeGoal && fadeDone != null) { final Runnable r = fadeDone; fadeDone = null; new Thread(r, "nova-fundido").start(); }
    }

    private void emit(String type, int slot, String src, double pos, double dur, String msg) {
        try { listener.onEvent(type, slot, src, pos, dur, msg); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- un plato
    /**
     * Una canción de la cola. xf: segundos de fundido para entrar en ella (0 = pegada, sin hueco); at: en qué segundo de
     * la anterior empieza el cambio (0 = al final); st: desde qué segundo empieza esta; g: su volumen igualado (NaN = el que haya).
     */
    public static final class Item {
        /** Cómo se entra: suave (sin ritmo claro), filtro (la anterior se apaga por arriba y esta entra sin graves) o dj (ritmos cuadrados). */
        public static final int SUAVE = 0, FILTRO = 1, DJ = 2;
        public final String src; public final double xf, at, st; public final float g;
        public int style = SUAVE;
        /** dj: en qué segundo del fundido cambian los graves de manos; la velocidad de esta para cuadrar el ritmo; las rejillas de pulsos. */
        public double swap = -1, rate = 1, gridA, beatA, gridB, beatB;
        public Item(String src, double xf, double at, double st, float g) { this.src = src; this.xf = Math.max(0, xf); this.at = at; this.st = Math.max(0, st); this.g = g; }
        public static Item plain(String src) { return new Item(src, 0, 0, 0, Float.NaN); }
    }

    /** Un trozo de sonido ya decodificado y a la frecuencia de salida, esperando a entrar en el anillo. */
    static final class Fifo {
        float[] buf = new float[16384]; int start, len;
        int frames() { return len; }
        void clear() { start = 0; len = 0; }
        void push(float[] src, int frames) {
            if (start + len + frames > buf.length / 2) {
                if (len + frames > buf.length / 2) { float[] nb = new float[(len + frames) * 4]; System.arraycopy(buf, start * 2, nb, 0, len * 2); buf = nb; }
                else System.arraycopy(buf, start * 2, buf, 0, len * 2);
                start = 0;
            }
            System.arraycopy(src, 0, buf, (start + len) * 2, frames * 2); len += frames;
        }
        float l(int i) { return buf[(start + i) * 2]; }
        float r(int i) { return buf[(start + i) * 2 + 1]; }
        void drop(int n) { n = Math.min(n, len); start += n; len -= n; if (len == 0) start = 0; }
        void truncate(int n) { if (n < len) len = Math.max(0, n); }
    }

    private final class Slot {
        final int id;
        volatile String src;
        volatile boolean playing;
        volatile float volume = 1f, norm = 1f;
        // volumen igualado de la canción que se está escribiendo (lo pide la app); gNow va hacia él poco a poco
        volatile float songGain = 1f;
        private float gNow = 1f;
        float volNow = 0f;
        volatile long durUs;
        // lo que el hilo de sonido ya ha sacado (tramas a la frecuencia de salida) desde el principio de la canción actual
        volatile long playedFrames;
        volatile double baseSec;
        Ring ring;
        Thread feeder;
        volatile int gen;
        volatile boolean eof, failed, overlapping;
        volatile int seekReq, seekDone;
        volatile double seekTo;
        // la siguiente y las de después (cola)
        volatile Item nextItem;
        final ArrayDeque<Item> upcoming = new ArrayDeque<>();
        // cambios de canción ya preparados en el anillo: en qué trama empieza cada una
        final ArrayDeque<long[]> marks = new ArrayDeque<>();
        final ArrayDeque<Object[]> markInfo = new ArrayDeque<>();
        volatile boolean endedSent;
        // 3.6: la siguiente que se está abriendo en este momento (aún sin marca en el anillo)
        volatile Item opening;

        Slot(int id) { this.id = id; }

        synchronized void load(String s, double startAt, boolean play) {
            stopFeeder();
            src = s; durUs = 0; playedFrames = 0; baseSec = Math.max(0, startAt);
            eof = false; failed = false; endedSent = false; overlapping = false;
            synchronized (upcoming) { upcoming.clear(); nextItem = null; opening = null; }
            synchronized (marks) { marks.clear(); markInfo.clear(); }
            ring = new Ring(rate * 2);
            seekTo = startAt; seekDone = seekReq; if (startAt > 0) seekReq++;
            playing = play; volNow = 0f; gNow = songGain;
            final int my = ++gen;
            feeder = new Thread(() -> feed(my, s), "orbita-plato-" + id);
            feeder.setPriority(Thread.MAX_PRIORITY);
            feeder.start();
        }

        synchronized void unload() {
            stopFeeder();
            playing = false; src = null; ring = null; eof = false; overlapping = false;
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
            /* si ya se estaban preparando las siguientes (o están fundiéndose), se vuelve a abrir esta y se conservan
               todas, en orden */
            ArrayDeque<Item> rest = new ArrayDeque<>();
            synchronized (marks) { for (Object[] info : markInfo) rest.add((Item) info[0]); }
            Item op = opening;
            if (op != null && !rest.contains(op)) rest.add(op);
            if (!rest.isEmpty() || overlapping) {
                boolean p = playing; String s = src;
                synchronized (upcoming) { if (nextItem != null) rest.add(nextItem); rest.addAll(upcoming); }
                load(s, pos, p);
                synchronized (upcoming) { nextItem = rest.poll(); upcoming.addAll(rest); }
                return;
            }
            synchronized (this) {
                seekTo = Math.max(0, pos); seekReq++;
                baseSec = seekTo; playedFrames = 0; eof = false; endedSent = false;
                synchronized (marks) { marks.clear(); markInfo.clear(); }
                if (ring != null) ring.clear();
            }
        }

        void setNext(String s) { setItems(s == null ? null : java.util.Collections.singletonList(Item.plain(s))); }

        void setQueue(java.util.List<String> list) {
            java.util.ArrayList<Item> it = new java.util.ArrayList<>();
            if (list != null) for (String x : list) if (x != null && !x.isEmpty()) it.add(Item.plain(x));
            setItems(it);
        }

        /** Las siguientes. Las que ya están preparadas en el anillo no se repiten. */
        void setItems(java.util.List<Item> list) {
            ArrayDeque<Item> q = new ArrayDeque<>();
            if (list != null) for (Item x : list) if (x != null && x.src != null && !x.src.isEmpty()) q.add(x);
            synchronized (marks) { for (Object[] info : markInfo) { if (!q.isEmpty() && q.peek().src.equals(((Item) info[0]).src)) q.poll(); } }
            synchronized (upcoming) {
                Item op = opening;
                if (op != null && !q.isEmpty() && q.peek().src.equals(op.src)) q.poll();
                upcoming.clear(); nextItem = q.poll(); upcoming.addAll(q);
            }
        }

        private Item peekNext() { return nextItem; }
        private Item pollNext() { synchronized (upcoming) { Item x = nextItem; nextItem = upcoming.poll(); return x; } }

        double position() { return baseSec + playedFrames / (double) rate; }

        /** Abre la siguiente de la cola que se pueda abrir (si una falla, la de después). */
        private Object[] openNext(int my) {
            while (my == gen) {
                Item nx;
                synchronized (upcoming) { nx = pollNext(); opening = nx; }
                if (nx == null) return null;
                Decoder d2 = null;
                try {
                    d2 = factory.open(nx.src);
                    if (nx.st > 0.05) d2.seekUs((long) (nx.st * 1e6));
                } catch (Throwable t) {
                    if (d2 != null) try { d2.close(); } catch (Throwable ignored) { }
                    d2 = null;
                }
                if (d2 != null) return new Object[] { nx, d2 };
                opening = null;
            }
            opening = null;
            return null;
        }

        private void addMark(Ring r, Item it, Decoder d) {
            synchronized (marks) { marks.add(new long[] { r.written(), d.durationUs(), (long) (it.st * 1e6) }); markInfo.add(new Object[] { it }); opening = null; }
        }

        /** Decodifica un trozo de d en la cola f (ya a la frecuencia de salida). -1 al acabar. */
        private int decodeInto(Decoder d, Resampler[] rs, Fifo f, float[] in) throws Exception {
            int ch = Math.max(1, d.channels());
            if (rs[0] == null || rs[0].rate() != d.sampleRate()) rs[0] = new Resampler(d.sampleRate(), rate);
            int want = Math.min(4096, in.length / ch);
            int n = d.read(in, want);
            if (n <= 0) return n;
            float[] st = toStereo(in, n, ch);
            float[] o = rs[0].process(st, n);
            f.push(o, rs[0].outFrames());
            return n;
        }

        /** El hilo que decodifica: llena el anillo por delante de lo que suena, y hace los fundidos entre canciones. */
        private void feed(int my, String first) {
            Decoder d = null, d2 = null;
            Resampler[] rs = { null }, rs2 = { null };
            Fifo f1 = new Fifo(), f2 = new Fifo();
            float[] in = new float[4096 * 8];
            float[] outb = new float[4096 * 2];
            String cur = first;
            // la canción que se está escribiendo: en qué segundo de su archivo va lo escrito y a qué velocidad va
            double curPos = 0, curRate = 1, glide = 0; long curDurUs = 0;
            boolean d1Done = false;
            Item xf = null; long xfLen = 0, xfDone = 0; double bPos = 0, bRate = 1;
            Biquad hpA = new Biquad(), lpA = new Biquad(), hpB = new Biquad();
            try {
                d = factory.open(first);
                curDurUs = d.durationUs(); durUs = curDurUs;
                emit("loaded", id, first, baseSec, durUs / 1e6, null);
                curPos = baseSec;
                while (my == gen && running) {
                    int sr = seekReq;
                    if (sr != seekDone) {
                        double to = seekTo;
                        d.seekUs((long) (to * 1e6));
                        if (rs[0] != null) { rs[0].reset(); rs[0].setSpeed(1); }
                        f1.clear(); d1Done = false; curPos = to; curRate = 1; glide = 0;
                        Ring rr = ring; if (rr != null) rr.clear();
                        seekDone = sr;
                        if (my == gen) emit("seeked", id, cur, to, durUs / 1e6, null);
                        continue;
                    }
                    Ring r = ring; if (r == null) return;
                    int space = r.space();
                    if (space < 256) { Thread.sleep(6); continue; }

                    if (d2 == null) {
                        // ¿toca empezar a cambiar a la siguiente? (en segundos del archivo de esta)
                        Item nx = peekNext();
                        double at = -1;
                        if (nx != null && !d1Done) {
                            double endSec = curDurUs > 0 ? curDurUs / 1e6 : -1;
                            at = nx.at > 0 ? nx.at : (nx.xf > 0.05 && endSec > 0 ? endSec - nx.xf : -1);
                        }
                        if (at > 0 && curPos >= at - 1e-6) {
                            if (nx.xf > 0.05) {
                                Object[] o = openNext(my);
                                if (o != null) {
                                    xf = (Item) o[0]; d2 = (Decoder) o[1]; rs2[0] = null; f2.clear();
                                    xfLen = Math.max(1, Math.round(xf.xf * rate)); xfDone = 0;
                                    bPos = xf.st; bRate = xf.rate > 0 ? xf.rate : 1;
                                    hpA.reset(); lpA.reset(); hpB.reset(); hpA.identity(); lpA.identity();
                                    wA = 0f; wL = 0f; wB = 1f;
                                    overlapping = true; addMark(r, xf, d2);
                                    continue;
                                }
                            }
                            // cambio seco en ese punto (o la siguiente no se pudo abrir): esta acaba aquí
                            d1Done = true; f1.clear();
                        }
                        if (!d1Done && f1.frames() < 2048) {
                            int n = decodeInto(d, rs, f1, in);
                            if (n < 0) d1Done = true;
                            else if (n == 0) { Thread.sleep(2); continue; }
                        }
                        if (f1.frames() > 0) {
                            int m = Math.min(Math.min(f1.frames(), space), glide != 0 ? 1024 : outb.length / 2);
                            if (at > curPos) m = (int) Math.max(1, Math.min(m, Math.ceil((at - curPos) * rate / curRate)));
                            final float gt = songGain;
                            for (int i = 0; i < m; i++) { gNow += (gt - gNow) * 0.0002f; outb[2 * i] = f1.l(i) * gNow; outb[2 * i + 1] = f1.r(i) * gNow; }
                            if (my != gen) return;
                            r.write(outb, m); f1.drop(m); curPos += m * curRate / rate;
                            // después de un fundido con el ritmo cuadrado: vuelve poco a poco a su velocidad
                            if (glide != 0) {
                                curRate += glide * m;
                                if ((glide > 0 && curRate >= 1) || (glide < 0 && curRate <= 1)) { curRate = 1; glide = 0; }
                                if (rs[0] != null) rs[0].setSpeed(curRate);
                            }
                            continue;
                        }
                        if (d1Done) {
                            // se acabó esta: la siguiente, pegada (sin hueco)
                            Object[] o = openNext(my);
                            if (o == null) {
                                /* 3.6 · aún no hay siguiente: mientras suena lo ya escrito (hasta 2 s) se espera, por si
                                   llega la lista (la app despertando, repetir una, otra vuelta de la lista) */
                                Ring rr = ring;
                                if (rr != null && rr.available() > rate / 20 && my == gen && running && seekReq == seekDone) { Thread.sleep(15); continue; }
                                eof = true; return;
                            }
                            Item it = (Item) o[0];
                            d.close(); d = (Decoder) o[1]; cur = it.src; rs[0] = null;
                            curDurUs = d.durationUs(); curPos = it.st; curRate = 1; glide = 0; d1Done = false;
                            if (!Float.isNaN(it.g)) songGain = clamp(it.g, 0f, 4f);
                            addMark(r, it, d);
                        }
                        continue;
                    }

                    // el fundido: las dos a la vez, en trozos de 10 ms para que filtros y volúmenes se muevan suaves
                    if (rs2[0] == null) { rs2[0] = new Resampler(d2.sampleRate(), rate); rs2[0].setSpeed(bRate); }
                    if (!d1Done && f1.frames() < 2048) { int n = decodeInto(d, rs, f1, in); if (n < 0) d1Done = true; }
                    boolean d2Done = false;
                    if (f2.frames() < 2048) { int n2 = decodeInto(d2, rs2, f2, in); if (n2 < 0) d2Done = true; }
                    int m = d1Done ? f2.frames() : Math.min(f1.frames(), f2.frames());
                    m = Math.min(Math.min(m, space), 480);
                    m = (int) Math.min(m, Math.max(1, xfLen - xfDone));
                    if (m <= 0 && !d2Done) { Thread.sleep(1); continue; }
                    if (m > 0) {
                        double p0 = xfDone / (double) xfLen, p1 = Math.min(1.0, (xfDone + m) / (double) xfLen);
                        float[] g0 = gains(xf, p0), g1 = gains(xf, p1);
                        final float gB = Float.isNaN(xf.g) ? 1f : clamp(xf.g, 0f, 4f);
                        boolean useHpA = false;
                        for (int i = 0; i < m; i++) {
                            /* 4.0b3 · los barridos de los filtros avanzan cada 0,7 ms (antes cada 10 ms, a saltos: se oía un
                               «zumbido» fino en los graves que entraban y en los agudos que se iban) */
                            if ((i & 31) == 0) useHpA = shape(xf, (xfDone + i) / (double) xfLen, hpA, lpA, hpB);
                            float u = m > 1 ? i / (float) (m - 1) : 0;
                            float gOut = g0[0] + (g1[0] - g0[0]) * u, gIn = g0[1] + (g1[1] - g0[1]) * u;
                            /* 3.7 · margen: con las dos casi a tope, se bajan juntas lo justo para que la suma no pase de
                               la potencia de una sola (el nivel que se oye no cambia; los picos ya no chocan con el techo) */
                            float pw = gOut * gOut + gIn * gIn;
                            if (pw > 1f) { float h = (float) (1 / Math.sqrt(pw)); gOut *= h; gIn *= h; }
                            /* 4.0b3 · con las dos sonando fuerte a la vez, un poco menos de volumen (menos de 1 dB en el centro):
                               el limitador casi no tiene que trabajar y el cambio suena limpio */
                            final float trim = 1f - 0.12f * Math.min(gOut, gIn);
                            gOut *= trim; gIn *= trim;
                            float al = 0, ar = 0;
                            if (i < f1.frames()) {
                                al = f1.l(i); ar = f1.r(i);
                                /* 4.0b3 · los filtros trabajan desde el principio del fundido (hasta que les toca, dejan pasar
                                   todo): así, cuando entran, no dan un chasquido */
                                /* y no entran ni salen de golpe: se mezclan con el sonido sin filtrar en 30 ms (al quitar un
                                   filtro de un tirón, la onda saltaba un poco: un chasquido) */
                                float hl = hpA.l(al), hr = hpA.r(ar);
                                wA = step(wA, useHpA); al += (hl - al) * wA; ar += (hr - ar) * wA;
                                float ll = lpA.l(al), lr = lpA.r(ar);
                                wL = step(wL, lpOn); al += (ll - al) * wL; ar += (lr - ar) * wL;
                            }
                            al *= gNow; ar *= gNow;
                            float bl = f2.l(i) * gB, br = f2.r(i) * gB;
                            float fl = hpB.l(bl), fr = hpB.r(br);
                            wB = step(wB, hpBOn); bl += (fl - bl) * wB; br += (fr - br) * wB;
                            outb[2 * i] = al * gOut + bl * gIn; outb[2 * i + 1] = ar * gOut + br * gIn;
                        }
                        if (my != gen) return;
                        r.write(outb, m); f1.drop(m); f2.drop(m); xfDone += m;
                        curPos += m * curRate / rate; bPos += m * bRate / rate;
                        // dj: los pulsos de las dos, en fase (como un DJ que toca el plato)
                        if (xf.style == Item.DJ && xf.beatA > 0 && xf.beatB > 0) {
                            double err = (curPos - xf.gridA) / xf.beatA - (bPos - xf.gridB) / xf.beatB;
                            err -= Math.floor(err); if (err > 0.5) err -= 1;
                            /* 4.0b3 · corrección pequeña y suave (antes hasta ±4 % de golpe cada 10 ms: la entrante «ondulaba») */
                            double want = xf.rate * (1 + Math.max(-0.008, Math.min(0.008, err * 0.12)));
                            bRate += (want - bRate) * 0.12;
                            rs2[0].setSpeed(bRate);
                        }
                    }
                    if (xfDone >= xfLen || d2Done || (d1Done && f1.frames() == 0)) {
                        // terminado: la nueva sigue sola (y si iba acelerada o frenada, vuelve despacio a su velocidad)
                        d.close(); d = d2; d2 = null; rs[0] = rs2[0]; rs2[0] = null;
                        if (!Float.isNaN(xf.g)) { songGain = clamp(xf.g, 0f, 4f); gNow = songGain; }
                        else { songGain = 1f; gNow = 1f; }   // 4.0b3 · la entrante sonó a 1 en el fundido: sigue igual (sin escalón)
                        Fifo tmp = f1; f1 = f2; f2 = tmp; f2.clear();
                        cur = xf.src; curDurUs = d.durationUs(); curPos = bPos; curRate = bRate;
                        // 4.0b3 · vuelve a su velocidad en 16 s (antes 6: se notaba cómo cambiaba el tono al empezar)
                        glide = Math.abs(curRate - 1) > 1e-4 ? (1 - curRate) / (16.0 * rate) : 0;
                        d1Done = d2Done; xf = null; overlapping = false;
                    }
                }
            } catch (InterruptedException e) {
                // cambio de canción
            } catch (Throwable t) {
                if (my == gen) { failed = true; playing = false; emit("error", id, cur, position(), durUs / 1e6, String.valueOf(t.getMessage())); }
            } finally {
                if (d != null) try { d.close(); } catch (Throwable ignored) { }
                if (d2 != null) try { d2.close(); } catch (Throwable ignored) { }
                overlapping = false;
            }
        }

        /* ---- las curvas de cada estilo (las mismas que los fundidos de la app) ---- */
        private boolean lpOn, hpBOn;
        /* 4.0b3 · cuánto se oye cada filtro (0 = nada, 1 = del todo): va hacia donde toca en 30 ms */
        private float wA, wL, wB;
        private float step(float w, boolean on) { final float k = 1f / (0.03f * rate); return on ? Math.min(1f, w + k) : Math.max(0f, w - k); }
        private float cUp(double x) { x = Math.max(0, Math.min(1, x)); return (float) Math.sin(x * Math.PI / 2); }
        private float cDown(double x) { x = Math.max(0, Math.min(1, x)); return (float) Math.cos(x * Math.PI / 2); }
        private float cS(double x) { x = Math.max(0, Math.min(1, x)); return (float) (0.5 - 0.5 * Math.cos(x * Math.PI)); }
        private double sweep(double f0, double f1, double u) { u = Math.max(0, Math.min(1, u)); return f0 * Math.pow(f1 / f0, u); }
        private double swapAt(Item it) { return it.swap > 0 && it.xf > 0 ? Math.max(0.15, Math.min(0.85, it.swap / it.xf)) : 0.55; }

        /** Volumen de la que se va [0] y de la que entra [1] en el punto p (0…1) del fundido. */
        private float[] gains(Item it, double p) {
            if (it.style == Item.FILTRO) return new float[] { p < 0.2 ? 1f : cDown((p - 0.2) / 0.75), cUp(p / 0.8) };
            if (it.style == Item.DJ) {
                double sw = swapAt(it), bf = it.beatA > 0 ? it.beatA / it.xf : 0.08;
                double xa = Math.max(0.05, sw - 0.75 * bf);
                float in = p < xa ? 0.9f * cS(p / xa) : (p < sw ? (float) (0.9 + 0.1 * (p - xa) / Math.max(1e-6, sw - xa)) : 1f);
                return new float[] { p < sw ? 1f : cDown((p - sw) / (1 - sw)), in };
            }
            // suave: la nueva sube primero; la otra aguanta un poco y luego se va (y los graves cambian de manos en medio)
            return new float[] { p < 0.3 ? 1f : cDown((p - 0.3) / 0.7), cUp(p / 0.7) };
        }

        /** Los filtros en el punto p: los graves nunca suenan dos veces a la vez. Devuelve si el paso-altos de la que se va está puesto. */
        private boolean shape(Item it, double p, Biquad hpA, Biquad lpA, Biquad hpB) {
            double nyq = rate * 0.45;
            if (it.style == Item.FILTRO) {
                lpOn = true; lpA.now(Biquad.LOWPASS, sweep(nyq, 420, p), rate);
                hpBOn = p < 0.75; if (hpBOn) hpB.now(Biquad.HIGHPASS, sweep(1500, 20, p / 0.75), rate);
                return false;
            }
            double xa, xb, lpFrom = 2;
            if (it.style == Item.DJ) {
                double sw = swapAt(it), bf = it.beatA > 0 ? it.beatA / it.xf : 0.08;
                xa = Math.max(0.05, sw - 0.75 * bf); xb = Math.max(xa + 0.02, Math.min(0.98, sw + bf)); lpFrom = sw;
            } else { xa = 0.45; xb = 0.65; }
            hpBOn = p < xb; if (hpBOn) hpB.now(Biquad.HIGHPASS, p < xa ? (it.style == Item.DJ ? 320 : 250) : sweep(it.style == Item.DJ ? 320 : 250, 18, (p - xa) / (xb - xa)), rate);
            lpOn = p > lpFrom; if (lpOn) lpA.now(Biquad.LOWPASS, sweep(nyq, 900, (p - lpFrom) / (1 - lpFrom)), rate);
            boolean hp = p > xa;
            if (hp) hpA.now(Biquad.HIGHPASS, p >= xb ? (it.style == Item.DJ ? 300 : 250) : sweep(14, it.style == Item.DJ ? 300 : 250, (p - xa) / (xb - xa)), rate);
            return hp;
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
                    Item it = (Item) info[0];
                    long over = readPos - m[0];
                    src = it.src; durUs = m[1]; baseSec = m[2] / 1e6; playedFrames = over;
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
        private double step;
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
        void setSpeed(double r) { step = inRate * Math.max(0.8, Math.min(1.25, r)) / (double) outRate; }
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
            if (inRate == outRate && step == 1.0) {
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
        static final int PEAKING = 0, LOWSHELF = 1, HIGHSHELF = 2, HIGHPASS = 3, LOWPASS = 4;
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
            if (type == HIGHPASS || type == LOWPASS) {
                double alpha = sn / (2 * q);
                if (type == HIGHPASS) { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2; }
                else { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = (1 - cs) / 2; }
                a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha;
            } else if (type == PEAKING) {
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
        /** Para los barridos de los fundidos: cambia ya (cada 10 ms), sin perder lo que lleva dentro. */
        void now(int type, double f, int fs) { double[] c = coefs(type, f, 0.707, 0, fs); b0 = tb0 = c[0]; b1 = tb1 = c[1]; b2 = tb2 = c[2]; a1 = ta1 = c[3]; a2 = ta2 = c[4]; flat = false; }
        void reset() { l1 = l2 = r1 = r2 = lo1 = lo2 = ro1 = ro2 = 0; }
        /** Deja pasar todo tal cual (pero sigue llevando la cuenta de lo que pasa, para entrar sin chasquido). */
        void identity() { b0 = tb0 = 1; b1 = tb1 = b2 = tb2 = a1 = ta1 = a2 = ta2 = 0; flat = true; }
        float l(float x) { double y = b0 * x + b1 * l1 + b2 * l2 - a1 * lo1 - a2 * lo2; l2 = l1; l1 = x; lo2 = lo1; lo1 = y; return (float) y; }
        float r(float x) { double y = b0 * x + b1 * r1 + b2 * r2 - a1 * ro1 - a2 * ro2; r2 = r1; r1 = x; ro2 = ro1; ro1 = y; return (float) y; }
    }

    /**
     * Limitador de seguridad con anticipación. 4.0b3: rehecho. El de la 3.7 bajaba el volumen demasiado tarde y, en el
     * último momento, lo ajustaba muestra a muestra: en los picos eso equivale a recortar la onda (sonaba «sucio» cuando
     * dos canciones fuertes se juntaban en un fundido). Ahora:
     *  1) cada muestra pide la ganancia que necesita para no pasar de -1 dB;
     *  2) se toma la menor de las que piden las muestras que vienen en los próximos 5 ms;
     *  3) baja al instante a ese valor y vuelve despacio (220 ms);
     *  4) esa curva se suaviza con una media de 5 ms.
     * Como el sonido sale con 5 ms de retraso, la media ya ha llegado abajo cuando el pico sale: nunca se pasa del umbral y
     * el volumen nunca da saltos de una muestra a otra. Sin trabajo (o apagado), la ganancia es exactamente 1.
     */
    static final class Limiter {
        private volatile float thr;
        private final float rel;
        private final int L;                         // muestras de anticipación (5 ms)
        private final float[] dl, dr, need;          // retraso (izquierda, derecha) y lo que pidió cada muestra
        private final long[] qi; private final float[] qv; private int qh, qt;   // mínimo de la ventana (cola monótona)
        private final double[] avg; private double sum;   // la media de los últimos L valores
        private long n;
        private float hold = 1f;
        float outL, outR;
        Limiter(int fs) { this(fs, -1f); }
        Limiter(int fs, float ceilDb) {
            thr = (float) Math.pow(10, ceilDb / 20.0);
            L = Math.max(32, Math.round(0.005f * fs));
            dl = new float[L]; dr = new float[L]; need = new float[L];
            qi = new long[L + 3]; qv = new float[L + 3];
            avg = new double[L];
            rel = (float) (1 - Math.exp(-1 / (0.22 * fs)));
            reset();
        }
        int delay() { return L; }
        /** El techo, en dB (‑1 normalmente; ‑2 con Bluetooth). */
        void ceiling(float db) { thr = (float) Math.pow(10, db / 20.0); }
        int clamps;   // (pruebas) veces que hizo falta el seguro
        /** Al quedarse en silencio (pausa, fin): se vacía el retraso, para que nada de lo anterior salga al volver a sonar. */
        void reset() {
            java.util.Arrays.fill(dl, 0f); java.util.Arrays.fill(dr, 0f); java.util.Arrays.fill(need, 1f);
            java.util.Arrays.fill(avg, 1.0); sum = L; qh = qt = 0; hold = 1f;
        }
        /** Entra una muestra; sale (en outL/outR) la de hace L muestras, ya con su volumen. on=false: no pide bajar (y en
         *  cuanto termina lo que estaba bajando, pasa tal cual: ganancia 1 exacta). */
        void push(float l, float r, boolean on) {
            final int w = (int) (n % L);
            float pk = Math.max(Math.abs(l), Math.abs(r));
            float want = on && pk > thr ? thr / pk : 1f;
            float ol = dl[w], or = dr[w], oNeed = need[w];
            dl[w] = l; dr[w] = r; need[w] = want;
            // el mínimo de lo que piden las L+1 últimas muestras (la que sale ahora y las L que vienen detrás)
            final int cap = qi.length;
            while (qh != qt && qv[(qt - 1 + cap) % cap] >= want) qt = (qt - 1 + cap) % cap;
            qi[qt] = n; qv[qt] = want; qt = (qt + 1) % cap;
            while (qh != qt && qi[qh] < n - L) qh = (qh + 1) % cap;
            float h = qh != qt ? qv[qh] : 1f;
            // baja al instante, sube despacio
            if (h < hold) hold = h; else { hold += (h - hold) * rel; if (hold > 0.99995f) hold = 1f; }
            // media de L: suave, y cuando el pico sale ya ha llegado abajo
            final int k = (int) (n % L);
            sum += hold - avg[k]; avg[k] = hold;
            float g = (float) (sum / L);
            if (g > 0.99995f) g = 1f;
            if (g > oNeed) { g = oNeed; clamps++; }   // (seguro: con lo anterior no llega a pasar)
            outL = ol * g; outR = or * g;
            n++;
            if ((n & 0xFFFF) == 0) { double t = 0; for (double v : avg) t += v; sum = t; }   // sin errores que se acumulen
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
            byte[] out = new byte[776];   // 256 bandas + 512 de onda + 8 de instrumentos (3.9)
            for (int k = 0; k < 256; k++) {
                double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]) / 512;
                smooth[k] = (float) (0.8 * smooth[k] + 0.2 * mag);
                double db = 20 * Math.log10(smooth[k] + 1e-12);
                int v = (int) Math.round((db + 100) / 70 * 255);
                out[k] = (byte) Math.max(0, Math.min(255, v));
            }
            /* y la forma de la onda (para el estilo «Ondas»): 512 muestras, 128 = silencio */
            for (int i = 0; i < 512; i++) { int v = Math.round(128 + hist[(n + i) & 511] * 127); out[256 + i] = (byte) Math.max(0, Math.min(255, v)); }
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

    /**
     * 3.9 · Lo que suena, por instrumentos, para las luces: bombo (graves de menos de 110 Hz), bajo (60-250 Hz),
     * caja (1,5-5 kHz), voz (300-3400 Hz) y platillos (más de 7 kHz). Cada uno con su envolvente, y los golpes
     * (cuando su energía salta por encima de la media de los últimos 0,35 s). Ocho bytes: bombo, bajo, caja, voz,
     * platillos, nivel (de 0 a 255, de -60 a 0 dB) y los golpes desde el último envío (1 bombo, 2 caja, 4 platillos).
     */
    static final class Bands {
        private final Biquad kLp = new Biquad(), kLp2 = new Biquad(), bHp = new Biquad(), bLp = new Biquad(), sHp = new Biquad(), sLp = new Biquad(),
            vHp = new Biquad(), vLp = new Biquad(), hHp = new Biquad();
        private final float att, rel, slow, relK;
        private final int fs;
        private float eK, eB, eS, eV, eH, eL, aK, aS, aH;
        private long n, lastK = -1_000_000, lastS = -1_000_000, lastH = -1_000_000;
        private volatile int flags;
        Bands(int fs) {
            this.fs = fs;
            kLp.set(Biquad.LOWPASS, 110, 0.707, 0, fs); kLp2.set(Biquad.LOWPASS, 110, 0.707, 0, fs);
            bHp.set(Biquad.HIGHPASS, 60, 0.707, 0, fs); bLp.set(Biquad.LOWPASS, 250, 0.707, 0, fs);
            sHp.set(Biquad.HIGHPASS, 1500, 0.707, 0, fs); sLp.set(Biquad.LOWPASS, 5000, 0.707, 0, fs);
            vHp.set(Biquad.HIGHPASS, 300, 0.707, 0, fs); vLp.set(Biquad.LOWPASS, 3400, 0.707, 0, fs);
            hHp.set(Biquad.HIGHPASS, 7000, 0.707, 0, fs);
            att = (float) (1 - Math.exp(-1 / (0.004 * fs))); rel = (float) (1 - Math.exp(-1 / (0.10 * fs)));
            relK = (float) (1 - Math.exp(-1 / (0.16 * fs))); slow = (float) (1 - Math.exp(-1 / (0.35 * fs)));
        }
        private float env(float e, float x, float r) { return e + (x - e) * (x > e ? att : r); }
        void push(float[] st, int frames) {
            for (int i = 0; i < frames; i++) {
                float x = (st[2 * i] + st[2 * i + 1]) * 0.5f;
                float k = Math.abs(kLp2.l(kLp.l(x))), b = Math.abs(bLp.l(bHp.l(x))), sn = Math.abs(sLp.l(sHp.l(x))), v = Math.abs(vLp.l(vHp.l(x))), h = Math.abs(hHp.l(x));
                eK = env(eK, k, relK); eB = env(eB, b, rel); eS = env(eS, sn, rel); eV += (v - eV) * slow * 4; eH = env(eH, h, rel); eL += (Math.abs(x) - eL) * slow * 4;
                aK += (eK - aK) * slow; aS += (eS - aS) * slow; aH += (eH - aH) * slow;
                if ((n & 63) == 0) {
                    if (eK > aK * 1.45f && eK > 0.02f && n - lastK > fs * 0.22) { flags |= 1; lastK = n; }
                    // la caja suena sobre todo entre 1,5 y 5 kHz; un platillo, mucho más arriba (así no se confunden)
                    if (eS > aS * 1.6f && eS > 0.01f && eS > eH * 0.6f && n - lastS > fs * 0.14) { flags |= 2; lastS = n; }
                    if (eH > aH * 1.6f && eH > 0.004f && eH > eS * 0.8f && n - lastH > fs * 0.07) { flags |= 4; lastH = n; }
                }
                n++;
            }
        }
        private static byte db(float e) { double d = 20 * Math.log10(e + 1e-7); return (byte) Math.max(0, Math.min(255, (int) Math.round((d + 60) / 60 * 255))); }
        void fill(byte[] out, int at) {
            if (out.length < at + 8) return;
            out[at] = db(eK); out[at + 1] = db(eB); out[at + 2] = db(eS); out[at + 3] = db(eV); out[at + 4] = db(eH); out[at + 5] = db(eL);
            out[at + 6] = (byte) flags; out[at + 7] = 0; flags = 0;
        }
    }

    private static float clamp(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }
}
