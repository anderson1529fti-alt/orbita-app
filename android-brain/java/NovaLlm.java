package orbita.nova;

/** Puente con el cerebro grande de Nova (llama.cpp, en la librería nativa «nova_llm»). */
public final class NovaLlm {
    private static boolean loaded;
    private static String loadError = "";

    static {
        try {
            System.loadLibrary("nova_llm");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            loadError = String.valueOf(t.getMessage());
        }
    }

    private NovaLlm() {}

    public static boolean available() { return loaded; }

    public static String loadError() { return loadError; }

    public static native long nLoad(String path, int nCtx, int nThreads);

    public static native byte[] nComplete(long h, byte[] prompt, byte[] grammar, int maxTokens, float temperature, float topP, int seed);

    public static native String nLastError();

    public static native String nInfo(long h);

    public static native void nCancel(long h);

    public static native void nFree(long h);
}
