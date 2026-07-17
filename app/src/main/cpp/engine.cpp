// engine.cpp — see engine.h.
#include "engine.h"
#include "note.h"

#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <time.h>
#include <sched.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "platform.h"
#if defined(__ANDROID__)
#include <android/native_window.h>
#endif

namespace apfa {

static uint64_t nowUs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull + ts.tv_nsec / 1000;
}

static uint64_t nowThreadCpuUs() {
    struct timespec ts;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull + ts.tv_nsec / 1000;
}

// UI thread: stash the image for the render thread to upload.
void Engine::setBgImage(const uint8_t* rgba, int w, int h) {
    std::lock_guard<std::mutex> lk(bgImgMutex_);
    if (rgba && w > 0 && h > 0) {
        bgImgPixels_.assign(rgba, rgba + static_cast<size_t>(w) * h * 4);
        bgImgW_ = w; bgImgH_ = h;
    } else {
        bgImgPixels_.clear();
        bgImgW_ = bgImgH_ = 0;
    }
    bgImgDirty_ = true;
}

// Render thread: upload the pending image (if any changed) to the GL texture.
void Engine::syncBgImage() {
    std::lock_guard<std::mutex> lk(bgImgMutex_);
    if (!bgImgDirty_) return;
    renderer_->uploadBgImage(bgImgPixels_.empty() ? nullptr : bgImgPixels_.data(),
                            bgImgW_, bgImgH_);
    bgImgDirty_ = false;
    std::vector<uint8_t>().swap(bgImgPixels_);   // free the staging copy
}

#ifdef APFA_STREAMING
// Fraction of total device RAM the predicted in-RAM parse may use before the
// load is routed to the streaming pool instead (Engine::load). ~40% of an
// 8 GB phone ≈ the 3 GB the flushed-cache ceiling actually allows.
static constexpr double kStreamRamFraction = 0.40;

// Load transient of an UN-chunked streaming parse: 16 B/event sort keys plus
// 8 B/note pairs (~4 B/event) held resident until pass D. Against the same
// RAM budget this puts the un-chunked ceiling at ~80 M notes on an 8 GB
// phone (RDR 40M loaded, NoK 90M was lmkd-reaped at 78%) — past it, only the
// chunked on-disk sort ("Chunked Disk Streaming") survives the load.
static constexpr uint64_t kSortTransientPerEvent = 20;

// ---- adaptive-load crash marker (see Engine::load) --------------------------
// Written before a parse attempt, deleted once playback starts or the engine
// tears down cleanly. Its survival across a process death is the evidence
// that THIS MIDI killed the in-RAM parse, flipping it to the streaming pool.
static std::string loadMarkerPath(const std::string& midiPath) {
    std::string dir = midiPath.substr(0, midiPath.find_last_of('/'));
    if (dir.empty()) dir = ".";
    return dir + "/apfa_lastload";
}

// Cheap identity for "same MIDI as last time": size folded with the first and
// last 4 KB (the cache copy is rewritten per load, so mtime is useless).
static uint64_t midiFingerprint(const std::string& midiPath) {
    int fd = open(midiPath.c_str(), O_RDONLY);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) != 0) { ::close(fd); return 0; }
    uint64_t h = static_cast<uint64_t>(st.st_size) * 1099511628211ull;
    uint64_t buf[512];
    ssize_t n = pread(fd, buf, sizeof(buf), 0);
    for (ssize_t i = 0; i * 8 < n; i++) h = (h ^ buf[i]) * 1099511628211ull;
    if (st.st_size > static_cast<off_t>(sizeof(buf))) {
        n = pread(fd, buf, sizeof(buf), st.st_size - sizeof(buf));
        for (ssize_t i = 0; i * 8 < n; i++) h = (h ^ buf[i]) * 1099511628211ull;
    }
    ::close(fd);
    return h ? h : 1;
}

static bool markerMatches(const std::string& path, uint64_t fp) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    uint64_t stored = 0;
    size_t got = fread(&stored, 1, sizeof(stored), f);
    fclose(f);
    return got == sizeof(stored) && stored == fp && fp != 0;
}

static void writeLoadMarker(const std::string& path, uint64_t fp) {
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return;
    fwrite(&fp, 1, sizeof(fp), f);
    fclose(f);
}

void Engine::clearLoadMarker() {
    if (markerArmed_ && !markerPath_.empty()) {
        unlink(markerPath_.c_str());
        markerArmed_ = false;
    }
}
#endif  // APFA_STREAMING

bool Engine::load(const std::string& midiPath, const std::string& soundfontPath,
                  int voiceCount, float noteSpeed, uint64_t cpuMask,
                  bool legacyRenderer, bool allowChunked) {
    sfPath_     = soundfontPath;
    voiceCount_ = voiceCount < 1 ? 1 : voiceCount;
    noteSpeed_  = noteSpeed;
    if (noteSpeed_ < 0.005f) noteSpeed_ = 0.005f;
    if (noteSpeed_ > 1.0f)   noteSpeed_ = 1.0f;
    cpuMask_        = cpuMask;
    legacyRenderer_ = legacyRenderer;
    loadError_      = kLoadErrNone;
#ifdef APFA_STREAMING
    // Adaptive: the pure in-RAM parse is the default — byte-for-byte the
    // legacy build, zero pagefile cost, zero extra storage writes. The
    // streaming pool takes over only when
    //   1. a fast read-only skim predicts the in-RAM footprint would exceed
    //      kStreamRamFraction of the device's total RAM (so a MIDI that would
    //      obviously die never burns a crash — or the eMMC writes of a doomed
    //      attempt — finding out), or
    //   2. a crash marker proves THIS MIDI already killed an in-RAM parse:
    //      the marker is written before parsing and only survives a process
    //      death; it is cleared once playback starts or on clean teardown,
    //      so ordinary backgrounded-app kills never flip the mode.
    // A streaming load then sorts in RAM (the old ceiling) unless the skim
    // predicts even that transient can't fit — the truly giant MIDIs — where
    // the chunked on-disk sort takes over IF the user opted into it
    // ("Chunked Disk Streaming" in Advanced Settings); with the toggle off
    // the load is refused with kLoadNeedsChunked instead of dying to lmkd
    // on every attempt.
    markerPath_ = loadMarkerPath(midiPath);
    uint64_t fp = midiFingerprint(midiPath);
    uint64_t events = Streamer::predictEventCount(midiPath);
    uint64_t totalRam =
        static_cast<uint64_t>(sysconf(_SC_PHYS_PAGES)) *
        static_cast<uint64_t>(sysconf(_SC_PAGESIZE));
    uint64_t budget = static_cast<uint64_t>(totalRam * kStreamRamFraction);
    bool wantStream = false;
    if (markerMatches(markerPath_, fp)) {
        LOGI("previous load of this MIDI died — using the streaming pool");
        wantStream = true;
    } else if (events > 0 && totalRam > 0) {
        uint64_t predictedBytes =
            events * (sizeof(PlayEvent) + sizeof(PlayEvent*));
        if (predictedBytes > budget) {
            LOGI("predicted in-RAM footprint %.1f MB > %.1f MB budget "
                 "(%.0f%% of %.1f GB RAM) — using the streaming pool",
                 predictedBytes / 1048576.0, budget / 1048576.0,
                 kStreamRamFraction * 100.0, totalRam / 1073741824.0);
            wantStream = true;
        }
    }
    bool chunked = false;
    if (wantStream && events > 0 && totalRam > 0 &&
        events * kSortTransientPerEvent > budget) {
        if (!allowChunked) {
            LOGI("predicted sort transient %.1f MB > %.1f MB budget and "
                 "Chunked Disk Streaming is off — refusing the load",
                 events * kSortTransientPerEvent / 1048576.0,
                 budget / 1048576.0);
            loadError_ = kLoadNeedsChunked;
            return false;
        }
        chunked = true;
    }
    writeLoadMarker(markerPath_, fp);
    markerArmed_ = true;
    if (wantStream) {
        if (!streamer_.open(midiPath, midi_, loadProgress_, chunked)) {
            if (streamer_.diskFull()) {
                LOGI("streaming pool aborted: not enough free storage");
                loadError_ = kLoadDiskFull;
                return false;
            }
            LOGI("streamer unavailable — falling back to in-RAM parse");
            midi_ = parseMidi(midiPath, loadProgress_);
        }
    } else {
        midi_ = parseMidi(midiPath, loadProgress_);   // may die: marker persists
    }
#else
    (void)allowChunked;
    midi_ = parseMidi(midiPath, loadProgress_);
#endif

    // Time of the first note-on, for PFA's 3-second pre-roll (events[] is sorted
    // by time, so the earliest note-on is the first one we hit). Mirrors PFA's
    // MIDI::GetInfo().llFirstNote.
    firstNoteUs_ = 0;
    for (const PlayEvent* e : midi_.events) {
        if (e->isNoteOn()) { firstNoteUs_ = e->absMicroSec; break; }
    }
#ifdef APFA_STREAMING
    if (!midi_.valid) clearLoadMarker();   // clean failure, not an OOM death
#endif
    return midi_.valid;
}

void Engine::start(void* surface) {
    if (running_.load()) return;
    window_  = surface;   // Android: ANativeWindow*  iOS: CAEAGLLayer*
    running_ = true;
    thread_  = std::thread(&Engine::threadMain, this);
}

void Engine::stop() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
#ifdef APFA_STREAMING
    clearLoadMarker();   // reached a clean stop: whatever happened, no OOM
#endif
    // Remember where playback was so a later start() (surface re-attach) resumes
    // here rather than jumping back to the pre-roll. Only meaningful if the thread
    // actually ran; harmless otherwise (engine is about to be deleted).
    resumeUs_  = clockUs_;
    hasResume_ = true;
}

void Engine::surfaceChanged(int w, int h) {
    surfW_ = w;
    surfH_ = h;
}

#if defined(__ANDROID__)
static int chooseBigCore() {
    int  ncpu = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    int  best = -1;
    long bestFreq = -1;
    for (int c = 0; c < ncpu; c++) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
        FILE* f = fopen(path, "r");
        if (!f) continue;
        long freq = 0;
        if (fscanf(f, "%ld", &freq) == 1 && freq > bestFreq) {
            bestFreq = freq;
            best = c;
        }
        fclose(f);
    }
    return best;
}

static bool setThreadAffinityMask(uint64_t mask) {
    if (mask == 0) return false;
    int ncpu = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    cpu_set_t set;
    CPU_ZERO(&set);
    bool any = false;
    for (int c = 0; c < ncpu && c < 64; c++) {
        if (((mask >> c) & 1ULL) != 0ULL) { CPU_SET(c, &set); any = true; }
    }
    if (!any) return false;
    // Direct syscall, not the libc wrapper: bionic only exports
    // sched_setaffinity from API 12, and the 2.3 loader rejects the whole
    // library over the unresolved symbol before any of it runs.
    syscall(__NR_sched_setaffinity, 0, sizeof(set), &set);
    return true;
}
#endif  // __ANDROID__

void Engine::threadMain() {
#if defined(__ANDROID__)
    // Pin strategy: identical to before, but now there is no render thread to
    // spawn in the "avoid" window. We still set avoid first so BASS's render
    // thread (spawned inside synth_.init) inherits it, then pin to the engine
    // core(s) once BASS is up.
    int ncpu = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    uint64_t ncpuMask  = (ncpu >= 64) ? ~0ULL : ((1ULL << ncpu) - 1ULL);
    uint64_t engineMask = cpuMask_ & ncpuMask;
    if (engineMask == 0) {
        int bigCore = chooseBigCore();
        if (bigCore >= 0) engineMask = 1ULL << bigCore;
    }
    uint64_t avoidMask = (~engineMask) & ncpuMask;
    bool engineAvoidApplied = setThreadAffinityMask(avoidMask);
#endif  // __ANDROID__

    if (!synth_.init(voiceCount_)) {
        LOGE("synth init failed — aborting engine");
        startError_ = kStartErrSynth;
        running_ = false;
        return;
    }
    if (!sfPath_.empty())
        synth_.loadSoundfont(sfPath_);

    // Pick the renderer: ES3 by default, or the ES2 "Legacy Renderer" when the
    // user ticked the toggle (iOS always gets the ES2/universal renderer).
    renderer_ = createRenderer(legacyRenderer_);

    // Init EGL on this thread — the engine thread owns the GL context, exactly
    // as PFA's GameThread owns the D3D device. eglSwapBuffers stalls enter the
    // clock the same way D3D Present() does in PFA.
    if (!renderer_->initEGL(window_)) {
        LOGE("renderer init failed — aborting engine");
        startError_ = kStartErrRenderer;
        running_ = false;
        return;
    }
    renderer_->setNoteRange(midi_.minNote, midi_.maxNote);  // PFA-faithful key layout
    renderer_->setSwapInterval(true);   // vsync on — paces to vblank like PFA
    renderer_->setBgColor(bgColor_.load());

    synth_.start();

#if defined(__ANDROID__)
    if (engineMask != 0 && setThreadAffinityMask(engineMask)) {
        // Remember the mask: Samsung's cpuset/GOS management silently rewrites
        // thread affinity mid-session (observed on One UI: a 0x80 pin comes
        // back as 0-7 and EAS drops the engine onto a mid core). The 500 ms
        // metrics tick in frame() re-asserts this, so the pin the design
        // mandates actually holds for the whole run.
        pinnedMask_ = engineMask;
        LOGI("engine pinned to cpu mask 0x%llx (%s)",
             static_cast<unsigned long long>(engineMask),
             cpuMask_ ? "user" : "auto");
    } else if (!engineAvoidApplied) {
        LOGE("could not read core frequencies — engine left unpinned");
    }
#elif defined(__APPLE__)
    // No per-core affinity on iOS. Bias this engine thread onto the performance
    // cluster via QoS — the strongest placement control the OS allows. BASS's
    // audio render runs on CoreAudio's own real-time I/O thread, so there is no
    // render thread here to "avoid" the way the Android pin strategy does.
    apfa::platform::setEngineThreadPolicy();
    LOGI("engine thread QoS = USER_INTERACTIVE (performance-cluster bias)");
#endif

    // PFA's 3-second pre-roll: the clock starts 3s before the first note and
    // counts up through "-0:03.0" to 0 before any note fires (GameState.cpp:155,
    // m_llStartTime = llFirstNote - 3000000).
    clockUs_     = firstNoteUs_ - 3000000;
    eventCursor_ = windowCursor_ = pcCursor_ = 0;
#ifdef APFA_STREAMING
    // Read-ahead thread: warms pool pages ahead of the published clock, kept
    // off the engine core like the BASS render threads. The 3-second pre-roll
    // doubles as the initial warm-up headroom.
    if (streamer_.isOpen()) {
#if defined(__ANDROID__)
        uint64_t loaderAvoid = engineMask;
#else
        uint64_t loaderAvoid = 0;
#endif
        streamer_.startLoader(&pubTimeUs_, clockUs_, loaderAvoid);
    }
#endif
    active_.clear();
    active_.reserve(16384);
    for (int& s : noteState_) s = -1;
    lastWall_ = fpsLastUs_ = nowUs();
    cpuLastUs_ = nowThreadCpuUs();
    playing_ = true;
#ifdef APFA_STREAMING
    clearLoadMarker();   // load + startup survived: this MIDI fits this mode
#endif
    LOGI("engine playing: %zu notes", midi_.noteCount());

    // Re-attaching to a fresh surface (returned from recents): pick up where we
    // left off instead of replaying the pre-roll. applySeek rebuilds the visible
    // notes and restores synth instrument/CC state at that position.
    if (hasResume_) {
        applySeek(resumeUs_);
        lastWall_ = nowUs();
        hasResume_ = false;
    }

    bool synthPlaying = true;
    while (running_.load()) {
        int64_t seekTo = seekRequest_.exchange(INT64_MIN);
        if (seekTo != INT64_MIN) {
            applySeek(seekTo);
            lastWall_ = nowUs();
        }

        if (paused_.load()) {
            if (synthPlaying) { synth_.pause(); synthPlaying = false; }
            // Still render the paused frame (seek may have moved the view),
            // then sleep — mirroring PFA's paused Logic()/Render() path.
            buildVisible();
            int sw = surfW_.load(), sh = surfH_.load();
            if (sw > 0 && sh > 0) renderer_->resize(sw, sh);
            renderer_->setBgColor(bgColor_.load());
            syncBgImage();
            renderer_->render(clockUs_ * 1e-6f, midi_.totalUs * 1e-6f, pubFps_.load(),
                             3.0f * noteSpeed_, instances_, keyColor_);
            usleep(10000);   // 10 ms idle — matches PFA's paused Sleep(10)
            lastWall_ = nowUs();
            continue;
        }
        if (!synthPlaying) { synth_.resume(); synthPlaying = true; }
        frame();
    }

#ifdef APFA_STREAMING
    streamer_.stopLoader();   // the mapping itself stays for a surface re-attach
#endif
    renderer_->destroyEGL();
    synth_.shutdown();
    playing_ = false;
}

void Engine::frame() {
    // --- one-frame-delayed clock ---
    // Advance by the previous frame's wall time, which now includes
    // eglSwapBuffers — exactly as PFA's clock advances by the time including
    // D3D Present(). GPU cost on slow hardware stretches the frame faithfully.
    uint64_t now     = nowUs();
    uint64_t elapsed = now - lastWall_;
    lastWall_ = now;
    if (eventCursor_ < midi_.events.size()) clockUs_ += static_cast<int64_t>(elapsed);

    uint64_t tDisp = nowUs();
    dispatch();
    advancePcCursor();
    synth_.flush();   // one raw BASS_MIDI_StreamEvents call for the whole frame
    uint64_t tBuild = nowUs();
    buildVisible();
    uint64_t tEnd = nowUs();

    uint64_t dUs = tBuild - tDisp, bUs = tEnd - tBuild;
    sumDispatchUs_ += dUs;  if (dUs > maxDispatchUs_) maxDispatchUs_ = dUs;
    sumBuildUs_    += bUs;  if (bUs > maxBuildUs_)    maxBuildUs_    = bUs;

    // Render + present — on the engine thread, so the swap stall enters the
    // next frame's clock advance (one-frame-delayed, same as PFA).
    int sw = surfW_.load(), sh = surfH_.load();
    if (sw > 0 && sh > 0) renderer_->resize(sw, sh);
    renderer_->setBgColor(bgColor_.load());
    syncBgImage();
    renderer_->render(clockUs_ * 1e-6f, midi_.totalUs * 1e-6f,
                     pubFps_.load(),
                     3.0f * noteSpeed_, instances_, keyColor_);
    // eglSwapBuffers is called inside renderer_->render(); it stalls here until
    // vblank. That stall is part of lastWall_ -> now on the next frame.

    // --- metrics ---
    pubTimeUs_.store(static_cast<int64_t>(clockUs_));
    fpsFrames_++;
    if (now - fpsLastUs_ >= 500000) {
#if defined(__ANDROID__)
        // Re-assert the engine pin (see threadMain): One UI strips per-thread
        // affinity mid-session. One syscall per 500 ms — not the hot path.
        if (pinnedMask_ != 0) setThreadAffinityMask(pinnedMask_);
#endif
        uint64_t wallNow  = nowUs();
        uint64_t cpuNow   = nowThreadCpuUs();
        uint64_t window   = wallNow - fpsLastUs_;
        uint64_t cpuDelta = cpuNow  - cpuLastUs_;
        fpsLastUs_ = wallNow;
        cpuLastUs_ = cpuNow;
        float fps = fpsFrames_ * 1e6f / static_cast<float>(window);
        pubFps_.store(fps);
        int frames = fpsFrames_;
        fpsFrames_ = 0;
        uint64_t sc = 0, su = 0, bp = 0;
        synth_.sampleEventCost(sc, su, bp);
        LOGI("perf: %.0f fps | engine %.0f%% cpu | synth %llu calls %.1f ms total (%.1f%% wall)",
             pubFps_.load(), cpuDelta * 100.0 / static_cast<double>(window),
             static_cast<unsigned long long>(sc), su / 1000.0,
             su * 100.0 / static_cast<double>(window));
        double inv = frames > 0 ? 1.0 / frames : 0.0;
        LOGI("frame: dispatch %.1f/%.1f | build %.1f/%.1f ms avg/max",
             sumDispatchUs_ * inv / 1000.0, maxDispatchUs_ / 1000.0,
             sumBuildUs_    * inv / 1000.0, maxBuildUs_    / 1000.0);
        sumDispatchUs_ = sumBuildUs_ = 0;
        maxDispatchUs_ = maxBuildUs_ = 0;
    }
}

void Engine::dispatch() {
    const std::vector<PlayEvent*>& ev = midi_.events;
    const size_t n = ev.size();
    while (eventCursor_ < n &&
           ev[eventCursor_]->absMicroSec <= static_cast<int64_t>(clockUs_)) {
        PlayEvent* e = ev[eventCursor_];
        int ch  = e->channel;
        int key = e->param1;
        if (e->isNonNote()) {
            synth_.sendRaw(static_cast<uint8_t>(e->eventCode), e->param1, e->param2);
        } else if (e->isNoteOn()) {
            synth_.noteOn(ch, key, e->param2);
            active_.push_back(static_cast<int>(eventCursor_));
            noteState_[key] = static_cast<int>(eventCursor_);
        } else {
            synth_.noteOff(ch, key);
            noteState_[key] = -1;
            const PlayEvent* sister = e->sister;
            size_t i = 0;
            while (i < active_.size()) {
                PlayEvent* a = ev[active_[i]];
                if (a == sister) {
                    active_.erase(active_.begin() + static_cast<long>(i));
                } else {
                    if (a->param1 == key) noteState_[key] = active_[i];
                    i++;
                }
            }
        }
        eventCursor_++;
    }
}

void Engine::buildVisible() {
    const std::vector<PlayEvent*>& ev     = midi_.events;
    const std::vector<uint32_t>&   colors = midi_.trackColors;
    const size_t n = ev.size();

    int64_t windowUs  = static_cast<int64_t>(3000000.0 * noteSpeed_);
    int64_t windowEnd = static_cast<int64_t>(clockUs_) + windowUs;

    if (windowCursor_ < eventCursor_) windowCursor_ = eventCursor_;
    while (windowCursor_ < n && ev[windowCursor_]->absMicroSec < windowEnd)
        windowCursor_++;

    // Derive PFA's three colour levels from the primary packed colour.
    // SetColor(color, dDark=0.6, dVeryDark=0.2) in PFA — same HSV, scaled V.
    auto deriveColors = [](uint32_t primary, uint32_t& dark, uint32_t& veryDark) {
        float r = ((primary >>  0) & 0xFF) / 255.0f;
        float g = ((primary >>  8) & 0xFF) / 255.0f;
        float b = ((primary >> 16) & 0xFF) / 255.0f;
        float vmax = r > g ? (r > b ? r : b) : (g > b ? g : b);
        float vmin = r < g ? (r < b ? r : b) : (g < b ? g : b);
        float v = vmax, s = (vmax > 0.0f ? (vmax - vmin) / vmax : 0.0f);
        float h = 0.0f;
        if (vmax != vmin) {
            float d = vmax - vmin;
            if      (vmax == r) h = (g - b) / d + (g < b ? 6.0f : 0.0f);
            else if (vmax == g) h = (b - r) / d + 2.0f;
            else                h = (r - g) / d + 4.0f;
            h /= 6.0f;
        }
        dark     = packHSV(h, s, v * 0.6f);
        veryDark = packHSV(h, s, v * 0.2f);
    };

    auto colorOf = [&](const PlayEvent& e) -> uint32_t {
        size_t idx = static_cast<size_t>(e.track) * 16 + e.channel;
        return idx < colors.size() ? colors[idx] : 0xFFFFFFFFu;
    };

    // Sharp key detection: C#,D#,F#,G#,A# — pitch class 1,3,6,8,10
    auto isSharpKey = [](int key) -> bool {
        int pc = key % 12;
        return pc==1||pc==3||pc==6||pc==8||pc==10;
    };

    auto toInstance = [&](const PlayEvent& e) -> NoteInstance {
        NoteInstance ni;
        ni.startSec = e.absMicroSec * 1e-6f;
        int64_t endUs = e.sister ? e.sister->absMicroSec : e.absMicroSec;
        ni.durSec = static_cast<float>(endUs - e.absMicroSec) * 1e-6f;
        ni.key    = static_cast<float>(e.param1);
        ni.colorPrimary = colorOf(e);
        deriveColors(ni.colorPrimary, ni.colorDark, ni.colorVeryDark);
        ni.isSharp = isSharpKey(e.param1) ? 1u : 0u;
        return ni;
    };

    instances_.clear();
    memset(keyColor_, 0, sizeof(keyColor_));

    for (int idx : active_)
        instances_.push_back(toInstance(*ev[idx]));
    for (int k = 0; k < 128; k++)
        if (noteState_[k] >= 0)
            keyColor_[k] = colorOf(*ev[noteState_[k]]);
    for (size_t j = eventCursor_; j < windowCursor_; j++) {
        const PlayEvent* e = ev[j];
        if (e->isNoteOn()) instances_.push_back(toInstance(*e));
    }
}

void Engine::seek(int64_t micros) {
    // Don't clamp here — applySeek does it against the live bounds (matching
    // PFA's JumpTo). Negative targets are valid: they land in the pre-roll.
    seekRequest_.store(micros);
}

void Engine::applySeek(int64_t target) {
    // Clamp to [minTimeUs, maxTimeUs], exactly as PFA's JumpTo:
    // m_llStartTime = min(max(llStartTime, llFirstTime), llLastTime)
    // (GameState.cpp:1454). The lower bound is the -3s pre-roll, so scrubbing
    // to the start shows "-0:03" instead of snapping to 0:00.
    if (target < minTimeUs()) target = minTimeUs();
    if (target > maxTimeUs()) target = maxTimeUs();
    const std::vector<PlayEvent*>& ev = midi_.events;
    const size_t n = ev.size();

    clockUs_ = target;

    // Binary search the first event after the target. With a pre-roll (negative)
    // target this converges to 0, so no events have fired yet — correct.
    size_t lo = 0, hi = n;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        if (static_cast<int64_t>(ev[mid]->absMicroSec) <= target) lo = mid + 1;
        else hi = mid;
    }
    eventCursor_  = lo;
    windowCursor_ = lo;

    active_.clear();
    for (int& s : noteState_) s = -1;
#ifdef APFA_STREAMING
    if (streamer_.isOpen()) {
        // Identical result to the loop below, computed from the resident
        // sister-position table instead of dereferencing every historical
        // event (which would fault the whole cold pool in random order).
        // A note-on at position j is still sounding iff its note-off sits at
        // position >= lo: events[] is time-sorted, so "off position >= lo"
        // and "off time > target" are the same predicate.
        for (size_t j = 0; j < eventCursor_; j++) {
            uint32_t s = streamer_.sisterPosAt(j);
            if (s < Streamer::kSisNoteOff && static_cast<size_t>(s) >= eventCursor_)
                active_.push_back(static_cast<int>(j));
        }
        // Warm what the next frame reads (visible band, the active notes and
        // their offs) before the param1 dereferences below.
        streamer_.warmSeek(target,
                           target + static_cast<int64_t>(3000000.0 * noteSpeed_),
                           active_);
        for (int j : active_)
            noteState_[ev[j]->param1] = j;
    } else
#endif
    for (size_t j = 0; j < eventCursor_; j++) {
        const PlayEvent* e = ev[j];
        if (e->isNoteOn() && e->sister &&
            static_cast<int64_t>(e->sister->absMicroSec) > target) {
            active_.push_back(static_cast<int>(j));
            noteState_[e->param1] = static_cast<int>(j);
        }
    }

    synth_.allNotesOff();
    
    size_t oldPcCursor = pcCursor_;
    advancePcCursor();
    playSkippedEvents(oldPcCursor);
    
    pubTimeUs_.store(static_cast<int64_t>(clockUs_));
    LOGI("seek -> %.1f s, %zu active", target * 1e-6, active_.size());
}

void Engine::advancePcCursor() {
    const std::vector<size_t>& pcIdx = midi_.programChangeIdx;
    // Signed compare: clockUs_ is negative during the pre-roll, so an unsigned
    // cast here would wrap and wrongly advance past every program change.
    while (pcCursor_ < pcIdx.size() &&
           midi_.events[pcIdx[pcCursor_]]->absMicroSec <= clockUs_) {
        pcCursor_++;
    }
}

void Engine::playSkippedEvents(size_t oldPcCursor) {
    if (oldPcCursor == pcCursor_) return;

    bool aControl[16][128] = {false};
    bool aProgram[16] = {false};
    bool aPitch[16] = {false};
    std::vector<const PlayEvent*> vControl;

    size_t endIdx = 0;
    if (oldPcCursor < pcCursor_) endIdx = oldPcCursor;

    for (size_t i = pcCursor_; i > endIdx; i--) {
        const PlayEvent* e = midi_.events[midi_.programChangeIdx[i - 1]];
        if (e->isController() && !aControl[e->channel][e->param1]) {
            aControl[e->channel][e->param1] = true;
            vControl.push_back(e);
        } else if (e->isProgramChange() && !aProgram[e->channel]) {
            aProgram[e->channel] = true;
            synth_.sendRaw(static_cast<uint8_t>(e->eventCode), e->param1, e->param2);
        } else if (e->isPitchBend() && !aPitch[e->channel]) {
            aPitch[e->channel] = true;
            synth_.sendRaw(static_cast<uint8_t>(e->eventCode), e->param1, e->param2);
        }
    }

    // Play controller events in chronological order
    for (auto it = vControl.rbegin(); it != vControl.rend(); ++it) {
        const PlayEvent* e = *it;
        synth_.sendRaw(static_cast<uint8_t>(e->eventCode), e->param1, e->param2);
    }
}

}  // namespace apfa
