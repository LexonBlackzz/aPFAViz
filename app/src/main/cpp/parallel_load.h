#pragma once

#include <algorithm>
#include <cstddef>
#include <thread>
#include <vector>

namespace apfa {

// Loading is bursty and short-lived. Four workers gives modern phones useful
// multicore speedup without waking every little core or multiplying temporary
// sort buffers too aggressively on older Android devices.
inline unsigned chooseLoadWorkers(std::size_t items,
                                  std::size_t minItemsPerWorker = 262144,
                                  unsigned maxWorkers = 4) {
    if (items < minItemsPerWorker * 2) return 1;
    unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 2;
    unsigned byWork = static_cast<unsigned>(
        (items + minItemsPerWorker - 1) / minItemsPerWorker);
    unsigned workers = std::min(maxWorkers, std::min(hw, byWork));
    return workers < 1 ? 1 : workers;
}

template <class Fn>
unsigned parallelForRanges(std::size_t count,
                           std::size_t minItemsPerWorker,
                           Fn&& fn,
                           unsigned maxWorkers = 4) {
    const unsigned workers =
        chooseLoadWorkers(count, minItemsPerWorker, maxWorkers);
    if (workers <= 1) {
        fn(0, count, 0u);
        return 1;
    }

    std::vector<std::thread> threads;
    threads.reserve(workers - 1);

    auto beginOf = [=](unsigned w) -> std::size_t {
        return (count * static_cast<std::size_t>(w)) / workers;
    };

    for (unsigned w = 1; w < workers; ++w) {
        const std::size_t begin = beginOf(w);
        const std::size_t end = beginOf(w + 1);
        threads.emplace_back([&, begin, end, w] {
            fn(begin, end, w);
        });
    }

    fn(beginOf(0), beginOf(1), 0u);
    for (std::thread& t : threads) t.join();
    return workers;
}

// Sort disjoint ranges concurrently, then merge them in deterministic rounds.
// std::inplace_merge may request a temporary buffer for the current merge, but
// merges run serially so there is never one large merge buffer per worker.
template <class RandomIt, class Compare>
unsigned parallelSort(RandomIt first,
                      RandomIt last,
                      Compare comp,
                      std::size_t minItemsPerWorker = 524288,
                      unsigned maxWorkers = 4) {
    using Diff = typename std::iterator_traits<RandomIt>::difference_type;
    const Diff diff = last - first;
    if (diff <= 1) return 1;
    const std::size_t count = static_cast<std::size_t>(diff);
    const unsigned workers =
        chooseLoadWorkers(count, minItemsPerWorker, maxWorkers);

    if (workers <= 1) {
        std::sort(first, last, comp);
        return 1;
    }

    auto bound = [&](unsigned w) -> RandomIt {
        const std::size_t off =
            (count * static_cast<std::size_t>(w)) / workers;
        return first + static_cast<Diff>(off);
    };

    std::vector<std::thread> threads;
    threads.reserve(workers - 1);
    for (unsigned w = 1; w < workers; ++w) {
        RandomIt a = bound(w);
        RandomIt b = bound(w + 1);
        threads.emplace_back([=, &comp] {
            std::sort(a, b, comp);
        });
    }
    std::sort(bound(0), bound(1), comp);
    for (std::thread& t : threads) t.join();

    for (unsigned width = 1; width < workers; width *= 2) {
        for (unsigned i = 0; i + width < workers; i += width * 2) {
            const unsigned midW = i + width;
            const unsigned endW = std::min<unsigned>(i + width * 2, workers);
            std::inplace_merge(bound(i), bound(midW), bound(endW), comp);
        }
    }
    return workers;
}

}  // namespace apfa
