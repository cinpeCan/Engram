#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <memory>
#include <ostream>
#include <string>
#include <vector>

#if ENGRAM_HAS_MNN_LIB
#include "llm/llm.hpp"
#endif

namespace {

constexpr const char* kLogTag = "EngramEdgeMnn";

struct EngramNativeSession {
    std::string model_dir;
    std::atomic<bool> cancel_requested{false};
    std::atomic<bool> released{false};
#if ENGRAM_HAS_MNN_LIB
    std::unique_ptr<MNN::Transformer::Llm> llm;
#endif
};

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

jstring Utf8ToJString(JNIEnv* env, const std::string& utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const size_t n = utf8.size();
    size_t i = 0;
    while (i < n) {
        uint8_t c = static_cast<uint8_t>(utf8[i]);
        uint32_t cp = 0xFFFD;
        size_t adv = 1;
        if ((c & 0x80) == 0) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < n) {
            cp = ((c & 0x1F) << 6) | (static_cast<uint8_t>(utf8[i + 1]) & 0x3F);
            adv = 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < n) {
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<uint8_t>(utf8[i + 1]) & 0x3F) << 6) |
                 (static_cast<uint8_t>(utf8[i + 2]) & 0x3F);
            adv = 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < n) {
            cp = ((c & 0x07) << 18) |
                 ((static_cast<uint8_t>(utf8[i + 1]) & 0x3F) << 12) |
                 ((static_cast<uint8_t>(utf8[i + 2]) & 0x3F) << 6) |
                 (static_cast<uint8_t>(utf8[i + 3]) & 0x3F);
            adv = 4;
        }
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += adv;
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

class CallbackStreamBuf final : public std::streambuf {
public:
    CallbackStreamBuf(
        JNIEnv* env,
        jobject callback,
        jmethodID on_token_method,
        EngramNativeSession* session,
        std::chrono::steady_clock::time_point start_time,
        int max_tokens
    ) : env_(env),
        callback_(callback),
        on_token_method_(on_token_method),
        session_(session),
        start_time_(start_time),
        max_tokens_(max_tokens) {
    }

    int64_t ttft_ms() const { return ttft_ms_ >= 0 ? ttft_ms_ : 0; }
    int generated_tokens() const { return token_count_; }

    void FlushValidUtf8Prefix(bool flush_all) {
        if (pending_utf8_.empty()) return;
        size_t emit_len = pending_utf8_.size();
        if (!flush_all) {
            emit_len = FindCompleteUtf8Boundary(pending_utf8_);
        }
        if (emit_len == 0) return;

        if (ttft_ms_ < 0) {
            auto now = std::chrono::steady_clock::now();
            ttft_ms_ = std::chrono::duration_cast<std::chrono::milliseconds>(now - start_time_).count();
        }
        ++token_count_;
        std::string chunk = pending_utf8_.substr(0, emit_len);
        pending_utf8_.erase(0, emit_len);
        emitted_tail_.append(chunk);
        if (emitted_tail_.size() > 1024) {
            emitted_tail_.erase(0, emitted_tail_.size() - 512);
        }
        if (session_ != nullptr &&
            !session_->cancel_requested.load() &&
            HasPeriodicRepetitionTail(emitted_tail_)) {
            session_->cancel_requested.store(true);
        }
        jstring jdelta = Utf8ToJString(env_, chunk);
        if (jdelta != nullptr) {
            env_->CallVoidMethod(callback_, on_token_method_, jdelta);
            env_->DeleteLocalRef(jdelta);
        }
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            session_->cancel_requested.store(true);
        }
    }

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (n <= 0 || session_->cancel_requested.load()) {
            return n;
        }
        if (token_count_ >= max_tokens_) {
            session_->cancel_requested.store(true);
            return n;
        }

        if (ttft_ms_ < 0) {
            auto now = std::chrono::steady_clock::now();
            ttft_ms_ = std::chrono::duration_cast<std::chrono::milliseconds>(now - start_time_).count();
        }

        pending_utf8_.append(s, static_cast<size_t>(n));
        FlushValidUtf8Prefix(false);
        return n;
    }

    int overflow(int c) override {
        if (c != EOF) {
            char ch = static_cast<char>(c);
            xsputn(&ch, 1);
        }
        return c;
    }

private:
    static size_t FindCompleteUtf8Boundary(const std::string& bytes) {
        const size_t size = bytes.size();
        if (size == 0) return 0;
        size_t tail_cont = 0;
        while (tail_cont < 3 && tail_cont < size &&
               (static_cast<uint8_t>(bytes[size - 1 - tail_cont]) & 0xC0) == 0x80) {
            ++tail_cont;
        }
        if (tail_cont == size) {
            return size;
        }
        const size_t lead_idx = size - 1 - tail_cont;
        const uint8_t lead = static_cast<uint8_t>(bytes[lead_idx]);
        size_t expected_len = 1;
        if ((lead & 0x80) == 0) {
            expected_len = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            expected_len = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            expected_len = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            expected_len = 4;
        } else {
            return size;
        }
        const size_t available = size - lead_idx;
        return (available < expected_len) ? lead_idx : size;
    }

    static bool HasPeriodicRepetitionTail(const std::string& tail) {
        constexpr size_t kMinPeriod = 3;
        constexpr size_t kMaxPeriod = 24;
        constexpr size_t kRepeatCount = 6;
        const size_t total = tail.size();
        for (size_t period = kMinPeriod; period <= kMaxPeriod; ++period) {
            const size_t window = period * kRepeatCount;
            if (total < window) break;
            const char* start = tail.data() + (total - window);
            if (std::memcmp(start, start + period, window - period) == 0) {
                return true;
            }
        }
        return false;
    }

    JNIEnv* env_;
    jobject callback_;
    jmethodID on_token_method_;
    EngramNativeSession* session_;
    std::chrono::steady_clock::time_point start_time_;
    int max_tokens_;
    int64_t ttft_ms_{-1};
    int token_count_{0};
    std::string pending_utf8_;
    std::string emitted_tail_;
};

void RunGenerateStream(
    JNIEnv* env,
    jlong handle,
    const std::string& system_str,
    const std::string& user_str,
    jint max_tokens,
    jobject callback
) {
    if (handle == 0 || callback == nullptr) return;
    auto* session = reinterpret_cast<EngramNativeSession*>(handle);
    if (session->released.load()) return;

    session->cancel_requested.store(false);

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token_id = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_complete_id = env->GetMethodID(cb_cls, "onComplete", "(JJI)V");
    if (on_token_id == nullptr || on_complete_id == nullptr) {
        env->DeleteLocalRef(cb_cls);
        return;
    }

    const auto start_time = std::chrono::steady_clock::now();
    int64_t ttft_ms = 0;
    int generated_tokens = 0;

#if ENGRAM_HAS_MNN_LIB
    if (session->llm != nullptr) {
        session->llm->reset();
        CallbackStreamBuf stream_buf(env, callback, on_token_id, session, start_time, max_tokens);
        std::ostream os(&stream_buf);
        if (!system_str.empty()) {
            MNN::Transformer::ChatMessages messages;
            messages.emplace_back("system", system_str);
            messages.emplace_back("user", user_str);
            session->llm->response(messages, &os, "", 0);
        } else {
            session->llm->response(user_str, &os, "", 0);
        }

        while (!session->llm->stoped() &&
               !session->cancel_requested.load() &&
               stream_buf.generated_tokens() < max_tokens) {
            session->llm->generate(1);
        }
        os.flush();
        stream_buf.FlushValidUtf8Prefix(true);
        ttft_ms = stream_buf.ttft_ms();
        generated_tokens = stream_buf.generated_tokens();

        const auto end_time = std::chrono::steady_clock::now();
        const int64_t total_ms = std::max<int64_t>(
            1,
            std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count()
        );
        env->CallVoidMethod(
            callback,
            on_complete_id,
            static_cast<jlong>(ttft_ms),
            static_cast<jlong>(total_ms),
            static_cast<jint>(generated_tokens)
        );
        env->DeleteLocalRef(cb_cls);
        return;
    }
#endif

    (void)system_str;
    (void)user_str;
    (void)max_tokens;
    env->CallVoidMethod(
        callback,
        on_complete_id,
        static_cast<jlong>(0),
        static_cast<jlong>(1),
        static_cast<jint>(0)
    );
    env->DeleteLocalRef(cb_cls);
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_cinpecan_engram_edgemnn_JniNativeBridge_nativeHasRealMnnBackend(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
#if ENGRAM_HAS_MNN_LIB
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_io_github_cinpecan_engram_edgemnn_JniNativeBridge_nativeCreateSession(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring model_dir
) {
#if !ENGRAM_HAS_MNN_LIB
    (void)env;
    (void)model_dir;
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "MNN native libraries are not bundled; refusing session creation");
    return 0;
#else
    const std::string dir_path = JStringToUtf8(env, model_dir);

    std::error_code ec;
    const std::filesystem::path config_path = std::filesystem::path(dir_path) / "config.json";
    if (dir_path.empty() || !std::filesystem::exists(config_path, ec)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Model config.json missing in %s", dir_path.c_str());
        return 0;
    }

    auto* raw_llm = MNN::Transformer::Llm::createLLM(config_path.string());
    if (raw_llm == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "MNN Llm::createLLM returned null for %s", dir_path.c_str());
        return 0;
    }
    if (!raw_llm->load()) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "MNN Llm::load failed for %s", dir_path.c_str());
        MNN::Transformer::Llm::destroy(raw_llm);
        return 0;
    }

    const std::string initial_cfg = raw_llm->dump_config();
    if (initial_cfg.find("\"sampler_type\"") == std::string::npos) {
        static constexpr const char* kDefaultEdgeSamplerJson =
            R"({"sampler_type":"mixed","mixed_samplers":["penalty","topK","topP","temperature"],"repetition_penalty":1.05,"penalty_window":64,"ngram_factor":1.0,"topK":20,"topP":0.8,"temperature":0.15})";
        (void)raw_llm->set_config(kDefaultEdgeSamplerJson);
    }

    auto session = std::make_unique<EngramNativeSession>();
    session->model_dir = dir_path;
    session->llm.reset(raw_llm);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Loaded real MNN-LLM from %s", dir_path.c_str());
    return reinterpret_cast<jlong>(session.release());
#endif
}

JNIEXPORT void JNICALL
Java_io_github_cinpecan_engram_edgemnn_JniNativeBridge_nativeRequestCancel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return;
    auto* session = reinterpret_cast<EngramNativeSession*>(handle);
    if (!session->released.load()) {
        session->cancel_requested.store(true);
    }
}

JNIEXPORT void JNICALL
Java_io_github_cinpecan_engram_edgemnn_JniNativeBridge_nativeReleaseSession(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    if (handle == 0) return;
    auto* session = reinterpret_cast<EngramNativeSession*>(handle);
    session->cancel_requested.store(true);
    session->released.store(true);
    delete session;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Released native MNN session");
}

JNIEXPORT void JNICALL
Java_io_github_cinpecan_engram_edgemnn_JniNativeBridge_nativeGenerateStream(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring prompt,
    jint max_tokens,
    jobject callback
) {
    const std::string prompt_str = JStringToUtf8(env, prompt);
    RunGenerateStream(env, handle, "", prompt_str, max_tokens, callback);
}

JNIEXPORT void JNICALL
Java_io_github_cinpecan_engram_edgemnn_JniNativeBridge_nativeGenerateChatStream(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring system_prompt,
    jstring user_prompt,
    jint max_tokens,
    jobject callback
) {
    const std::string system_str = JStringToUtf8(env, system_prompt);
    const std::string user_str = JStringToUtf8(env, user_prompt);
    RunGenerateStream(env, handle, system_str, user_str, max_tokens, callback);
}

} // extern "C"
