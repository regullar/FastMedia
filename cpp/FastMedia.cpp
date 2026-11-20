#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <objbase.h>
#include <mmsystem.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Storage.Streams.h>

#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <audiopolicy.h>
#include <endpointvolume.h>
#include <appmodel.h>
#include <mmreg.h>

#include <string>
#include <vector>
#include <memory>
#include <new>
#include <chrono>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <thread>
#include <cctype>
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

struct AudioSource {
    int32_t pid;
    char name[256];
    float peak;
    uint8_t active;
    uint8_t current;
};
#pragma pack(pop)

namespace {
    // ----- SMTC tuning -----
    constexpr auto kAsyncWaitTimeout = std::chrono::milliseconds(500);
    constexpr auto kPositionCacheWindow = std::chrono::milliseconds(1200);
    // Players (Spotify, Chromium) publish the new title first and the new thumbnail a moment
    // later, so the cover keeps being re-probed for a while after every track change.
    constexpr auto kArtSettleWindow = std::chrono::seconds(6);

    // ----- Waveform tuning -----
    constexpr int kWavePoints = 256;       // ring length the renderer reads back
    static_assert((kWavePoints & (kWavePoints - 1)) == 0, "ring index masking needs a power of two");
    // 1 s shared-mode buffer: large enough to absorb a slow target re-resolution on the
    // capture thread without dropping packets (latency is irrelevant for a visualiser).
    constexpr REFERENCE_TIME kCaptureBufferHns = 10000000;
    constexpr float kLevelAttack = 0.35f;  // smoothing toward louder values
    constexpr float kLevelRelease = 0.12f; // smoothing toward quieter values
    constexpr auto kTargetRecheck = std::chrono::seconds(2);   // auto-follow re-resolution rate
    constexpr DWORD kActivationTimeoutMs = 2000;
}

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

namespace {
    struct MediaCache {
        std::mutex mutex;
        std::string lastTitle;
        std::string lastArtist;
        std::string lastAlbumTitle;
        std::string lastAlbumArtist;
        std::string lastSource;
        uint8_t* albumArt = nullptr;
        int32_t albumArtSize = 0;
        uint64_t albumArtFingerprint = 0;

        int64_t lastPositionMs = 0;
        int64_t lastDurationMs = 0;
        uint8_t lastIsPlaying = 0;
        std::chrono::steady_clock::time_point lastUpdate;
        std::chrono::steady_clock::time_point lastAlbumArtProbe;
        std::chrono::steady_clock::time_point lastTrackChange;
    };

    struct WaveformState {
        std::mutex lifecycleMutex;
        float points[kWavePoints] = {};
        std::atomic<int> head{0};
        std::atomic<float> level{0.0f};

        std::thread thread;
        std::atomic<bool> running{false};
        HANDLE stopEvent = nullptr;
        bool started = false;

        // 0 = auto-follow the current SMTC session, otherwise an explicit PID.
        std::atomic<uint32_t> requestedPid{0};
        // Bumped whenever the capture thread must drop its current session.
        std::atomic<uint32_t> generation{0};
        // PID the capture thread is actually reading, 0 when it fell back to the mix.
        std::atomic<uint32_t> activePid{0};
    };

    // One process that currently owns an audio render session on some endpoint.
    struct AudioSessionCandidate {
        uint32_t pid = 0;
        float peak = 0.0f;
        bool active = false;
        std::string exeName;   // UTF-8 base name, e.g. "Spotify.exe"
        std::string aumid;     // UTF-8 package AUMID, empty for unpackaged Win32 processes
    };

    struct AudioSourceSnapshot {
        std::mutex mutex;
        std::vector<AudioSessionCandidate> items;
        uint32_t currentPid = 0;
    };

    typedef void (__stdcall *MediaEventCallback)(int32_t eventType);

    enum MediaEventType {
        kEventNone = 0,
        kEventMediaPropertiesChanged = 1,
        kEventPlaybackInfoChanged = 2,
        kEventTimelinePropertiesChanged = 3,
        kEventSessionChanged = 4,
        kEventPlaybackStopped = 5
    };

    struct EventState {
        std::mutex mutex;
        MediaEventCallback callback = nullptr;
        bool isRegistered = false;   // Java wants events; manager may still be unbound
        GlobalSystemMediaTransportControlsSessionManager manager = nullptr;
        GlobalSystemMediaTransportControlsSession session = nullptr;
        std::string sessionAumid;
        winrt::event_token tokenManagerCurrentSessionChanged{};
        winrt::event_token tokenManagerSessionsChanged{};
        winrt::event_token tokenMediaPropertiesChanged{};
        winrt::event_token tokenPlaybackInfoChanged{};
        winrt::event_token tokenTimelinePropertiesChanged{};
    };

    // Lock order (outer -> inner): events.mutex -> sessionManagerMutex / cache.mutex.
    struct GlobalState {
        MediaCache cache;

        std::mutex sessionManagerMutex;
        GlobalSystemMediaTransportControlsSessionManager sessionManager = nullptr;

        EventState events;
        WaveformState waveform;
        AudioSourceSnapshot sources;
    };

    // Intentionally leaked: no destructor may touch COM proxies or join threads while the
    // process is being torn down.
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
    inline AudioSourceSnapshot& Sources() { return State().sources; }
    inline EventState& Events() { return State().events; }

    // 0 - unknown, 1 - ready for WinRT calls, 2 - unsupported on thread.
    thread_local uint8_t g_apartmentState = 0;

    inline bool EnsureApartment() {
        if (g_apartmentState == 1) return true;
        if (g_apartmentState == 2) return false;
        try {
            winrt::init_apartment(winrt::apartment_type::multi_threaded);
            g_apartmentState = 1;
            return true;
        } catch (const winrt::hresult_error& ex) {
            // If the thread is already in STA mode (e.g. Swing EDT / JavaFX UI thread),
            // COM is already initialized and WinRT calls can still proceed safely.
            if (ex.code() == RPC_E_CHANGED_MODE) {
                g_apartmentState = 1;
                return true;
            }
            g_apartmentState = 2;
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
        // Never cut a UTF-8 sequence in half: back off to the start of the code point.
        if (len < src.size()) {
            while (len > 0 && (static_cast<unsigned char>(src[len]) & 0xC0) == 0x80) --len;
        }
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

    struct AsyncWaitContext {
        HANDLE hEvent = nullptr;
        AsyncWaitContext() {
            hEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        }
        ~AsyncWaitContext() {
            if (hEvent) {
                CloseHandle(hEvent);
                hEvent = nullptr;
            }
        }
    };

    template<typename TOp, typename TResult>
    bool TryGetAsyncResult(const TOp& op, TResult* out,
                           std::chrono::milliseconds timeout) {
        if (!op || !out) return false;
        try {
            if (op.Status() == AsyncStatus::Completed) {
                *out = op.GetResults();
                return true;
            }
            if (op.Status() == AsyncStatus::Canceled || op.Status() == AsyncStatus::Error) {
                return false;
            }

            auto ctx = std::make_shared<AsyncWaitContext>();
            if (!ctx->hEvent) return false;

            op.Completed([ctx](auto const&, AsyncStatus) {
                if (ctx->hEvent) {
                    SetEvent(ctx->hEvent);
                }
            });

            DWORD waitRes = WaitForSingleObject(ctx->hEvent, static_cast<DWORD>(timeout.count()));

            if (waitRes == WAIT_OBJECT_0 && op.Status() == AsyncStatus::Completed) {
                *out = op.GetResults();
                return true;
            }
            try { op.Cancel(); } catch (...) {}
        } catch (...) {}
        return false;
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
    using SessionManager = GlobalSystemMediaTransportControlsSessionManager;
    using SmtcSession = GlobalSystemMediaTransportControlsSession;

    void RebindEvents();

    // Never called with events.mutex held by the caller's own frame on this thread
    // (RequestAsync can take up to kAsyncWaitTimeout).
    bool TryGetSessionManager(SessionManager* out) {
        *out = nullptr;
        {
            std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
            if (State().sessionManager) {
                *out = State().sessionManager;
                return true;
            }
        }
        SessionManager manager = nullptr;
        if (!TryGetAsyncResult(SessionManager::RequestAsync(), &manager, kAsyncWaitTimeout) ||
            !manager) {
            return false;
        }
        std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
        if (!State().sessionManager) State().sessionManager = manager;
        *out = State().sessionManager;
        return true;
    }

    void DropSessionManager(const SessionManager& dead) {
        std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
        if (State().sessionManager == dead) State().sessionManager = nullptr;
    }

    enum class Lookup { Ok, NoSession, Error };

    // Distinguishes "nothing is playing" from "could not ask right now" so callers never
    // report a transient SMTC hiccup as a stopped player.
    Lookup GetActiveSession(SmtcSession& out) {
        out = nullptr;
        SessionManager manager = nullptr;
        if (!TryGetSessionManager(&manager)) return Lookup::Error;
        try {
            out = manager.GetCurrentSession();
        } catch (...) {
            // The SMTC host went away (e.g. explorer restart): request a fresh manager next
            // time and move the push events over to it.
            DropSessionManager(manager);
            RebindEvents();
            return Lookup::Error;
        }
        bool needsBind;
        {
            std::lock_guard<std::mutex> lock(Events().mutex);
            needsBind = Events().isRegistered && !Events().manager;
        }
        if (needsBind) RebindEvents();
        return out ? Lookup::Ok : Lookup::NoSession;
    }

    void InvalidatePositionCache() {
        std::lock_guard<std::mutex> lock(Cache().mutex);
        Cache().lastUpdate = {};
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
// Audio source resolution -> per-process capture target
// ---------------------------------------------------------------------------

namespace {
    std::string WideToUtf8(const wchar_t* data, int length) {
        if (!data || length <= 0) return {};
        int size = WideCharToMultiByte(CP_UTF8, 0, data, length, nullptr, 0, nullptr, nullptr);
        if (size <= 0) return {};
        std::string out(static_cast<size_t>(size), '\0');
        WideCharToMultiByte(CP_UTF8, 0, data, length, &out[0], size, nullptr, nullptr);
        return out;
    }

    std::string Utf8Lower(std::string value) {
        for (char& c : value) {
            c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        }
        return value;
    }

    std::string BeforeBang(const std::string& aumid) {
        size_t bang = aumid.find('!');
        return bang == std::string::npos ? aumid : aumid.substr(0, bang);
    }

    // "SpotifyAB.SpotifyMusic_1.2.3.0_x64__zpdnekdrzrea0!Spotify" -> "SpotifyAB.SpotifyMusic"
    std::string PackageFamily(const std::string& aumid) {
        std::string head = BeforeBang(aumid);
        size_t underscore = head.find('_');
        return underscore == std::string::npos ? head : head.substr(0, underscore);
    }

    std::string ExeStem(const std::string& exe) {
        size_t dot = exe.find_last_of('.');
        return dot == std::string::npos ? exe : exe.substr(0, dot);
    }

    bool ProcessAlive(uint32_t pid) {
        if (pid == 0) return false;
        HANDLE proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid));
        if (!proc) {
            // A live process we are not allowed to query is still a valid capture target.
            return GetLastError() == ERROR_ACCESS_DENIED;
        }
        DWORD exitCode = 0;
        BOOL ok = GetExitCodeProcess(proc, &exitCode);
        CloseHandle(proc);
        return ok && exitCode == STILL_ACTIVE;
    }

    void FillProcessIdentity(AudioSessionCandidate& candidate) {
        HANDLE proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE,
                                  static_cast<DWORD>(candidate.pid));
        if (!proc) {
            candidate.exeName = "pid-" + std::to_string(candidate.pid);
            return;
        }

        wchar_t path[MAX_PATH * 2] = {};
        DWORD pathLen = static_cast<DWORD>(_countof(path));
        if (QueryFullProcessImageNameW(proc, 0, path, &pathLen) && pathLen > 0) {
            std::wstring full(path, pathLen);
            size_t slash = full.find_last_of(L"\\/");
            std::wstring base = slash == std::wstring::npos ? full : full.substr(slash + 1);
            candidate.exeName = WideToUtf8(base.data(), static_cast<int>(base.size()));
        }
        if (candidate.exeName.empty()) {
            candidate.exeName = "pid-" + std::to_string(candidate.pid);
        }

        wchar_t aumid[512] = {};
        UINT32 aumidLen = static_cast<UINT32>(_countof(aumid));
        if (GetApplicationUserModelId(proc, &aumidLen, aumid) == ERROR_SUCCESS) {
            candidate.aumid = WideToUtf8(aumid, static_cast<int>(wcsnlen(aumid, _countof(aumid))));
        }
        CloseHandle(proc);
    }

    void CollectSession(IAudioSessionControl* control, std::vector<AudioSessionCandidate>& out) {
        IAudioSessionControl2* control2 = nullptr;
        if (FAILED(control->QueryInterface(__uuidof(IAudioSessionControl2),
                                          reinterpret_cast<void**>(&control2))) || !control2) {
            return;
        }
        DWORD pid = 0;
        control2->GetProcessId(&pid);
        AudioSessionState state = AudioSessionStateInactive;
        control2->GetState(&state);
        control2->Release();
        if (pid == 0) return; // the system-sounds session has no owner process

        float peak = 0.0f;
        IAudioMeterInformation* meter = nullptr;
        if (SUCCEEDED(control->QueryInterface(__uuidof(IAudioMeterInformation),
                                             reinterpret_cast<void**>(&meter))) && meter) {
            meter->GetPeakValue(&peak);
            meter->Release();
        }
        const bool isActive = (state == AudioSessionStateActive);

        for (auto& existing : out) {
            if (existing.pid == pid) {
                existing.peak = (std::max)(existing.peak, peak);
                existing.active = existing.active || isActive;
                return;
            }
        }
        AudioSessionCandidate candidate;
        candidate.pid = pid;
        candidate.peak = peak;
        candidate.active = isActive;
        out.push_back(std::move(candidate));
    }

    // Every render endpoint on the machine, reduced to one entry per owning process.
    void EnumerateAudioSources(std::vector<AudioSessionCandidate>& out) {
        out.clear();

        IMMDeviceEnumerator* enumerator = nullptr;
        HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                      __uuidof(IMMDeviceEnumerator),
                                      reinterpret_cast<void**>(&enumerator));
        if (FAILED(hr) || !enumerator) return;

        IMMDeviceCollection* devices = nullptr;
        if (SUCCEEDED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &devices)) &&
            devices) {
            UINT deviceCount = 0;
            devices->GetCount(&deviceCount);
            for (UINT d = 0; d < deviceCount; ++d) {
                IMMDevice* device = nullptr;
                if (FAILED(devices->Item(d, &device)) || !device) continue;

                IAudioSessionManager2* manager = nullptr;
                if (SUCCEEDED(device->Activate(__uuidof(IAudioSessionManager2), CLSCTX_ALL, nullptr,
                                              reinterpret_cast<void**>(&manager))) && manager) {
                    IAudioSessionEnumerator* sessions = nullptr;
                    if (SUCCEEDED(manager->GetSessionEnumerator(&sessions)) && sessions) {
                        int sessionCount = 0;
                        sessions->GetCount(&sessionCount);
                        for (int s = 0; s < sessionCount; ++s) {
                            IAudioSessionControl* control = nullptr;
                            if (SUCCEEDED(sessions->GetSession(s, &control)) && control) {
                                CollectSession(control, out);
                                control->Release();
                            }
                        }
                        sessions->Release();
                    }
                    manager->Release();
                }
                device->Release();
            }
            devices->Release();
        }
        enumerator->Release();

        for (auto& candidate : out) FillProcessIdentity(candidate);
    }

    // How confidently a candidate process is the owner of an SMTC SourceAppUserModelId.
    // 0 means "no relationship found".
    int ScoreCandidate(const AudioSessionCandidate& candidate, const std::string& source) {
        if (source.empty() || candidate.pid == 0) return 0;

        const std::string src = Utf8Lower(source);
        const std::string srcHead = BeforeBang(src);
        const std::string srcFamily = PackageFamily(src);
        const std::string candAumid = Utf8Lower(candidate.aumid);
        const std::string candExe = Utf8Lower(candidate.exeName);

        if (!candAumid.empty()) {
            if (candAumid == src) return 100;
            if (BeforeBang(candAumid) == srcHead) return 95;
            if (PackageFamily(candAumid) == srcFamily) return 85;
        }
        if (!candExe.empty()) {
            if (candExe == src || candExe == srcHead) return 75;
            const std::string stem = ExeStem(candExe);
            if (stem == srcFamily || stem == srcHead) return 65;
        }
        return 0;
    }

    uint32_t PickMatchingPid(const std::vector<AudioSessionCandidate>& candidates,
                             const std::string& source) {
        int bestScore = 0;
        uint32_t bestPid = 0;
        float bestPeak = -1.0f;
        bool bestActive = false;

        uint32_t onlyActivePid = 0;
        int activeCount = 0;

        for (const auto& candidate : candidates) {
            if (candidate.active) {
                ++activeCount;
                onlyActivePid = candidate.pid;
            }
            const int score = ScoreCandidate(candidate, source);
            if (score <= 0) continue;

            const bool better = score > bestScore ||
                (score == bestScore && ((candidate.active && !bestActive) ||
                    (candidate.active == bestActive && candidate.peak > bestPeak)));
            if (better) {
                bestScore = score;
                bestPid = candidate.pid;
                bestPeak = candidate.peak;
                bestActive = candidate.active;
            }
        }
        if (bestPid != 0) return bestPid;
        // No identity match: when a single process owns all current sound it is the player.
        return activeCount == 1 ? onlyActivePid : 0;
    }

    // false when SMTC could not be asked right now; `out` is empty when nothing is playing.
    bool CurrentSourceAumid(std::string& out) {
        out.clear();
        if (!EnsureApartment()) return false;
        try {
            SmtcSession session = nullptr;
            switch (GetActiveSession(session)) {
                case Lookup::Ok:
                    out = winrt::to_string(session.SourceAppUserModelId());
                    return true;
                case Lookup::NoSession:
                    return true;
                default:
                    return false;
            }
        } catch (...) {
            return false;
        }
    }

    // Sticky SMTC source -> PID resolution, owned by the capture thread.
    struct AutoTarget {
        std::string aumid;
        uint32_t pid = 0;
        std::chrono::steady_clock::time_point resolvedAt{};

        uint32_t Resolve() {
            const auto now = std::chrono::steady_clock::now();
            std::string current;
            // A transient SMTC failure must not flip the capture over to the system mix.
            if (!CurrentSourceAumid(current)) return pid;
            if (current != aumid) {
                aumid = current;
                pid = 0; // the player changed, drop the stale target
            } else if (pid != 0 && ProcessAlive(pid)) {
                return pid;
            } else if (pid == 0 && now - resolvedAt < kTargetRecheck) {
                return pid; // unresolved: throttle the endpoint rescan
            }

            resolvedAt = now;
            if (aumid.empty()) {
                pid = 0;
                return pid;
            }
            std::vector<AudioSessionCandidate> candidates;
            EnumerateAudioSources(candidates);
            pid = PickMatchingPid(candidates, aumid);
            return pid;
        }
    };

    struct ProcessLoopbackHandler : IActivateAudioInterfaceCompletionHandler {
        ULONG ref = 1;
        // Owned by the handler, not by the caller: a completion that arrives after the caller
        // timed out must still signal a live handle, never a closed (or reused) one.
        HANDLE doneEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        HRESULT result = E_FAIL;
        IUnknown* activated = nullptr;

        ~ProcessLoopbackHandler() {
            if (activated) activated->Release();
            if (doneEvent) CloseHandle(doneEvent);
        }

        HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) noexcept override {
            if (!ppv) return E_POINTER;
            if (riid == __uuidof(IUnknown) ||
                riid == __uuidof(IActivateAudioInterfaceCompletionHandler) ||
                riid == __uuidof(IAgileObject)) {
                *ppv = static_cast<IActivateAudioInterfaceCompletionHandler*>(this);
                AddRef();
                return S_OK;
            }
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
        ULONG STDMETHODCALLTYPE AddRef() noexcept override { return InterlockedIncrement(&ref); }
        ULONG STDMETHODCALLTYPE Release() noexcept override {
            ULONG next = InterlockedDecrement(&ref);
            if (next == 0) delete this;
            return next;
        }

        HRESULT STDMETHODCALLTYPE ActivateCompleted(
            IActivateAudioInterfaceAsyncOperation* operation) override {
            HRESULT activateResult = E_FAIL;
            IUnknown* obj = nullptr;
            HRESULT hr = operation->GetActivateResult(&activateResult, &obj);
            result = FAILED(hr) ? hr : activateResult;
            activated = SUCCEEDED(result) ? obj : nullptr;
            if (activated != obj && obj) obj->Release();
            SetEvent(doneEvent);
            return S_OK;
        }
    };

    // Windows 10 20H1+: capture only what the given process tree renders, instead of the
    // whole endpoint mix. Fails for protected or higher-integrity targets and on older OS.
    HRESULT ActivateProcessLoopbackClient(uint32_t pid, IAudioClient** outClient) {
        if (!outClient) return E_POINTER;
        *outClient = nullptr;
        if (pid == 0) return E_INVALIDARG;

        AUDIOCLIENT_ACTIVATION_PARAMS params{};
        params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
        params.ProcessLoopbackParams.TargetProcessId = static_cast<DWORD>(pid);
        params.ProcessLoopbackParams.ProcessLoopbackMode =
            PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;

        PROPVARIANT activateParams;
        PropVariantInit(&activateParams);
        activateParams.vt = VT_BLOB;
        activateParams.blob.cbSize = sizeof(params);
        activateParams.blob.pBlobData = reinterpret_cast<BYTE*>(&params);

        auto* handler = new (std::nothrow) ProcessLoopbackHandler();
        if (!handler) return E_OUTOFMEMORY;
        if (!handler->doneEvent) {
            handler->Release();
            return E_OUTOFMEMORY;
        }

        IActivateAudioInterfaceAsyncOperation* operation = nullptr;
        HRESULT hr = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK,
                                                __uuidof(IAudioClient), &activateParams,
                                                handler, &operation);
        if (SUCCEEDED(hr)) {
            if (WaitForSingleObject(handler->doneEvent, kActivationTimeoutMs) == WAIT_OBJECT_0) {
                hr = handler->result;
                if (SUCCEEDED(hr)) {
                    hr = handler->activated
                        ? handler->activated->QueryInterface(
                              __uuidof(IAudioClient), reinterpret_cast<void**>(outClient))
                        : E_NOINTERFACE;
                }
            } else {
                hr = HRESULT_FROM_WIN32(WAIT_TIMEOUT);
            }
        }
        if (operation) operation->Release();
        handler->Release(); // a late completion keeps it alive through the operation's own ref

        if (FAILED(hr) && *outClient) {
            (*outClient)->Release();
            *outClient = nullptr;
        }
        return hr;
    }

    std::wstring EndpointId(IMMDevice* device) {
        std::wstring id;
        LPWSTR raw = nullptr;
        if (device && SUCCEEDED(device->GetId(&raw)) && raw) {
            id = raw;
            CoTaskMemFree(raw);
        }
        return id;
    }

    std::wstring DefaultRenderEndpointId(IMMDeviceEnumerator* enumerator) {
        IMMDevice* device = nullptr;
        if (FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device)) || !device) {
            return {};
        }
        std::wstring id = EndpointId(device);
        device->Release();
        return id;
    }
}

// ---------------------------------------------------------------------------
// WASAPI loopback capture -> waveform (Lock-Free Event-Driven)
// ---------------------------------------------------------------------------

namespace {
    inline void PushWavePoint(float peak) {
        WaveformState& w = Wave();
        int h = w.head.load(std::memory_order_relaxed);
        w.points[h] = peak;
        w.head.store((h + 1) & (kWavePoints - 1), std::memory_order_release);

        float prev = w.level.load(std::memory_order_relaxed);
        float k = peak > prev ? kLevelAttack : kLevelRelease;
        w.level.store(prev + (peak - prev) * k, std::memory_order_relaxed);
    }

    // Drops buffered points so a source switch never shows the previous source's tail.
    inline void ResetWaveBuffer() {
        WaveformState& w = Wave();
        std::fill(std::begin(w.points), std::end(w.points), 0.0f);
        w.head.store(0, std::memory_order_relaxed);
        w.level.store(0.0f, std::memory_order_relaxed);
    }

    // Folds samples into one waveform point per ~6 ms of audio (keeps the ring ~1.6 s wide).
    struct WaveBucket {
        uint32_t samplesPerBucket = 1;
        uint32_t count = 0;
        float peak = 0.0f;

        inline void Add(float amplitude) {
            if (amplitude > peak) peak = amplitude;
            if (++count >= samplesPerBucket) {
                PushWavePoint(peak > 1.0f ? 1.0f : peak);
                peak = 0.0f;
                count = 0;
            }
        }

        inline void AddSilence(uint32_t frames) {
            count += frames;
            while (count >= samplesPerBucket) {
                PushWavePoint(0.0f);
                count -= samplesPerBucket;
            }
            peak = 0.0f;
        }
    };

    enum class SampleKind { Unsupported, Float32, Int16, Int24, Int32 };

    template <SampleKind K>
    inline float ReadSample(const BYTE* p) {
        if constexpr (K == SampleKind::Float32) {
            float v;
            std::memcpy(&v, p, sizeof(v));
            return v;
        } else if constexpr (K == SampleKind::Int16) {
            int16_t v;
            std::memcpy(&v, p, sizeof(v));
            return static_cast<float>(v) * (1.0f / 32768.0f);
        } else if constexpr (K == SampleKind::Int24) {
            const int32_t v = static_cast<int32_t>(static_cast<uint32_t>(p[0]) << 8 |
                                                   static_cast<uint32_t>(p[1]) << 16 |
                                                   static_cast<uint32_t>(p[2]) << 24) >> 8;
            return static_cast<float>(v) * (1.0f / 8388608.0f);
        } else {
            // 32-bit container; 24-in-32 samples are left-justified, so the same scale applies.
            int32_t v;
            std::memcpy(&v, p, sizeof(v));
            return static_cast<float>(v) * (1.0f / 2147483648.0f);
        }
    }

    template <SampleKind K>
    void ConsumeFrames(const BYTE* data, UINT32 frames, WORD channels, UINT32 stride,
                       WaveBucket& bucket) {
        const float invChannels = 1.0f / static_cast<float>(channels);
        for (UINT32 f = 0; f < frames; ++f) {
            float sum = 0.0f;
            for (WORD c = 0; c < channels; ++c) {
                sum += ReadSample<K>(data);
                data += stride;
            }
            bucket.Add(std::fabs(sum * invChannels));
        }
    }

    SampleKind DetectSampleKind(const WAVEFORMATEX* mix, UINT32* stride) {
        *stride = 0;
        if (!mix->nChannels) return SampleKind::Unsupported;
        WORD tag = mix->wFormatTag;
        if (tag == WAVE_FORMAT_EXTENSIBLE && mix->cbSize >= 22) {
            tag = static_cast<WORD>(
                reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(mix)->SubFormat.Data1);
        }
        *stride = mix->nBlockAlign / mix->nChannels; // container size, e.g. 4 for 24-in-32
        if (tag == WAVE_FORMAT_IEEE_FLOAT && *stride == 4) return SampleKind::Float32;
        if (tag == WAVE_FORMAT_PCM) {
            switch (*stride) {
                case 2: return SampleKind::Int16;
                case 3: return SampleKind::Int24;
                case 4: return SampleKind::Int32;
                default: break;
            }
        }
        return SampleKind::Unsupported;
    }

    // Runs one capture session against the requested source: a single process when
    // wantedPid is set, otherwise the default render endpoint (the whole system mix).
    // Returns when the stop event fires, the target changes, or the device becomes invalid.
    HRESULT RunCaptureSession(uint32_t wantedPid, uint32_t startGeneration, AutoTarget* autoTarget,
                              HANDLE stopEvent, std::atomic<uint32_t>& activePidOut) {
        IMMDeviceEnumerator* enumerator = nullptr;
        IMMDevice* device = nullptr;
        IAudioClient* client = nullptr;
        IAudioCaptureClient* capture = nullptr;
        WAVEFORMATEX* mix = nullptr;
        HANDLE hAudioEvent = nullptr;
        HRESULT hr = S_OK;
        bool started = false;
        bool timerRaised = false;
        bool eventDriven = false;
        WaveformState& w = Wave();

        auto cleanup = [&]() {
            if (timerRaised) timeEndPeriod(1);
            if (client && started) client->Stop();
            if (mix) CoTaskMemFree(mix);
            if (capture) capture->Release();
            if (client) client->Release();
            if (device) device->Release();
            if (enumerator) enumerator->Release();
            if (hAudioEvent) CloseHandle(hAudioEvent);
        };

        // The process-loopback virtual device implements neither GetMixFormat nor
        // IsFormatSupported, so the shared mix format always comes from a real endpoint.
        hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                              __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(&enumerator));
        if (FAILED(hr)) { cleanup(); return hr; }

        hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);
        if (FAILED(hr)) { cleanup(); return hr; }
        const std::wstring endpointId = EndpointId(device);

        IAudioClient* formatProbe = nullptr;
        hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                              reinterpret_cast<void**>(&formatProbe));
        if (FAILED(hr)) { cleanup(); return hr; }
        hr = formatProbe->GetMixFormat(&mix);
        formatProbe->Release();
        if (FAILED(hr) || !mix) { cleanup(); return FAILED(hr) ? hr : E_FAIL; }

        hAudioEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);

        // Opens and initializes a client on one route (a process, or the endpoint when pid is 0):
        // event-driven first, timer-polled as fallback. A failed Initialize poisons the client,
        // so every attempt starts from a fresh activation.
        auto initRoute = [&](uint32_t pid) -> HRESULT {
            HRESULT r = E_FAIL;
            for (int mode = hAudioEvent ? 0 : 1; mode < 2; ++mode) {
                if (client) { client->Release(); client = nullptr; }
                r = pid != 0 ? ActivateProcessLoopbackClient(pid, &client)
                             : device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                                reinterpret_cast<void**>(&client));
                if (FAILED(r)) break;
                const bool useEvent = (mode == 0);
                const DWORD flags = AUDCLNT_STREAMFLAGS_LOOPBACK |
                    (useEvent ? AUDCLNT_STREAMFLAGS_EVENTCALLBACK : 0);
                r = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, kCaptureBufferHns, 0,
                                       mix, nullptr);
                if (SUCCEEDED(r) && useEvent) r = client->SetEventHandle(hAudioEvent);
                if (SUCCEEDED(r)) {
                    eventDriven = useEvent;
                    return S_OK;
                }
            }
            if (client) { client->Release(); client = nullptr; }
            return r;
        };

        uint32_t capturedPid = 0;
        hr = E_FAIL;
        if (wantedPid != 0) {
            hr = initRoute(wantedPid);
            if (SUCCEEDED(hr)) capturedPid = wantedPid;
        }
        if (FAILED(hr)) {
            // Per-process capture unavailable (older Windows, protected or elevated target,
            // dead process, or a format the virtual device refused): fall back to the endpoint
            // mix so the waveform keeps working.
            hr = initRoute(0);
            if (FAILED(hr)) { cleanup(); return hr; }
        }

        hr = client->GetService(__uuidof(IAudioCaptureClient),
                                reinterpret_cast<void**>(&capture));
        if (FAILED(hr)) { cleanup(); return hr; }

        UINT32 stride = 0;
        const SampleKind kind = DetectSampleKind(mix, &stride);
        const WORD channels = mix->nChannels;
        WaveBucket bucket;
        bucket.samplesPerBucket =
            (std::max<uint32_t>)(1u, static_cast<uint32_t>(mix->nSamplesPerSec) / 160u);

        hr = client->Start();
        if (FAILED(hr)) { cleanup(); return hr; }
        started = true;

        // Only the timer-polled fallback needs 1 ms scheduler granularity; raising it
        // system-wide in event mode would just cost every other process battery.
        if (!eventDriven) {
            timeBeginPeriod(1);
            timerRaised = true;
        }
        activePidOut.store(capturedPid, std::memory_order_relaxed);

        HANDLE waitHandles[2] = { stopEvent, hAudioEvent };
        const DWORD waitCount = eventDriven ? 2 : 1;
        auto lastTargetCheck = std::chrono::steady_clock::now();

        while (w.running.load(std::memory_order_relaxed)) {
            DWORD waitRes = WaitForMultipleObjects(waitCount, waitHandles, FALSE,
                                                   eventDriven ? 50 : 8);
            if (waitRes == WAIT_OBJECT_0) break; // stopEvent

            // The source changed under us (explicit SetWaveformSource or an SMTC session
            // switch): bail out so the caller restarts against the new target.
            if (w.generation.load(std::memory_order_acquire) != startGeneration) break;

            const auto now = std::chrono::steady_clock::now();
            if (now - lastTargetCheck >= kTargetRecheck) {
                lastTargetCheck = now;
                // Compared with the *wanted* PID, not the one we fell back to, so a refused
                // per-process capture does not restart the session on every recheck.
                if (autoTarget && autoTarget->Resolve() != wantedPid) break;
                if (!autoTarget && wantedPid != 0 && !ProcessAlive(wantedPid)) break;
                // The mix is tied to the endpoint; process loopback follows the process.
                if (capturedPid == 0 && DefaultRenderEndpointId(enumerator) != endpointId) break;
            }

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
                if (silent || !data) {
                    bucket.AddSilence(frames);
                } else {
                    switch (kind) {
                        case SampleKind::Float32:
                            ConsumeFrames<SampleKind::Float32>(data, frames, channels, stride, bucket);
                            break;
                        case SampleKind::Int16:
                            ConsumeFrames<SampleKind::Int16>(data, frames, channels, stride, bucket);
                            break;
                        case SampleKind::Int24:
                            ConsumeFrames<SampleKind::Int24>(data, frames, channels, stride, bucket);
                            break;
                        case SampleKind::Int32:
                            ConsumeFrames<SampleKind::Int32>(data, frames, channels, stride, bucket);
                            break;
                        default:
                            // Unknown format: keep the ring moving instead of freezing it.
                            bucket.AddSilence(frames);
                            break;
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

    void CaptureThreadMain(HANDLE stopEvent) {
        bool apartment = false;
        try {
            winrt::init_apartment(winrt::apartment_type::multi_threaded);
            apartment = true;
        } catch (...) {}

        WaveformState& w = Wave();
        AutoTarget autoTarget;
        uint32_t lastTarget = UINT32_MAX;
        while (w.running.load(std::memory_order_relaxed)) {
            HRESULT hr;
            // Nothing may escape a std::thread entry point: it would call std::terminate and
            // take the whole JVM down with it.
            try {
                // Snapshot the generation *before* choosing the target, so a SetWaveformSource
                // that lands while this session is still starting up ends it right away.
                const uint32_t generation = w.generation.load(std::memory_order_acquire);
                const uint32_t requested = w.requestedPid.load(std::memory_order_acquire);
                uint32_t target;
                AutoTarget* autoRef;
                if (requested != 0) {
                    // A dead manual target degrades to the system mix instead of spinning.
                    target = ProcessAlive(requested) ? requested : 0;
                    autoRef = nullptr;
                } else {
                    target = autoTarget.Resolve();
                    autoRef = &autoTarget;
                }

                if (target != lastTarget) {
                    ResetWaveBuffer();
                    lastTarget = target;
                }

                hr = RunCaptureSession(target, generation, autoRef, stopEvent, w.activePid);
            } catch (...) {
                hr = E_UNEXPECTED;
            }
            w.activePid.store(0, std::memory_order_relaxed);
            if (!w.running.load(std::memory_order_relaxed)) break;
            if (FAILED(hr)) {
                ResetWaveBuffer(); // no frozen picture of the last good audio
                if (WaitForSingleObject(stopEvent, 250) == WAIT_OBJECT_0) break;
            }
        }
        if (apartment) winrt::uninit_apartment();
    }
}

// ---------------------------------------------------------------------------
// SMTC push events
// ---------------------------------------------------------------------------

namespace {
    void NotifyJavaEvent(int32_t eventType) {
        MediaEventCallback cb = nullptr;
        {
            std::lock_guard<std::mutex> lock(Events().mutex);
            cb = Events().callback;
        }
        // Outside the lock: a lock held across a JVM upcall would serialize every WinRT event
        // thread behind it. Java only enqueues work here and never calls back synchronously.
        if (cb) cb(eventType);
    }

    void UnhookSessionEventsLocked() {
        EventState& e = Events();
        if (e.session) {
            try {
                if (e.tokenMediaPropertiesChanged.value != 0)
                    e.session.MediaPropertiesChanged(e.tokenMediaPropertiesChanged);
                if (e.tokenPlaybackInfoChanged.value != 0)
                    e.session.PlaybackInfoChanged(e.tokenPlaybackInfoChanged);
                if (e.tokenTimelinePropertiesChanged.value != 0)
                    e.session.TimelinePropertiesChanged(e.tokenTimelinePropertiesChanged);
            } catch (...) {}
        }
        e.tokenMediaPropertiesChanged = {};
        e.tokenPlaybackInfoChanged = {};
        e.tokenTimelinePropertiesChanged = {};
        e.session = nullptr;
        e.sessionAumid.clear();
    }

    void HookSessionEventsLocked(const SmtcSession& session) {
        UnhookSessionEventsLocked();
        if (!session) return;
        EventState& e = Events();
        try {
            e.session = session;
            e.sessionAumid = winrt::to_string(session.SourceAppUserModelId());
            // Every change makes the extrapolated position cache stale (seek, pause, new track).
            e.tokenMediaPropertiesChanged = session.MediaPropertiesChanged(
                [](auto const&, auto const&) {
                    InvalidatePositionCache();
                    NotifyJavaEvent(kEventMediaPropertiesChanged);
                });
            e.tokenPlaybackInfoChanged = session.PlaybackInfoChanged(
                [](auto const&, auto const&) {
                    InvalidatePositionCache();
                    NotifyJavaEvent(kEventPlaybackInfoChanged);
                });
            e.tokenTimelinePropertiesChanged = session.TimelinePropertiesChanged(
                [](auto const&, auto const&) {
                    InvalidatePositionCache();
                    NotifyJavaEvent(kEventTimelinePropertiesChanged);
                });
        } catch (...) {
            UnhookSessionEventsLocked();
        }
    }

    void UnhookManagerLocked() {
        EventState& e = Events();
        if (e.manager) {
            try {
                if (e.tokenManagerCurrentSessionChanged.value != 0)
                    e.manager.CurrentSessionChanged(e.tokenManagerCurrentSessionChanged);
                if (e.tokenManagerSessionsChanged.value != 0)
                    e.manager.SessionsChanged(e.tokenManagerSessionsChanged);
            } catch (...) {}
        }
        e.tokenManagerCurrentSessionChanged = {};
        e.tokenManagerSessionsChanged = {};
        e.manager = nullptr;
        UnhookSessionEventsLocked();
    }

    void OnSessionManagerEvent() {
        bool stopped = false;
        bool sourceChanged = false;
        {
            std::lock_guard<std::mutex> lock(Events().mutex);
            EventState& e = Events();
            if (!e.isRegistered || !e.manager) return;
            try {
                SmtcSession current = e.manager.GetCurrentSession();
                // SessionsChanged also fires when unrelated apps come and go: only re-hook (and
                // restart an auto-follow capture) when the current session really changed.
                if (current != e.session) {
                    const std::string before = e.sessionAumid;
                    HookSessionEventsLocked(current);
                    sourceChanged = e.sessionAumid != before;
                }
                stopped = (current == nullptr);
            } catch (...) {
                // Unknown state: let Java re-query, which tells "no session" from "error".
            }
        }
        if (sourceChanged && Wave().requestedPid.load(std::memory_order_acquire) == 0) {
            Wave().generation.fetch_add(1, std::memory_order_release);
        }
        InvalidatePositionCache();
        NotifyJavaEvent(stopped ? kEventPlaybackStopped : kEventSessionChanged);
    }

    // Binds the push events to the current session manager. Called on registration, when a
    // lookup notices the events are not bound yet, and when the SMTC host has died.
    void RebindEvents() {
        {
            std::lock_guard<std::mutex> lock(Events().mutex);
            if (!Events().isRegistered) return;
        }
        // Fetched before taking events.mutex: RequestAsync may block for kAsyncWaitTimeout.
        SessionManager manager = nullptr;
        if (!TryGetSessionManager(&manager)) return; // retried on the next session lookup

        std::lock_guard<std::mutex> lock(Events().mutex);
        EventState& e = Events();
        if (!e.isRegistered || e.manager == manager) return;
        UnhookManagerLocked();
        try {
            e.manager = manager;
            e.tokenManagerCurrentSessionChanged = manager.CurrentSessionChanged(
                [](auto const&, auto const&) { OnSessionManagerEvent(); });
            e.tokenManagerSessionsChanged = manager.SessionsChanged(
                [](auto const&, auto const&) { OnSessionManagerEvent(); });
            HookSessionEventsLocked(manager.GetCurrentSession());
        } catch (...) {
            UnhookManagerLocked();
        }
    }
}

// ---------------------------------------------------------------------------
// Entry point bodies (C++ linkage, so the try/catch in the exports below is honoured
// under /EHsc). Return codes: 1 = ok, 0 = no media session, -1 = transient failure.
// ---------------------------------------------------------------------------

namespace {
    int32_t GetCurrentMediaInfoBody(MediaInfo* info) {
        if (!info) return -1;
        std::memset(info, 0, sizeof(MediaInfo));
        if (!EnsureApartment()) return -1;

        uint8_t* tmpArt = nullptr;
        int32_t tmpSize = 0;
        uint64_t tmpFingerprint = 0;

        try {
            SmtcSession session = nullptr;
            switch (GetActiveSession(session)) {
                case Lookup::NoSession: return 0;
                case Lookup::Error: return -1;
                default: break;
            }

            auto propsAsync = session.TryGetMediaPropertiesAsync();
            auto playbackInfo = session.GetPlaybackInfo();
            auto timelineProps = session.GetTimelineProperties();

            GlobalSystemMediaTransportControlsSessionMediaProperties props = nullptr;
            if (!TryGetAsyncResult(propsAsync, &props, kAsyncWaitTimeout) || !props) return -1;

            std::string title = winrt::to_string(props.Title());
            std::string artist = winrt::to_string(props.Artist());
            std::string albumTitle = winrt::to_string(props.AlbumTitle());
            std::string albumArtist = winrt::to_string(props.AlbumArtist());
            std::string source = GetProcessNameFast(session.SourceAppUserModelId());
            const auto now = std::chrono::steady_clock::now();

            bool shouldProbe;
            {
                std::lock_guard<std::mutex> lock(Cache().mutex);
                const bool trackChangedPreview = title != Cache().lastTitle ||
                    artist != Cache().lastArtist || source != Cache().lastSource;
                const bool needsInitialArt = (Cache().albumArt == nullptr &&
                    Cache().lastAlbumArtProbe == std::chrono::steady_clock::time_point{});
                const bool sameAlbum = !albumTitle.empty() && albumTitle == Cache().lastAlbumTitle &&
                    albumArtist == Cache().lastAlbumArtist && Cache().albumArt != nullptr;
                const bool settling =
                    Cache().lastTrackChange != std::chrono::steady_clock::time_point{} &&
                    now - Cache().lastTrackChange < kArtSettleWindow;
                // A new track on the same album keeps its cover; otherwise keep probing while
                // the player may still be swapping the thumbnail in after the title.
                shouldProbe = needsInitialArt || (trackChangedPreview ? !sameAlbum : settling);
                if (shouldProbe) Cache().lastAlbumArtProbe = now;
            }

            // Blocking thumbnail fetch happens outside the cache lock
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
                    Cache().lastAlbumTitle = albumTitle;
                    Cache().lastAlbumArtist = albumArtist;
                    Cache().lastSource = source;
                    Cache().lastTrackChange = now;
                }

                // The cover is handed out on every call (an independent copy Java frees through
                // FreeMediaInfo): change detection lives on the caller's side, so no caller can
                // "consume" a change another caller still needs to see.
                if (Cache().albumArt && Cache().albumArtSize > 0) {
                    info->albumArtPng = static_cast<uint8_t*>(CoTaskMemAlloc(Cache().albumArtSize));
                    if (info->albumArtPng) {
                        std::memcpy(info->albumArtPng, Cache().albumArt, Cache().albumArtSize);
                        info->albumArtSize = Cache().albumArtSize;
                    }
                }
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
            if (info->albumArtPng) {
                CoTaskMemFree(info->albumArtPng);
                info->albumArtPng = nullptr;
                info->albumArtSize = 0;
            }
            return -1;
        }
    }

    int32_t GetCurrentPositionInfoBody(PositionInfo* info) {
        if (!info) return -1;
        std::memset(info, 0, sizeof(PositionInfo));
        if (!EnsureApartment()) return -1;

        // Serve from position cache when within window to avoid redundant COM IPC. Every SMTC
        // event and transport command invalidates it, so it never hides a real change.
        {
            std::lock_guard<std::mutex> lock(Cache().mutex);
            if (Cache().lastUpdate != std::chrono::steady_clock::time_point{}) {
                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - Cache().lastUpdate).count();
                if (elapsed >= 0 && elapsed < kPositionCacheWindow.count()) {
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
            SmtcSession session = nullptr;
            switch (GetActiveSession(session)) {
                case Lookup::NoSession: return 0;
                case Lookup::Error: return -1;
                default: break;
            }
            UpdatePositionInfo(session.GetPlaybackInfo(), session.GetTimelineProperties(),
                               &info->positionMs, &info->durationMs, &info->isPlaying);
            std::lock_guard<std::mutex> lock(Cache().mutex);
            Cache().lastPositionMs = info->positionMs;
            Cache().lastDurationMs = info->durationMs;
            Cache().lastIsPlaying = info->isPlaying;
            Cache().lastUpdate = std::chrono::steady_clock::now();
            return 1;
        } catch (...) {
            return -1;
        }
    }

    int32_t RegisterMediaEventCallbackBody(MediaEventCallback callback) {
        if (!EnsureApartment()) return 0;
        {
            std::lock_guard<std::mutex> lock(Events().mutex);
            Events().callback = callback;
            Events().isRegistered = true;
        }
        RebindEvents();
        std::lock_guard<std::mutex> lock(Events().mutex);
        return Events().manager ? 1 : 0;
    }

    void UnregisterMediaEventCallbackBody() {
        std::lock_guard<std::mutex> lock(Events().mutex);
        UnhookManagerLocked();
        Events().callback = nullptr;
        Events().isRegistered = false;
    }

    void ClearCacheBody() {
        {
            std::lock_guard<std::mutex> lock(Cache().mutex);
            FreeCachedAlbumArt();
            Cache().lastTitle.clear();
            Cache().lastArtist.clear();
            Cache().lastAlbumTitle.clear();
            Cache().lastAlbumArtist.clear();
            Cache().lastSource.clear();
            Cache().lastPositionMs = 0;
            Cache().lastDurationMs = 0;
            Cache().lastIsPlaying = 0;
            Cache().albumArtFingerprint = 0;
            Cache().lastUpdate = {};
            Cache().lastAlbumArtProbe = {};
            Cache().lastTrackChange = {};
        }
        std::lock_guard<std::mutex> lock(State().sessionManagerMutex);
        State().sessionManager = nullptr;
    }

    int32_t SendMediaCommandBody(int32_t commandType, int64_t seekMs) {
        if (!EnsureApartment()) return 0;
        try {
            SmtcSession session = nullptr;
            if (GetActiveSession(session) != Lookup::Ok) return 0;
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
            InvalidatePositionCache();
            if (!op) return 0;
            if (op.Status() == AsyncStatus::Completed) {
                return op.GetResults() ? 1 : 0;
            }
            // Fast non-blocking wait (up to 30ms) for immediate feedback without freezing UI
            auto ctx = std::make_shared<AsyncWaitContext>();
            if (ctx->hEvent) {
                op.Completed([ctx](auto const&, AsyncStatus) {
                    if (ctx->hEvent) SetEvent(ctx->hEvent);
                });
                DWORD waitRes = WaitForSingleObject(ctx->hEvent, 30);
                if (waitRes == WAIT_OBJECT_0 && op.Status() == AsyncStatus::Completed) {
                    return op.GetResults() ? 1 : 0;
                }
            }
            // If still in-flight, let it complete in background (do NOT cancel)
            return 1;
        } catch (...) {
            return 0;
        }
    }

    int32_t StartAudioCaptureBody() {
        WaveformState& w = Wave();
        std::lock_guard<std::mutex> lock(w.lifecycleMutex);
        if (w.started) return 1;
        w.stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!w.stopEvent) return 0;
        w.activePid.store(0, std::memory_order_relaxed);
        w.running.store(true, std::memory_order_relaxed);
        try {
            // The thread gets its own copy of the handle; it is closed only after the join.
            w.thread = std::thread(CaptureThreadMain, w.stopEvent);
        } catch (...) {
            w.running.store(false, std::memory_order_relaxed);
            CloseHandle(w.stopEvent);
            w.stopEvent = nullptr;
            return 0;
        }
        w.started = true;
        return 1;
    }

    void StopAudioCaptureBody() {
        WaveformState& w = Wave();
        std::thread worker;
        HANDLE evt = nullptr;
        {
            std::lock_guard<std::mutex> lock(w.lifecycleMutex);
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
        ResetWaveBuffer();
        w.activePid.store(0, std::memory_order_relaxed);
    }

    // Lock-free read of recent waveform points oldest->newest into outBuffer.
    int32_t GetWaveformBody(float* outBuffer, int32_t maxSamples) {
        if (!outBuffer || maxSamples <= 0) return 0;
        WaveformState& w = Wave();
        int count = (std::min)(maxSamples, kWavePoints);
        int h = w.head.load(std::memory_order_acquire);
        int start = (h - count + kWavePoints) & (kWavePoints - 1);
        int firstPart = (std::min)(count, kWavePoints - start);
        std::memcpy(outBuffer, &w.points[start], firstPart * sizeof(float));
        int secondPart = count - firstPart;
        if (secondPart > 0) {
            std::memcpy(outBuffer + firstPart, &w.points[0], secondPart * sizeof(float));
        }
        return count;
    }

    // Re-scans every render endpoint and snapshots the processes that own an audio session.
    // Returns the snapshot size; read it back with GetAudioSource.
    int32_t RefreshAudioSourcesBody() {
        if (!EnsureApartment()) return 0;
        std::vector<AudioSessionCandidate> candidates;
        EnumerateAudioSources(candidates);
        std::string aumid;
        CurrentSourceAumid(aumid);
        const uint32_t currentPid = PickMatchingPid(candidates, aumid);
        std::lock_guard<std::mutex> lock(Sources().mutex);
        Sources().items.swap(candidates);
        Sources().currentPid = currentPid;
        return static_cast<int32_t>(Sources().items.size());
    }

    int32_t GetAudioSourceBody(int32_t index, AudioSource* out) {
        if (!out || index < 0) return 0;
        std::memset(out, 0, sizeof(AudioSource));
        std::lock_guard<std::mutex> lock(Sources().mutex);
        if (static_cast<size_t>(index) >= Sources().items.size()) return 0;
        const AudioSessionCandidate& candidate = Sources().items[static_cast<size_t>(index)];
        out->pid = static_cast<int32_t>(candidate.pid);
        CopyToBuffer(out->name, sizeof(out->name), candidate.exeName);
        out->peak = candidate.peak;
        out->active = candidate.active ? 1 : 0;
        out->current = (candidate.pid == Sources().currentPid) ? 1 : 0;
        return 1;
    }

    // pid == 0 restores auto-follow of the current SMTC session.
    int32_t SetWaveformSourceBody(int32_t pid) {
        if (pid < 0) return 0;
        if (pid != 0 && !ProcessAlive(static_cast<uint32_t>(pid))) return 0;
        WaveformState& w = Wave();
        w.requestedPid.store(static_cast<uint32_t>(pid), std::memory_order_release);
        w.generation.fetch_add(1, std::memory_order_release);
        return 1;
    }
}

// ---------------------------------------------------------------------------
// Exported entry points. No SEH here on purpose: swallowing an access violation would leave
// whatever std::mutex was held locked forever (destructors do not run under /EHsc), turning a
// crash into a silent permanent hang. C++ exceptions are still stopped at the boundary.
// ---------------------------------------------------------------------------

extern "C" {

    __declspec(dllexport) int32_t __stdcall GetCurrentMediaInfo(MediaInfo* info) {
        try { return GetCurrentMediaInfoBody(info); } catch (...) { return -1; }
    }

    __declspec(dllexport) int32_t __stdcall GetCurrentPositionInfo(PositionInfo* info) {
        try { return GetCurrentPositionInfoBody(info); } catch (...) { return -1; }
    }

    __declspec(dllexport) void __stdcall FreeMediaInfo(MediaInfo* info) {
        if (info) {
            if (info->albumArtPng) {
                CoTaskMemFree(info->albumArtPng);
                info->albumArtPng = nullptr;
            }
            info->albumArtSize = 0;
        }
    }

    __declspec(dllexport) int32_t __stdcall RegisterMediaEventCallback(MediaEventCallback callback) {
        try { return RegisterMediaEventCallbackBody(callback); } catch (...) { return 0; }
    }

    __declspec(dllexport) void __stdcall UnregisterMediaEventCallback() {
        try { UnregisterMediaEventCallbackBody(); } catch (...) {}
    }

    __declspec(dllexport) void __stdcall ClearCache() {
        try { ClearCacheBody(); } catch (...) {}
    }

    __declspec(dllexport) int32_t __stdcall SendMediaCommand(int32_t commandType, int64_t seekMs = 0) {
        try { return SendMediaCommandBody(commandType, seekMs); } catch (...) { return 0; }
    }

    __declspec(dllexport) int32_t __stdcall Play() { return SendMediaCommand(0); }
    __declspec(dllexport) int32_t __stdcall Pause() { return SendMediaCommand(1); }
    __declspec(dllexport) int32_t __stdcall TogglePlayPause() { return SendMediaCommand(2); }
    __declspec(dllexport) int32_t __stdcall SkipNext() { return SendMediaCommand(3); }
    __declspec(dllexport) int32_t __stdcall SkipPrevious() { return SendMediaCommand(4); }
    __declspec(dllexport) int32_t __stdcall SeekToMs(int64_t ms) { return SendMediaCommand(5, ms); }

    __declspec(dllexport) int32_t __stdcall StartAudioCapture() {
        try { return StartAudioCaptureBody(); } catch (...) { return 0; }
    }

    __declspec(dllexport) void __stdcall StopAudioCapture() {
        try { StopAudioCaptureBody(); } catch (...) {}
    }

    __declspec(dllexport) int32_t __stdcall GetWaveform(float* outBuffer, int32_t maxSamples) {
        return GetWaveformBody(outBuffer, maxSamples);
    }

    __declspec(dllexport) float __stdcall GetAudioLevel() {
        return Wave().level.load(std::memory_order_relaxed);
    }

    __declspec(dllexport) int32_t __stdcall RefreshAudioSources() {
        try { return RefreshAudioSourcesBody(); } catch (...) { return 0; }
    }

    __declspec(dllexport) int32_t __stdcall GetAudioSource(int32_t index, AudioSource* out) {
        try { return GetAudioSourceBody(index, out); } catch (...) { return 0; }
    }

    __declspec(dllexport) int32_t __stdcall SetWaveformSource(int32_t pid) {
        try { return SetWaveformSourceBody(pid); } catch (...) { return 0; }
    }

    // PID actually being captured right now; 0 means the whole system mix.
    __declspec(dllexport) int32_t __stdcall GetWaveformSourcePid() {
        return static_cast<int32_t>(Wave().activePid.load(std::memory_order_relaxed));
    }

    BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason, LPVOID) {
        if (ul_reason == DLL_PROCESS_ATTACH) {
            DisableThreadLibraryCalls(hModule);
        }
        return TRUE;
    }
}
