#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <cfloat>
#include <set>
#include <random>
#include <utility>

static const int LUT_SIZE = 33;
static const int TOTAL_NODES = LUT_SIZE * LUT_SIZE * LUT_SIZE;
// MLS 邻域半径（网格单元数）：拟合一个空洞时参考多大局部区域的数据
static const int MLS_R = 6;
// MLS 二次多项式基项数：1, r, g, b, r², g², b², rg, rb, gb
static const int MLS_BASIS = 10;
// MLS 单点近邻上限（超出按距离截断）
static const int MLS_MAX_NEIGHBORS = 200;
// 误差评估的加权蓄水池容量（~8000 个均匀样本）
static const int EVAL_K = 8000;

// 26 邻域偏移（3×3×3 立方体去掉自身）及其欧氏距离权重 1/dist
static const int NEIGHBOR_DX[26] = {
    1,-1,0,0,0,0, 1,1,-1,-1, 1,1,-1,-1, 0,0,0,0, 1,1,1,1,-1,-1,-1,-1
};
static const int NEIGHBOR_DY[26] = {
    0,0,1,-1,0,0, 1,-1,1,-1, 0,0,0,0, 1,1,-1,-1, 1,1,-1,-1, 1,1,-1,-1
};
static const int NEIGHBOR_DZ[26] = {
    0,0,0,0,1,-1, 0,0,0,0, 1,-1,1,-1, 1,-1,1,-1, 1,-1,1,-1, 1,-1,1,-1
};
static const float NEIGHBOR_W[26] = {
    1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
    0.70710678f, 0.70710678f, 0.70710678f, 0.70710678f,
    0.70710678f, 0.70710678f, 0.70710678f, 0.70710678f,
    0.70710678f, 0.70710678f, 0.70710678f, 0.70710678f,
    0.57735027f, 0.57735027f, 0.57735027f, 0.57735027f,
    0.57735027f, 0.57735027f, 0.57735027f, 0.57735027f
};

struct Vec3 { float r, g, b; };

// 误差评估的加权样本（按频次加权，Efraimidis–Spirakis 保留 key 最大的 EVAL_K 个）
struct WeightedSample {
    float key;              // = u^(1/count)，count 越大 key 越接近 1，越易入选
    float r, g, b;          // 输入色
    float actR, actG, actB; // 滤镜真实输出色
};
struct KeyCmp {
    bool operator()(const WeightedSample& a, const WeightedSample& b) const {
        return a.key < b.key;
    }
};

static inline float clamp01(float x) {
    return fmaxf(0.0f, fminf(1.0f, x));
}

// 线性基（MLS 点数不足时降级用）：1, r, g, b
static void linBasis(float r, float g, float b, float* out) {
    out[0] = 1.0f; out[1] = r; out[2] = g; out[3] = b;
}

// 二次基（MLS 用）：1, r, g, b, r², g², b², rg, rb, gb
static void quadBasis(float r, float g, float b, float* out) {
    out[0] = 1.0f;
    out[1] = r; out[2] = g; out[3] = b;
    out[4] = r * r; out[5] = g * g; out[6] = b * b;
    out[7] = r * g; out[8] = r * b; out[9] = g * b;
}

// 高斯消元（列主元）求解 n×n 线性方程组，A 对称行主序，原地修改 b 为解
static bool gaussSolve(int n, float* A, float* b) {
    float* m = new float[n * (n + 1)];
    for (int i = 0; i < n; ++i)
        for (int j = 0; j < n; ++j)
            m[i * (n + 1) + j] = A[j * n + i];
    for (int i = 0; i < n; ++i)
        m[i * (n + 1) + n] = b[i];

    for (int col = 0; col < n; ++col) {
        int pivot = col;
        float maxV = fabsf(m[col * (n + 1) + col]);
        for (int row = col + 1; row < n; ++row) {
            float v = fabsf(m[row * (n + 1) + col]);
            if (v > maxV) { maxV = v; pivot = row; }
        }
        if (maxV < 1e-12f) { delete[] m; return false; }
        if (pivot != col) {
            for (int k = col; k <= n; ++k)
                std::swap(m[col * (n + 1) + k], m[pivot * (n + 1) + k]);
        }
        float piv = m[col * (n + 1) + col];
        for (int row = col + 1; row < n; ++row) {
            float factor = m[row * (n + 1) + col] / piv;
            for (int k = col; k <= n; ++k)
                m[row * (n + 1) + k] -= factor * m[col * (n + 1) + k];
        }
    }

    for (int i = n - 1; i >= 0; --i) {
        float sum = m[i * (n + 1) + n];
        for (int j = i + 1; j < n; ++j)
            sum -= m[i * (n + 1) + j] * b[j];
        b[i] = sum / m[i * (n + 1) + i];
    }
    delete[] m;
    return true;
}

// ── MLS 局部拟合：以查询点 (r,g,b) 为原点的局部坐标系里，
//    用近邻点二次多项式拟合局部函数，多项式在原点处的值即预测输出色。
//    邻域 R 已限定范围，区域内各近邻点等权参与，不再做距离加权 ──
static void mlsFitPts(const std::vector<Vec3>& in, const std::vector<Vec3>& outv,
                      float r, float g, float b, Vec3* result) {
    int n = (int)in.size();
    bool quad = (n >= MLS_BASIS);
    int B = quad ? MLS_BASIS : 4;

    const float rho = (float)MLS_R / (float)(LUT_SIZE - 1);
    const float rho2 = rho * rho;
    float sc[MLS_BASIS] = {
        1.0f, rho, rho, rho,
        rho2, rho2, rho2, rho2, rho2, rho2
    };

    float ATA[MLS_BASIS * MLS_BASIS] = {0};
    float bR[MLS_BASIS] = {0}, bG[MLS_BASIS] = {0}, bB[MLS_BASIS] = {0};
    for (int k = 0; k < n; ++k) {
        float dx = in[k].r - r, dy = in[k].g - g, dz = in[k].b - b;
        float fb[MLS_BASIS];
        if (quad) quadBasis(dx, dy, dz, fb);
        else      linBasis(dx, dy, dz, fb);
        for (int i = 0; i < B; ++i) fb[i] /= sc[i];
        for (int i = 0; i < B; ++i) {
            for (int j = 0; j < B; ++j)
                ATA[i * B + j] += fb[i] * fb[j];
            bR[i] += fb[i] * outv[k].r;
            bG[i] += fb[i] * outv[k].g;
            bB[i] += fb[i] * outv[k].b;
        }
    }
    float regTrace = 0.0f;
    for (int i = 0; i < B; ++i) regTrace += ATA[i * B + i];
    float reg = 0.03f * regTrace / (float)B;
    for (int i = 0; i < B; ++i) ATA[i * B + i] += reg;

    float solR[MLS_BASIS], solG[MLS_BASIS], solB[MLS_BASIS];
    float Ac[MLS_BASIS * MLS_BASIS];
    memcpy(Ac, ATA, sizeof(float) * B * B);
    memcpy(solR, bR, sizeof(float) * B); gaussSolve(B, Ac, solR);
    memcpy(Ac, ATA, sizeof(float) * B * B);
    memcpy(solG, bG, sizeof(float) * B); gaussSolve(B, Ac, solG);
    memcpy(Ac, ATA, sizeof(float) * B * B);
    memcpy(solB, bB, sizeof(float) * B); gaussSolve(B, Ac, solB);

    result->r = solR[0];
    result->g = solG[0];
    result->b = solB[0];
}

// ── 从 LUT 网格三线性采样一个颜色 ──
static void sampleLut(const std::vector<Vec3>& lut, float r, float g, float b, Vec3* out) {
    float fx = r * (LUT_SIZE - 1), fy = g * (LUT_SIZE - 1), fz = b * (LUT_SIZE - 1);
    int x0 = (int)floorf(fx), y0 = (int)floorf(fy), z0 = (int)floorf(fz);
    int x1 = std::min(x0 + 1, LUT_SIZE - 1);
    int y1 = std::min(y0 + 1, LUT_SIZE - 1);
    int z1 = std::min(z0 + 1, LUT_SIZE - 1);
    float dx = fx - x0, dy = fy - y0, dz = fz - z0;

    int i000 = x0 + y0 * LUT_SIZE + z0 * LUT_SIZE * LUT_SIZE;
    int i100 = x1 + y0 * LUT_SIZE + z0 * LUT_SIZE * LUT_SIZE;
    int i010 = x0 + y1 * LUT_SIZE + z0 * LUT_SIZE * LUT_SIZE;
    int i110 = x1 + y1 * LUT_SIZE + z0 * LUT_SIZE * LUT_SIZE;
    int i001 = x0 + y0 * LUT_SIZE + z1 * LUT_SIZE * LUT_SIZE;
    int i101 = x1 + y0 * LUT_SIZE + z1 * LUT_SIZE * LUT_SIZE;
    int i011 = x0 + y1 * LUT_SIZE + z1 * LUT_SIZE * LUT_SIZE;
    int i111 = x1 + y1 * LUT_SIZE + z1 * LUT_SIZE * LUT_SIZE;

    out->r = (lut[i000].r * (1 - dx) + lut[i100].r * dx) * (1 - dy) +
             (lut[i010].r * (1 - dx) + lut[i110].r * dx) * dy;
    out->g = (lut[i000].g * (1 - dx) + lut[i100].g * dx) * (1 - dy) +
             (lut[i010].g * (1 - dx) + lut[i110].g * dx) * dy;
    out->b = (lut[i000].b * (1 - dx) + lut[i100].b * dx) * (1 - dy) +
             (lut[i010].b * (1 - dx) + lut[i110].b * dx) * dy;

    Vec3 top = {lut[i001].r * (1 - dx) + lut[i101].r * dx,
                lut[i001].g * (1 - dx) + lut[i101].g * dx,
                lut[i001].b * (1 - dx) + lut[i101].b * dx};
    top.r = top.r * (1 - dy) + (lut[i011].r * (1 - dx) + lut[i111].r * dx) * dy;
    top.g = top.g * (1 - dy) + (lut[i011].g * (1 - dx) + lut[i111].g * dx) * dy;
    top.b = top.b * (1 - dy) + (lut[i011].b * (1 - dx) + lut[i111].b * dx) * dy;

    out->r = out->r * (1 - dz) + top.r * dz;
    out->g = out->g * (1 - dz) + top.g * dz;
    out->b = out->b * (1 - dz) + top.b * dz;
}

// ── 学习会话：跨多次 addChunk 持久累积，内存与像素总数 N 无关 ──
struct LutSession {
    std::vector<Vec3>  outVal;   // TOTAL_NODES 体素累计输出色
    std::vector<float> wsum;     // TOTAL_NODES 体素累计权重（含频次）
    std::vector<char>  filled;   // TOTAL_NODES 已覆盖标记
    std::set<WeightedSample, KeyCmp> top;   // 加权蓄水池（key 最大 EVAL_K 个）
    std::mt19937 rng;

    LutSession() : outVal(TOTAL_NODES, {0, 0, 0}),
                   wsum(TOTAL_NODES, 0.0f), filled(TOTAL_NODES, 0),
                   rng(123456789u) {
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_classic_camera_LutEngine_createSession(JNIEnv*, jclass) {
    LutSession* s = new LutSession();
    return (jlong)s;
}

// 查询当前会话已覆盖（有样本落入）的 LUT 体素数，用于 UI 展示覆盖率；不改动会话。
extern "C" JNIEXPORT jint JNICALL
Java_com_classic_camera_LutEngine_coveredNodes(JNIEnv*, jclass, jlong ptr) {
    LutSession* s = (LutSession*)ptr;
    int cnt = 0;
    for (int i = 0; i < TOTAL_NODES; ++i)
        if (s->wsum[i] > 0.0f) ++cnt;
    return (jint)cnt;
}

// ── 分块喂入：每批唯一色 (orig, 平均滤镜色, 频次)，
//    散播进体素（权重 × 频次，实现频率加权），并做加权蓄水池抽样 ──
extern "C" JNIEXPORT void JNICALL
Java_com_classic_camera_LutEngine_addChunk(
    JNIEnv* env, jclass,
    jlong ptr, jintArray origArr, jintArray filtArr, jintArray cntArr,
    jint offset, jint num) {
    LutSession* s = (LutSession*)ptr;
    jint* orig = env->GetIntArrayElements(origArr, nullptr);
    jint* filt = env->GetIntArrayElements(filtArr, nullptr);
    jint* cnt  = env->GetIntArrayElements(cntArr, nullptr);

    std::uniform_real_distribution<float> ud(1e-9f, 1.0f);
    for (int i = 0; i < num; ++i) {
        int ii = offset + i;
        int r = (orig[ii] >> 16) & 0xFF, g = (orig[ii] >> 8) & 0xFF, b = orig[ii] & 0xFF;
        int fR = (filt[ii] >> 16) & 0xFF, fG = (filt[ii] >> 8) & 0xFF, fB = filt[ii] & 0xFF;
        int c = cnt[ii];
        if (c <= 0) continue;
        float pr = r / 255.0f, pg = g / 255.0f, pb = b / 255.0f;
        float actR = fR / 255.0f, actG = fG / 255.0f, actB = fB / 255.0f;

        // 三线性散播，权重 × count（频率加权：出现越多的颜色影响越大）
        float fx = pr * (LUT_SIZE - 1), fy = pg * (LUT_SIZE - 1), fz = pb * (LUT_SIZE - 1);
        int x0 = (int)floorf(fx), y0 = (int)floorf(fy), z0 = (int)floorf(fz);
        int x1 = std::min(x0 + 1, LUT_SIZE - 1);
        int y1 = std::min(y0 + 1, LUT_SIZE - 1);
        int z1 = std::min(z0 + 1, LUT_SIZE - 1);
        float wx = fx - x0, wy = fy - y0, wz = fz - z0;
        for (int dz = 0; dz < 2; ++dz)
            for (int dy = 0; dy < 2; ++dy)
                for (int dx = 0; dx < 2; ++dx) {
                    int idx = (dx ? x1 : x0) + (dy ? y1 : y0) * LUT_SIZE + (dz ? z1 : z0) * LUT_SIZE * LUT_SIZE;
                    float w = (dx ? wx : 1.0f - wx) * (dy ? wy : 1.0f - wy) * (dz ? wz : 1.0f - wz) * (float)c;
                    s->outVal[idx].r += w * actR;
                    s->outVal[idx].g += w * actG;
                    s->outVal[idx].b += w * actB;
                    s->wsum[idx] += w;
                }

        // 加权蓄水池（Efraimidis–Spirakis）：key = u^(1/count)，保留 key 最大 EVAL_K 个
        float key = std::pow(ud(s->rng), 1.0f / (float)c);
        s->top.insert({key, pr, pg, pb, actR, actG, actB});
        if ((int)s->top.size() > EVAL_K) s->top.erase(s->top.begin());
    }

    env->ReleaseIntArrayElements(origArr, orig, 0);
    env->ReleaseIntArrayElements(filtArr, filt, 0);
    env->ReleaseIntArrayElements(cntArr, cnt, 0);
}

// ── 结算：归一化 → MLS 动态优先填充 → 高斯平滑 → 输出 LUT + 误差统计 ──
extern "C" JNIEXPORT jfloat JNICALL
Java_com_classic_camera_LutEngine_finalize(
    JNIEnv* env, jclass, jlong ptr, jfloatArray out_lut_array, jfloatArray out_stats) {
    LutSession* s = (LutSession*)ptr;
    std::vector<char>& filled = s->filled;

    // 1. 归一化体素（按含频次的权重平均），标记已覆盖
    for (int idx = 0; idx < TOTAL_NODES; ++idx) {
        if (s->wsum[idx] > 0.0f) {
            s->outVal[idx].r /= s->wsum[idx];
            s->outVal[idx].g /= s->wsum[idx];
            s->outVal[idx].b /= s->wsum[idx];
            filled[idx] = 1;
        }
    }

    // 预取坐标
    std::vector<int> cx(TOTAL_NODES), cy(TOTAL_NODES), cz(TOTAL_NODES);
    for (int idx = 0; idx < TOTAL_NODES; ++idx) {
        cx[idx] = idx % LUT_SIZE;
        cy[idx] = (idx / LUT_SIZE) % LUT_SIZE;
        cz[idx] = idx / (LUT_SIZE * LUT_SIZE);
    }
    auto idxOf = [](int x, int y, int z) {
        return x + y * LUT_SIZE + z * LUT_SIZE * LUT_SIZE;
    };

    // 2. 动态优先填充：26 邻域已填充近邻的加权和越大越优先
    std::vector<float> score(TOTAL_NODES, 0.0f);
    std::set<std::pair<float, int>> cand;
    auto computeScore = [&](int idx) {
        float sc = 0.0f;
        int x = cx[idx], y = cy[idx], z = cz[idx];
        for (int k = 0; k < 26; ++k) {
            int nx = x + NEIGHBOR_DX[k], ny = y + NEIGHBOR_DY[k], nz = z + NEIGHBOR_DZ[k];
            if (nx < 0 || nx >= LUT_SIZE || ny < 0 || ny >= LUT_SIZE || nz < 0 || nz >= LUT_SIZE) continue;
            if (filled[idxOf(nx, ny, nz)]) sc += NEIGHBOR_W[k];
        }
        return sc;
    };
    for (int idx = 0; idx < TOTAL_NODES; ++idx)
        if (!filled[idx]) { score[idx] = computeScore(idx); cand.insert({score[idx], idx}); }

    const int R = MLS_R;
    const float cellW = 1.0f / (float)(LUT_SIZE - 1);
    int fitQuadCount = 0, fitLinCount = 0;
    while (!cand.empty()) {
        auto it = cand.end(); --it;
        int idx = it->second;
        cand.erase(it);
        int x = cx[idx], y = cy[idx], z = cz[idx];

        std::vector<Vec3> in, outv;
        in.reserve(128); outv.reserve(128);
        for (int dz = -R; dz <= R; ++dz) {
            int nz = z + dz; if (nz < 0 || nz >= LUT_SIZE) continue;
            for (int dy = -R; dy <= R; ++dy) {
                int ny = y + dy; if (ny < 0 || ny >= LUT_SIZE) continue;
                for (int dx = -R; dx <= R; ++dx) {
                    int nx = x + dx; if (nx < 0 || nx >= LUT_SIZE) continue;
                    int ni = idxOf(nx, ny, nz);
                    if (!filled[ni]) continue;
                    if (in.size() < (size_t)MLS_MAX_NEIGHBORS) {
                        in.push_back({nx * cellW, ny * cellW, nz * cellW});
                        outv.push_back(s->outVal[ni]);
                    }
                }
            }
        }

        Vec3 result;
        if ((int)in.size() >= MLS_BASIS) fitQuadCount++; else fitLinCount++;
        mlsFitPts(in, outv, x * cellW, y * cellW, z * cellW, &result);
        s->outVal[idx] = {clamp01(result.r), clamp01(result.g), clamp01(result.b)};
        filled[idx] = 1;

        for (int k = 0; k < 26; ++k) {
            int nx = x + NEIGHBOR_DX[k], ny = y + NEIGHBOR_DY[k], nz = z + NEIGHBOR_DZ[k];
            if (nx < 0 || nx >= LUT_SIZE || ny < 0 || ny >= LUT_SIZE || nz < 0 || nz >= LUT_SIZE) continue;
            int ni = idxOf(nx, ny, nz);
            if (filled[ni]) continue;
            cand.erase({score[ni], ni});
            score[ni] += NEIGHBOR_W[k];
            cand.insert({score[ni], ni});
        }
    }

    // 3. 多轮高斯平滑：种子体素保留真实值，梯度逐步扩散到空洞
    std::vector<char> isSeed(TOTAL_NODES, 0);
    for (int i = 0; i < TOTAL_NODES; ++i)
        if (s->wsum[i] > 0.0f) isSeed[i] = 1;
    const int SMOOTH_PASSES = 3;
    std::vector<Vec3> smoothBuf(TOTAL_NODES);
    for (int pass = 0; pass < SMOOTH_PASSES; ++pass) {
        for (int i = 0; i < TOTAL_NODES; ++i) {
            if (isSeed[i]) continue;
            int x = i % LUT_SIZE, y = (i / LUT_SIZE) % LUT_SIZE, z = i / (LUT_SIZE * LUT_SIZE);
            Vec3 a = {0, 0, 0}; float ws = 0.0f;
            for (int k = 0; k < 26; ++k) {
                int nx = x + NEIGHBOR_DX[k], ny = y + NEIGHBOR_DY[k], nz = z + NEIGHBOR_DZ[k];
                if (nx < 0 || nx >= LUT_SIZE || ny < 0 || ny >= LUT_SIZE || nz < 0 || nz >= LUT_SIZE) continue;
                int ni = nx + ny * LUT_SIZE + nz * LUT_SIZE * LUT_SIZE;
                float w = NEIGHBOR_W[k];
                a.r += w * s->outVal[ni].r; a.g += w * s->outVal[ni].g; a.b += w * s->outVal[ni].b;
                ws += w;
            }
            if (ws > 0.0f)
                smoothBuf[i] = {clamp01(a.r / ws), clamp01(a.g / ws), clamp01(a.b / ws)};
        }
        for (int i = 0; i < TOTAL_NODES; ++i)
            if (!isSeed[i]) s->outVal[i] = smoothBuf[i];
    }

    // 4. 输出 LUT
    jsize outLen = env->GetArrayLength(out_lut_array);
    std::vector<jfloat> lutOut(outLen);
    for (int i = 0; i < TOTAL_NODES; ++i) {
        lutOut[i * 3]     = s->outVal[i].r;
        lutOut[i * 3 + 1] = s->outVal[i].g;
        lutOut[i * 3 + 2] = s->outVal[i].b;
    }
    env->SetFloatArrayRegion(out_lut_array, 0, outLen, lutOut.data());

    // 5. 误差评估（加权蓄水池样本）
    float sum = 0.0f, mx = 0.0f;
    int worstR = 0, worstG = 0, worstB = 0;
    int cnt = (int)s->top.size();
    for (auto it = s->top.begin(); it != s->top.end(); ++it) {
        Vec3 pred; sampleLut(s->outVal, it->r, it->g, it->b, &pred);
        float err = fmaxf(fabsf(pred.r - it->actR),
                    fmaxf(fabsf(pred.g - it->actG), fabsf(pred.b - it->actB)));
        sum += err;
        if (err > mx) { mx = err;
            worstR = (int)(it->r * 255.0f + 0.5f);
            worstG = (int)(it->g * 255.0f + 0.5f);
            worstB = (int)(it->b * 255.0f + 0.5f);
        }
    }
    float avg = cnt ? sum / cnt : 0.0f;

    jfloat stats[9] = {avg, avg, mx, mx, (float)worstR, (float)worstG, (float)worstB,
                       (float)fitQuadCount, (float)fitLinCount};
    env->SetFloatArrayRegion(out_stats, 0, 9, stats);

    delete s;
    return mx;
}