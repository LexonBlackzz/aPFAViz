// streamer.h — file-backed event pool: identical layout, kernel-managed residency.
//
// The 72-byte PlayEvent pool is aPFA's memory floor: Tau-class MIDIs (6.28M
// notes) commit ~1 GB of pool + pointer table, all of it load-bearing for the
// crash behaviour (note.h). This streamer keeps THE SAME pool — same struct,
// same parse order, same virtual addresses relative to the pool base, same
// sister pointers, same time-sorted events[] walk — but backs it with a
// read-only file mapping instead of anonymous RAM:
//
//   1. The parse writes the pool to a temp file in PARSE order (track by
//      track), with absMicroSec already in µs and `sister` holding the real
//      pointer values, precomputed against a VA region reserved up front.
//   2. The file is mapped PROT_READ | MAP_FIXED over that reservation and
//      unlinked. events[] is a normal resident vector of pointers into the
//      mapping, sorted exactly as midi_parser.cpp sorts it.
//   3. A loader thread (kept off the engine core, like the BASS render
//      threads) walks ahead of the playhead touching each track's upcoming
//      byte range, so pages are warm before dispatch/buildVisible reads them,
//      and MADV_DONTNEEDs ranges the playhead has left behind.
//
// The engine's hot path is untouched — dispatch(), buildVisible(), and frame()
// compile to the same code walking the same addresses; the scatter, the cache
// working set, and the TLB span are the baseline's by construction. What
// changes is only WHO holds the cold majority of the pool: the page cache
// (evictable, disk-backed) instead of the process heap. If the loader ever
// falls behind, a page fault stretches the faulting frame — pressure enters
// the clock the same way every other stall does; audio never skips.
//
// Residency ~= events[] table + sisterPos + the sliding window
// [playhead - back, playhead + front] (defaults below, env-tunable via
// APFA_WIN_FRONT_US / APFA_WIN_BACK_US). The pool never needs to be resident
// at once, which is the entire point.
//
// Everything here is behind the APFA_STREAMING compile flag (default OFF);
// the legacy path is byte-identical when the flag is off. Android-only wiring
// — iOS builds never define the flag (engine signatures unchanged).
#pragma once
#ifdef APFA_STREAMING

#include "midi_parser.h"

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace apfa {

class Streamer {
public:
    // sisterPos values for non-note events and note-offs. Everything below
    // kSisNoteOff is a note-on whose value is its note-off's events[] position.
    static constexpr uint32_t kSisNonNote = 0xFFFFFFFFu;
    static constexpr uint32_t kSisNoteOff = 0xFFFFFFFEu;

    ~Streamer() { close(); }

    // Parse `midiPath` and fill `out` with exactly what parseMidi() would have
    // produced — events[], trackColors, programChangeIdx, totalUs, note range,
    // note count, valid — except out.eventPool stays empty: the pool events
    // live in the read-only file mapping instead. The temp pool file is
    // created next to the MIDI (the app cache dir — PlaybackActivity copies
    // the MIDI there) and unlinked once mapped.
    //
    // `chunked` selects the sort strategy for pass B/D ("Chunked Disk
    // Streaming" in Advanced Settings, chosen by Engine::load only when
    // needed): false = the whole (key, idx) table and note-pair list stay in
    // RAM until pass D — a 16 B/event + 8 B/note load transient that caps
    // un-chunked loads at roughly 80 M notes on an 8 GB phone; true = keys
    // spill to disk in pre-sorted 32 MB runs and pairs stream through a temp
    // file, k-way merged in pass D — the transient disappears and free
    // storage becomes the only ceiling. Total event order is identical in
    // both modes (same comparator, same tie-break).
    //
    // Returns false on any failure (no space, VA reservation, oversized file)
    // with everything cleaned up, so the caller can fall back to the legacy
    // in-RAM parse — except when diskFull() is set: the statfs guard tripped
    // (the pagefile would leave the volume under ~2% free), and the caller
    // should abort with the "free up space" message instead of falling back.
    bool open(const std::string& midiPath, MidiData& out,
              std::atomic<float>& progress, bool chunked = false);
    // True when the last open() failed because pool/spill writes would (or
    // did) exhaust the storage volume — see kMinFreeDiskFraction.
    bool diskFull() const { return diskFull_; }
    void close();
    bool isOpen() const { return base_ != nullptr; }

    // Fast read-only skim (pass A alone): exact event count, no temp files, no
    // allocations per event. Lets the engine predict the in-RAM parse footprint
    // BEFORE committing to it — 0 on failure/empty.
    static uint64_t predictEventCount(const std::string& midiPath);

    // For events[] position `pos`: kSisNonNote, kSisNoteOff, or (for a
    // note-on) the events[] position of its note-off. Lets applySeek rebuild
    // active_ without dereferencing the pool for every historical event
    // (which would fault the entire cold file in random order).
    uint32_t sisterPosAt(size_t pos) const { return sisterPos_[pos]; }

    // Start/stop the read-ahead thread. `playheadUs` is the engine's published
    // clock (Engine::pubTimeUs_); `initialUs` seeds the window before the
    // first frame publishes (the pre-roll start). `engineCpuMask` is the
    // engine pin mask — the loader is pinned to the complement, same policy
    // as the BASS render threads.
    void startLoader(const std::atomic<int64_t>* playheadUs, int64_t initialUs,
                     uint64_t engineCpuMask);
    void stopLoader();

    // Engine thread, during applySeek: reposition the window and synchronously
    // warm what the very next frame reads — the visible band
    // [targetUs, visibleEndUs] plus the still-sounding note-ons in
    // `activePositions` (events[] positions) and their note-offs. The loader
    // thread widens to the full window asynchronously afterwards.
    void warmSeek(int64_t targetUs, int64_t visibleEndUs,
                  const std::vector<int>& activePositions);

    // Honest resident estimate: the tables held in RAM plus the pool bytes the
    // sliding window currently keeps warm (per-track spans + pinned pages).
    // Kernel page cache beyond the window is reclaimable and not counted —
    // it is not RAM the app is holding.
    size_t memoryBytes() const;

    // Exact pool size on disk (the pagefile the load streamed through). 0 when
    // no streaming pool is open, so it doubles as "was streaming used at all".
    size_t diskBytes() const { return poolBytes_; }

private:
    struct TrackRange { size_t first = 0, count = 0; };   // pool-index range
    struct TrackSample { int64_t us; uint32_t poolIdx; };  // sampled time index

    void loaderMain();
    void loaderTickLocked(int64_t playheadUs);
    // Touch (fault in) the pool bytes of events [firstIdx, lastIdx] — reads
    // one byte per page. madvise(WILLNEED) is issued first for async readahead.
    void touchPoolRange(size_t firstByte, size_t lastByte, bool willneed);
    // Per-track pool-index range covering [fromUs, toUs] via the sampled index.
    void trackWindow(int track, int64_t fromUs, int64_t toUs,
                     size_t& outFirst, size_t& outLast) const;
    // Coarse events[]-position for a time (floor of the sampled global index).
    size_t coarsePosOf(int64_t us) const;
    void resetWindowLocked(int64_t aroundUs);

    // ---- mapping ----
    uint8_t* base_    = nullptr;   // pool mapping base (also the reservation)
    size_t   mapLen_  = 0;         // page-rounded mapping length
    size_t   poolBytes_ = 0;       // exact pool size = totalEvents_ * sizeof(PlayEvent)
    bool     diskFull_  = false;   // last open() aborted by the free-space guard
    size_t   totalEvents_ = 0;

    // ---- resident tables ----
    std::vector<uint32_t>    sisterPos_;     // per events[] position (see above)
    std::vector<TrackRange>  trackRange_;    // pool-index span per track
    std::vector<uint32_t>    trackSampleOff_;// per-track offset into trackSamples_
    std::vector<TrackSample> trackSamples_;  // every kTrackSampleStep events
    std::vector<int64_t>     posTimes_;      // events[] time every kPosSampleStep
    const std::vector<PlayEvent*>* events_ = nullptr;  // out.events (stable after open)

    // ---- loader ----
    std::thread          loaderThread_;
    std::atomic<bool>    loaderRunning_{false};
    const std::atomic<int64_t>* playheadUs_ = nullptr;
    int64_t              initialUs_ = 0;
    uint64_t             engineCpuMask_ = 0;
    int64_t              frontUs_ = 0, backUs_ = 0;   // window horizons
    // Loader state — guarded by mutex_ (loader tick, warmSeek; never the
    // engine hot path).
    mutable std::mutex   mutex_;
    std::vector<size_t>  trackLo_, trackHi_;   // warmed byte range per track
    size_t               frontPos_ = 0;        // events[] positions already scanned
    size_t               backPos_  = 0;
    // Pages of note-ons that are still sounding after the window has moved
    // past them (long notes): page address -> events[] position of the
    // note-off, after which the pin is dropped. Re-touched every tick so
    // neither our DONTNEED nor kernel reclaim cools them under the O(P) scan.
    std::unordered_map<uintptr_t, uint32_t> pinnedPages_;

    static constexpr size_t  kTrackSampleStep = 4096;    // events per track sample
    static constexpr size_t  kPosSampleStep   = 65536;   // events per global sample
    static constexpr int64_t kDefaultFrontUs  = 30000000; // 30 s ahead
    static constexpr int64_t kDefaultBackUs   = 10000000; // 10 s behind
};

}  // namespace apfa

#endif  // APFA_STREAMING
