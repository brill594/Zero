// llama_jni.cpp — Android + 最新 vocab API 版
// - 分词: llama_tokenize(vocab, ...)
// - 反解: llama_detokenize(vocab, ...)，两段式分配缓冲
// - 生成: 手动填充 llama_batch + llama_decode（不依赖 batch_add/clear 辅助）
// - 选词: 贪婪解码（argmax）；后续要 top-p/temperature 再加也容易
// - API: 使用 llama_model_load_from_file / llama_init_from_model / llama_model_free

// 标准库
#include <string>
#include <vector>
#include <algorithm>
#include <cstdarg>

// JNI / Android 头文件（IDE 若未配置 NDK，可使用轻量级兜底）
#if __has_include(<jni.h>)
#  include <jni.h>
#else
   typedef void* JNIEnv;          // 仅供静态分析兜底使用
   typedef void* jobject;
   typedef long long jlong;
   typedef const char* jstring;
#  define JNIEXPORT
#  define JNICALL
#endif

#if __has_include(<android/log.h>)
#  include <android/log.h>
#else
#  include <stdio.h>
   static inline int __android_log_print(int, const char*, const char* fmt, ...) {
       va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); fprintf(stderr, "\n"); va_end(ap); return 0;
   }
#  define ANDROID_LOG_ERROR 6
#  define ANDROID_LOG_INFO  4
#endif

// llama.cpp 与 ggml 头文件（IDE 未识别 includePath 时，加兜底路径）
// 先引入 ggml 基础头，再引入 llama 顶层头，确保 IDE 能解析到 ggml-cpu.h
#if __has_include("ggml.h")
#  include "ggml.h"
#elif __has_include("../../../../third_party/llama.cpp/ggml/include/ggml.h")
#  include "../../../../third_party/llama.cpp/ggml/include/ggml.h"
#endif

#if __has_include("ggml-backend.h")
#  include "ggml-backend.h"
#elif __has_include("../../../../third_party/llama.cpp/ggml/include/ggml-backend.h")
#  include "../../../../third_party/llama.cpp/ggml/include/ggml-backend.h"
#endif

// ggml-cpu.h：优先使用编译器的头搜索路径；若 IDE 未解析 CMake includePath，则提供供应商兜底路径
#if __has_include("ggml-cpu.h")
#  include "ggml-cpu.h"
#elif __has_include("../../../../third_party/llama.cpp/ggml/include/ggml-cpu.h")
#  include "../../../../third_party/llama.cpp/ggml/include/ggml-cpu.h"
#elif __has_include("../../../../third_party/llama.cpp/ggml/src/ggml-cpu.h")
#  include "../../../../third_party/llama.cpp/ggml/src/ggml-cpu.h"
#else
#  error "ggml-cpu.h not found: please ensure CMake includes ggml/include and ggml/src"
#endif

#if __has_include("llama.h")
#  include "llama.h"
#elif __has_include("../../../../third_party/llama.cpp/include/llama.h")
#  include "../../../../third_party/llama.cpp/include/llama.h"
#else
   // 仅用于静态分析兜底的前置声明；真实构建使用上面的头文件
   struct llama_model;
   struct llama_context;
   struct llama_vocab;
   typedef int llama_token;
   struct llama_batch { int n_tokens; };
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ZeroLLM", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "ZeroLLM", __VA_ARGS__)

struct LlamaState {
    llama_model*         model  = nullptr;
    llama_context*       ctx    = nullptr;
    const llama_vocab*   vocab  = nullptr;
    int n_ctx     = 4096;
    int n_threads = 4;
};

// ---- vocab 版分词（两次调用拿长度）----
static std::vector<llama_token>
tokenize_with_vocab(const llama_vocab* vocab, const std::string& text,
                    bool add_special, bool parse_special) {
    const int len = (int)text.size();
    int n = llama_tokenize(vocab, text.c_str(), len,
            /*tokens*/nullptr, /*max*/0,
                           add_special, parse_special);
    int need = n >= 0 ? n : -n;
    std::vector<llama_token> toks(need);
    n = llama_tokenize(vocab, text.c_str(), len,
                       toks.data(), (int)toks.size(),
                       add_special, parse_special);
    if (n < 0) n = -n;
    toks.resize(n);
    return toks;
}

// ---- 单 token 反解为 std::string（vocab 版 detokenize）----
static std::string detok_one(const llama_vocab* vocab, llama_token tok,
                             bool remove_special = false, bool unparse_special = false) {
    int need = llama_detokenize(vocab, &tok, 1, /*out*/nullptr, /*max*/0,
                                remove_special, unparse_special);
    if (need < 0) need = -need;
    std::string s;
    s.resize(need);
    // NDK/IDE 某些模式下 std::string::data() 可能推断为 const char*；改用 &s[0]
    char* out_buf = s.empty() ? nullptr : &s[0];
    int got = llama_detokenize(vocab, &tok, 1, out_buf, s.empty() ? 0 : need,
                               remove_special, unparse_special);
    if (got < 0) got = -got;
    s.resize(got);
    return s;
}

// ---- 不依赖 inline 的 batch_*：手工 push/clear ----
static inline void batch_push_token(llama_batch& batch,
                                    llama_token tok, int32_t pos,
                                    bool want_logits) {
    const int i = batch.n_tokens;
    batch.token[i]     = tok;
    batch.pos[i]       = pos;
    batch.n_seq_id[i]  = 1;
    batch.seq_id[i][0] = 0;     // 单序列 id = 0
    batch.logits[i]    = want_logits;
    batch.n_tokens     = i + 1;
}
static inline void batch_clear_tokens(llama_batch& batch) { batch.n_tokens = 0; }

extern "C" JNIEXPORT jlong JNICALL
Java_com_brill_zero_llama_Llama_nativeInit(
        JNIEnv* env, jobject /*thiz*/,
        jstring jModelPath, jint nCtx, jint nGpuLayers, jint nThreads) {

    const char* cpath = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path = cpath ? cpath : "";
    if (cpath) env->ReleaseStringUTFChars(jModelPath, cpath);

    llama_backend_init();

    // 设备探测：如无 GPU/IGPU 设备，禁用 GPU 卸载以避免后端空指针
    size_t dev_count = ggml_backend_dev_count();
    int gpu_like = 0;
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            gpu_like++;
        }
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap  = true;
    mparams.use_mlock = false;
    // 显式覆盖 GPU 层数（包括 0，避免默认 999 触发大量卸载）
    mparams.n_gpu_layers = nGpuLayers;
    // 当不卸载至 GPU 时，关闭多设备拆分，保持 CPU-only 更稳
    if (mparams.n_gpu_layers == 0) {
        mparams.split_mode = LLAMA_SPLIT_MODE_NONE;
        mparams.use_extra_bufts = false; // 保守：禁用重量重打包，避免误触发设备缓冲
    }
    // 当 CPU-only 时，限制设备列表为仅 CPU，避免 Vulkan 后端初始化与调度参与
    ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    ggml_backend_dev_t devices_cpu_only[2] = { cpu_dev, nullptr };
    if (mparams.n_gpu_layers == 0 || gpu_like == 0) {
        mparams.devices    = devices_cpu_only;
        mparams.main_gpu   = 0;
        mparams.tensor_split = nullptr;
    }
    // 若未检测到 GPU/IGPU 设备，强制 CPU 模式
    if (gpu_like == 0 && mparams.n_gpu_layers > 0) {
        LOGE("no GPU devices detected: forcing n_gpu_layers=0 (was %d)", (int)mparams.n_gpu_layers);
        mparams.n_gpu_layers = 0;
        mparams.split_mode = LLAMA_SPLIT_MODE_NONE;
        mparams.use_extra_bufts = false;
        mparams.devices    = devices_cpu_only;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (nCtx > 0 ? nCtx : 4096);
    cparams.n_threads = (nThreads > 0 ? nThreads : 4);
    // 为提示词批处理也设置线程数（默认可能与 n_threads 不同，显式统一）
    cparams.n_threads_batch = cparams.n_threads;
    // 保守：禁用 KQV 卸载，先验证稳定性（后续再按需开启）
    cparams.offload_kqv = false;

    auto* st = new LlamaState();
    st->n_ctx     = cparams.n_ctx;
    st->n_threads = cparams.n_threads;

    // ✅ 使用新 API 名称
    st->model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!st->model) { LOGE("load_model failed: %s", model_path.c_str()); delete st; return 0; }

    st->ctx = llama_init_from_model(st->model, cparams);
    if (!st->ctx)   { LOGE("init_from_model failed"); llama_model_free(st->model); delete st; return 0; }

    // 显式设置生成与批处理线程数，确保运行时一致
    llama_set_n_threads(st->ctx, st->n_threads, st->n_threads);

    st->vocab = llama_model_get_vocab(st->model);
    if (!st->vocab) { LOGE("get_vocab failed"); llama_free(st->ctx); llama_model_free(st->model); delete st; return 0; }

    LOGI("llama init ok: n_ctx=%d, threads=%d, threads_batch=%d, n_gpu_layers=%d, devices_total=%zu, gpu_like=%d",
         st->n_ctx, st->n_threads, llama_n_threads_batch(st->ctx), mparams.n_gpu_layers, dev_count, gpu_like);
    return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_brill_zero_llama_Llama_nativeCompletion(
        JNIEnv* env, jobject /*thiz*/, jlong handle,
        jstring jPrompt, jstring jGrammar, jint maxTokens, jfloat temp, jfloat top_p, jint seed) {

    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st || !st->ctx || !st->model || !st->vocab) return env->NewStringUTF("");

    const char* cprompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt = cprompt ? cprompt : "";
    if (cprompt) env->ReleaseStringUTFChars(jPrompt, cprompt);

    // 可选：回调 Java 端进度方法
    jclass jLlamaCls = env->FindClass("com/brill/zero/llama/Llama");
    jmethodID jOnProgress = jLlamaCls ? env->GetStaticMethodID(jLlamaCls, "onNativeProgress", "(II)V") : nullptr;

    // 1) 分词（vocab 版）
    std::vector<llama_token> toks = tokenize_with_vocab(st->vocab, prompt, /*add_special*/true, /*parse_special*/false);
    if (toks.empty()) { LOGE("tokenize returned empty"); return env->NewStringUTF(""); }
    LOGI("tokenize ok: %d tokens; maxNew=%d", (int)toks.size(), maxTokens);

    // 2) 提示词进 KV（最后一个 token 打开 logits）
    llama_batch batch = llama_batch_init(
            /*n_tokens_alloc*/ std::max(512, (int)toks.size() + maxTokens + 8),
            /*embd*/ 0, /*n_seq_max*/ 1);
    int n_past = 0;
    for (size_t i = 0; i < toks.size(); ++i) {
        const bool want_logits = (i + 1 == toks.size());
        batch_push_token(batch, toks[i], n_past, want_logits);
        n_past++;
    }
    if (llama_decode(st->ctx, batch) != 0) {
        LOGE("llama_decode(prompt) failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }
    LOGI("prompt loaded into KV: n_past=%d", n_past);

    // 2.5) 构建采样器链（按需接入温度 / top-p / 语法 + 以 greedy 收尾）
    auto sparams = llama_sampler_chain_default_params();
    struct llama_sampler* chain = llama_sampler_chain_init(sparams);
    // 温度（t<=0 视为禁用）
    if (temp > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
    }
    // top-p（>=1 视为禁用；保留至少一个候选）
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, /*min_keep*/1));
    }
    // 语法（若 jGrammar 非空）
    std::string grammar_str;
    if (jGrammar) {
        const char* cgram = env->GetStringUTFChars(jGrammar, nullptr);
        grammar_str = cgram ? cgram : "";
        if (cgram) env->ReleaseStringUTFChars(jGrammar, cgram);
        if (!grammar_str.empty()) {
            struct llama_sampler* gram = llama_sampler_init_grammar(st->vocab, grammar_str.c_str(), "root");
            if (gram) {
                llama_sampler_chain_add(chain, gram);
                LOGI("grammar sampler enabled (root='root')");
            } else {
                LOGE("grammar parse failed; continuing without grammar");
            }
        }
    }
    // 以 greedy 作为最后选择器
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    // 3) 生成循环（采样链 + 语法约束）
    const int64_t t_start_us = llama_time_us();
    std::string out;
    out.reserve(std::max(64, maxTokens * 4));

    const llama_token eos = llama_vocab_eos(st->vocab);       // ✅ EOS 走 vocab 版
    const int n_vocab = llama_n_vocab(st->vocab);              // 或者 llama_vocab_n_tokens(st->vocab)
    bool saw_open_brace = false;  // 见到 '{' 后再等待 '}' 提前收敛

    int gen_tokens = 0;
    for (int i = 0; i < maxTokens; ++i) {
        // 采样链基于最后一个 token 的 logits 采样
        const llama_token tok = llama_sampler_sample(chain, st->ctx, /*idx*/-1);
        if (tok == eos) break;

        // detokenize 当前 token
        std::string piece = detok_one(st->vocab, tok, /*remove_special=*/false, /*unparse_special=*/false);
        out += piece;
        {
            // 每步详细日志（片段最多 48 字符）
            std::string frag = piece.size() > 48 ? piece.substr(0, 48) : piece;
            LOGI("llama step %d: tok=%d, piece='%s'", i, (int)tok, frag.c_str());
        }

        // 生成进度日志（每 8 个 token 打点）
        if ((i + 1) % 8 == 0) {
            // 仅打印片段的前 32 字符避免日志过长
            std::string frag = piece.size() > 32 ? piece.substr(0, 32) : piece;
            LOGI("llama gen %d tokens, last='%s', out_len=%d", i + 1, frag.c_str(), (int)out.size());
            if (jOnProgress) {
                env->CallStaticVoidMethod(jLlamaCls, jOnProgress, (jint)(i + 1), (jint)maxTokens);
            }
        }

        // 如果看到了 '{'，且随后出现了 '}'，则提前结束
        if (!saw_open_brace && (piece.find('{') != std::string::npos || out.find('{') != std::string::npos)) {
            saw_open_brace = true;
        }
        if (saw_open_brace && (piece.find('}') != std::string::npos || out.find('}') != std::string::npos)) {
            LOGI("llama early stop on '}' after %d tokens", i + 1);
            break;
        }

        // 将该 token 送入下一步；本步要 logits
        batch_clear_tokens(batch);
        batch_push_token(batch, tok, n_past, /*want_logits*/true);
        if (llama_decode(st->ctx, batch) != 0) {
            LOGE("llama_decode(step %d) failed", i);
            break;
        }
        n_past++;
        gen_tokens = i + 1;
    }

    // 释放采样器链
    llama_sampler_free(chain);
    llama_batch_free(batch);
    {
        const int64_t t_end_us = llama_time_us();
        const double elapsed_ms = (t_end_us - t_start_us) / 1000.0;
        const double tps = elapsed_ms > 0.0 ? (gen_tokens * 1000.0 / elapsed_ms) : 0.0;
        std::string frag = out.size() > 64 ? out.substr(0, 64) : out;
        LOGI("nativeCompletion gen=%d tokens in %.1f ms (%.2f tok/s), frag='%s'",
             gen_tokens, elapsed_ms, tps, frag.c_str());
        if (jOnProgress) {
            // 最终一次回调，报告已生成数（可能小于 maxTokens）
            env->CallStaticVoidMethod(jLlamaCls, jOnProgress, (jint)gen_tokens, (jint)maxTokens);
        }
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_brill_zero_llama_Llama_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st) return;
    if (st->ctx)   llama_free(st->ctx);
    if (st->model) llama_model_free(st->model);    // ✅ 新 API 名称
    llama_backend_free();
    delete st;
}
