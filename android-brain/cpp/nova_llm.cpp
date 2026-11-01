// Nova's big brain: a small chat model (Qwen3, GGUF) running with llama.cpp, on the phone, without internet.
// One model, one conversation slot. The prompt prefix already in memory is reused (only the new part is read),
// and an optional GBNF grammar keeps the answer in the format the app expects.
#include "nova_llm.h"
#include "llama.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

struct NovaLlm {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> cached;      // tokens currently in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
    std::string last_info;
    int n_threads = 4;
};

static std::once_flag g_backend_once;
static void quiet_log(enum ggml_log_level, const char *, void *) {}

static double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

NovaLlm * nova_load(const char * path, int n_ctx, int n_threads, std::string & error) {
    std::call_once(g_backend_once, [] { llama_log_set(quiet_log, nullptr); llama_backend_init(); });
    auto * h = new NovaLlm();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;      // the file is read from storage as needed, not copied into memory
    h->model = llama_model_load_from_file(path, mp);
    if (!h->model) { error = "no se pudo leer el modelo"; delete h; return nullptr; }
    h->vocab = llama_model_get_vocab(h->model);
    h->n_threads = n_threads > 0 ? n_threads : 4;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx > 0 ? (uint32_t) n_ctx : 1536;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = h->n_threads;
    cp.n_threads_batch = h->n_threads;
    cp.no_perf = true;
    cp.abort_callback = [](void * data) -> bool { return ((NovaLlm *) data)->cancel.load(); };
    cp.abort_callback_data = h;
    h->ctx = llama_init_from_model(h->model, cp);
    if (!h->ctx) { error = "no hay memoria para el contexto"; llama_model_free(h->model); delete h; return nullptr; }
    return h;
}

void nova_free(NovaLlm * h) {
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

void nova_cancel(NovaLlm * h) { if (h) h->cancel.store(true); }

const std::string & nova_info(NovaLlm * h) { return h->last_info; }

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> out(n > 0 ? n : 0);
    if (n > 0 && llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, false, true) < 0) out.clear();
    return out;
}

static std::string piece(const llama_vocab * vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, t, &big[0], (int32_t) big.size(), 0, false);
        return n > 0 ? big.substr(0, n) : std::string();
    }
    return std::string(buf, n);
}

// The logits of the last position, as candidates for the samplers
static void fill_candidates(llama_context * ctx, const llama_vocab * vocab, std::vector<llama_token_data> & cur, llama_token_data_array & arr) {
    const float * logits = llama_get_logits_ith(ctx, -1);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    cur.resize(n_vocab);
    for (int i = 0; i < n_vocab; i++) cur[i] = llama_token_data{ (llama_token) i, logits[i], 0.0f };
    arr = llama_token_data_array{ cur.data(), cur.size(), -1, false };
}

std::string nova_complete(NovaLlm * h, const std::string & prompt, const std::string & grammar, int max_tokens,
                          float temperature, float top_p, uint32_t seed, std::string & error) {
    h->cancel.store(false);
    const double t0 = now_ms();
    std::vector<llama_token> toks = tokenize(h->vocab, prompt);
    if (toks.empty()) { error = "no se pudo leer la petición"; return ""; }
    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if ((int) toks.size() + max_tokens + 4 > n_ctx) { error = "la petición es demasiado larga"; return ""; }

    // Reuse what's already in memory: only the part of the prompt that changed is read again
    size_t common = 0;
    while (common < h->cached.size() && common < toks.size() && h->cached[common] == toks[common]) common++;
    if (common == toks.size()) common--;   // the last token is always read again, to get its logits
    llama_memory_t mem = llama_get_memory(h->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) { llama_memory_clear(mem, true); common = 0; }
    h->cached.resize(common);
    const int n_batch = 256;
    for (size_t i = common; i < toks.size(); i += n_batch) {
        const int n = (int) std::min<size_t>(n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, n);
        if (llama_decode(h->ctx, b) != 0) {
            llama_memory_clear(mem, true); h->cached.clear();
            error = h->cancel.load() ? "cancelado" : "no se pudo leer la petición";
            return "";
        }
        h->cached.insert(h->cached.end(), toks.begin() + i, toks.begin() + i + n);
    }
    const double t1 = now_ms();

    // Samplers: the usual chain, and the grammar checked only when the chosen token breaks it (much faster)
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p > 0 ? top_p : 0.9f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    }
    llama_sampler * grmr = nullptr;
    if (!grammar.empty()) {
        grmr = llama_sampler_init_grammar(h->vocab, grammar.c_str(), "root");
        if (!grmr) { llama_sampler_free(chain); error = "gramática no válida"; return ""; }
    }

    std::string out;
    std::vector<llama_token_data> cur;
    llama_token_data_array arr;
    int generated = 0;
    bool stopped_by_grammar = false;
    while (generated < max_tokens) {
        if (h->cancel.load()) { error = "cancelado"; break; }
        fill_candidates(h->ctx, h->vocab, cur, arr);
        llama_sampler_apply(chain, &arr);
        llama_token id = arr.data[arr.selected].id;
        if (grmr) {
            llama_token_data one = { id, 1.0f, 0.0f };
            llama_token_data_array one_arr = { &one, 1, -1, false };
            llama_sampler_apply(grmr, &one_arr);
            if (one_arr.data[0].logit == -INFINITY) {
                fill_candidates(h->ctx, h->vocab, cur, arr);
                llama_sampler_apply(grmr, &arr);
                llama_sampler_apply(chain, &arr);
                if (arr.selected < 0) { stopped_by_grammar = true; break; }
                id = arr.data[arr.selected].id;
            }
            llama_sampler_accept(grmr, id);
        }
        llama_sampler_accept(chain, id);
        if (llama_vocab_is_eog(h->vocab, id)) break;
        out += piece(h->vocab, id);
        generated++;
        llama_batch b = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, b) != 0) { error = h->cancel.load() ? "cancelado" : "error al pensar"; break; }
        h->cached.push_back(id);
    }
    const double t2 = now_ms();
    if (grmr) llama_sampler_free(grmr);
    llama_sampler_free(chain);
    (void) stopped_by_grammar;

    char info[256];
    snprintf(info, sizeof(info), "{\"prompt\":%d,\"reused\":%d,\"generated\":%d,\"msPrompt\":%d,\"msGen\":%d}",
             (int) toks.size(), (int) common, generated, (int) (t1 - t0), (int) (t2 - t1));
    h->last_info = info;
    return out;
}
