#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <objbase.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Storage.Streams.h>

#include <mmdeviceapi.h>
#include <audioclient.h>
#include <mmreg.h>
#include <winhttp.h>

#include <string>
#include <vector>
#include <chrono>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <thread>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cmath>

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Media::Control;
using namespace winrt::Windows::Storage::Streams;

// ---------------------------------------------------------------------------
// Exported structures
// ---------------------------------------------------------------------------

#pragma pack(push, 1)
struct MediaInfo {
    char title[512];
    char artist[512];
    char sourceApp[256];
    uint8_t* albumArtPng;
    int32_t albumArtSize;
    uint8_t isPlaying;
    int64_t durationMs;
    int64_t positionMs;
    uint8_t trackChanged;
};

struct PositionInfo {
    uint8_t isPlaying;
    int64_t durationMs;
    int64_t positionMs;
};
#pragma pack(pop)

// LyricsResult keeps natural alignment so it maps directly onto a default
// (non-packed) JNA Structure on the Java side.
struct LyricsResult {
    char* syncedLrc;   // raw LRC text with [mm:ss.xx] tags, or null
    int32_t syncedLen;
    char* plainText;   // plain lyrics text, or null
    int32_t plainLen;
    int32_t status;    // 0 = found, 1 = not found, 2 = transient error
};

namespace {
    constexpr int32_t kLyricsFound = 0;
    constexpr int32_t kLyricsNotFound = 1;
    constexpr int32_t kLyricsError = 2;

    // ----- SMTC tuning -----
    constexpr auto kAsyncWaitTimeout = std::chrono::milliseconds(500);
    constexpr auto kAsyncPollMin = std::chrono::milliseconds(1);
    constexpr auto kAsyncPollMax = std::chrono::milliseconds(8);
    constexpr auto kSessionManagerRefresh = std::chrono::seconds(45);
    constexpr auto kAlbumArtProbeInterval = std::chrono::milliseconds(2500);
    constexpr auto kPositionCachePlaying = std::chrono::milliseconds(180);
    constexpr auto kPositionCachePaused = std::chrono::milliseconds(700);

    // ----- Waveform tuning -----
    constexpr int kWavePoints = 256;       // ring length the renderer reads back
    constexpr float kLevelAttack = 0.35f;  // smoothing toward louder values
    constexpr float kLevelRelease = 0.12f; // smoothing toward quieter values
}

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

namespace {
    struct MediaCache {
        std::mutex mutex;
        std::string lastTitle;
        std::string lastArtist;
        std::string lastSource;
        uint8_t* albumArt = nullptr;
        int32_t albumArtSize = 0;
        uint64_t albumArtFingerprint = 0;

        int64_t lastPositionMs = 0;
        int64_t lastDurationMs = 0;
        uint8_t lastIsPlaying = 0;
        std::chrono::steady_clock::time_point lastUpdate;
        std::chrono::steady_clock::time_point lastAlbumArtProbe;
    };

    struct WaveformState {
        std::mutex mutex;
        float points[kWavePoints] = {};
        int head = 0;
        std::atomic<float> level{0.0f};

        std::thread thread;
        std::atomic<bool> running{false};
        HANDLE stopEvent = nullptr;
        bool started = false;
    };

    struct GlobalState {
        MediaCache cache;

        std::mutex sessionManagerMutex;
        GlobalSystemMediaTransportControlsSessionManager sessionManager = nullptr;
        std::chrono::steady_clock::time_point sessionManagerUpdate;

        WaveformState waveform;
    };

    GlobalState* g_state = nullptr;
    INIT_ONCE g_stateInitOnce = INIT_ONCE_STATIC_INIT;

    BOOL CALLBACK InitGlobalState(PINIT_ONCE, PVOID, PVOID*) {
        g_state = new GlobalState();
        return TRUE;
    }

    inline GlobalState& State() {
        InitOnceExecuteOnce(&g_stateInitOnce, InitGlobalState, nullptr, nullptr);
        return *g_state;
    }

    inline MediaCache& Cache() { return State().cache; }
    inline WaveformState& Wave() { return State().waveform; }

    // 0 - unknown, 1 - ready for blocking WinRT waits, 2 - unsupported on thread.
    thread_local uint8_t g_apartmentState = 0;

    inline bool EnsureApartment() {
        if (g_apartmentState == 1) return true;
        if (g_apartmentState == 2) return false;
        try {
            winrt::init_apartment(winrt::apartment_type::multi_threaded);
            g_apartmentState = 1;
            return true;
        } catch (const winrt::hresult_error& ex) {
            // STA thread already initialised differently: blocking waits unsafe.
            g_apartmentState = (ex.code() == RPC_E_CHANGED_MODE) ? 2 : 2;
            return false;
        } catch (...) {
            g_apartmentState = 2;
            return false;
        }
    }
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

namespace {
    inline void CopyToBuffer(char* dest, size_t destSize, const std::string& src) {
        if (!dest || destSize == 0) return;
        size_t len = (std::min)(src.size(), destSize - 1);
        if (len > 0) std::memcpy(dest, src.data(), len);
        dest[len] = '\0';
    }

    uint64_t ComputeAlbumArtFingerprint(const uint8_t* data, size_t len) {
        if (!data || len == 0) return 0;
        uint64_t hash = 1469598103934665603ULL; // FNV-1a offset
        auto mix = [&hash](uint8_t v) {
            hash ^= static_cast<uint64_t>(v);
            hash *= 1099511628211ULL;
        };
        const size_t window = (std::min)(len, static_cast<size_t>(96));
        for (size_t i = 0; i < window; ++i) {
            mix(data[i]);
            mix(data[len - 1 - i]);
        }
        const size_t middle = len / 2;
        const size_t start = middle > window / 2 ? middle - window / 2 : 0;
        const size_t end = (std::min)(len, start + window);
        for (size_t i = start; i < end; ++i) mix(data[i]);
        for (int shift = 0; shift < 8; ++shift)
            mix(static_cast<uint8_t>((len >> (shift * 8)) & 0xFFU));
        return hash;
    }

    template<typename TAsync>
    bool WaitAsyncCompleted(const TAsync& op, std::chrono::milliseconds timeout) {
        if (!op) return false;
        try {
            const auto deadline = std::chrono::steady_clock::now() + timeout;
            auto poll = kAsyncPollMin;
            while (std::chrono::steady_clock::now() < deadline) {
                auto status = op.Status();
                if (status == AsyncStatus::Completed) return true;
                if (status == AsyncStatus::Canceled || status == AsyncStatus::Error) return false;
                std::this_thread::sleep_for(poll);
                if (poll < kAsyncPollMax) poll = (std::min)(poll * 2, kAsyncPollMax);
            }
            if (op.Status() == AsyncStatus::Completed) return true;
            try { op.Cancel(); } catch (...) {}
        } catch (...) {}
        return false;
    }

    template<typename TResult>
    bool TryGetAsyncResult(const IAsyncOperation<TResult>& op, TResult* out,
                           std::chrono::milliseconds timeout) {
        if (!out || !WaitAsyncCompleted(op, timeout)) return false;
        try {
            *out = op.GetResults();
            return true;
        } catch (...) {
            return false;
        }
    }

    std::string GetProcessNameFast(const winrt::hstring& aumid) {
        if (aumid.empty()) return "Unknown";
        std::string full = winrt::to_string(aumid);
        size_t bang = full.find('!');
        if (bang != std::string::npos) {
            size_t underscore = full.find_last_of('_', bang);
            return underscore != std::string::npos ? full.substr(0, underscore)
                                                   : full.substr(0, bang);
        }
        return full;
    }
}

// ---------------------------------------------------------------------------
// SMTC session management
// ---------------------------------------------------------------------------

namespace {
    bool TryGetSessionManager(GlobalSystemMediaTransportControlsSessionManager* out) {
        if (!out) return false;
        *out = nullptr;
        const auto now = std::chrono::steady_clock::now();
        {
            std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
            if (State().sessionManager &&
                (now - State().sessionManagerUpdate) < kSessionManagerRefresh) {
                *out = State().sessionManager;
                return true;
            }
        }

        GlobalSystemMediaTransportControlsSessionManager manager = nullptr;
        if (!TryGetAsyncResult(
                GlobalSystemMediaTransportControlsSessionManager::RequestAsync(),
                &manager, kAsyncWaitTimeout)) {
            return false;
        }

        std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
        State().sessionManager = manager;
        State().sessionManagerUpdate = now;
        *out = manager;
        return static_cast<bool>(manager);
    }

    GlobalSystemMediaTransportControlsSession GetActiveSession() {
        try {
            GlobalSystemMediaTransportControlsSessionManager manager = nullptr;
            if (!TryGetSessionManager(&manager) || !manager) return nullptr;
            return manager.GetCurrentSession();
        } catch (const winrt::hresult_error&) {
            std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
            State().sessionManager = nullptr;
            State().sessionManagerUpdate = {};
            return nullptr;
        } catch (...) {
            return nullptr;
        }
    }

    bool LoadThumbnail(const IRandomAccessStreamReference& thumb,
                       uint8_t** outBuffer, int32_t* outSize) {
        try {
            IRandomAccessStreamWithContentType stream = nullptr;
            if (!TryGetAsyncResult(thumb.OpenReadAsync(), &stream, kAsyncWaitTimeout) || !stream)
                return false;

            uint32_t size = static_cast<uint32_t>(stream.Size());
            if (size == 0 || size > 10485760) return false;

            uint8_t* buffer = static_cast<uint8_t*>(CoTaskMemAlloc(size));
            if (!buffer) return false;

            DataReader reader(stream);
            uint32_t loaded = 0;
            if (TryGetAsyncResult(reader.LoadAsync(size), &loaded, kAsyncWaitTimeout) &&
                loaded == size) {
                reader.ReadBytes({buffer, size});
                *outBuffer = buffer;
                *outSize = static_cast<int32_t>(size);
                return true;
            }
            CoTaskMemFree(buffer);
        } catch (...) {}
        return false;
    }

    void FreeCachedAlbumArt() {
        if (Cache().albumArt) {
            CoTaskMemFree(Cache().albumArt);
            Cache().albumArt = nullptr;
            Cache().albumArtSize = 0;
        }
    }

    void UpdatePositionInfo(
        const GlobalSystemMediaTransportControlsSessionPlaybackInfo& playbackInfo,
        const GlobalSystemMediaTransportControlsSessionTimelineProperties& timelineProps,
        int64_t* outPositionMs, int64_t* outDurationMs, uint8_t* outIsPlaying) {
        if (!timelineProps) return;
        using namespace std::chrono;

        int64_t posMs = duration_cast<milliseconds>(timelineProps.Position()).count();
        *outDurationMs = duration_cast<milliseconds>(timelineProps.EndTime()).count();

        double rate = 1.0;
        if (playbackInfo) {
            if (auto pr = playbackInfo.PlaybackRate()) rate = static_cast<double>(pr.Value());
        }
        bool playing = playbackInfo &&
            playbackInfo.PlaybackStatus() ==
                GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing;
        *outIsPlaying = playing ? 1 : 0;

        if (playing && rate > 0.0) {
            int64_t deltaMs = duration_cast<milliseconds>(
                winrt::clock::now() - timelineProps.LastUpdatedTime()).count();
            int64_t estimated = static_cast<int64_t>(posMs + static_cast<double>(deltaMs) * rate);
            if (estimated < 0) estimated = 0;
            if (*outDurationMs > 0 && estimated > *outDurationMs) estimated = *outDurationMs;
            *outPositionMs = estimated;
        } else {
            *outPositionMs = posMs;
        }
    }
}

// ---------------------------------------------------------------------------
// WASAPI loopback capture -> waveform
// ---------------------------------------------------------------------------

namespace {
    inline void PushWavePoint(float peak) {
        WaveformState& w = Wave();
        {
            std::lock_guard<std::mutex> lock(w.mutex);
            w.points[w.head] = peak;
            w.head = (w.head + 1) % kWavePoints;
        }
        float prev = w.level.load(std::memory_order_relaxed);
        float k = peak > prev ? kLevelAttack : kLevelRelease;
        w.level.store(prev + (peak - prev) * k, std::memory_order_relaxed);
    }

    // Runs one capture session against the current default render endpoint.
    // Returns when the stop event fires or the device becomes invalid.
    HRESULT RunCaptureSession() {
        IMMDeviceEnumerator* enumerator = nullptr;
        IMMDevice* device = nullptr;
        IAudioClient* client = nullptr;
        IAudioCaptureClient* capture = nullptr;
        WAVEFORMATEX* mix = nullptr;
        HRESULT hr = S_OK;

        auto cleanup = [&]() {
            if (client) client->Stop();
            if (mix) CoTaskMemFree(mix);
            if (capture) capture->Release();
            if (client) client->Release();
            if (device) device->Release();
            if (enumerator) enumerator->Release();
        };

        hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                              __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(&enumerator));
        if (FAILED(hr)) { cleanup(); return hr; }

        hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);
        if (FAILED(hr)) { cleanup(); return hr; }

        hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                              reinterpret_cast<void**>(&client));
        if (FAILED(hr)) { cleanup(); return hr; }

        hr = client->GetMixFormat(&mix);
        if (FAILED(hr) || !mix) { cleanup(); return FAILED(hr) ? hr : E_FAIL; }

        // 200 ms shared buffer, loopback mode.
        hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                                2000000, 0, mix, nullptr);
        if (FAILED(hr)) { cleanup(); return hr; }

        hr = client->GetService(__uuidof(IAudioCaptureClient),
                                reinterpret_cast<void**>(&capture));
        if (FAILED(hr)) { cleanup(); return hr; }

        const WORD channels = mix->nChannels ? mix->nChannels : 2;
        WORD tag = mix->wFormatTag;
        if (tag == WAVE_FORMAT_EXTENSIBLE && mix->cbSize >= 22) {
            tag = static_cast<WORD>(reinterpret_cast<WAVEFORMATEXTENSIBLE*>(mix)->SubFormat.Data1);
        }
        const bool isFloat = (tag == WAVE_FORMAT_IEEE_FLOAT);
        const bool isPcm16 = (tag == WAVE_FORMAT_PCM && mix->wBitsPerSample == 16);

        // One waveform point per ~6 ms of audio keeps the ring ~1.6 s wide.
        const uint32_t samplesPerBucket =
            (std::max<uint32_t>)(1u, static_cast<uint32_t>(mix->nSamplesPerSec) / 160u);

        hr = client->Start();
        if (FAILED(hr)) { cleanup(); return hr; }

        float bucketPeak = 0.0f;
        uint32_t bucketCount = 0;
        WaveformState& w = Wave();

        while (w.running.load(std::memory_order_relaxed)) {
            if (WaitForSingleObject(w.stopEvent, 8) == WAIT_OBJECT_0) break;

            UINT32 packet = 0;
            hr = capture->GetNextPacketSize(&packet);
            if (FAILED(hr)) break;

            while (packet > 0) {
                BYTE* data = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;
                hr = capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr);
                if (FAILED(hr)) break;

                const bool silent = (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
                for (UINT32 f = 0; f < frames; ++f) {
                    float mono = 0.0f;
                    if (!silent && data) {
                        if (isFloat) {
                            const float* s = reinterpret_cast<const float*>(data) + f * channels;
                            for (WORD c = 0; c < channels; ++c) mono += s[c];
                        } else if (isPcm16) {
                            const int16_t* s = reinterpret_cast<const int16_t*>(data) + f * channels;
                            for (WORD c = 0; c < channels; ++c) mono += s[c] * (1.0f / 32768.0f);
                        }
                        mono /= static_cast<float>(channels);
                    }
                    float a = std::fabs(mono);
                    if (a > bucketPeak) bucketPeak = a;
                    if (++bucketCount >= samplesPerBucket) {
                        PushWavePoint(bucketPeak > 1.0f ? 1.0f : bucketPeak);
                        bucketPeak = 0.0f;
                        bucketCount = 0;
                    }
                }

                capture->ReleaseBuffer(frames);
                hr = capture->GetNextPacketSize(&packet);
                if (FAILED(hr)) break;
            }
            if (FAILED(hr)) break;
        }

        cleanup();
        return hr;
    }

    void CaptureThreadMain() {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);
        WaveformState& w = Wave();
        while (w.running.load(std::memory_order_relaxed)) {
            HRESULT hr = RunCaptureSession();
            if (!w.running.load(std::memory_order_relaxed)) break;
            // Endpoint switched or transient failure: drift to silence and retry.
            if (FAILED(hr)) {
                w.level.store(0.0f, std::memory_order_relaxed);
                if (WaitForSingleObject(w.stopEvent, 250) == WAIT_OBJECT_0) break;
            }
        }
        winrt::uninit_apartment();
    }
}

// ---------------------------------------------------------------------------
// Lyrics: WinHTTP fetch + lightweight JSON field extraction
// ---------------------------------------------------------------------------

namespace {
    std::string UrlEncode(const std::string& value) {
        static const char* hex = "0123456789ABCDEF";
        std::string out;
        out.reserve(value.size() * 2);
        for (unsigned char c : value) {
            if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
                out.push_back(static_cast<char>(c));
            } else if (c == ' ') {
                out.push_back('+');
            } else {
                out.push_back('%');
                out.push_back(hex[c >> 4]);
                out.push_back(hex[c & 0xF]);
            }
        }
        return out;
    }

    // HTTPS GET against lrclib.net. Returns true on a completed exchange and
    // fills status/body; transport failures return false.
    bool HttpsGet(const std::wstring& path, int* outStatus, std::string* outBody) {
        bool ok = false;
        HINTERNET session = WinHttpOpen(L"FastMedia/2.0",
            WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME,
            WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session) return false;

        WinHttpSetTimeouts(session, 10000, 10000, 15000, 15000);
        HINTERNET connect = WinHttpConnect(session, L"lrclib.net",
                                           INTERNET_DEFAULT_HTTPS_PORT, 0);
        if (connect) {
            HINTERNET request = WinHttpOpenRequest(connect, L"GET", path.c_str(),
                nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                WINHTTP_FLAG_SECURE);
            if (request) {
                if (WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                        WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
                    WinHttpReceiveResponse(request, nullptr)) {

                    DWORD code = 0, codeSize = sizeof(code);
                    WinHttpQueryHeaders(request,
                        WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &code, &codeSize,
                        WINHTTP_NO_HEADER_INDEX);
                    *outStatus = static_cast<int>(code);

                    std::string body;
                    DWORD available = 0;
                    while (WinHttpQueryDataAvailable(request, &available) && available > 0) {
                        std::string chunk(available, '\0');
                        DWORD read = 0;
                        if (!WinHttpReadData(request, chunk.data(), available, &read) || read == 0)
                            break;
                        body.append(chunk.data(), read);
                    }
                    *outBody = std::move(body);
                    ok = true;
                }
                WinHttpCloseHandle(request);
            }
            WinHttpCloseHandle(connect);
        }
        WinHttpCloseHandle(session);
        return ok;
    }

    void AppendUtf8(std::string& out, uint32_t cp) {
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    // Reads a JSON string literal starting at json[pos] == '"'. Advances pos
    // past the closing quote and returns the decoded UTF-8 value.
    std::string ReadJsonString(const std::string& json, size_t& pos) {
        std::string out;
        ++pos; // opening quote
        while (pos < json.size()) {
            char c = json[pos++];
            if (c == '"') break;
            if (c == '\\' && pos < json.size()) {
                char e = json[pos++];
                switch (e) {
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case '/': out.push_back('/'); break;
                    case '\\': out.push_back('\\'); break;
                    case '"': out.push_back('"'); break;
                    case 'u': {
                        if (pos + 4 <= json.size()) {
                            uint32_t cp = static_cast<uint32_t>(
                                std::strtol(json.substr(pos, 4).c_str(), nullptr, 16));
                            pos += 4;
                            // Surrogate pair.
                            if (cp >= 0xD800 && cp <= 0xDBFF && pos + 6 <= json.size() &&
                                json[pos] == '\\' && json[pos + 1] == 'u') {
                                uint32_t lo = static_cast<uint32_t>(
                                    std::strtol(json.substr(pos + 2, 4).c_str(), nullptr, 16));
                                pos += 6;
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                            }
                            AppendUtf8(out, cp);
                        }
                        break;
                    }
                    default: out.push_back(e); break;
                }
            } else {
                out.push_back(c);
            }
        }
        return out;
    }

    // Finds top-level "key": value within a single JSON object substring and,
    // when the value is a string, returns it decoded. found=false means the
    // key was absent or null.
    bool ExtractJsonString(const std::string& obj, const std::string& key,
                           std::string* out) {
        const std::string needle = "\"" + key + "\"";
        size_t pos = 0;
        while ((pos = obj.find(needle, pos)) != std::string::npos) {
            size_t after = pos + needle.size();
            // Skip whitespace then ':'.
            while (after < obj.size() && std::isspace(static_cast<unsigned char>(obj[after]))) ++after;
            if (after >= obj.size() || obj[after] != ':') { pos = after; continue; }
            ++after;
            while (after < obj.size() && std::isspace(static_cast<unsigned char>(obj[after]))) ++after;
            if (after >= obj.size()) return false;
            if (obj[after] == '"') {
                *out = ReadJsonString(obj, after);
                return true;
            }
            return false; // null / number / object -> treat as absent string
        }
        return false;
    }

    // Returns the substring covering the first balanced {...} object, honouring
    // string literals and escapes. Empty if none.
    std::string FirstJsonObject(const std::string& json) {
        size_t start = json.find('{');
        if (start == std::string::npos) return "";
        int depth = 0;
        bool inStr = false;
        for (size_t i = start; i < json.size(); ++i) {
            char c = json[i];
            if (inStr) {
                if (c == '\\') ++i;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                ++depth;
            } else if (c == '}') {
                if (--depth == 0) return json.substr(start, i - start + 1);
            }
        }
        return "";
    }

    std::string TrimCopy(const std::string& s) {
        size_t a = s.find_first_not_of(" \t\r\n");
        if (a == std::string::npos) return "";
        size_t b = s.find_last_not_of(" \t\r\n");
        return s.substr(a, b - a + 1);
    }

    // Reduces an artist string to its primary performer.
    std::string PrimaryArtist(const std::string& artist) {
        static const char* seps[] = {" feat. ", " feat ", " ft. ", " ft ", ", ", " & ", " x ", "; ", " / "};
        std::string lower = artist;
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        size_t best = std::string::npos;
        for (const char* sep : seps) {
            size_t idx = lower.find(sep);
            if (idx != std::string::npos && idx > 0) best = (std::min)(best, idx);
        }
        return best == std::string::npos ? artist : TrimCopy(artist.substr(0, best));
    }

    // Strips trailing "(...)"/"[...]" decorations and feat. clauses.
    std::string StripTrackDecorations(const std::string& track) {
        std::string s = track;
        std::string lower = s;
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        for (const char* feat : {" feat", " ft.", " ft ", "(feat", "[feat"}) {
            size_t idx = lower.find(feat);
            if (idx != std::string::npos) { s = s.substr(0, idx); break; }
        }
        bool changed = true;
        while (changed) {
            changed = false;
            s = TrimCopy(s);
            if (!s.empty() && (s.back() == ')' || s.back() == ']')) {
                char open = s.back() == ')' ? '(' : '[';
                size_t idx = s.rfind(open);
                if (idx != std::string::npos) { s = s.substr(0, idx); changed = true; }
            }
        }
        return TrimCopy(s);
    }

    struct LyricsContent {
        std::string synced;
        std::string plain;
        bool found = false;
        bool transient = false;
    };

    // Parses one lrclib JSON object (get response or one search item).
    void ParseLyricsObject(const std::string& obj, LyricsContent* out) {
        std::string synced, plain;
        bool hasSynced = ExtractJsonString(obj, "syncedLyrics", &synced) && !synced.empty();
        bool hasPlain = ExtractJsonString(obj, "plainLyrics", &plain) && !plain.empty();
        if (hasSynced) { out->synced = synced; out->found = true; }
        if (hasPlain && out->plain.empty()) { out->plain = plain; out->found = out->found || true; }
    }

    bool FetchViaGet(const std::string& artist, const std::string& track, LyricsContent* out) {
        if (track.empty()) return false;
        std::string path = "/api/get?artist_name=" + UrlEncode(artist) +
                           "&track_name=" + UrlEncode(track);
        std::wstring wpath(path.begin(), path.end());
        int status = 0;
        std::string body;
        if (!HttpsGet(wpath, &status, &body)) { out->transient = true; return false; }
        if (status == 404) return false;
        if (status < 200 || status >= 300) { out->transient = true; return false; }
        ParseLyricsObject(body, out);
        return !out->synced.empty();
    }

    bool FetchViaSearch(const std::string& artist, const std::string& track, LyricsContent* out) {
        if (track.empty()) return false;
        std::string query = artist.empty() ? track : (artist + " " + track);
        std::string path = "/api/search?q=" + UrlEncode(query);
        std::wstring wpath(path.begin(), path.end());
        int status = 0;
        std::string body;
        if (!HttpsGet(wpath, &status, &body)) { out->transient = true; return false; }
        if (status == 404) return false;
        if (status < 200 || status >= 300) { out->transient = true; return false; }
        std::string first = FirstJsonObject(body);
        if (first.empty()) return false;
        ParseLyricsObject(first, out);
        return !out->synced.empty();
    }

    char* DupToCoTaskMem(const std::string& s, int32_t* outLen) {
        if (s.empty()) { *outLen = 0; return nullptr; }
        char* buf = static_cast<char*>(CoTaskMemAlloc(s.size() + 1));
        if (!buf) { *outLen = 0; return nullptr; }
        std::memcpy(buf, s.data(), s.size());
        buf[s.size()] = '\0';
        *outLen = static_cast<int32_t>(s.size());
        return buf;
    }
}

// ---------------------------------------------------------------------------
// Exported entry points
// ---------------------------------------------------------------------------

extern "C" {

    static int32_t __stdcall GetCurrentMediaInfoBody(MediaInfo* info) {
        if (!info) return 0;
        std::memset(info, 0, sizeof(MediaInfo));
        if (!EnsureApartment()) return 0;

        uint8_t* tmpArt = nullptr;
        int32_t tmpSize = 0;
        uint64_t tmpFingerprint = 0;

        try {
            auto session = GetActiveSession();
            if (!session) return 0;

            auto propsAsync = session.TryGetMediaPropertiesAsync();
            auto playbackInfo = session.GetPlaybackInfo();
            auto timelineProps = session.GetTimelineProperties();

            GlobalSystemMediaTransportControlsSessionMediaProperties props = nullptr;
            if (!TryGetAsyncResult(propsAsync, &props, kAsyncWaitTimeout) || !props) return 0;

            std::string title = winrt::to_string(props.Title());
            std::string artist = winrt::to_string(props.Artist());
            std::string source = GetProcessNameFast(session.SourceAppUserModelId());
            const auto now = std::chrono::steady_clock::now();

            // Decide whether to probe album art (cheap field read under lock).
            bool shouldProbe;
            {
                std::lock_guard<std::mutex> lock(Cache().mutex);
                bool trackChangedPreview = title != Cache().lastTitle ||
                    artist != Cache().lastArtist || source != Cache().lastSource;
                bool periodic = Cache().lastAlbumArtProbe == std::chrono::steady_clock::time_point{} ||
                    (now - Cache().lastAlbumArtProbe) >= kAlbumArtProbeInterval;
                shouldProbe = trackChangedPreview || periodic;
                if (shouldProbe) Cache().lastAlbumArtProbe = now;
            }

            // Blocking thumbnail fetch happens outside the lock.
            bool thumbAvailable = false;
            if (shouldProbe) {
                try {
                    if (auto thumb = props.Thumbnail()) {
                        if (LoadThumbnail(thumb, &tmpArt, &tmpSize) && tmpArt && tmpSize > 0) {
                            tmpFingerprint = ComputeAlbumArtFingerprint(
                                tmpArt, static_cast<size_t>(tmpSize));
                            thumbAvailable = true;
                        } else if (tmpArt) {
                            CoTaskMemFree(tmpArt); tmpArt = nullptr; tmpSize = 0;
                        }
                    }
                } catch (...) {
                    if (tmpArt) { CoTaskMemFree(tmpArt); tmpArt = nullptr; tmpSize = 0; }
                }
            }

            {
                std::lock_guard<std::mutex> lock(Cache().mutex);
                bool trackChanged = title != Cache().lastTitle ||
                    artist != Cache().lastArtist || source != Cache().lastSource;
                bool albumArtChanged = shouldProbe && thumbAvailable &&
                    (tmpSize != Cache().albumArtSize || tmpFingerprint != Cache().albumArtFingerprint);
                bool clearArt = shouldProbe && !thumbAvailable && trackChanged;

                CopyToBuffer(info->title, sizeof(info->title), title);
                CopyToBuffer(info->artist, sizeof(info->artist), artist);
                CopyToBuffer(info->sourceApp, sizeof(info->sourceApp), source);
                info->trackChanged = (trackChanged || albumArtChanged) ? 1 : 0;

                if (albumArtChanged && tmpArt && tmpSize > 0) {
                    FreeCachedAlbumArt();
                    Cache().albumArt = tmpArt;
                    Cache().albumArtSize = tmpSize;
                    Cache().albumArtFingerprint = tmpFingerprint;
                    tmpArt = nullptr; tmpSize = 0;
                } else if (clearArt) {
                    FreeCachedAlbumArt();
                    Cache().albumArtFingerprint = 0;
                }
                if (tmpArt) { CoTaskMemFree(tmpArt); tmpArt = nullptr; tmpSize = 0; }

                if (trackChanged) {
                    Cache().lastTitle = title;
                    Cache().lastArtist = artist;
                    Cache().lastSource = source;
                }
                info->albumArtPng = Cache().albumArt;
                info->albumArtSize = Cache().albumArtSize;
            }

            UpdatePositionInfo(playbackInfo, timelineProps,
                               &info->positionMs, &info->durationMs, &info->isPlaying);
            {
                std::lock_guard<std::mutex> lock(Cache().mutex);
                Cache().lastPositionMs = info->positionMs;
                Cache().lastDurationMs = info->durationMs;
                Cache().lastIsPlaying = info->isPlaying;
                Cache().lastUpdate = now;
            }
            return 1;
        } catch (...) {
            if (tmpArt) CoTaskMemFree(tmpArt);
            return 0;
        }
    }

    __declspec(dllexport) int32_t __stdcall GetCurrentMediaInfo(MediaInfo* info) {
        __try { return GetCurrentMediaInfoBody(info); }
        __except (EXCEPTION_EXECUTE_HANDLER) { return 0; }
    }

    static int32_t __stdcall GetCurrentPositionInfoBody(PositionInfo* info) {
        if (!info) return 0;
        std::memset(info, 0, sizeof(PositionInfo));
        if (!EnsureApartment()) return 0;

        // Serve from the short-lived position cache when fresh.
        {
            std::lock_guard<std::mutex> lock(Cache().mutex);
            if (Cache().lastUpdate != std::chrono::steady_clock::time_point{}) {
                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - Cache().lastUpdate).count();
                auto window = Cache().lastIsPlaying ? kPositionCachePlaying.count()
                                                    : kPositionCachePaused.count();
                if (elapsed >= 0 && elapsed < window) {
                    info->isPlaying = Cache().lastIsPlaying;
                    info->durationMs = Cache().lastDurationMs;
                    if (Cache().lastIsPlaying) {
                        int64_t estimated = Cache().lastPositionMs + elapsed;
                        if (Cache().lastDurationMs > 0 && estimated > Cache().lastDurationMs)
                            estimated = Cache().lastDurationMs;
                        info->positionMs = estimated;
                    } else {
                        info->positionMs = Cache().lastPositionMs;
                    }
                    return 1;
                }
            }
        }

        try {
            auto session = GetActiveSession();
            if (!session) return 0;
            UpdatePositionInfo(session.GetPlaybackInfo(), session.GetTimelineProperties(),
                               &info->positionMs, &info->durationMs, &info->isPlaying);
            std::lock_guard<std::mutex> lock(Cache().mutex);
            Cache().lastPositionMs = info->positionMs;
            Cache().lastDurationMs = info->durationMs;
            Cache().lastIsPlaying = info->isPlaying;
            Cache().lastUpdate = std::chrono::steady_clock::now();
            return 1;
        } catch (...) {
            return 0;
        }
    }

    __declspec(dllexport) int32_t __stdcall GetCurrentPositionInfo(PositionInfo* info) {
        __try { return GetCurrentPositionInfoBody(info); }
        __except (EXCEPTION_EXECUTE_HANDLER) { return 0; }
    }

    __declspec(dllexport) void __stdcall FreeMediaInfo(MediaInfo* info) {
        if (info) { info->albumArtPng = nullptr; info->albumArtSize = 0; }
    }

    static void __stdcall ClearCacheBody() {
        {
            std::lock_guard<std::mutex> lock(Cache().mutex);
            FreeCachedAlbumArt();
            Cache().lastTitle.clear();
            Cache().lastArtist.clear();
            Cache().lastSource.clear();
            Cache().lastPositionMs = 0;
            Cache().lastDurationMs = 0;
            Cache().lastIsPlaying = 0;
            Cache().albumArtFingerprint = 0;
            Cache().lastUpdate = {};
            Cache().lastAlbumArtProbe = {};
        }
        {
            std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
            State().sessionManager = nullptr;
            State().sessionManagerUpdate = {};
        }
    }

    __declspec(dllexport) void __stdcall ClearCache() {
        __try { ClearCacheBody(); }
        __except (EXCEPTION_EXECUTE_HANDLER) {}
    }

    static int32_t __stdcall SendMediaCommandBody(int32_t commandType, int64_t seekMs) {
        if (!EnsureApartment()) return 0;
        try {
            auto session = GetActiveSession();
            if (!session) return 0;
            IAsyncOperation<bool> op = nullptr;
            switch (commandType) {
                case 0: op = session.TryPlayAsync(); break;
                case 1: op = session.TryPauseAsync(); break;
                case 2: op = session.TryTogglePlayPauseAsync(); break;
                case 3: op = session.TrySkipNextAsync(); break;
                case 4: op = session.TrySkipPreviousAsync(); break;
                case 5: op = session.TryChangePlaybackPositionAsync(seekMs * 10000LL); break;
                default: return 0;
            }
            bool applied = false;
            if (!TryGetAsyncResult(op, &applied, kAsyncWaitTimeout)) return 0;
            return applied ? 1 : 0;
        } catch (...) {
            return 0;
        }
    }

    __declspec(dllexport) int32_t __stdcall SendMediaCommand(int32_t commandType, int64_t seekMs = 0) {
        __try { return SendMediaCommandBody(commandType, seekMs); }
        __except (EXCEPTION_EXECUTE_HANDLER) { return 0; }
    }

    __declspec(dllexport) int32_t __stdcall Play() { return SendMediaCommand(0); }
    __declspec(dllexport) int32_t __stdcall Pause() { return SendMediaCommand(1); }
    __declspec(dllexport) int32_t __stdcall TogglePlayPause() { return SendMediaCommand(2); }
    __declspec(dllexport) int32_t __stdcall SkipNext() { return SendMediaCommand(3); }
    __declspec(dllexport) int32_t __stdcall SkipPrevious() { return SendMediaCommand(4); }
    __declspec(dllexport) int32_t __stdcall SeekToMs(int64_t ms) { return SendMediaCommand(5, ms); }

    // ----- Audio waveform -----

    static int32_t __stdcall StartAudioCaptureBody() {
        WaveformState& w = Wave();
        std::lock_guard<std::mutex> lock(w.mutex);
        if (w.started) return 1;
        w.stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!w.stopEvent) return 0;
        w.running.store(true, std::memory_order_relaxed);
        try {
            w.thread = std::thread(CaptureThreadMain);
        } catch (...) {
            w.running.store(false, std::memory_order_relaxed);
            CloseHandle(w.stopEvent);
            w.stopEvent = nullptr;
            return 0;
        }
        w.started = true;
        return 1;
    }

    __declspec(dllexport) int32_t __stdcall StartAudioCapture() {
        __try { return StartAudioCaptureBody(); }
        __except (EXCEPTION_EXECUTE_HANDLER) { return 0; }
    }

    static void __stdcall StopAudioCaptureBody() {
        WaveformState& w = Wave();
        std::thread worker;
        HANDLE evt = nullptr;
        {
            std::lock_guard<std::mutex> lock(w.mutex);
            if (!w.started) return;
            w.running.store(false, std::memory_order_relaxed);
            if (w.stopEvent) SetEvent(w.stopEvent);
            worker = std::move(w.thread);
            evt = w.stopEvent;
            w.stopEvent = nullptr;
            w.started = false;
        }
        if (worker.joinable()) worker.join();
        if (evt) CloseHandle(evt);
        std::lock_guard<std::mutex> lock(w.mutex);
        std::fill(std::begin(w.points), std::end(w.points), 0.0f);
        w.head = 0;
        w.level.store(0.0f, std::memory_order_relaxed);
    }

    __declspec(dllexport) void __stdcall StopAudioCapture() {
        __try { StopAudioCaptureBody(); }
        __except (EXCEPTION_EXECUTE_HANDLER) {}
    }

    // Copies the most recent waveform points oldest->newest into outBuffer.
    // Returns the number of points written (<= maxSamples, <= kWavePoints).
    static int32_t __stdcall GetWaveformBody(float* outBuffer, int32_t maxSamples) {
        if (!outBuffer || maxSamples <= 0) return 0;
        WaveformState& w = Wave();
        std::lock_guard<std::mutex> lock(w.mutex);
        int count = (std::min)(maxSamples, kWavePoints);
        int start = (w.head - count % kWavePoints + kWavePoints) % kWavePoints;
        for (int i = 0; i < count; ++i)
            outBuffer[i] = w.points[(start + i) % kWavePoints];
        return count;
    }

    __declspec(dllexport) int32_t __stdcall GetWaveform(float* outBuffer, int32_t maxSamples) {
        __try { return GetWaveformBody(outBuffer, maxSamples); }
        __except (EXCEPTION_EXECUTE_HANDLER) { return 0; }
    }

    __declspec(dllexport) float __stdcall GetAudioLevel() {
        __try { return Wave().level.load(std::memory_order_relaxed); }
        __except (EXCEPTION_EXECUTE_HANDLER) { return 0.0f; }
    }

    // ----- Lyrics -----

    static int32_t __stdcall FetchLyricsBody(const char* artistC, const char* titleC,
                                             LyricsResult* out) {
        if (!out) return kLyricsError;
        std::memset(out, 0, sizeof(LyricsResult));
        out->status = kLyricsNotFound;

        std::string artist = artistC ? artistC : "";
        std::string title = titleC ? titleC : "";
        if (title.empty()) return out->status;

        std::string primaryArtist = PrimaryArtist(artist);
        std::string strippedTitle = StripTrackDecorations(title);

        // Direct get candidates, most specific first.
        struct Candidate { std::string artist, track; };
        std::vector<Candidate> candidates = {
            {artist, title},
            {primaryArtist, title},
            {artist, strippedTitle},
            {primaryArtist, strippedTitle},
        };

        LyricsContent best;
        for (const auto& c : candidates) {
            LyricsContent attempt;
            if (FetchViaGet(c.artist, c.track, &attempt)) { best = attempt; break; }
            if (best.plain.empty() && !attempt.plain.empty()) best.plain = attempt.plain;
            if (attempt.transient) best.transient = true;
        }

        // Fall back to fuzzy search when no synced lyrics yet.
        if (best.synced.empty()) {
            for (const auto& c : {Candidate{primaryArtist, strippedTitle}, Candidate{artist, title}}) {
                LyricsContent attempt;
                if (FetchViaSearch(c.artist, c.track, &attempt)) { best.synced = attempt.synced; break; }
                if (best.plain.empty() && !attempt.plain.empty()) best.plain = attempt.plain;
                if (attempt.transient) best.transient = true;
            }
        }

        if (!best.synced.empty() || !best.plain.empty()) {
            out->syncedLrc = DupToCoTaskMem(best.synced, &out->syncedLen);
            out->plainText = DupToCoTaskMem(best.plain, &out->plainLen);
            out->status = kLyricsFound;
        } else {
            out->status = best.transient ? kLyricsError : kLyricsNotFound;
        }
        return out->status;
    }

    __declspec(dllexport) int32_t __stdcall FetchLyrics(const char* artist, const char* title,
                                                        LyricsResult* out) {
        __try { return FetchLyricsBody(artist, title, out); }
        __except (EXCEPTION_EXECUTE_HANDLER) { return kLyricsError; }
    }

    __declspec(dllexport) void __stdcall FreeLyrics(LyricsResult* result) {
        if (!result) return;
        if (result->syncedLrc) { CoTaskMemFree(result->syncedLrc); result->syncedLrc = nullptr; }
        if (result->plainText) { CoTaskMemFree(result->plainText); result->plainText = nullptr; }
        result->syncedLen = 0;
        result->plainLen = 0;
    }

    BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason, LPVOID) {
        if (ul_reason == DLL_PROCESS_ATTACH) {
            DisableThreadLibraryCalls(hModule);
        }
        return TRUE;
    }
}
