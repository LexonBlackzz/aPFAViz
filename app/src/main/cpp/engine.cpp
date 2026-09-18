// engine.cpp — see engine.h.
#include "engine.h"
#include "note.h"

#include <cstdio>
#include <cstring>
#include <new>
#include <fcntl.h>
#include <time.h>
#include <sched.h>
#include <sys/resource.h>
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

// Major faults taken by the CALLING thread — RUSAGE_THREAD, not RUSAGE_SELF,
// because the whole point is to tell an engine-thread stall apart from the
// loader thread's read-ahead. Sampled once per 500 ms tick, never in the hot
// path. RUSAGE_THREAD is a plain syscall wrapper with no API-level gate, so it
// is safe at the minSdk 10 floor (it needs kernel 2.6.26; Gingerbread is 2.6.35).
static uint64_t threadMajFlt() {
    struct rusage ru;
    if (getrusage(RUSAGE_THREAD, &ru) != 0) return 0;
    return static_cast<uint64_t>(ru.ru_majflt);
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
// Fraction of total device RAM allowed for the predicted permanent in-RAM
// event representation. A second fixed soft cap matters on high-RAM phones:
// "40% of 8 GB" is technically available, but letting a Black MIDI parser peak
// near a gigabyte is still unfriendly to Android and especially bad precedent
// for older devices.
static constexpr double   kStreamRamFraction      = 0.40;
static constexpr uint64_t kInRamResidentSoftCap  = 256ull << 20;

// Automatic streaming-sort spill threshold. SortKey is 12 B/event and Pair is
// 8 B/note (~4 B/event for note-heavy files), so ~16 B/event is a good upper
// estimate of the purely temporary resident sort/pair payload. Above 192 MB,
// spill it even if the manual Chunked Disk Streaming switch is off.
static constexpr uint64_t kSortScratchPerEvent    = 16;
static constexpr uint64_t kAutoChunkScratchCap    = 192ull << 20;

// Peak non-pool cost of an UN-chunked streaming parse, per event. This includes
// permanent tables that overlap the sort plus the transient sort/pair payload.
static constexpr uint64_t kSortTransientPerEvent =
      12                       // compact SortKey
    + 4                        // Pair average (~one 8 B pair per two events)
    + sizeof(PlayEvent*)       // MidiData::events
    + 4                        // Streamer::sisterPos_
    + 4;                       // inv[], pass-D inverse map

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
                  bool legacyRenderer, bool allowChunked,
                  const std::string& poolDir) {
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
    // A streaming load automatically spills the sort once its temporary key/pair
    // payload crosses a conservative cap or the predicted working set exceeds
    // the device budget. The Advanced Settings switch can still force chunked
    // sorting for smaller streaming loads.
    markerPath_ = loadMarkerPath(midiPath);
    uint64_t fp = midiFingerprint(midiPath);
    uint64_t events = Streamer::predictEventCount(midiPath);
    uint64_t totalRam =
        static_cast<uint64_t>(sysconf(_SC_PHYS_PAGES)) *
        static_cast<uint64_t>(sysconf(_SC_PAGESIZE));
    uint64_t budget = static_cast<uint64_t>(totalRam * kStreamRamFraction);
    bool wantStream = false;
    // Whether the size prediction (not just the crash marker) said the in-RAM
    // parse cannot fit. If it did, "fall back to the in-RAM parse" is not a
    // fallback — it is the thing we already know will be OOM-killed.
    bool predictedTooBig = false;
    if (markerMatches(markerPath_, fp)) {
        LOGI("previous load of this MIDI died — using the streaming pool");
        wantStream = true;
    } else if (events > 0 && totalRam > 0) {
        const uint64_t predictedBytes =
            events * (sizeof(PlayEvent) + sizeof(PlayEvent*));
        const uint64_t inRamLimit = std::min<uint64_t>(budget, kInRamResidentSoftCap);
        predictedTooBig = predictedBytes > inRamLimit;
        if (predictedTooBig) {
            LOGI("predicted in-RAM resident core %.1f MB > %.1f MB soft limit "
                 "(device budget %.1f MB) — using the streaming pool",
                 predictedBytes / 1048576.0, inRamLimit / 1048576.0,
                 budget / 1048576.0);
            wantStream = true;
        }
    }
    bool chunked = false;
    // Whether a streaming load needs the on-disk sort. A lambda because the
    // in-RAM parse can hand the load back to the streaming pool (see below),
    // and that path has to make this decision too — it was skipped the first
    // time round, when streaming was not yet on the table.
    auto decideChunkedSort = [&]() -> bool {
        chunked = false;
        if (events == 0) return true;

        const uint64_t scratchBytes = events * kSortScratchPerEvent;
        const uint64_t workingBytes = events * kSortTransientPerEvent;
        if (allowChunked ||
            scratchBytes > kAutoChunkScratchCap ||
            (totalRam > 0 && workingBytes > budget)) {
            chunked = true;
            LOGI("stream sort: chunked (%s), scratch %.1f MB, working %.1f MB, "
                 "budget %.1f MB",
                 allowChunked ? "manual" :
                 (scratchBytes > kAutoChunkScratchCap ? "automatic scratch cap" :
                                                       "automatic RAM budget"),
                 scratchBytes / 1048576.0,
                 workingBytes / 1048576.0,
                 budget / 1048576.0);
        }
        return true;
    };
    if (wantStream && !decideChunkedSort()) return false;
    writeLoadMarker(markerPath_, fp);
    markerArmed_ = true;
    // At most two passes: the in-RAM parse may hand the load to the streaming
    // pool, never the reverse twice (predictedTooBig is latched when it does).
    for (int attempt = 0; attempt < 2; attempt++) {
    if (wantStream) {
        // Size the read-ahead window off what buildVisible actually reads
        // (3.0s * noteSpeed_, matching the windowUs there) rather than a fixed
        // horizon — see the note at the top of streamer.h.
        streamer_.setVisibleUs(static_cast<int64_t>(3000000.0 * noteSpeed_));
        // A load that runs out of memory must fail, not abort. The pool's
        // tables are the largest allocations the app ever makes, and on a
        // 32-bit process an operator new that cannot be satisfied throws
        // std::bad_alloc — uncaught, that is SIGABRT and a dead app, which is
        // exactly how a 14.65M-note MIDI died on an LG X410. Everything past
        // this point is already written to refuse cleanly; this just makes the
        // allocator's failure take the same route.
        bool opened = false;
        try {
            opened = streamer_.open(midiPath, midi_, loadProgress_, chunked, poolDir);
        } catch (const std::bad_alloc&) {
            LOGE("streaming pool aborted: out of memory during the parse");
            streamer_.close();
            loadError_ = kLoadNoAddrSpace;
            return false;
        }
        if (!opened) {
            if (streamer_.diskFull()) {
                LOGI("streaming pool aborted: not enough free storage");
                loadError_ = kLoadDiskFull;
                return false;
            }
            if (streamer_.fileTooBig()) {
                LOGI("streaming pool aborted: pagefile past the volume's file limit");
                loadError_ = kLoadFileTooBig;
                return false;
            }
            if (streamer_.noAddrSpace()) {
                // The pool itself fits but the un-chunked sort table does not,
                // and the chunked on-disk sort would. That is a mode problem,
                // not an impossible load: take the other mode if the user has
                // allowed it, and otherwise say which switch to flip rather
                // than telling them to go find a 64-bit phone.
                if (streamer_.needsChunked() && !chunked) {
                    if (!allowChunked) {
                        LOGI("needs the chunked on-disk sort to fit the address "
                             "space, and Chunked Disk Streaming is off");
                        loadError_ = kLoadNeedsChunked;
                        return false;
                    }
                    LOGI("retrying with the chunked on-disk sort — it needs "
                         "less address space than the in-RAM sort");
                    chunked = true;
                    try {
                        opened = streamer_.open(midiPath, midi_, loadProgress_,
                                                true, poolDir);
                    } catch (const std::bad_alloc&) {
                        LOGE("chunked retry aborted: out of memory");
                        streamer_.close();
                        loadError_ = kLoadNoAddrSpace;
                        return false;
                    }
                }
                if (!opened) {
                    // Genuinely out of address space in either mode. Refuse
                    // cleanly rather than falling back to the in-RAM parse,
                    // which needs even more than the pool does: that would be
                    // an OOM kill with extra steps, and because the crash
                    // marker only clears on a clean teardown, a killed process
                    // leaves it armed and the SAME MIDI dies again on every
                    // future attempt. The engine's destructor clears it here.
                    LOGI("streaming pool aborted: not enough address space");
                    loadError_ = kLoadNoAddrSpace;
                    return false;
                }
            }
            if (!opened) {
                if (predictedTooBig) {
                    LOGI("streaming pool unavailable and the in-RAM parse was "
                         "predicted not to fit — refusing the load");
                    loadError_ = kLoadTooBigForRam;
                    return false;
                }
                LOGI("streamer unavailable — falling back to in-RAM parse");
                midi_ = parseMidi(midiPath, loadProgress_, events);
            }
        }
    } else {
        // The in-RAM parse is allowed to fail, not to abort — and its failure is
        // NOT the end of the load. This pool is the largest single allocation
        // the app makes, and on a 32-bit process an operator new that cannot be
        // satisfied throws std::bad_alloc. The streaming pool routinely carries
        // four times the events that just failed here, so hand the load over
        // instead of refusing. Refusing is how a 7.08 M-event MIDI reported
        // "too big for this phone's RAM" on a phone that plays a 29.3 M-event
        // one — and because a clean refusal clears the crash marker on
        // teardown, every retry repeated the same failure instead of escalating.
        try {
            midi_ = parseMidi(midiPath, loadProgress_, events);  // may die: marker persists
        } catch (const std::bad_alloc&) {
            LOGI("in-RAM parse ran out of memory — handing the load to the "
                 "streaming pool");
            midi_ = MidiData{};             // drop whatever survived the throw
            if (!decideChunkedSort()) return false;
            wantStream     = true;
            predictedTooBig = true;         // never bounce back to this parse
            continue;
        }
    }
    break;
    }
#else
    (void)allowChunked;
    (void)poolDir;
    midi_ = parseMidi(midiPath, loadProgress_);
#endif

    // Time of the first note-on, for PFA's 3-second pre-roll (events[] is sorted
    // by time, so the earliest note-on is the first one we hit). Mirrors PFA's
    // MIDI::GetInfo().llFirstNote.
    firstNoteUs_ = 0;
#ifdef APFA_STREAMING
    if (streamer_.isSliced()) {
        firstNoteUs_ = streamer_.firstNoteUs();   // events[] is not walkable yet
    } else
#endif
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
// Highest cpuinfo_max_freq wins; among cores TIED at that frequency, the
// highest-numbered one.
//
// The tie-break is the whole point. On a homogeneous SoC — an SD425's four
// identical A53s, and every budget chip without a big cluster — all cores
// report the same max frequency, so a strict > keeps the first and pins the
// engine to cpu0. That is precisely the core to avoid: Linux concentrates IRQ
// and softirq handling on cpu0, and every one of those preempts the
// single-threaded dispatch loop that IS the bottleneck. Measured on an LG X410
// (SD425): cpu0 carried 1,068,688 IRQs and 782,570 softirqs against cpu3's
// 275,517 and 113,209 — 4x and 7x. Pinning to cpu3 was visibly faster on the
// same MIDI.
//
// Heterogeneous SoCs are unaffected: a prime core that is strictly faster still
// wins outright, and where several identical big cores tie, the higher-numbered
// one is no worse — same core, same cache class ([[phone-cache-class]]), just
// less kernel noise. The pin itself is unchanged and still PFA-faithful; only
// the choice of which core to take is fixed.
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
        if (fscanf(f, "%ld", &freq) == 1 && freq >= bestFreq) {
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