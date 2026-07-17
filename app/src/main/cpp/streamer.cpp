// streamer.cpp — see streamer.h.
//
// The parse walks the MIDI twice with ONE shared track walker (walkTracks), so
// the two passes cannot disagree about what gets emitted:
//
//   Pass A (skim)  — counts emissions per track, collects the tempo map and
//                    the note-bearing (track,channel) set. Nothing allocated
//                    per event.
//   Pass B (emit)  — re-parses, appending finished PlayEvents (µs times,
//                    sister pointers against the reserved base) to the pool
//                    file through a sequential write buffer. Note pairs whose
//                    note-on already left the buffer become fixups.
//   Pass C (patch) — applies the fixups to the pool file in sorted chunks.
//   Pass D (sort)  — builds the time-sorted events[] exactly like
//                    midi_parser.cpp's stable_sort (same keys, same tie-break
//                    = pool order), plus programChangeIdx, sisterPos, and the
//                    sampled time indexes the loader navigates by.
//
// Every semantic here (pairing stacks, end-of-track FIFO close-out, the
// non-note sister = &pool[0] artifact, the µs cap, the sort comparator) is a
// deliberate copy of midi_parser.cpp — the host parity harness diffs the two
// outputs field by field.
#ifdef APFA_STREAMING

#include "streamer.h"

#include <algorithm>
#include <cerrno>
#include <cstdlib>   // srand/getenv
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <queue>
#include <sched.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <utility>

#include "platform.h"

namespace apfa {
namespace {

constexpr size_t kPageSize   = 4096;
constexpr size_t kBufEvents  = 131072;   // pass-B write buffer (~9 MB)
constexpr size_t kEventSize  = sizeof(PlayEvent);
constexpr size_t kRunEntries = 2097152;  // sort-run spill threshold (32 MB)
constexpr size_t kPairBufEntries = 1048576;  // pair spill threshold (8 MB)
constexpr size_t kMergeBudget = 64 << 20;    // total merge read-buffer RAM

// Bounds-checked big-endian byte cursor — verbatim from midi_parser.cpp.
struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    bool ok = true;

    uint8_t u8() {
        if (p >= end) { ok = false; return 0; }
        return *p++;
    }
    uint32_t u16() { uint32_t a = u8(), b = u8(); return (a << 8) | b; }
    uint32_t u32() {
        uint32_t a = u8(), b = u8(), c = u8(), d = u8();
        return (a << 24) | (b << 16) | (c << 8) | d;
    }
    uint32_t varlen() {
        uint32_t v = 0;
        for (int i = 0; i < 4; i++) {
            uint8_t c = u8();
            v = (v << 7) | (c & 0x7F);
            if (!(c & 0x80)) break;
        }
        return v;
    }
    void skip(uint32_t n) {
        if (p + n > end) { p = end; ok = false; } else { p += n; }
    }
};

struct TempoEvent { uint32_t tick; uint32_t usPerQuarter; };
struct TempoSeg   { uint32_t tick; uint64_t usAtTick; uint32_t usPerQuarter; };

// The one track walker both passes share. `sink` receives exactly the
// emissions midi_parser.cpp performs, in the same order:
//   uint32_t noteOn(track, tick, ch, key, vel)  -> token for the pairing stack
//   void     noteOff(track, tick, ch, key, onToken)
//   void     channelEvent(track, tick, status, p1, p2)
//   void     tempo(tick, usPerQuarter)
//   void     trackDone(track)
// Pairing (LIFO within the track, FIFO close-out at track end) lives here so
// it is decided once. Mirrors midi_parser.cpp's track loop line for line.
template <class Sink>
void walkTracks(const uint8_t* afterHeader, const uint8_t* fileEnd,
                uint32_t numTracks, Sink& sink,
                std::atomic<float>& progress, float p0, float p1,
                int& maxTrackOut) {
    static thread_local std::vector<uint32_t> pending[16][128];
    const uint8_t* cur = afterHeader;
    int maxTrack = 0;

    for (uint32_t t = 0; t < numTracks && cur + 8 <= fileEnd; t++) {
        if (!(cur[0] == 'M' && cur[1] == 'T' && cur[2] == 'r' && cur[3] == 'k')) break;
        uint32_t trkLen = (uint32_t(cur[4]) << 24) | (uint32_t(cur[5]) << 16) |
                          (uint32_t(cur[6]) << 8)  |  uint32_t(cur[7]);
        cur += 8;
        const uint8_t* trkEnd = cur + trkLen;
        if (trkEnd > fileEnd) trkEnd = fileEnd;
        Reader r{ cur, trkEnd };

        for (int c = 0; c < 16; c++)
            for (int k = 0; k < 128; k++) pending[c][k].clear();

        uint32_t absTick = 0;
        uint8_t  running = 0;

        while (r.ok && r.p < r.end) {
            absTick += r.varlen();
            uint8_t status = r.u8();
            if (!r.ok) break;
            if (status < 0x80) {            // running status: re-use last status
                r.p--;                      // the byte just read is data, not status
                status = running;
                if (status < 0x80) break;
            } else {
                running = status;
            }
            uint8_t hi = status & 0xF0;
            int     ch = status & 0x0F;

            if (hi == 0x90) {               // note on
                uint8_t key = r.u8() & 0x7F;
                uint8_t vel = r.u8() & 0x7F;
                if (vel > 0) {
                    pending[ch][key].push_back(
                        sink.noteOn(static_cast<int>(t), absTick, ch, key, vel));
                } else if (!pending[ch][key].empty()) {   // vel 0 = note off
                    sink.noteOff(static_cast<int>(t), absTick, ch, key,
                                 pending[ch][key].back());
                    pending[ch][key].pop_back();
                }
            } else if (hi == 0x80) {        // note off
                uint8_t key = r.u8() & 0x7F;
                r.u8();                     // release velocity (ignored)
                if (!pending[ch][key].empty()) {
                    sink.noteOff(static_cast<int>(t), absTick, ch, key,
                                 pending[ch][key].back());
                    pending[ch][key].pop_back();
                }
            } else if (hi == 0xA0 || hi == 0xB0 || hi == 0xE0) {
                uint8_t p1b = r.u8(), p2b = r.u8();
                sink.channelEvent(static_cast<int>(t), absTick, status, p1b, p2b);
            } else if (hi == 0xC0 || hi == 0xD0) {
                uint8_t p1b = r.u8();
                sink.channelEvent(static_cast<int>(t), absTick, status, p1b, 0);
            } else if (status == 0xFF) {    // meta event
                uint8_t  metaType = r.u8();
                uint32_t len      = r.varlen();
                if (metaType == 0x51 && len == 3) {
                    uint8_t b0 = r.u8(), b1 = r.u8(), b2 = r.u8();
                    sink.tempo(absTick,
                               (uint32_t(b0) << 16) | (uint32_t(b1) << 8) | b2);
                } else {
                    r.skip(len);
                }
            } else if (status == 0xF0 || status == 0xF7) {  // sysex
                r.skip(r.varlen());
            } else {
                break;                      // malformed
            }
        }

        // notes still held at track end: terminate them there (FIFO — matches
        // midi_parser.cpp's range-for over the pending vector)
        for (int c = 0; c < 16; c++)
            for (int k = 0; k < 128; k++)
                for (uint32_t onTok : pending[c][k])
                    sink.noteOff(static_cast<int>(t), absTick, c, k, onTok);

        sink.trackDone(static_cast<int>(t));
        maxTrack = static_cast<int>(t);
        cur = trkEnd;
        progress.store(p0 + (p1 - p0) * float(t + 1) /
                            float(numTracks ? numTracks : 1));
    }
    maxTrackOut = maxTrack;
}

// Pass A: count emissions and gather metadata. Tokens are dummies — the
// pending stacks only need the right depths for the walker's pairing.
struct SkimSink {
    std::vector<TempoEvent> tempos;
    std::vector<size_t>     trackCounts;   // emissions per track index
    std::vector<uint8_t>    hasNotes16;    // (track*16 + ch) bitmap, grown on demand
    size_t noteCount = 0;

    void ensureTrack(int t) {
        if (trackCounts.size() <= static_cast<size_t>(t)) trackCounts.resize(t + 1, 0);
        if (hasNotes16.size() < static_cast<size_t>(t + 1) * 16)
            hasNotes16.resize(static_cast<size_t>(t + 1) * 16, 0);
    }
    uint32_t noteOn(int t, uint32_t, int ch, int, int) {
        ensureTrack(t);
        trackCounts[t]++;
        noteCount++;
        hasNotes16[static_cast<size_t>(t) * 16 + ch] = 1;
        return 0;
    }
    void noteOff(int t, uint32_t, int, int, uint32_t) { ensureTrack(t); trackCounts[t]++; }
    void channelEvent(int t, uint32_t, uint8_t, uint8_t, uint8_t) { ensureTrack(t); trackCounts[t]++; }
    void tempo(uint32_t tick, uint32_t uspq) { tempos.push_back({ tick, uspq }); }
    void trackDone(int t) { ensureTrack(t); }
};

// Fixup: a note-on that had already left the write buffer when its note-off
// was emitted; its sister pointer is patched into the file by pass C.
struct Fixup { uint32_t onIdx, offIdx; };
struct Pair  { uint32_t onIdx, offIdx; };
struct SortKey { uint64_t key; uint32_t idx; };

// (µs, track, chType) packed key; idx tie-break = parse order. ONE comparator
// shared by the chunked run sort and the un-chunked whole-table sort so the
// total event order can never diverge between the two modes.
inline bool sortKeyLess(const SortKey& a, const SortKey& b) {
    if (a.key != b.key) return a.key < b.key;
    return a.idx < b.idx;
}

// Free-space guard ("Not enough space on disk"): never fill the pool/spill
// volume past 98% — a load that would strand the phone at 0 B free aborts
// instead, and Streamer::diskFull() routes the right message to the UI.
// Checked once up front with the predicted total, then re-checked as the
// files grow (one fstatvfs per ~multi-MB write, cost is noise).
constexpr double kMinFreeDiskFraction = 0.02;

bool wouldExhaustDisk(int fd, uint64_t moreBytes) {
    struct statvfs vfs;
    if (fstatvfs(fd, &vfs) != 0) return false;   // can't tell — don't block the load
    uint64_t frsize = vfs.f_frsize ? vfs.f_frsize : vfs.f_bsize;
    uint64_t total  = static_cast<uint64_t>(vfs.f_blocks) * frsize;
    uint64_t avail  = static_cast<uint64_t>(vfs.f_bavail) * frsize;
    return avail < moreBytes + static_cast<uint64_t>(total * kMinFreeDiskFraction);
}

// Append `len` bytes, riding out short writes. False on error (ENOSPC etc.).
bool writeAll(int fd, const void* p, size_t len) {
    const uint8_t* b = static_cast<const uint8_t*>(p);
    while (len > 0) {
        ssize_t n = write(fd, b, len);
        if (n <= 0) return false;
        b += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

// mkstemp in `dir`, unlinked immediately — the fd is the only handle, so the
// space is reclaimed automatically on close, or on a crash.
int makeUnlinkedTemp(const std::string& dir, const char* tag) {
    std::string tmpl = dir + "/" + tag + "_XXXXXX";
    std::vector<char> nameBuf(tmpl.begin(), tmpl.end());
    nameBuf.push_back('\0');
    int fd = mkstemp(nameBuf.data());
    if (fd < 0) return fd;
    unlink(nameBuf.data());
    return fd;
}

// Pass B: emit finished PlayEvents through a sequential write buffer.
struct EmitSink {
    // configured by open()
    int      fd = -1;
    uint8_t* base = nullptr;
    const std::vector<TempoSeg>* segs = nullptr;
    int      ticksPerQuarter = 480;
    size_t   trackSampleStep = 4096;

    // outputs
    std::vector<Fixup>   fixups;         // cross-buffer sister patches (RAM; rare)
    std::vector<int64_t>  sampleUs;      // flattened per-track (µs, poolIdx) samples
    std::vector<uint32_t> sampleIdx;
    std::vector<uint32_t> sampleTrackOff; // per-track start into sampleUs/sampleIdx
    uint64_t totalUs = 0;
    uint32_t nextIdx = 0;
    bool     ioError  = false;
    bool     diskFull = false;           // ioError caused by the free-space guard

    // Sort keys. chunked=false (the automatic path): runBuf holds EVERY key
    // until pass D — 16 B/event resident, the load transient that caps
    // un-chunked streaming at ~80 M notes on an 8 GB phone (it's what lmkd
    // killed at 78% on NoK 90M). chunked=true ("Chunked Disk Streaming"):
    // keys spill to disk in pre-sorted 32 MB runs and a k-way merge in pass D
    // consumes them — no transient, storage-bound only.
    bool     chunked = false;
    int      runsFd = -1;
    std::vector<SortKey>  runBuf;
    std::vector<uint64_t> runStarts;     // first entry index of each run
    uint64_t runEntries = 0;
    // Note pairs: same split (8 B/note resident, or spilled and streamed back
    // to build sisterPos).
    int      pairsFd = -1;
    std::vector<Pair> pairBuf;
    uint64_t pairCount = 0;

    // write buffer
    std::vector<PlayEvent> buf;
    uint32_t bufStart = 0;
    size_t   inTrackCount = 0;

    void spillRun() {
        if (runBuf.empty() || ioError) return;
        std::sort(runBuf.begin(), runBuf.end(), sortKeyLess);
        if (wouldExhaustDisk(runsFd, runBuf.size() * sizeof(SortKey))) {
            diskFull = ioError = true;
            return;
        }
        if (!writeAll(runsFd, runBuf.data(), runBuf.size() * sizeof(SortKey)))
            ioError = true;
        runStarts.push_back(runEntries);
        runEntries += runBuf.size();
        runBuf.clear();
    }
    void spillPairs() {
        if (pairBuf.empty() || ioError) return;
        if (wouldExhaustDisk(pairsFd, pairBuf.size() * sizeof(Pair))) {
            diskFull = ioError = true;
            return;
        }
        if (!writeAll(pairsFd, pairBuf.data(), pairBuf.size() * sizeof(Pair)))
            ioError = true;
        pairCount += pairBuf.size();
        pairBuf.clear();
    }

    uint64_t tickToUs(uint32_t tick) const {
        const std::vector<TempoSeg>& s = *segs;
        size_t lo = 0, hi2 = s.size();
        while (lo + 1 < hi2) {
            size_t mid = (lo + hi2) / 2;
            if (s[mid].tick <= tick) lo = mid; else hi2 = mid;
        }
        return s[lo].usAtTick +
               uint64_t(tick - s[lo].tick) * s[lo].usPerQuarter / ticksPerQuarter;
    }

    void flush() {
        if (buf.empty() || ioError) { bufStart = nextIdx; buf.clear(); return; }
        if (wouldExhaustDisk(fd, buf.size() * kEventSize)) {
            diskFull = ioError = true;
            bufStart = nextIdx; buf.clear();
            return;
        }
        const uint8_t* p = reinterpret_cast<const uint8_t*>(buf.data());
        size_t left = buf.size() * kEventSize;
        while (left > 0) {
            ssize_t n = write(fd, p, left);
            if (n <= 0) { ioError = true; break; }
            p += n; left -= static_cast<size_t>(n);
        }
        buf.clear();
        bufStart = nextIdx;
    }

    // Shared tail for every emission: time key, sample index, totalUs.
    uint32_t emit(const PlayEvent& e, int track, uint32_t tick, int chType) {
        uint32_t idx = nextIdx++;
        uint64_t us  = tickToUs(tick);
        if (us > 0xFFFFFFFFull) us = 0xFFFFFFFFull;   // parser's per-event cap
        if (us > totalUs) totalUs = us;
        PlayEvent out = e;
        out.absMicroSec = static_cast<int64_t>(us);
        buf.push_back(out);
        if (buf.size() >= kBufEvents) flush();
        runBuf.push_back({ (us << 19) |
                           (static_cast<uint64_t>(track) << 3) |
                           static_cast<uint64_t>(14 - chType), idx });
        if (chunked && runBuf.size() >= kRunEntries) spillRun();
        if (inTrackCount % trackSampleStep == 0) {
            sampleUs.push_back(static_cast<int64_t>(us));
            sampleIdx.push_back(idx);
        }
        inTrackCount++;
        return idx;
    }

    uint32_t noteOn(int t, uint32_t tick, int ch, int key, int vel) {
        int c = ch & 0x0F;
        PlayEvent e{ 0, 0x90 | c, t, 0, static_cast<int32_t>(tick),
                     0 /*µs set in emit*/, kNoteOn, 0,
                     static_cast<uint8_t>(c), static_cast<uint8_t>(key & 0x7F),
                     static_cast<uint8_t>(vel & 0x7F),
                     reinterpret_cast<PlayEvent*>(base) /*patched by noteOff*/,
                     0, nullptr };
        return emit(e, t, tick, kNoteOn);
    }

    void noteOff(int t, uint32_t tick, int ch, int key, uint32_t onIdx) {
        int c = ch & 0x0F;
        PlayEvent e{ 0, 0x80 | c, t, 0, static_cast<int32_t>(tick),
                     0, kNoteOff, 0,
                     static_cast<uint8_t>(c), static_cast<uint8_t>(key & 0x7F), 0,
                     reinterpret_cast<PlayEvent*>(base + static_cast<size_t>(onIdx) * kEventSize),
                     0, nullptr };
        uint32_t offIdx = emit(e, t, tick, kNoteOff);
        pairBuf.push_back({ onIdx, offIdx });
        if (chunked && pairBuf.size() >= kPairBufEntries) spillPairs();
        PlayEvent* sisterPtr =
            reinterpret_cast<PlayEvent*>(base + static_cast<size_t>(offIdx) * kEventSize);
        if (onIdx >= bufStart) {
            buf[onIdx - bufStart].sister = sisterPtr;
        } else {
            fixups.push_back({ onIdx, offIdx });
        }
    }

    void channelEvent(int t, uint32_t tick, uint8_t status, uint8_t p1, uint8_t p2) {
        int c    = status & 0x0F;
        int type = status >> 4;
        // sister = &pool[0]: midi_parser.cpp's final index->pointer pass turns
        // the parse-time nullptr (index 0) into a pointer at the pool base for
        // every non-note event. Reproduce the artifact exactly.
        PlayEvent e{ 0, status, t, 0, static_cast<int32_t>(tick),
                     0, type, 0,
                     static_cast<uint8_t>(c), p1, p2,
                     reinterpret_cast<PlayEvent*>(base), 0, nullptr };
        emit(e, t, tick, type);
    }

    void tempo(uint32_t, uint32_t) {}   // tempo map already built in pass A

    void trackDone(int) {
        sampleTrackOff.push_back(static_cast<uint32_t>(sampleUs.size()));
        inTrackCount = 0;
    }
};

#if defined(__ANDROID__)
bool setThreadAffinityMaskSelf(uint64_t mask) {
    if (mask == 0) return false;
    int ncpu = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    cpu_set_t set;
    CPU_ZERO(&set);
    bool any = false;
    for (int c = 0; c < ncpu && c < 64; c++) {
        if (((mask >> c) & 1ULL) != 0ULL) { CPU_SET(c, &set); any = true; }
    }
    if (!any) return false;
    // See engine.cpp: direct syscall so the ELF carries no sched_setaffinity
    // UND symbol, which Gingerbread's loader cannot resolve.
    syscall(__NR_sched_setaffinity, 0, sizeof(set), &set);
    return true;
}
#endif

// Fault a byte range in: one volatile read per page. The mapping is
// read-only, so this is purely a page-cache populate.
void touchRange(const uint8_t* first, const uint8_t* last) {
    const uint8_t* p = reinterpret_cast<const uint8_t*>(
        reinterpret_cast<uintptr_t>(first) & ~(kPageSize - 1));
    for (; p <= last; p += kPageSize) {
        (void)*const_cast<volatile uint8_t*>(p);
    }
}

int64_t envUs(const char* name, int64_t fallback) {
    const char* v = getenv(name);
    if (!v || !*v) return fallback;
    char* end = nullptr;
    long long x = strtoll(v, &end, 10);
    return (end && *end == '\0' && x > 0) ? static_cast<int64_t>(x) : fallback;
}

}  // namespace

// ---- predict -------------------------------------------------------------------

uint64_t Streamer::predictEventCount(const std::string& midiPath) {
    int fd = ::open(midiPath.c_str(), O_RDONLY);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 14) { ::close(fd); return 0; }
    size_t fileSize = static_cast<size_t>(st.st_size);
    void* map = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);
    if (map == MAP_FAILED) return 0;
    const uint8_t* mbase   = static_cast<const uint8_t*>(map);
    const uint8_t* fileEnd = mbase + fileSize;

    Reader hdr{ mbase, fileEnd };
    if (hdr.u8() != 'M' || hdr.u8() != 'T' || hdr.u8() != 'h' || hdr.u8() != 'd') {
        munmap(map, fileSize); return 0;
    }
    uint32_t hdrLen = hdr.u32();
    hdr.u16();
    uint32_t numTracks = hdr.u16();
    hdr.u16();
    if (hdrLen > 6) hdr.skip(hdrLen - 6);

    SkimSink skim;
    std::atomic<float> dummy{0};
    int maxTrack = 0;
    walkTracks(hdr.p, fileEnd, numTracks, skim, dummy, 0.0f, 0.0f, maxTrack);
    munmap(map, fileSize);

    uint64_t total = 0;
    for (size_t c : skim.trackCounts) total += c;
    return total;
}

// ---- open --------------------------------------------------------------------

bool Streamer::open(const std::string& midiPath, MidiData& out,
                    std::atomic<float>& progress, bool chunked) {
    progress.store(0.0f);
    close();
    diskFull_ = false;

    // ---- map the MIDI file, parse the header (verbatim parseMidi) ----
    int fd = ::open(midiPath.c_str(), O_RDONLY);
    if (fd < 0) { LOGE("streamer: cannot open %s", midiPath.c_str()); return false; }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 14) {
        ::close(fd); LOGE("streamer: file too small"); return false;
    }
    size_t fileSize = static_cast<size_t>(st.st_size);
    void* midiMap = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);
    if (midiMap == MAP_FAILED) { LOGE("streamer: mmap failed"); return false; }
    const uint8_t* mbase   = static_cast<const uint8_t*>(midiMap);
    const uint8_t* fileEnd = mbase + fileSize;

    Reader hdr{ mbase, fileEnd };
    if (hdr.u8() != 'M' || hdr.u8() != 'T' || hdr.u8() != 'h' || hdr.u8() != 'd') {
        munmap(midiMap, fileSize); LOGE("streamer: not a MIDI file"); return false;
    }
    uint32_t hdrLen    = hdr.u32();
    hdr.u16();                                   // format (unused)
    uint32_t numTracks = hdr.u16();
    int16_t  division  = static_cast<int16_t>(hdr.u16());
    if (hdrLen > 6) hdr.skip(hdrLen - 6);
    int ticksPerQuarter = division > 0 ? division : 480;  // SMPTE -> fallback
    progress.store(0.02f);

    // ---- pass A: skim ----
    SkimSink skim;
    int maxTrack = 0;
    walkTracks(hdr.p, fileEnd, numTracks, skim, progress, 0.02f, 0.25f, maxTrack);

    size_t totalEvents = 0;
    for (size_t c : skim.trackCounts) totalEvents += c;
    if (totalEvents == 0 || totalEvents >= kSisNoteOff ||
        totalEvents > (SIZE_MAX / kEventSize) - kPageSize) {
        munmap(midiMap, fileSize);
        LOGE("streamer: unusable event count %zu", totalEvents);
        return false;
    }
    totalEvents_ = totalEvents;
    poolBytes_   = totalEvents * kEventSize;
    mapLen_      = (poolBytes_ + kPageSize - 1) & ~(kPageSize - 1);

    // ---- tempo map (verbatim parseMidi) ----
    std::sort(skim.tempos.begin(), skim.tempos.end(),
              [](const TempoEvent& a, const TempoEvent& b) { return a.tick < b.tick; });
    std::vector<TempoSeg> segs;
    segs.push_back({ 0u, 0ull, 500000u });
    for (const TempoEvent& te : skim.tempos) {
        TempoSeg& last = segs.back();
        if (te.tick <= last.tick) { last.usPerQuarter = te.usPerQuarter; continue; }
        uint64_t us = last.usAtTick +
            uint64_t(te.tick - last.tick) * last.usPerQuarter / ticksPerQuarter;
        segs.push_back({ te.tick, us, te.usPerQuarter });
    }

    // ---- reserve the pool's virtual address range ----
    // Sister pointers are written into the FILE, so the final base must be
    // known before pass B: reserve now, map the finished file here MAP_FIXED.
    void* res = mmap(nullptr, mapLen_, PROT_NONE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (res == MAP_FAILED) {
        munmap(midiMap, fileSize);
        LOGE("streamer: cannot reserve %zu MB of address space", mapLen_ >> 20);
        return false;
    }
    base_ = static_cast<uint8_t*>(res);

    // ---- create the pool file in the MIDI's directory = the app cache dir.
    //      Temps are unlinked immediately (zero litter even if we crash
    //      mid-parse) — see makeUnlinkedTemp.
    std::string dir = midiPath.substr(0, midiPath.find_last_of('/'));
    if (dir.empty()) dir = ".";
    int poolFd = makeUnlinkedTemp(dir, "apfa_pool");
    if (poolFd < 0) {
        LOGE("streamer: temp pool file failed in %s (errno=%d)", dir.c_str(), errno);
        munmap(midiMap, fileSize); close();
        return false;
    }

    // Pre-flight free-space check: pass A gave exact event/note counts, so the
    // total on-disk footprint (pool + spills when chunked) is known before a
    // single byte is written. Refuse now rather than strand the phone at 0 B
    // free three passes in.
    uint64_t predictedDiskBytes = poolBytes_;
    if (chunked)
        predictedDiskBytes += totalEvents * sizeof(SortKey) +
                              static_cast<uint64_t>(skim.noteCount) * sizeof(Pair);
    if (wouldExhaustDisk(poolFd, predictedDiskBytes)) {
        LOGE("streamer: %.1f MB pagefile would exhaust free storage — refusing",
             predictedDiskBytes / 1048576.0);
        diskFull_ = true;
        munmap(midiMap, fileSize); ::close(poolFd); close();
        return false;
    }

    // ---- pass B: emit ----
    EmitSink emit;
    emit.fd   = poolFd;
    emit.base = base_;
    emit.segs = &segs;
    emit.ticksPerQuarter = ticksPerQuarter;
    emit.trackSampleStep = kTrackSampleStep;
    emit.chunked = chunked;
    emit.buf.reserve(kBufEvents);
    emit.sampleTrackOff.push_back(0);
    if (chunked) {
        emit.runBuf.reserve(kRunEntries);
        emit.pairBuf.reserve(kPairBufEntries);
        emit.runsFd  = makeUnlinkedTemp(dir, "apfa_runs");
        emit.pairsFd = makeUnlinkedTemp(dir, "apfa_pairs");
        if (emit.runsFd < 0 || emit.pairsFd < 0) {
            LOGE("streamer: spill temp files failed in %s (errno=%d)", dir.c_str(), errno);
            if (emit.runsFd >= 0) ::close(emit.runsFd);
            if (emit.pairsFd >= 0) ::close(emit.pairsFd);
            munmap(midiMap, fileSize); ::close(poolFd); close();
            return false;
        }
    } else {
        // Un-chunked: hold everything for pass D in RAM up front. This IS the
        // deliberate ceiling (16 B/event + 8 B/note transient); past it the
        // engine routes to chunked mode or refuses.
        emit.runBuf.reserve(totalEvents);
        emit.pairBuf.reserve(skim.noteCount);
    }

    int maxTrackB = 0;
    walkTracks(hdr.p, fileEnd, numTracks, emit, progress, 0.25f, 0.60f, maxTrackB);
    emit.flush();
    if (chunked) {
        emit.spillRun();
        emit.spillPairs();
    } else {
        // One whole-table sort in place of the run spills — same comparator,
        // so the merged and un-merged orders are byte-identical.
        std::sort(emit.runBuf.begin(), emit.runBuf.end(), sortKeyLess);
        emit.runEntries = emit.runBuf.size();
        emit.pairCount  = emit.pairBuf.size();
    }
    munmap(midiMap, fileSize);

    // From here on, every failure path must release the spill fds too
    // (chunked mode only creates them; close(-1) is guarded).
    auto failCleanup = [&]() {
        if (emit.runsFd >= 0)  ::close(emit.runsFd);
        if (emit.pairsFd >= 0) ::close(emit.pairsFd);
        ::close(poolFd);
        close();
    };

    if (emit.ioError || emit.nextIdx != totalEvents || maxTrackB != maxTrack ||
        emit.runEntries != totalEvents) {
        diskFull_ = emit.diskFull;
        LOGE("streamer: emit pass failed (io=%d, full=%d, %u/%zu events, %llu keys)",
             emit.ioError ? 1 : 0, emit.diskFull ? 1 : 0, emit.nextIdx, totalEvents,
             static_cast<unsigned long long>(emit.runEntries));
        failCleanup();
        return false;
    }

    // ---- pass C: patch cross-buffer sister pointers ----
    progress.store(0.62f);
    {
        std::sort(emit.fixups.begin(), emit.fixups.end(),
                  [](const Fixup& a, const Fixup& b) { return a.onIdx < b.onIdx; });
        std::vector<PlayEvent> chunk(kBufEvents);
        size_t i = 0;
        while (i < emit.fixups.size()) {
            uint32_t c0 = emit.fixups[i].onIdx - (emit.fixups[i].onIdx % kBufEvents);
            size_t   n  = std::min(kBufEvents, totalEvents - c0);
            off_t    off = static_cast<off_t>(c0) * kEventSize;
            ssize_t  got = pread(poolFd, chunk.data(), n * kEventSize, off);
            if (got != static_cast<ssize_t>(n * kEventSize)) {
                LOGE("streamer: fixup pread failed"); failCleanup(); return false;
            }
            while (i < emit.fixups.size() && emit.fixups[i].onIdx < c0 + n) {
                const Fixup& f = emit.fixups[i];
                chunk[f.onIdx - c0].sister = reinterpret_cast<PlayEvent*>(
                    base_ + static_cast<size_t>(f.offIdx) * kEventSize);
                i++;
            }
            if (pwrite(poolFd, chunk.data(), n * kEventSize, off) !=
                static_cast<ssize_t>(n * kEventSize)) {
                LOGE("streamer: fixup pwrite failed"); failCleanup(); return false;
            }
        }
        emit.fixups.clear(); emit.fixups.shrink_to_fit();
    }

    // ---- pass D: sorted keys -> events[], pcIdx, samples ----
    // Same total order as midi_parser.cpp's stable_sort: (µs, track,
    // channelEventType DESC), ties broken by pool index = parse order.
    // us<<19 | track<<3 | (14-chType) packs all three keys; idx is the tie.
    // Un-chunked: one linear walk over the whole sorted key table (resident
    // since pass B). Chunked: k-way merge of the spilled runs — peak RAM is
    // the permanent tables plus ~64 MB of merge buffers, which is what lets
    // 90M-note files through where the resident table gets lmkd reaped
    // ("low on swap and thrashing").
    progress.store(0.62f);
    out.events.resize(totalEvents);
    out.programChangeIdx.clear();
    posTimes_.clear();
    auto placeEvent = [&](size_t pos, const SortKey& sk) {
        out.events[pos] = reinterpret_cast<PlayEvent*>(
            base_ + static_cast<size_t>(sk.idx) * kEventSize);
        int chType = 14 - static_cast<int>(sk.key & 7);
        if (chType == kProgramChange || chType == kController || chType == kPitchBend)
            out.programChangeIdx.push_back(pos);
        if (pos % kPosSampleStep == 0)
            posTimes_.push_back(static_cast<int64_t>(sk.key >> 19));
        if ((pos & 0xFFFFF) == 0)
            progress.store(0.62f + 0.18f * float(pos) / float(totalEvents));
    };
    if (!chunked) {
        for (size_t pos = 0; pos < totalEvents; pos++)
            placeEvent(pos, emit.runBuf[pos]);
        emit.runBuf.clear();
        emit.runBuf.shrink_to_fit();   // release the 16 B/event key table now
    } else {
        struct RunCursor {
            uint64_t next, end;            // entry indices in the runs file
            std::vector<SortKey> buf;
            size_t bufPos = 0;
        };
        size_t nRuns = emit.runStarts.size();
        size_t perRunEntries = std::max<size_t>(
            4096, kMergeBudget / (nRuns ? nRuns : 1) / sizeof(SortKey));

        std::vector<RunCursor> runs(nRuns);
        for (size_t r = 0; r < nRuns; r++) {
            runs[r].next = emit.runStarts[r];
            runs[r].end  = (r + 1 < nRuns) ? emit.runStarts[r + 1] : emit.runEntries;
        }
        auto refill = [&](RunCursor& rc) -> bool {
            if (rc.next >= rc.end) return false;
            size_t n = static_cast<size_t>(
                std::min<uint64_t>(perRunEntries, rc.end - rc.next));
            rc.buf.resize(n);
            ssize_t got = pread(emit.runsFd, rc.buf.data(), n * sizeof(SortKey),
                                static_cast<int64_t>(rc.next) * sizeof(SortKey));
            if (got != static_cast<ssize_t>(n * sizeof(SortKey))) return false;
            rc.next += n;
            rc.bufPos = 0;
            return true;
        };

        using HeapItem = std::pair<SortKey, uint32_t>;   // (key, run index)
        auto heapGreater = [](const HeapItem& a, const HeapItem& b) {
            if (a.first.key != b.first.key) return a.first.key > b.first.key;
            return a.first.idx > b.first.idx;
        };
        std::priority_queue<HeapItem, std::vector<HeapItem>, decltype(heapGreater)>
            heap(heapGreater);
        for (size_t r = 0; r < nRuns; r++)
            if (refill(runs[r]))
                heap.push({ runs[r].buf[runs[r].bufPos++], static_cast<uint32_t>(r) });

        size_t pos = 0;
        while (!heap.empty()) {
            HeapItem top = heap.top();
            heap.pop();
            placeEvent(pos, top.first);
            pos++;

            RunCursor& rc = runs[top.second];
            if (rc.bufPos >= rc.buf.size() && !refill(rc)) continue;
            heap.push({ rc.buf[rc.bufPos++], top.second });
        }
        if (pos != totalEvents) {
            LOGE("streamer: merge produced %zu/%zu events", pos, totalEvents);
            failCleanup();
            return false;
        }
    }
    if (emit.runsFd >= 0) { ::close(emit.runsFd); emit.runsFd = -1; }
    progress.store(0.80f);

    // ---- sisterPos: inverse map (transient) + pairs (resident or streamed) ----
    {
        std::vector<uint32_t> inv(totalEvents);
        for (size_t pos = 0; pos < totalEvents; pos++)
            inv[static_cast<size_t>(
                reinterpret_cast<uint8_t*>(out.events[pos]) - base_) / kEventSize] =
                static_cast<uint32_t>(pos);
        sisterPos_.assign(totalEvents, kSisNonNote);
        if (!chunked) {
            for (const Pair& pr : emit.pairBuf) {
                sisterPos_[inv[pr.onIdx]]  = inv[pr.offIdx];
                sisterPos_[inv[pr.offIdx]] = kSisNoteOff;
            }
            emit.pairBuf.clear();
            emit.pairBuf.shrink_to_fit();
        } else {
            std::vector<Pair> chunk(kPairBufEntries);
            uint64_t done = 0;
            while (done < emit.pairCount) {
                size_t n = static_cast<size_t>(
                    std::min<uint64_t>(chunk.size(), emit.pairCount - done));
                ssize_t got = pread(emit.pairsFd, chunk.data(), n * sizeof(Pair),
                                    static_cast<int64_t>(done) * sizeof(Pair));
                if (got != static_cast<ssize_t>(n * sizeof(Pair))) {
                    LOGE("streamer: pairs pread failed");
                    ::close(emit.pairsFd); ::close(poolFd); close();
                    return false;
                }
                for (size_t i = 0; i < n; i++) {
                    sisterPos_[inv[chunk[i].onIdx]]  = inv[chunk[i].offIdx];
                    sisterPos_[inv[chunk[i].offIdx]] = kSisNoteOff;
                }
                done += n;
            }
        }
    }
    if (emit.pairsFd >= 0) { ::close(emit.pairsFd); emit.pairsFd = -1; }
    progress.store(0.92f);

    // per-track pool ranges + sampled (µs -> pool idx) index
    trackRange_.assign(skim.trackCounts.size(), TrackRange{});
    {
        size_t first = 0;
        for (size_t t = 0; t < skim.trackCounts.size(); t++) {
            trackRange_[t] = { first, skim.trackCounts[t] };
            first += skim.trackCounts[t];
        }
    }
    trackSamples_.resize(emit.sampleUs.size());
    for (size_t i = 0; i < emit.sampleUs.size(); i++)
        trackSamples_[i] = { emit.sampleUs[i], emit.sampleIdx[i] };
    trackSampleOff_ = std::move(emit.sampleTrackOff);
    // walkTracks may have stopped early (malformed file): pad offsets so every
    // trackRange_ index has a sample span (possibly empty).
    while (trackSampleOff_.size() < trackRange_.size() + 1)
        trackSampleOff_.push_back(static_cast<uint32_t>(trackSamples_.size()));

    // ---- map the pool file read-only over the reservation ----
    void* mapped = mmap(base_, mapLen_, PROT_READ, MAP_FIXED | MAP_PRIVATE, poolFd, 0);
    ::close(poolFd);
    if (mapped == MAP_FAILED || mapped != base_) {
        LOGE("streamer: pool mmap failed (errno=%d)", errno);
        // base_ reservation may be gone after a failed MAP_FIXED; forget it.
        base_ = nullptr;
        close();
        return false;
    }
    events_ = &out.events;

    // ---- MidiData metadata (identical to parseMidi's tail) ----
    out.totalUs = static_cast<uint32_t>(std::min<uint64_t>(emit.totalUs, 0xFFFFFFFFull));
    out.trackCount = maxTrack + 1;
    out.trackColors.assign(static_cast<size_t>(out.trackCount) * 16, 0xFFFFFFFFu);
    {
        uint32_t palette[16];
        defaultPalettePFA(palette);
        srand(static_cast<unsigned>(time(nullptr)));   // PianoFromAbove.cpp:43
        int iPos = 0;
        for (int trk = 0; trk < out.trackCount; trk++)
            for (int chn = 0; chn < 16; chn++) {
                size_t idx = static_cast<size_t>(trk) * 16 + chn;
                if (idx >= skim.hasNotes16.size() || !skim.hasNotes16[idx]) continue;
                out.trackColors[idx] = (iPos < 16) ? palette[iPos] : randColorPFA();
                iPos++;
            }
    }
    out.minNote = 0;     // parser's "All keys" override
    out.maxNote = 127;
    out.actualNoteCount = skim.noteCount;
    out.valid = true;
    // out.eventPool stays EMPTY — the pool lives in the mapping. events[]
    // points into it; MidiData stays move-only and never copies.

    frontUs_ = envUs("APFA_WIN_FRONT_US", kDefaultFrontUs);
    backUs_  = envUs("APFA_WIN_BACK_US",  kDefaultBackUs);

    progress.store(1.0f);
    LOGI("streamer: %zu notes (%zu events), %d tracks, %.1f s | pool %.1f MB on disk, "
         "window %+.0fs/%-.0fs, tables %.1f MB resident, %s sort",
         out.noteCount(), totalEvents, out.trackCount, out.totalUs / 1e6,
         poolBytes_ / 1048576.0, frontUs_ / 1e6, backUs_ / 1e6,
         (out.events.size() * sizeof(PlayEvent*) + sisterPos_.size() * 4) / 1048576.0,
         chunked ? "chunked" : "in-RAM");
    return true;
}

void Streamer::close() {
    stopLoader();
    if (base_) { munmap(base_, mapLen_); base_ = nullptr; }
    mapLen_ = poolBytes_ = totalEvents_ = 0;
    sisterPos_.clear();      sisterPos_.shrink_to_fit();
    trackRange_.clear();     trackRange_.shrink_to_fit();
    trackSamples_.clear();   trackSamples_.shrink_to_fit();
    trackSampleOff_.clear(); trackSampleOff_.shrink_to_fit();
    posTimes_.clear();       posTimes_.shrink_to_fit();
    pinnedPages_.clear();
    events_ = nullptr;
    trackLo_.clear();        trackLo_.shrink_to_fit();
    trackHi_.clear();        trackHi_.shrink_to_fit();
}

// ---- window navigation --------------------------------------------------------

void Streamer::trackWindow(int track, int64_t fromUs, int64_t toUs,
                           size_t& outFirst, size_t& outLast) const {
    const TrackRange& tr = trackRange_[track];
    if (tr.count == 0) { outFirst = 1; outLast = 0; return; }   // empty
    const uint32_t s0 = trackSampleOff_[track];
    const uint32_t s1 = trackSampleOff_[track + 1];

    // floor sample for fromUs
    size_t first = tr.first;
    {
        uint32_t lo = s0, hi = s1;
        while (lo < hi) {                    // first sample with us > fromUs
            uint32_t mid = (lo + hi) / 2;
            if (trackSamples_[mid].us > fromUs) hi = mid; else lo = mid + 1;
        }
        if (lo > s0) first = trackSamples_[lo - 1].poolIdx;
    }
    // ceiling sample for toUs
    size_t last = tr.first + tr.count - 1;
    {
        uint32_t lo = s0, hi = s1;
        while (lo < hi) {                    // first sample with us > toUs
            uint32_t mid = (lo + hi) / 2;
            if (trackSamples_[mid].us > toUs) hi = mid; else lo = mid + 1;
        }
        if (lo < s1) last = trackSamples_[lo].poolIdx;   // one sample past = safe ceil
    }
    outFirst = first;
    outLast  = last;
}

size_t Streamer::coarsePosOf(int64_t us) const {
    if (posTimes_.empty()) return 0;
    size_t lo = 0, hi = posTimes_.size();
    while (lo < hi) {                        // first sample with time > us
        size_t mid = (lo + hi) / 2;
        if (posTimes_[mid] > us) hi = mid; else lo = mid + 1;
    }
    return (lo > 0 ? lo - 1 : 0) * kPosSampleStep;
}

void Streamer::touchPoolRange(size_t firstByte, size_t lastByte, bool willneed) {
    if (firstByte > lastByte || lastByte >= poolBytes_) {
        if (lastByte >= poolBytes_) lastByte = poolBytes_ ? poolBytes_ - 1 : 0;
        if (firstByte > lastByte) return;
    }
    uint8_t* a = base_ + (firstByte & ~(kPageSize - 1));
    uint8_t* b = base_ + lastByte;
    if (willneed)
        madvise(a, static_cast<size_t>(b - a) + 1, MADV_WILLNEED);
    touchRange(a, b);
}

void Streamer::resetWindowLocked(int64_t aroundUs) {
    trackLo_.assign(trackRange_.size(), 0);
    trackHi_.assign(trackRange_.size(), 0);
    for (size_t t = 0; t < trackRange_.size(); t++) {
        size_t f, l;
        trackWindow(static_cast<int>(t), aroundUs - backUs_, aroundUs - backUs_, f, l);
        size_t startByte = (f <= l ? f : trackRange_[t].first) * kEventSize;
        trackLo_[t] = trackHi_[t] = startByte;
    }
    frontPos_ = coarsePosOf(aroundUs);
    backPos_  = coarsePosOf(aroundUs - backUs_);
}

// ---- loader thread --------------------------------------------------------------

void Streamer::startLoader(const std::atomic<int64_t>* playheadUs,
                           int64_t initialUs, uint64_t engineCpuMask) {
    if (loaderRunning_.load() || !isOpen()) return;
    playheadUs_    = playheadUs;
    initialUs_     = initialUs;
    engineCpuMask_ = engineCpuMask;
    {
        std::lock_guard<std::mutex> lk(mutex_);
        resetWindowLocked(initialUs);
    }
    loaderRunning_ = true;
    loaderThread_  = std::thread(&Streamer::loaderMain, this);
}

void Streamer::stopLoader() {
    loaderRunning_ = false;
    if (loaderThread_.joinable()) loaderThread_.join();
    playheadUs_ = nullptr;
}

void Streamer::loaderMain() {
#if defined(__ANDROID__)
    // Same placement policy as the BASS render threads: everywhere BUT the
    // engine core, so read-ahead I/O never contends with dispatch/render.
    int ncpu = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    uint64_t ncpuMask = (ncpu >= 64) ? ~0ULL : ((1ULL << ncpu) - 1ULL);
    uint64_t avoid = (~engineCpuMask_) & ncpuMask;
    if (engineCpuMask_ != 0) setThreadAffinityMaskSelf(avoid);
#elif defined(__APPLE__)
    apfa::platform::setAuxThreadPolicy();
#endif
    bool seenPlayhead = false;
    while (loaderRunning_.load()) {
        int64_t t = playheadUs_ ? playheadUs_->load() : 0;
        if (t != 0) seenPlayhead = true;
        if (!seenPlayhead) t = initialUs_;
        {
            std::lock_guard<std::mutex> lk(mutex_);
            loaderTickLocked(t);
        }
        usleep(100000);   // 100 ms cadence — the window is 30 s deep
    }
}

void Streamer::loaderTickLocked(int64_t t) {
    const std::vector<PlayEvent*>& ev = *events_;
    const size_t n = totalEvents_;
    size_t curPos = coarsePosOf(t);

    // Front-edge scan: pre-touch the note-offs of long notes entering the
    // window, so buildVisible's duration read (e->sister->absMicroSec) never
    // faults on the engine thread.
    size_t newFront = std::min(coarsePosOf(t + frontUs_) + kPosSampleStep, n);
    for (size_t pos = frontPos_; pos < newFront; pos++) {
        uint32_t s = sisterPos_[pos];
        if (s < kSisNoteOff && static_cast<size_t>(s) > newFront) {
            const uint8_t* off = reinterpret_cast<const uint8_t*>(ev[s]);
            touchRange(off, off + kEventSize - 1);
        }
    }
    if (newFront > frontPos_) frontPos_ = newFront;

    // Back-edge scan: note-ons leaving the window that are STILL sounding
    // (their off is ahead of the playhead) get pinned — the O(P) note-off
    // scan and buildVisible keep reading them until the off dispatches.
    size_t newBack = std::min(coarsePosOf(t - backUs_), n);
    for (size_t pos = backPos_; pos < newBack; pos++) {
        uint32_t s = sisterPos_[pos];
        if (s < kSisNoteOff && static_cast<size_t>(s) > curPos) {
            uintptr_t page = reinterpret_cast<uintptr_t>(ev[pos]) & ~(kPageSize - 1);
            uintptr_t page2 = (reinterpret_cast<uintptr_t>(ev[pos]) + kEventSize - 1)
                              & ~(kPageSize - 1);
            auto& rel = pinnedPages_[page];
            if (s > rel) rel = s;
            if (page2 != page) {
                auto& rel2 = pinnedPages_[page2];
                if (s > rel2) rel2 = s;
            }
        }
    }
    if (newBack > backPos_) backPos_ = newBack;

    // Per-track sliding window: warm new bytes ahead, drop bytes behind.
    for (size_t trk = 0; trk < trackRange_.size(); trk++) {
        if (trackRange_[trk].count == 0) continue;
        size_t f, l;
        trackWindow(static_cast<int>(trk), t - backUs_, t + frontUs_, f, l);
        if (f > l) continue;
        size_t loByte = f * kEventSize;
        size_t hiByte = l * kEventSize + kEventSize - 1;
        if (hiByte >= poolBytes_) hiByte = poolBytes_ - 1;

        if (hiByte + 1 > trackHi_[trk]) {          // warm the newly-entered range
            size_t from = std::max(trackHi_[trk], loByte);
            touchPoolRange(from, hiByte, /*willneed=*/true);
            trackHi_[trk] = hiByte + 1;
        }
        if (loByte > trackLo_[trk] + (kPageSize << 8)) {   // retire ≥1 MB behind
            uint8_t* a = base_ + ((trackLo_[trk] + kPageSize - 1) & ~(kPageSize - 1));
            uint8_t* b = base_ + (loByte & ~(kPageSize - 1));
            if (b > a) madvise(a, static_cast<size_t>(b - a), MADV_DONTNEED);
            trackLo_[trk] = loByte;
        }
    }

    // Pins: drop the completed ones, keep the rest warm (a re-touch after our
    // own DONTNEED costs one 4K read; there are few pins).
    for (auto it = pinnedPages_.begin(); it != pinnedPages_.end();) {
        if (static_cast<size_t>(it->second) <= curPos) {
            it = pinnedPages_.erase(it);
        } else {
            (void)*const_cast<volatile uint8_t*>(
                reinterpret_cast<const uint8_t*>(it->first));
            ++it;
        }
    }
}

// ---- seek -----------------------------------------------------------------------

void Streamer::warmSeek(int64_t targetUs, int64_t visibleEndUs,
                        const std::vector<int>& activePositions) {
    if (!isOpen()) return;
    std::lock_guard<std::mutex> lk(mutex_);
    const std::vector<PlayEvent*>& ev = *events_;

    resetWindowLocked(targetUs);
    frontPos_ = coarsePosOf(targetUs);   // let the next tick re-scan the window

    // Kick off readahead for the visible band on every track, then fault it in.
    int64_t warmFrom = targetUs - 2000000;
    int64_t warmTo   = visibleEndUs + 2000000;
    std::vector<std::pair<size_t, size_t>> ranges(trackRange_.size(), { 1, 0 });
    for (size_t trk = 0; trk < trackRange_.size(); trk++) {
        if (trackRange_[trk].count == 0) continue;
        size_t f, l;
        trackWindow(static_cast<int>(trk), warmFrom, warmTo, f, l);
        if (f > l) continue;
        size_t loByte = f * kEventSize;
        size_t hiByte = std::min(l * kEventSize + kEventSize - 1, poolBytes_ - 1);
        madvise(base_ + (loByte & ~(kPageSize - 1)),
                (hiByte - (loByte & ~(kPageSize - 1))) + 1, MADV_WILLNEED);
        ranges[trk] = { loByte, hiByte };
    }
    for (size_t trk = 0; trk < ranges.size(); trk++) {
        if (ranges[trk].first > ranges[trk].second) continue;
        touchPoolRange(ranges[trk].first, ranges[trk].second, /*willneed=*/false);
        trackHi_[trk] = std::max(trackHi_[trk], ranges[trk].second + 1);
    }

    // Long-note offs inside the visible band (buildVisible reads their times
    // on the very next frame).
    size_t p0 = coarsePosOf(targetUs);
    size_t p1 = std::min(coarsePosOf(visibleEndUs) + kPosSampleStep, totalEvents_);
    for (size_t pos = p0; pos < p1; pos++) {
        uint32_t s = sisterPos_[pos];
        if (s < kSisNoteOff && static_cast<size_t>(s) > p1) {
            const uint8_t* off = reinterpret_cast<const uint8_t*>(ev[s]);
            touchRange(off, off + kEventSize - 1);
        }
    }

    // Still-sounding note-ons resurrected by the seek: touch them (applySeek
    // reads param1 right after this) and their offs, and pin their pages.
    for (int posInt : activePositions) {
        size_t pos = static_cast<size_t>(posInt);
        const uint8_t* on = reinterpret_cast<const uint8_t*>(ev[pos]);
        touchRange(on, on + kEventSize - 1);
        uint32_t s = sisterPos_[pos];
        if (s < kSisNoteOff) {
            const uint8_t* off = reinterpret_cast<const uint8_t*>(ev[s]);
            touchRange(off, off + kEventSize - 1);
            uintptr_t page = reinterpret_cast<uintptr_t>(on) & ~(kPageSize - 1);
            uintptr_t page2 = (reinterpret_cast<uintptr_t>(on) + kEventSize - 1)
                              & ~(kPageSize - 1);
            auto& rel = pinnedPages_[page];
            if (s > rel) rel = s;
            if (page2 != page) {
                auto& rel2 = pinnedPages_[page2];
                if (s > rel2) rel2 = s;
            }
        }
    }
}

// ---- memory accounting ------------------------------------------------------------

size_t Streamer::memoryBytes() const {
    if (!isOpen()) return 0;
    size_t tables = sisterPos_.capacity() * sizeof(uint32_t) +
                    trackRange_.capacity() * sizeof(TrackRange) +
                    trackSamples_.capacity() * sizeof(TrackSample) +
                    trackSampleOff_.capacity() * sizeof(uint32_t) +
                    posTimes_.capacity() * sizeof(int64_t);

    std::lock_guard<std::mutex> lk(mutex_);
    size_t window = 0;
    for (size_t t = 0; t < trackLo_.size(); t++)
        if (trackHi_[t] > trackLo_[t]) window += trackHi_[t] - trackLo_[t];
    window += pinnedPages_.size() * kPageSize;
    return tables + window;
}

}  // namespace apfa

#endif  // APFA_STREAMING
