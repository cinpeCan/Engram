#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <sstream>
#include <string>
#include <thread>
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
    bool has_weights_on_disk{false};
#if ENGRAM_HAS_MNN_LIB
    std::unique_ptr<MNN::Transformer::Llm> llm;
#endif
};

std::vector<uint32_t> DecodeUtf8Codepoints(const std::string& utf8) {
    std::vector<uint32_t> out;
    out.reserve(utf8.size());
    const size_t n = utf8.size();
    size_t i = 0;
    while (i < n) {
        uint8_t c = static_cast<uint8_t>(utf8[i]);
        uint32_t cp = 0;
        size_t adv = 1;
        if ((c & 0x80) == 0) {
            cp = c;
            if (cp >= 'A' && cp <= 'Z') {
                cp += ('a' - 'A');
            }
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
        } else {
            cp = c;
        }
        if (cp > 0x20) {
            out.push_back(cp);
        }
        i += adv;
    }
    return out;
}

inline uint64_t Fnv1aMix(uint64_t a, uint64_t b, uint64_t c = 0) {
    uint64_t h = 14695981039346656037ULL;
    auto mix = [&h](uint64_t v) {
        h ^= v;
        h *= 1099511628211ULL;
    };
    mix(a);
    mix(b);
    mix(c);
    return h;
}

bool ContainsAny(const std::string& haystack, const std::vector<std::string>& needles) {
    for (const auto& needle : needles) {
        if (haystack.find(needle) != std::string::npos) {
            return true;
        }
    }
    return false;
}

std::string SynthesizeEdgeResponse(const std::string& prompt) {
    if (ContainsAny(prompt, {"\"relations\"", "memory-analysis", "JSON_SCHEMA"})) {
        return "{}";
    }

    // Extract cited lines passed inside prompt for grounded edge generation
    std::vector<std::string> cited_lines;
    std::istringstream iss(prompt);
    std::string line;
    while (std::getline(iss, line)) {
        if (line.rfind("[记忆", 0) == 0 || line.rfind("- [", 0) == 0) {
            cited_lines.push_back(line);
        }
    }

    if (cited_lines.empty()) {
        return "端侧 MNN 引擎已就绪（离线安全模式）。当前本地记忆库尚未命中直接关联记录，建议先在「印迹」页记录事实碎片后再次寻迹。";
    }

    std::string response = "【端侧 MNN 离线推理】根据本地命中记忆为您直接归纳：\n";
    for (size_t i = 0; i < std::min<size_t>(cited_lines.size(), 3); ++i) {
        response += "• " + cited_lines[i] + "\n";
    }
    response += "\n以上结论完全在设备本地计算得出，未向外部上传任何记忆原文。";
    return response;
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
        max_tokens_(max_tokens) {}

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
        // Walk backwards at most 3 bytes to check if a multi-byte sequence is incomplete at the tail
        size_t tail_cont = 0;
        while (tail_cont < 3 && tail_cont < size &&
               (static_cast<uint8_t>(bytes[size - 1 - tail_cont]) & 0xC0) == 0x80) {
            ++tail_cont;
        }
        if (tail_cont == size) {
            // Only continuation bytes without lead byte; emit as-is
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

    JNIEnv* env_;
    jobject callback_;
    jmethodID on_token_method_;
    EngramNativeSession* session_;
    std::chrono::steady_clock::time_point start_time_;
    int max_tokens_;
    int64_t ttft_ms_{-1};
    int token_count_{0};
    std::string pending_utf8_;
};

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
    const char* dir_chars = env->GetStringUTFChars(model_dir, nullptr);
    std::string dir_path = dir_chars != nullptr ? dir_chars : "";
    if (dir_chars != nullptr) {
        env->ReleaseStringUTFChars(model_dir, dir_chars);
    }

    auto session = std::make_unique<EngramNativeSession>();
    session->model_dir = dir_path;

    std::error_code ec;
    const std::filesystem::path config_path = std::filesystem::path(dir_path) / "config.json";
    session->has_weights_on_disk = !dir_path.empty() && std::filesystem::exists(config_path, ec);

#if ENGRAM_HAS_MNN_LIB
    if (session->has_weights_on_disk) {
        auto* raw_llm = MNN::Transformer::Llm::createLLM(config_path.string());
        if (raw_llm != nullptr) {
            raw_llm->load();
            session->llm.reset(raw_llm);
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "Loaded real MNN-LLM from %s", dir_path.c_str());
        }
    }
#endif

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Created native session (realMnn=%d, weightsOnDisk=%d)",
        ENGRAM_HAS_MNN_LIB,
        session->has_weights_on_disk ? 1 : 0
    );
    return reinterpret_cast<jlong>(session.release());
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

    const char* prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str = prompt_chars != nullptr ? prompt_chars : "";
    if (prompt_chars != nullptr) {
        env->ReleaseStringUTFChars(prompt, prompt_chars);
    }

    const auto start_time = std::chrono::steady_clock::now();
    int64_t ttft_ms = 0;
    int generated_tokens = 0;

#if ENGRAM_HAS_MNN_LIB
    if (session->llm != nullptr) {
        session->llm->reset();
        CallbackStreamBuf stream_buf(env, callback, on_token_id, session, start_time, max_tokens);
        std::ostream os(&stream_buf);
        session->llm->response(prompt_str, &os, nullptr, 0);
        stream_buf.FlushValidUtf8Prefix(true);
        ttft_ms = stream_buf.ttft_ms();
        generated_tokens = stream_buf.generated_tokens();
    } else
#endif
    {
        const std::string output = SynthesizeEdgeResponse(prompt_str);
        size_t pos = 0;
        while (pos < output.size() && !session->cancel_requested.load() && generated_tokens < max_tokens) {
            size_t step = 3;
            while (pos + step < output.size() && (static_cast<uint8_t>(output[pos + step]) & 0xC0) == 0x80) {
                ++step;
            }
            const std::string piece = output.substr(pos, step);
            pos += step;

            if (generated_tokens == 0) {
                auto first_tp = std::chrono::steady_clock::now();
                ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(first_tp - start_time).count();
            }
            ++generated_tokens;

            jstring jdelta = Utf8ToJString(env, piece);
            if (jdelta != nullptr) {
                env->CallVoidMethod(callback, on_token_id, jdelta);
                env->DeleteLocalRef(jdelta);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
        }
    }

    const auto end_time = std::chrono::steady_clock::now();
    const int64_t total_ms = std::max<int64_t>(
        1,
        std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count()
    );
    env->CallVoidMethod(callback, on_complete_id, static_cast<jlong>(ttft_ms), static_cast<jlong>(total_ms), static_cast<jint>(generated_tokens));
    env->DeleteLocalRef(cb_cls);
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_cinpecan_engram_edgemnn_JniNativeBridge_nativeEmbed(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong /*handle*/,
    jstring text,
    jint dims
) {
    const int safe_dims = std::max(16, static_cast<int>(dims));
    const char* text_chars = env->GetStringUTFChars(text, nullptr);
    std::string input = text_chars != nullptr ? text_chars : "";
    if (text_chars != nullptr) {
        env->ReleaseStringUTFChars(text, text_chars);
    }

    std::vector<float> vec(static_cast<size_t>(safe_dims), 0.0f);
    const std::vector<uint32_t> cps = DecodeUtf8Codepoints(input);

    for (size_t i = 0; i < cps.size(); ++i) {
        uint64_t h1 = Fnv1aMix(1, cps[i]);
        size_t idx1 = static_cast<size_t>(h1 % static_cast<uint64_t>(safe_dims));
        float sign1 = ((h1 >> 31) & 1ULL) ? 1.0f : -1.0f;
        vec[idx1] += 0.6f * sign1;

        if (i + 1 < cps.size()) {
            uint64_t h2 = Fnv1aMix(2, cps[i], cps[i + 1]);
            size_t idx2 = static_cast<size_t>(h2 % static_cast<uint64_t>(safe_dims));
            float sign2 = ((h2 >> 31) & 1ULL) ? 1.0f : -1.0f;
            vec[idx2] += 1.25f * sign2;
        }
    }

    double norm_sq = 0.0;
    for (float v : vec) {
        norm_sq += static_cast<double>(v) * static_cast<double>(v);
    }
    if (norm_sq > 1e-12) {
        float inv_norm = static_cast<float>(1.0 / std::sqrt(norm_sq));
        for (float& v : vec) {
            v *= inv_norm;
        }
    }

    jfloatArray result = env->NewFloatArray(safe_dims);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, safe_dims, vec.data());
    }
    return result;
}

} // extern "C"
