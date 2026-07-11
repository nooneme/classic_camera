#ifndef ALIGN_MERGE_H
#define ALIGN_MERGE_H

#define _USE_MATH_DEFINES
#include <cstdint>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <jni.h>

constexpr int T_SIZE = 32;
constexpr int T_SIZE_2 = 16;
constexpr int DOWNSAMPLE_RATE = 4;
constexpr int SEARCH_RADIUS = 4;
constexpr int MIN_OFFSET = -168;
constexpr int MAX_OFFSET = 126;

struct Pyramid {
    std::vector<uint16_t> layer0;
    std::vector<uint16_t> layer1;
    std::vector<uint16_t> layer2;
    int w0, h0;
    int w1, h1;
    int w2, h2;
};

static inline void box_down2(const uint16_t* src, int sw, int sh,
                             std::vector<uint16_t>& dst, int& dw, int& dh) {
    dw = (sw + 1) / 2;
    dh = (sh + 1) / 2;
    dst.resize(dw * dh);
    for (int oy = 0; oy < dh; oy++) {
        for (int ox = 0; ox < dw; ox++) {
            int sx = ox * 2, sy = oy * 2;
            int sum = 0, cnt = 0;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int ix = sx + dx, iy = sy + dy;
                    if (ix < sw && iy < sh) {
                        sum += src[iy * sw + ix];
                        cnt++;
                    }
                }
            }
            dst[oy * dw + ox] = (cnt > 0) ? (sum / cnt) : 0;
        }
    }
}

static inline void gauss_down4(const uint16_t* src, int sw, int sh,
                               std::vector<uint16_t>& dst, int& dw, int& dh) {
    const int kernel[5][5] = {
        {1, 4, 6, 4, 1},
        {4, 16, 24, 16, 4},
        {6, 24, 36, 24, 6},
        {4, 16, 24, 16, 4},
        {1, 4, 6, 4, 1}
    };
    const int KSUM = 256;

    dw = (sw + 3) / 4;
    dh = (sh + 3) / 4;
    dst.resize(dw * dh);
    for (int oy = 0; oy < dh; oy++) {
        for (int ox = 0; ox < dw; ox++) {
            int cx = ox * 4, cy = oy * 4;
            int sum = 0, total_w = 0;
            for (int ky = -2; ky <= 2; ky++) {
                for (int kx = -2; kx <= 2; kx++) {
                    int ix = cx + kx, iy = cy + ky;
                    if (ix >= 0 && ix < sw && iy >= 0 && iy < sh) {
                        int w = kernel[ky + 2][kx + 2];
                        sum += src[iy * sw + ix] * w;
                        total_w += w;
                    }
                }
            }
            dst[oy * dw + ox] = (total_w > 0) ? (sum / total_w) : 0;
        }
    }
}

static inline int clamp_offset(int v, int min_o, int max_o) {
    return std::max(min_o, std::min(max_o, v));
}

static inline int idx_im(int t, int i) {
    return t * T_SIZE_2 + i;
}

static inline int idx_layer(int t, int i) {
    return t * T_SIZE_2 / 2 + i;
}

static inline int tile_0(int e) {
    return e / T_SIZE_2 - 1;
}

static inline int tile_1(int e) {
    return e / T_SIZE_2;
}

static inline int tile_clamp(int t, int num_tiles) {
    return std::max(0, std::min(num_tiles - 1, t));
}

static inline int idx_0_inner(int e) {
    return e % T_SIZE_2 + T_SIZE_2;
}

static inline int idx_1_inner(int e) {
    return e % T_SIZE_2;
}

#endif
