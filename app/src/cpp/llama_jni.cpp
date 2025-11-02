// llama_jni.cpp — JNI bridge for llama.cpp (Android)
// - 支持 GBNF（从 Java 传入完整 grammar 文本）
// - 使用 sampler chain，温度/TopP，可选 grammar 采样器
// - 仅 CPU 路径；若后续要 Vulkan/RDMA，可在 CMake 打开相应宏后扩展

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"

// grammar parser 的头文件在不同版本/布局下路径略有不同：
// 先尝试新的 "common/grammar-parser.h"，如果不可用则退回 "grammar-parser.h"
#if __has_include("common/grammar-parser.h")
#include "common/grammar-parser.h"
#elif __has_include("grammar-parser.h")
#include "grammar-parser.h"
#else
#error "Cannot find grammar parser header (common/grammar-parser.h or grammar-parser.h)"
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ZeroLLM", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "ZeroLLM", __VA_ARGS__)

struct LlamaState {
    llama_model*   model  = nullptr;
    llama_context* ctx    = nullptr;
    int n_ctx = 4096;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_Brill_zero_llama_Llama_nativeInit(
        JNIEnv* env, jobject /*thiz*/,
        jstring jModelPath, jint nCtx, jint /*nGpuLayers*/, jint nThreads) {

    const char* cpath = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path(cpath);
    env->ReleaseStringUTFChars(jModelPath, cpath);

    // 全局后端初始化
    llama_backend_init();

    // —— 模型参数 —— //
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap  = true;   // 建议 mmap
    mparams.use_mlock = false;  // Android 不推荐 mlock

    // —— 上下文参数 —— //
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (nCtx > 0 ? nCtx : 4096);
    cparams.n_threads = (nThreads > 0 ? nThreads : 4);
    cparams.seed      = 42;

    // —— 载入模型 —— //
    auto* st = new LlamaState();
    st->model = llama_load_model_from_file(model_path.c_str(), mparams);
    if (!st->model) { LOGE("load_model failed: %s", model_path.c_str()); delete st; return 0; }

    st->ctx = llama_new_context_with_model(st->model, cparams);
    if (!st->ctx) { LOGE("new_context failed"); llama_free_model(st->model); delete st; return 0; }

    st->n_ctx = cparams.n_ctx;
    LOGI("llama init ok: n_ctx=%d, threads=%d", st->n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_Brill_zero_llama_Llama_nativeCompletion(
        JNIEnv* env, jobject /*thiz*/, jlong handle,
        jstring jPrompt, jstring jGrammar, jint maxTokens, jfloat temp, jfloat top_p, jint seed) {

    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st || !st->ctx || !st->model) return env->NewStringUTF("");

    // —— 读取入参字符串 —— //
    const char* cprompt  = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cprompt ? cprompt : "");
    if (cprompt) env->ReleaseStringUTFChars(jPrompt, cprompt);

    std::string grammar_text;
    if (jGrammar) {
        const char* cgram = env->GetStringUTFChars(jGrammar, nullptr);
        if (cgram) grammar_text.assign(cgram);
        if (cgram) env->ReleaseStringUTFChars(jGrammar, cgram);
    }

    // —— 可重复性：如果需要复写种子 —— //
    if (seed >= 0) {
        // 新的采样链一般把随机性放在采样器里；这里保持上下文种子固定
        //（若要“每次不同”，可以在 Java 端传 -1）
    }

    // —— 分词 —— //
    // 第三个参数 add_bos=true（Chat 模型通常需要 BOS）；第四个参数 special=false
    std::vector<llama_token> toks = llama_tokenize(st->ctx, prompt, /*add_special*/true, /*parse_special*/false);
    if (toks.empty()) {
        LOGE("tokenize returned empty");
        return env->NewStringUTF("");
    }

    // —— 构建 batch，前向提示词 —— //
    llama_batch batch = llama_batch_init(/*n_tokens_alloc*/512, /*embd*/0, /*n_seq_max*/1);
    int n_past = 0;

    for (size_t i = 0; i < toks.size(); ++i) {
        llama_batch_add(batch, toks[i], n_past, /*seq_ids*/{0}, /*logits*/false);
        n_past++;
        // 以 chunk 为单位解码，避免 batch 过大；简单起见这里直接整段解码一次也可以
    }
    if (llama_decode(st->ctx, batch) != 0) {
        LOGE("llama_decode(prompt) failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // —— 采样器链（默认 + 参数 + 可选 GBNF）—— //
    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(chain_params);

    // 温度 / top_p（如果你还需要 top_k/min_p/tfs_z，可继续 add）
    if (temp > 0.f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    }
    if (top_p > 0.f && top_p < 1.f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p));
    }

    // —— GBNF grammar（如果提供了 grammar 文本）——
    // 使用 parser 把文本转为规则，随后用 grammar sampler 约束解码
    llama_sampler* smpl_grammar = nullptr;
    if (!grammar_text.empty()) {
        try {
            auto parsed = grammar_parser::parse(grammar_text.c_str());
            // 取模型词表（grammar 需要 vocab）
            const llama_vocab * vocab = llama_model_get_vocab(st->model);
            smpl_grammar = llama_sampler_init_grammar(
                    vocab,
                    parsed.rules,            // std::vector<llama_grammar_element>
                    parsed.symbol_ids,       // std::vector<uint32_t>
                    parsed.start_rule_index  // uint32_t
            );
            if (smpl_grammar) {
                llama_sampler_chain_add(smpl, smpl_grammar);
                LOGI("grammar sampler attached");
            } else {
                LOGE("failed to init grammar sampler");
            }
        } catch (const std::exception& e) {
            LOGE("grammar parse exception: %s", e.what());
        } catch (...) {
            LOGE("grammar parse unknown exception");
        }
    }

    // —— 生成循环 —— //
    std::string out;
    out.reserve(std::max(64, maxTokens * 4)); // 粗略预留

    for (int i = 0; i < maxTokens; ++i) {
        // 采样一个 token（-1 表示基于最近一步 logits）
        llama_token tok = llama_sampler_sample(smpl, st->ctx, -1);
        if (tok == llama_token_eos(st->ctx)) {
            break;
        }

        // 反解成文本片段并累加（注意中文可能有分词切分）
        out += llama_token_to_piece(st->ctx, tok);

        // 将该 token 作为下一步输入继续前向
        llama_batch_clear(batch);
        llama_batch_add(batch, tok, n_past, {0}, /*logits*/false);
        if (llama_decode(st->ctx, batch) != 0) {
            LOGE("llama_decode(step %d) failed", i);
            break;
        }
        n_past++;
    }

    // —— 清理 —— //
    llama_sampler_free(smpl);       // 会连带释放 chain 内的子 sampler（包括 grammar）
    llama_batch_free(batch);

    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_Brill_zero_llama_Llama_nativeFree(JNIEnv*, jobject, jlong handle) {
auto* st = reinterpret_cast<LlamaState*>(handle);
if (!st) return;
if (st->ctx)   llama_free(st->ctx);
if (st->model) llama_free_model(st->model);
llama_backend_free();
delete st;
}
