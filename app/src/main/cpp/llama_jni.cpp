#include <android/log.h>
#include <jni.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * kTag = "ScreenTranslatorSakura";

std::atomic_bool g_cancelled{false};
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
int g_context_size = 2048;
int g_threads = 4;
int g_batch_size = 2048;
bool g_use_repack = false;
bool g_use_big_cores = false;
bool g_use_q8_kv = false;
bool g_prompt_cache_ready = false;
std::vector<llama_token> g_cached_prompt_tokens;
std::mutex g_mutex;

void log_message(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) {
        return;
    }
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        priority = ANDROID_LOG_WARN;
    } else if (level == GGML_LOG_LEVEL_INFO) {
        priority = ANDROID_LOG_INFO;
    }
    __android_log_write(priority, kTag, text);
}

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void release_context() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
}

void release_model() {
    release_context();
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

bool create_context(
        int context_size,
        int threads,
        int batch_size,
        int ubatch_size,
        bool q8_kv,
        std::string & error
) {
    release_context();
    if (g_model == nullptr) {
        error = "model is not loaded";
        return false;
    }

    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(context_size);
    params.n_batch = static_cast<uint32_t>(batch_size);
    params.n_ubatch = static_cast<uint32_t>(
            std::min(batch_size, ubatch_size)
    );
    params.n_threads = threads;
    params.n_threads_batch = threads;
    if (q8_kv) {
        params.type_k = GGML_TYPE_Q8_0;
        params.type_v = GGML_TYPE_Q8_0;
    }
    params.no_perf = false;

    g_context = llama_init_from_model(g_model, params);
    if (g_context == nullptr) {
        error = "llama_init_from_model returned null";
        return false;
    }
    g_context_size = context_size;
    g_threads = threads;
    g_batch_size = batch_size;
    g_use_q8_kv = q8_kv;
    return true;
}

void configure_cpu_affinity(bool big_cores) {
    cpu_set_t mask;
    CPU_ZERO(&mask);
    if (big_cores) {
        CPU_SET(4, &mask);
        CPU_SET(5, &mask);
        CPU_SET(6, &mask);
        CPU_SET(7, &mask);
    } else {
        const long cpu_count = sysconf(_SC_NPROCESSORS_ONLN);
        for (long cpu = 0; cpu < cpu_count && cpu < CPU_SETSIZE; ++cpu) {
            CPU_SET(static_cast<int>(cpu), &mask);
        }
    }
    int result = sched_setaffinity(0, sizeof(mask), &mask);
    if (result != 0) {
        __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "Unable to set big-core affinity"
        );
    }
}

bool abort_callback(void *) {
    return g_cancelled.load();
}

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_oen1day_floatingtranslator_HeavyTranslationService_nativeLoad(
        JNIEnv * env,
        jobject,
        jstring model_path,
        jint context_size,
        jint threads,
        jint batch_size,
        jint ubatch_size,
        jboolean repack,
        jboolean big_cores,
        jboolean q8_kv
) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_cancelled.store(true);
    release_model();
    g_prompt_cache_ready = false;
    g_cached_prompt_tokens.clear();

    const std::string path = to_string(env, model_path);
    if (path.empty()) {
        throw_illegal_state(env, "model path is empty");
        return 1;
    }

    llama_log_set(log_message, nullptr);
    llama_backend_init();
    configure_cpu_affinity(big_cores == JNI_TRUE);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_AUTO;
    model_params.use_extra_bufts = repack == JNI_TRUE;

    __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "Loading Sakura model: %s",
            path.c_str()
    );
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) {
        throw_illegal_state(env, "llama_model_load_from_file returned null");
        return 2;
    }

    std::string error;
    if (!create_context(
            context_size,
            threads,
            batch_size,
            ubatch_size,
            q8_kv == JNI_TRUE,
            error
    )) {
        release_model();
        throw_illegal_state(env, error);
        return 3;
    }

    g_cancelled.store(false);
    g_use_repack = repack == JNI_TRUE;
    g_use_big_cores = big_cores == JNI_TRUE;
    __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "Sakura model ready context=%d threads=%d batch=%d ubatch=%d "
            "repack=%d bigCores=%d q8Kv=%d",
            context_size,
            threads,
            batch_size,
            ubatch_size,
            g_use_repack,
            g_use_big_cores,
            g_use_q8_kv
    );
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_oen1day_floatingtranslator_HeavyTranslationService_nativeIsLoaded(
        JNIEnv *,
        jobject
) {
    return g_model != nullptr && g_context != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_oen1day_floatingtranslator_HeavyTranslationService_nativeCancel(
        JNIEnv *,
        jobject
) {
    g_cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_oen1day_floatingtranslator_HeavyTranslationService_nativeUnload(
        JNIEnv *,
        jobject
) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_cancelled.store(true);
    release_model();
    __android_log_print(ANDROID_LOG_INFO, kTag, "Sakura model unloaded");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_oen1day_floatingtranslator_HeavyTranslationService_nativeGenerate(
        JNIEnv * env,
        jobject,
        jstring prompt_java,
        jint max_tokens
) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_model == nullptr || g_context == nullptr) {
        throw_illegal_state(env, "model is not loaded");
        return nullptr;
    }

    const std::string prompt = to_string(env, prompt_java);
    if (prompt.empty()) {
        throw_illegal_state(env, "prompt is empty");
        return nullptr;
    }

    g_cancelled.store(false);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int prompt_tokens = -llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            nullptr,
            0,
            true,
            true
    );
    if (prompt_tokens <= 0) {
        throw_illegal_state(env, "prompt tokenization failed");
        return nullptr;
    }
    if (prompt_tokens + max_tokens + 8 >= g_context_size) {
        throw_illegal_state(env, "prompt exceeds Sakura context window");
        return nullptr;
    }

    std::vector<llama_token> tokens(static_cast<size_t>(prompt_tokens));
    if (llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
    ) < 0) {
        throw_illegal_state(env, "prompt tokenization failed");
        return nullptr;
    }

    llama_set_abort_callback(g_context, abort_callback, nullptr);

    llama_batch_ext * batch = llama_batch_ext_init(g_context);
    if (batch == nullptr) {
        throw_illegal_state(env, "unable to allocate llama batch");
        return nullptr;
    }

    llama_sampler_chain_params sampler_params =
            llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.20f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(17));

    std::string output;
    output.reserve(static_cast<size_t>(max_tokens) * 3);
    llama_pos position = 0;
    bool failed = false;
    std::string failure;

    llama_memory_t memory = llama_get_memory(g_context);
    if (!g_prompt_cache_ready) {
        llama_memory_clear(memory, true);
    }
    int32_t reusedTokens = 0;
    if (g_prompt_cache_ready && !g_cached_prompt_tokens.empty()) {
        const int32_t limit = std::min(
                prompt_tokens,
                static_cast<int32_t>(g_cached_prompt_tokens.size())
        );
        while (reusedTokens < limit
                && tokens[static_cast<size_t>(reusedTokens)]
                        == g_cached_prompt_tokens[static_cast<size_t>(reusedTokens)]) {
            reusedTokens++;
        }
    }
    reusedTokens = std::min(reusedTokens, prompt_tokens - 1);
    if (reusedTokens < static_cast<int32_t>(g_cached_prompt_tokens.size())) {
        llama_memory_seq_rm(memory, 0, reusedTokens, -1);
    }

    const auto promptStartedAt = std::chrono::steady_clock::now();
    int rc = 0;
    for (int32_t chunkStart = reusedTokens;
         chunkStart < prompt_tokens && rc == 0;
         chunkStart += g_batch_size) {
        const int32_t chunkCount = std::min(
                g_batch_size,
                prompt_tokens - chunkStart
        );
        llama_batch_ext_clear(batch);
        for (int32_t i = 0; i < chunkCount; ++i) {
            const int32_t tokenIndex = chunkStart + i;
            const int32_t index = llama_batch_ext_add_token(
                    batch,
                    0,
                    tokens[static_cast<size_t>(tokenIndex)]
            );
            const llama_pos tokenPosition = tokenIndex;
            llama_batch_ext_set_pos(batch, index, &tokenPosition);
            llama_batch_ext_set_output_logits(
                    batch,
                    index,
                    tokenIndex == prompt_tokens - 1
            );
        }
        rc = llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch);
        position = chunkStart + chunkCount;
    }
    const auto promptFinishedAt = std::chrono::steady_clock::now();
    const auto promptMilliseconds = std::chrono::duration_cast<std::chrono::milliseconds>(
            promptFinishedAt - promptStartedAt
    ).count();
    if (rc != 0) {
        failed = true;
        failure = "prompt decode failed rc=" + std::to_string(rc);
    }

    char piece_buffer[256];
    int generatedCount = 0;
    const auto generationStartedAt = promptFinishedAt;
    for (int generated = 0;
         !failed && generated < max_tokens && !g_cancelled.load();
         ++generated) {
        const llama_token next = llama_sampler_sample(sampler, g_context, -1);
        if (next == LLAMA_TOKEN_NULL || llama_vocab_is_eog(vocab, next)) {
            break;
        }
        llama_sampler_accept(sampler, next);

        const int piece_length = llama_token_to_piece(
                vocab,
                next,
                piece_buffer,
                sizeof(piece_buffer),
                0,
                true
        );
        if (piece_length < 0) {
            failed = true;
            failure = "token-to-piece conversion failed";
            break;
        }
        output.append(piece_buffer, static_cast<size_t>(piece_length));
        generatedCount++;

        llama_batch_ext_clear(batch);
        const int32_t index = llama_batch_ext_add_token(batch, 0, next);
        llama_batch_ext_set_pos(batch, index, &position);
        llama_batch_ext_set_output_logits(batch, index, true);
        ++position;

        rc = llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) {
            failed = true;
            failure = "generation decode failed rc=" + std::to_string(rc);
        }
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_set_abort_callback(g_context, nullptr, nullptr);

    if (failed) {
        llama_memory_clear(memory, true);
        g_prompt_cache_ready = false;
        g_cached_prompt_tokens.clear();
        throw_illegal_state(env, failure);
        return nullptr;
    }
    if (g_cancelled.load()) {
        llama_memory_clear(memory, true);
        g_prompt_cache_ready = false;
        g_cached_prompt_tokens.clear();
        throw_illegal_state(env, "generation cancelled");
        return nullptr;
    }

    llama_memory_seq_rm(memory, 0, prompt_tokens, -1);
    g_cached_prompt_tokens = tokens;
    g_prompt_cache_ready = true;

    __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "Generated %zu bytes promptTokens=%d reusedPromptTokens=%d generatedTokens=%d "
            "promptMs=%lld generateMs=%lld threads=%d",
            output.size(),
            prompt_tokens,
            reusedTokens,
            generatedCount,
            static_cast<long long>(promptMilliseconds),
            static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - generationStartedAt
            ).count()),
            g_threads
    );
    return env->NewStringUTF(output.c_str());
}
