// midi_parser.cpp — see midi_parser.h.
//
// Each MIDI message becomes a compact PlayEvent appended to eventPool in FILE
// order — note-on when parsed, note-off when parsed. Keeping parse order retains
// the useful locality of note-on records while the time-sorted events[] table
// preserves the exact playback/render ordering.
//
// Times are stored as TICKS in absMicroSec during parsing and converted to
// microseconds in place. A note-on's link temporarily holds its partner pool
// index, then becomes the note's absolute end time. No runtime sister pointer
// is stored inside the 16-byte event.
#include "midi_parser.h"

#include <algorithm>
#include <array>
#include <queue>
#include <cstdlib>   // srand (PFA seeds the RNG once at startup)
#include <ctime>     // time   (srand seed source)
#include <vector>
#include <utility>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
#include "platform.h"
#include "parallel_load.h"

namespace apfa {
namespace {

// Bounds-checked big-endian byte cursor.
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

uint64_t parserMonoUs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull +
           static_cast<uint64_t>(ts.tv_nsec / 1000);
}

struct TempoEvent { uint32_t tick; uint32_t usPerQuarter; };
struct TempoSeg   { uint32_t tick; uint64_t usAtTick; uint32_t usPerQuarter; };

// During parsing a note-on's link temporarily holds its paired note-off POOL
// INDEX. After ticks are converted to microseconds, it is replaced by the
// note-off's absolute end time. Note-off events themselves need no back-link.
uint32_t pushNoteOn(std::vector<PlayEvent>& pool, uint32_t tick,
                    int key, int vel, int track, int channel) {
    int      c   = channel & 0x0F;
    uint8_t  k   = static_cast<uint8_t>(key & 0x7F);
    uint32_t idx = static_cast<uint32_t>(pool.size());
    pool.push_back(PlayEvent{
        tick, kNoEventLink, static_cast<uint16_t>(track),
        static_cast<uint8_t>(0x90 | c), k,
        static_cast<uint8_t>(vel & 0x7F), static_cast<uint8_t>(c),
        static_cast<uint8_t>(kNoteOn), 0 });
    return idx;
}

// Append a note-off PlayEvent at tick closing the note-on at onIdx.
// Both events hold the partner's pool index until the time-order table exists.
void pushNoteOff(std::vector<PlayEvent>& pool, uint32_t tick, uint32_t onIdx) {
    int     c     = pool[onIdx].channel;   // read before push_back may realloc
    int     track = pool[onIdx].track;
    uint8_t k     = pool[onIdx].param1;
    uint32_t offIdx = static_cast<uint32_t>(pool.size());
    pool.push_back(PlayEvent{
        tick, onIdx, static_cast<uint16_t>(track),
        static_cast<uint8_t>(0x80 | c), k, 0,
        static_cast<uint8_t>(c), static_cast<uint8_t>(kNoteOff), 0 });
    pool[onIdx].link = offIdx;
}

// Append a singleton PlayEvent (CC, ProgramChange, etc.) at `tick`.
void pushChannelEvent(std::vector<PlayEvent>& pool, uint32_t tick,
                      uint8_t status, uint8_t p1, uint8_t p2, int track) {
    int c = status & 0x0F;
    int type = status >> 4;
    pool.push_back(PlayEvent{
        tick, kNoEventLink, static_cast<uint16_t>(track),
        status, p1, p2, static_cast<uint8_t>(c),
        static_cast<uint8_t>(type), 0 });
}

struct ScanSink {
    MidiTrackPlan* plan = nullptr;

    uint32_t noteOn(int, uint32_t, int ch, int, int) {
        plan->eventCount++;
        plan->noteCount++;
        plan->hasNotesMask |= static_cast<uint16_t>(1u << (ch & 15));
        return 0;
    }
    void noteOff(int, uint32_t, int, int, uint32_t) { plan->eventCount++; }
    void channelEvent(int, uint32_t, uint8_t, uint8_t, uint8_t) {
        plan->eventCount++;
    }
    void tempo(uint32_t tick, uint32_t uspq) {
        plan->tempos.push_back(MidiTempoPoint{tick, uspq});
    }
};

struct DirectSink {
    std::vector<PlayEvent>* pool = nullptr;
    size_t next = 0;
    size_t end = 0;
    bool ok = true;

    uint32_t noteOn(int t, uint32_t tick, int ch, int key, int vel) {
        if (next >= end || next > 0xFFFFFFFFu) { ok = false; return 0; }
        const uint32_t idx = static_cast<uint32_t>(next++);
        const int c = ch & 0x0F;
        (*pool)[idx] = PlayEvent{
            tick, kNoEventLink, static_cast<uint16_t>(t),
            static_cast<uint8_t>(0x90 | c), static_cast<uint8_t>(key & 0x7F),
            static_cast<uint8_t>(vel & 0x7F), static_cast<uint8_t>(c),
            static_cast<uint8_t>(kNoteOn), 0
        };
        return idx;
    }

    void noteOff(int t, uint32_t tick, int ch, int key, uint32_t onIdx) {
        if (!ok || next >= end || next > 0xFFFFFFFFu ||
            onIdx >= pool->size()) {
            ok = false;
            return;
        }
        const uint32_t offIdx = static_cast<uint32_t>(next++);
        const int c = ch & 0x0F;
        (*pool)[offIdx] = PlayEvent{
            tick, onIdx, static_cast<uint16_t>(t),
            static_cast<uint8_t>(0x80 | c), static_cast<uint8_t>(key & 0x7F), 0,
            static_cast<uint8_t>(c), static_cast<uint8_t>(kNoteOff), 0
        };
        (*pool)[onIdx].link = offIdx;
    }

    void channelEvent(int t, uint32_t tick, uint8_t status,
                      uint8_t p1, uint8_t p2) {
        if (!ok || next >= end || next > 0xFFFFFFFFu) {
            ok = false;
            return;
        }
        const uint32_t idx = static_cast<uint32_t>(next++);
        const int c = status & 0x0F;
        (*pool)[idx] = PlayEvent{
            tick, kNoEventLink, static_cast<uint16_t>(t),
            status, p1, p2, static_cast<uint8_t>(c),
            static_cast<uint8_t>(status >> 4), 0
        };
    }

    void tempo(uint32_t, uint32_t) {}
};

template <class Sink>
bool walkOneTrack(const uint8_t* begin, const uint8_t* end, int track, Sink& sink) {
    static thread_local std::vector<uint32_t> pending[16][128];
    for (int c = 0; c < 16; ++c)
        for (int k = 0; k < 128; ++k)
            pending[c][k].clear();

    Reader r{begin, end};
    uint32_t absTick = 0;
    uint8_t running = 0;

    while (r.ok && r.p < r.end) {
        absTick += r.varlen();
        uint8_t status = r.u8();
        if (!r.ok) break;
        if (status < 0x80) {
            r.p--;
            status = running;
            if (status < 0x80) break;
        } else {
            running = status;
        }

        const uint8_t hi = status & 0xF0;
        const int ch = status & 0x0F;
        if (hi == 0x90) {
            const uint8_t key = r.u8() & 0x7F;
            const uint8_t vel = r.u8() & 0x7F;
            if (vel > 0) {
                pending[ch][key].push_back(
                    sink.noteOn(track, absTick, ch, key, vel));
            } else if (!pending[ch][key].empty()) {
                sink.noteOff(track, absTick, ch, key, pending[ch][key].back());
                pending[ch][key].pop_back();
            }
        } else if (hi == 0x80) {
            const uint8_t key = r.u8() & 0x7F;
            r.u8();
            if (!pending[ch][key].empty()) {
                sink.noteOff(track, absTick, ch, key, pending[ch][key].back());
                pending[ch][key].pop_back();
            }
        } else if (hi == 0xA0 || hi == 0xB0 || hi == 0xE0) {
            const uint8_t p1 = r.u8();
            const uint8_t p2 = r.u8();
            sink.channelEvent(track, absTick, status, p1, p2);
        } else if (hi == 0xC0 || hi == 0xD0) {
            const uint8_t p1 = r.u8();
            sink.channelEvent(track, absTick, status, p1, 0);
        } else if (status == 0xFF) {
            const uint8_t metaType = r.u8();
            const uint32_t len = r.varlen();
            if (metaType == 0x51 && len == 3) {
                const uint8_t b0 = r.u8(), b1 = r.u8(), b2 = r.u8();
                sink.tempo(absTick,
                    (uint32_t(b0) << 16) | (uint32_t(b1) << 8) | b2);
            } else {
                r.skip(len);
            }
        } else if (status == 0xF0 || status == 0xF7) {
            r.skip(r.varlen());
        } else {
            break;
        }
        if (!r.ok) break;
    }

    // Preserve the original FIFO close-out at end-of-track.
    for (int c = 0; c < 16; ++c)
        for (int k = 0; k < 128; ++k)
            for (uint32_t onTok : pending[c][k])
                sink.noteOff(track, absTick, c, k, onTok);

    return true;
}

bool readHeaderAndTracks(const uint8_t* base, const uint8_t* fileEnd,
                         MidiPreScan& scan) {
    Reader hdr{base, fileEnd};
    if (hdr.u8() != 'M' || hdr.u8() != 'T' ||
        hdr.u8() != 'h' || hdr.u8() != 'd') return false;
    const uint32_t hdrLen = hdr.u32();
    hdr.u16();
    const uint32_t numTracks = hdr.u16();
    const int16_t division = static_cast<int16_t>(hdr.u16());
    if (hdrLen > 6) hdr.skip(hdrLen - 6);
    if (!hdr.ok) return false;

    scan.ticksPerQuarter = division > 0 ? division : 480;
    scan.tracks.clear();
    scan.tracks.reserve(numTracks);

    const uint8_t* cur = hdr.p;
    for (uint32_t t = 0; t < numTracks && cur + 8 <= fileEnd; ++t) {
        if (!(cur[0] == 'M' && cur[1] == 'T' &&
              cur[2] == 'r' && cur[3] == 'k')) return false;
        const uint32_t len =
            (uint32_t(cur[4]) << 24) | (uint32_t(cur[5]) << 16) |
            (uint32_t(cur[6]) << 8) | uint32_t(cur[7]);
        cur += 8;
        const uint8_t* end = cur + len;
        if (end > fileEnd) end = fileEnd;
        MidiTrackPlan plan;
        plan.fileOffset = static_cast<uint64_t>(cur - base);
        plan.fileLength = static_cast<uint32_t>(end - cur);
        scan.tracks.push_back(std::move(plan));
        cur = end;
    }
    return !scan.tracks.empty();
}

}  // namespace

MidiPreScan scanMidi(const std::string& path, std::atomic<float>& progress) {
    const uint64_t phaseStart = parserMonoUs();
    MidiPreScan scan;
    progress = 0.0f;

    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return scan;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 14) {
        close(fd);
        return scan;
    }
    const size_t fileSize = static_cast<size_t>(st.st_size);
    void* map = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return scan;

    const uint8_t* base = static_cast<const uint8_t*>(map);
    const uint8_t* fileEnd = base + fileSize;
    if (!readHeaderAndTracks(base, fileEnd, scan)) {
        munmap(map, fileSize);
        return MidiPreScan{};
    }

    std::vector<uint8_t> trackOk(scan.tracks.size(), 0);
    std::atomic<uint32_t> completed{0};
    const unsigned workers = parallelForRanges(
        scan.tracks.size(), 1,
        [&](size_t begin, size_t end, unsigned) {
            for (size_t t = begin; t < end; ++t) {
                MidiTrackPlan& plan = scan.tracks[t];
                ScanSink sink{&plan};
                const uint8_t* p = base + plan.fileOffset;
                trackOk[t] = walkOneTrack(
                    p, p + plan.fileLength, static_cast<int>(t), sink) ? 1 : 0;
                const uint32_t done = completed.fetch_add(
                    1, std::memory_order_relaxed) + 1;
                progress.store(0.02f + 0.18f *
                    static_cast<float>(done) /
                    static_cast<float>(scan.tracks.size()),
                    std::memory_order_relaxed);
            }
        }, 4);

    scan.eventCount = 0;
    scan.noteCount = 0;
    scan.valid = true;
    for (size_t t = 0; t < scan.tracks.size(); ++t) {
        if (!trackOk[t]) scan.valid = false;
        scan.eventCount += scan.tracks[t].eventCount;
        scan.noteCount += scan.tracks[t].noteCount;
    }
    if (scan.eventCount > 0xFFFFFFFFull) scan.valid = false;
    munmap(map, fileSize);

    LOGI("scanMidi: %zu tracks, %llu events, %llu notes, %u worker(s), %.1f ms",
         scan.tracks.size(),
         static_cast<unsigned long long>(scan.eventCount),
         static_cast<unsigned long long>(scan.noteCount),
         workers, (parserMonoUs() - phaseStart) / 1000.0);
    return scan;
}

MidiData parseMidi(const std::string& path, std::atomic<float>& progress,
                   uint64_t expectedEvents, const MidiPreScan* preScan) {
    const uint64_t parseStartUs = parserMonoUs();
    MidiData out;

    MidiPreScan ownedScan;
    const MidiPreScan* scan = preScan;
    if (!scan || !scan->valid) {
        ownedScan = scanMidi(path, progress);
        scan = &ownedScan;
    }
    if (!scan->valid || scan->tracks.empty() || scan->eventCount == 0)
        return out;
    if (expectedEvents && expectedEvents != scan->eventCount)
        LOGI("parseMidi: prior event prediction changed (%llu -> %llu)",
             static_cast<unsigned long long>(expectedEvents),
             static_cast<unsigned long long>(scan->eventCount));

    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return out;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 14) {
        close(fd);
        return out;
    }
    const size_t fileSize = static_cast<size_t>(st.st_size);
    void* map = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return out;
    const uint8_t* base = static_cast<const uint8_t*>(map);

    const size_t trackCount = scan->tracks.size();
    std::vector<size_t> trackStart(trackCount + 1, 0);
    for (size_t t = 0; t < trackCount; ++t) {
        if (scan->tracks[t].eventCount >
            static_cast<uint64_t>(SIZE_MAX - trackStart[t])) {
            munmap(map, fileSize);
            return out;
        }
        trackStart[t + 1] =
            trackStart[t] + static_cast<size_t>(scan->tracks[t].eventCount);
    }
    const size_t poolN = trackStart.back();
    if (poolN != static_cast<size_t>(scan->eventCount)) {
        munmap(map, fileSize);
        return out;
    }

    // One exact final allocation. Every worker owns a disjoint track slice, so
    // there are no locks, push_back contention, or per-track event copies.
    out.eventPool.resize(poolN);
    out.actualNoteCount = static_cast<size_t>(scan->noteCount);
    std::vector<uint8_t> trackOk(trackCount, 0);
    std::atomic<uint32_t> completed{0};

    const unsigned parseWorkers = parallelForRanges(
        trackCount, 1,
        [&](size_t begin, size_t end, unsigned) {
            for (size_t t = begin; t < end; ++t) {
                const MidiTrackPlan& plan = scan->tracks[t];
                if (plan.fileOffset + plan.fileLength > fileSize) {
                    trackOk[t] = 0;
                } else {
                    DirectSink sink{
                        &out.eventPool, trackStart[t], trackStart[t + 1], true
                    };
                    const uint8_t* p = base + plan.fileOffset;
                    const bool walked = walkOneTrack(
                        p, p + plan.fileLength, static_cast<int>(t), sink);
                    trackOk[t] =
                        (walked && sink.ok && sink.next == trackStart[t + 1]) ? 1 : 0;
                }
                const uint32_t done = completed.fetch_add(
                    1, std::memory_order_relaxed) + 1;
                progress.store(0.20f + 0.32f *
                    static_cast<float>(done) /
                    static_cast<float>(trackCount),
                    std::memory_order_relaxed);
            }
        }, 4);

    for (uint8_t ok : trackOk) {
        if (!ok) {
            munmap(map, fileSize);
            LOGE("parseMidi: parallel track parse diverged from pre-scan");
            return MidiData{};
        }
    }

    const uint64_t tracksDoneUs = parserMonoUs();

    // Rebuild the tempo input in exactly the old track-major discovery order.
    std::vector<TempoEvent> tempos;
    size_t tempoCount = 0;
    for (const MidiTrackPlan& p : scan->tracks) tempoCount += p.tempos.size();
    tempos.reserve(tempoCount);
    for (const MidiTrackPlan& p : scan->tracks)
        for (const MidiTempoPoint& te : p.tempos)
            tempos.push_back({te.tick, te.usPerQuarter});

    std::sort(tempos.begin(), tempos.end(),
              [](const TempoEvent& a, const TempoEvent& b) {
                  return a.tick < b.tick;
              });
    std::vector<TempoSeg> segs;
    segs.push_back({0u, 0ull, 500000u});
    for (const TempoEvent& te : tempos) {
        TempoSeg& last = segs.back();
        if (te.tick <= last.tick) {
            last.usPerQuarter = te.usPerQuarter;
            continue;
        }
        const uint64_t us = last.usAtTick +
            uint64_t(te.tick - last.tick) * last.usPerQuarter /
            scan->ticksPerQuarter;
        segs.push_back({te.tick, us, te.usPerQuarter});
    }
    auto tickToUs = [&](uint32_t tick) -> uint64_t {
        size_t lo = 0, hi = segs.size();
        while (lo + 1 < hi) {
            const size_t mid = (lo + hi) / 2;
            if (segs[mid].tick <= tick) lo = mid;
            else hi = mid;
        }
        const TempoSeg& sg = segs[lo];
        return sg.usAtTick +
            uint64_t(tick - sg.tick) * sg.usPerQuarter /
            scan->ticksPerQuarter;
    };

    progress = 0.54f;
    uint64_t totalUs = 0;
    std::array<uint64_t, 4> localMaxUs{};
    const unsigned convertWorkers = parallelForRanges(
        poolN, 262144,
        [&](size_t begin, size_t end, unsigned worker) {
            uint64_t localMax = 0;
            for (size_t i = begin; i < end; ++i) {
                PlayEvent& e = out.eventPool[i];
                uint64_t us = tickToUs(static_cast<uint32_t>(e.absMicroSec));
                if (us > 0xFFFFFFFFull) us = 0xFFFFFFFFull;
                e.absMicroSec = static_cast<uint32_t>(us);
                if (us > localMax) localMax = us;
            }
            localMaxUs[worker] = localMax;
        }, 4);
    for (unsigned w = 0; w < convertWorkers; ++w)
        if (localMaxUs[w] > totalUs) totalUs = localMaxUs[w];

    parallelForRanges(
        poolN, 262144,
        [&](size_t begin, size_t end, unsigned) {
            for (size_t i = begin; i < end; ++i) {
                PlayEvent& e = out.eventPool[i];
                if (e.isNoteOn()) {
                    e.link = e.link < poolN
                        ? out.eventPool[e.link].absMicroSec
                        : e.absMicroSec;
                } else if (e.isNoteOff()) {
                    e.link = kNoEventLink;
                }
            }
        }, 4);
    progress = 0.70f;
    const uint64_t convertDoneUs = parserMonoUs();

    // K-way merge the already-time-monotonic track slices directly into the
    // FINAL events[] array. No global comparison sort and no second N-sized
    // pointer buffer. Within one (track,time) group we use two linear passes:
    // count event types, then write each pointer into its final type bucket.
    // That exactly reproduces: time -> track -> eventType descending -> parse
    // order, while eventPool itself stays in original track-major parse order.
    out.events.resize(poolN);
    struct Head {
        uint32_t time;
        uint32_t track;
    };
    struct HeadGreater {
        bool operator()(const Head& a, const Head& b) const {
            if (a.time != b.time) return a.time > b.time;
            return a.track > b.track;
        }
    };
    std::priority_queue<Head, std::vector<Head>, HeadGreater> heap;
    std::vector<size_t> cursor(trackCount);
    for (size_t t = 0; t < trackCount; ++t) {
        cursor[t] = trackStart[t];
        if (cursor[t] < trackStart[t + 1])
            heap.push(Head{
                out.eventPool[cursor[t]].absMicroSec,
                static_cast<uint32_t>(t)
            });
    }

    size_t outPos = 0;
    while (!heap.empty()) {
        const Head h = heap.top();
        heap.pop();
        const size_t t = h.track;
        const size_t begin = cursor[t];
        const size_t endTrack = trackStart[t + 1];
        size_t groupEnd = begin + 1;
        while (groupEnd < endTrack &&
               out.eventPool[groupEnd].absMicroSec == h.time)
            ++groupEnd;

        size_t counts[16] = {};
        for (size_t i = begin; i < groupEnd; ++i)
            counts[out.eventPool[i].channelEventType & 15]++;

        size_t next[16] = {};
        size_t bucket = outPos;
        for (int type = 15; type >= 0; --type) {
            next[type] = bucket;
            bucket += counts[type];
        }
        for (size_t i = begin; i < groupEnd; ++i) {
            const uint8_t type = out.eventPool[i].channelEventType & 15;
            out.events[next[type]++] = &out.eventPool[i];
        }
        outPos = bucket;
        cursor[t] = groupEnd;
        if (groupEnd < endTrack)
            heap.push(Head{
                out.eventPool[groupEnd].absMicroSec,
                static_cast<uint32_t>(t)
            });
    }
    if (outPos != poolN) {
        munmap(map, fileSize);
        LOGE("parseMidi: k-way merge emitted %zu/%zu events", outPos, poolN);
        return MidiData{};
    }
    progress = 0.88f;
    const uint64_t mergeDoneUs = parserMonoUs();

    for (size_t i = 0; i < out.events.size(); ++i) {
        if (out.events[i]->isProgramChange() ||
            out.events[i]->isController() ||
            out.events[i]->isPitchBend())
            out.programChangeIdx.push_back(i);
    }

    out.trackCount = static_cast<int>(trackCount);
    out.trackColors.assign(trackCount * 16, 0xFFFFFFFFu);
    uint32_t palette[16];
    defaultPalettePFA(palette);
    srand(static_cast<unsigned>(time(nullptr)));
    int colorPos = 0;
    for (size_t trk = 0; trk < trackCount; ++trk) {
        const uint16_t mask = scan->tracks[trk].hasNotesMask;
        for (int ch = 0; ch < 16; ++ch) {
            if ((mask & static_cast<uint16_t>(1u << ch)) == 0) continue;
            out.trackColors[trk * 16 + static_cast<size_t>(ch)] =
                colorPos < 16 ? palette[colorPos] : randColorPFA();
            ++colorPos;
        }
    }

    out.minNote = 0;
    out.maxNote = 127;
    out.totalUs =
        static_cast<uint32_t>(std::min<uint64_t>(totalUs, 0xFFFFFFFFull));
    out.valid = !out.eventPool.empty();
    progress = 1.0f;

    munmap(map, fileSize);
    LOGI("parseMidi: workers parse=%u convert=%u | track parse %.1f ms | "
         "convert/link %.1f ms | k-way merge %.1f ms | total %.1f ms",
         parseWorkers, convertWorkers,
         (tracksDoneUs - parseStartUs) / 1000.0,
         (convertDoneUs - tracksDoneUs) / 1000.0,
         (mergeDoneUs - convertDoneUs) / 1000.0,
         (parserMonoUs() - parseStartUs) / 1000.0);
    LOGI("parseMidi: %zu notes, %zu events, %d tracks, %.1f s, %.1f MB",
         out.noteCount(), out.eventPool.size(), out.trackCount,
         out.totalUs / 1e6, out.memoryBytes() / 1048576.0);
    return out;
}

}  // namespace apfa