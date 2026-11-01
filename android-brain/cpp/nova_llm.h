// Nova's big brain (llama.cpp): load a GGUF model and complete prompts, optionally constrained by a GBNF grammar.
#pragma once
#include <cstdint>
#include <string>

struct NovaLlm;
NovaLlm * nova_load(const char * path, int n_ctx, int n_threads, std::string & error);
std::string nova_complete(NovaLlm * h, const std::string & prompt, const std::string & grammar, int max_tokens,
                          float temperature, float top_p, uint32_t seed, std::string & error);
void nova_cancel(NovaLlm * h);
void nova_free(NovaLlm * h);
const std::string & nova_info(NovaLlm * h);
