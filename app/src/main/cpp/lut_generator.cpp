#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <cfloat>
#include <set>
#include <utility>

static const int LUT_SIZE = 33;
static const int TOTAL_NODES = LUT_SIZE * LUT_SIZE * LUT_SIZE;
// MLS 邻域半径（网格单元数）：拟合一个空洞时参考多大局部区域的数据
static const int MLS_R = 6;

// MLS 二次多项式基项数：1, r, g, b, r², g², b², rg, rb, gb
static const int MLS_BASIS = 10;
// MLS 单点近邻上限（超出按距离截断）
static const int MLS_MAX_NEIGHBORS = 200;
// 统计误差时的最大评估点数（过多时抽稀）
static const int EVAL_SAMPLE_LIMIT = 8000;

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

// 单个数据点：输入颜色 + 到滤镜图的偏移(dr,dg,db) + 权重(恒为1)
struct Point {
    float r, g, b;
    float dr, dg, db;
    float w;
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

// 从像素对构建数据点：每个像素一个点，记录颜色与到滤镜图的偏移
static int buildPoints(jint* orig, jint* filt, int num, std::vector<Point>& pts) {
    pts.reserve((size_t)num);

    for (int i = 0; i < num; ++i) {
        int r = (orig[i] >> 16) & 0xFF, g = (orig[i] >> 8) & 0xFF, b = orig[i] & 0xFF;
        int fR = (filt[i] >> 16) & 0xFF, fG = (filt[i] >> 8) & 0xFF, fB = filt[i] & 0xFF;
        float dr = (fR - r) / 255.0f, dg = (fG - g) / 255.0f, db = (fB - b) / 255.0f;
        pts.push_back({r / 255.0f, g / 255.0f, b / 255.0f, dr, dg, db, 1.0f});
    }
    return (int)pts.size();
}

// ── MLS 局部拟合：以查询点 (r,g,b) 为原点的局部坐标系里，
//    用近邻点二次多项式拟合局部函数，多项式在原点处的值即预测输出色。
//    邻域 R 已限定范围，区域内各近邻点等权参与，不再做距离加权 ──
static void mlsFitPts(const std::vector<Vec3>& in, const std::vector<Vec3>& outv,
                      float r, float g, float b, Vec3* result) {
    int n = (int)in.size();
    // 二次基有 10 个未知数，需 ≥10 个点才良定；点数不足时降级为线性基（4 未知数）
    bool quad = (n >= MLS_BASIS);
    int B = quad ? MLS_BASIS : 4;

    // 基函数按特征量级归一化（列缩放），使 ATA 对角线量级一致，
    // 统一小岭正则才能对各项均匀生效：常数=1，线性=ρ，二次=ρ²
    const float rho = (float)MLS_R / (float)(LUT_SIZE - 1);  // 邻域半径的色空间宽度
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
    // 岭正则：相对 ATA 迹施加（原 1e-6 绝对量对 n~100 的对角几乎无作用）。
    // 均匀作用于列归一基，等价于对二次项实际系数更重的收缩（1/rho²≈28×），
    // 抑制稀疏区域局部二次拟合的过冲/振荡。
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

    // 查询点即局部原点 (0,0,0)，基函数除常数项外全为 0 → 预测值 = 常数项系数
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

    // 沿 z 方向三线性插值
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

extern "C" JNIEXPORT jfloat JNICALL
Java_com_classic_camera_LutEngine_fitMlsLut(
    JNIEnv* env, jobject thiz,
    jintArray orig_pixels, jintArray filt_pixels,
    jint num_pixels, jfloatArray out_lut_array,
    jfloatArray out_stats) {

    jint* orig = env->GetIntArrayElements(orig_pixels, nullptr);
    jint* filt = env->GetIntArrayElements(filt_pixels, nullptr);

    std::vector<Point> pts;
    int nPts = buildPoints(orig, filt, num_pixels, pts);

    // ── 1. 种子：按三线性权重把样本散播到相邻体素（已覆盖体素保存真实输出色） ──
    std::vector<Vec3> outVal(TOTAL_NODES, {0, 0, 0});
    std::vector<float> wsum(TOTAL_NODES, 0.0f);
    std::vector<char> filled(TOTAL_NODES, 0);
    for (int i = 0; i < nPts; ++i) {
        const Point& p = pts[i];
        float fx = p.r * (LUT_SIZE - 1), fy = p.g * (LUT_SIZE - 1), fz = p.b * (LUT_SIZE - 1);
        int x0 = (int)floorf(fx), y0 = (int)floorf(fy), z0 = (int)floorf(fz);
        int x1 = std::min(x0 + 1, LUT_SIZE - 1);
        int y1 = std::min(y0 + 1, LUT_SIZE - 1);
        int z1 = std::min(z0 + 1, LUT_SIZE - 1);
        float wx = fx - x0, wy = fy - y0, wz = fz - z0;

        Vec3 val = {clamp01(p.r + p.dr), clamp01(p.g + p.dg), clamp01(p.b + p.db)};
        for (int dz = 0; dz < 2; ++dz) {
            for (int dy = 0; dy < 2; ++dy) {
                for (int dx = 0; dx < 2; ++dx) {
                    int idx = (dx ? x1 : x0) + (dy ? y1 : y0) * LUT_SIZE + (dz ? z1 : z0) * LUT_SIZE * LUT_SIZE;
                    float w = (dx ? wx : 1.0f - wx) * (dy ? wy : 1.0f - wy) * (dz ? wz : 1.0f - wz);
                    outVal[idx].r += w * val.r;
                    outVal[idx].g += w * val.g;
                    outVal[idx].b += w * val.b;
                    wsum[idx] += w * p.w;
                }
            }
        }
    }
    for (int idx = 0; idx < TOTAL_NODES; ++idx) {
        if (wsum[idx] > 0.0f) {
            outVal[idx].r /= wsum[idx];
            outVal[idx].g /= wsum[idx];
            outVal[idx].b /= wsum[idx];
            filled[idx] = 1;
        }
    }

    // ── 2. 动态优先级填充：26 邻域已填充近邻的欧氏距离加权和越大者越优先 ──
    // score[idx]：每个空洞体素当前的已填充近邻加权数（随填充动态更新）
    std::vector<float> score(TOTAL_NODES, 0.0f);
    // 候选集合：按 (加权近邻数, 索引) 排序，取最大者填充
    std::set<std::pair<float, int>> cand;
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

    // 计算某体素 26 邻域内已填充近邻的欧氏距离加权和
    auto computeScore = [&](int idx) {
        float s = 0.0f;
        int x = cx[idx], y = cy[idx], z = cz[idx];
        for (int k = 0; k < 26; ++k) {
            int nx = x + NEIGHBOR_DX[k], ny = y + NEIGHBOR_DY[k], nz = z + NEIGHBOR_DZ[k];
            if (nx < 0 || nx >= LUT_SIZE || ny < 0 || ny >= LUT_SIZE ||
                nz < 0 || nz >= LUT_SIZE) continue;
            if (filled[idxOf(nx, ny, nz)]) s += NEIGHBOR_W[k];
        }
        return s;
    };

    // 初始：所有空洞体素入候选集
    for (int idx = 0; idx < TOTAL_NODES; ++idx) {
        if (!filled[idx]) {
            score[idx] = computeScore(idx);
            cand.insert({score[idx], idx});
        }
    }

    // MLS 参数：邻域半径
    const int R = MLS_R;
    const float cellW = 1.0f / (float)(LUT_SIZE - 1);

    // ── 3. 迭代填充：每轮取加权近邻数最大的空洞做 MLS，填完更新其周围空洞的分数 ──
    // 统计二次 / 线性拟合的空洞数量（供 UI 展示百分比）
    int fitQuadCount = 0, fitLinCount = 0;
    while (!cand.empty()) {
        auto it = cand.end();
        --it;
        int idx = it->second;
        cand.erase(it);

        int x = cx[idx], y = cy[idx], z = cz[idx];

        std::vector<Vec3> in, outv;
        in.reserve(128); outv.reserve(128);
        for (int dz = -R; dz <= R; ++dz) {
            int nz = z + dz;
            if (nz < 0 || nz >= LUT_SIZE) continue;
            for (int dy = -R; dy <= R; ++dy) {
                int ny = y + dy;
                if (ny < 0 || ny >= LUT_SIZE) continue;
                for (int dx = -R; dx <= R; ++dx) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= LUT_SIZE) continue;
                    int ni = idxOf(nx, ny, nz);
                    if (!filled[ni]) continue;
                    if (in.size() < (size_t)MLS_MAX_NEIGHBORS) {
                        in.push_back({nx * cellW, ny * cellW, nz * cellW});
                        outv.push_back(outVal[ni]);
                    }
                }
            }
        }

        Vec3 result;
        if ((int)in.size() >= MLS_BASIS) fitQuadCount++; else fitLinCount++;
        mlsFitPts(in, outv, x * cellW, y * cellW, z * cellW, &result);
        outVal[idx] = {clamp01(result.r), clamp01(result.g), clamp01(result.b)};
        filled[idx] = 1;

        // 填充后更新 idx 周围 26 个仍为空洞的邻居的加权近邻数
        for (int k = 0; k < 26; ++k) {
            int nx = x + NEIGHBOR_DX[k], ny = y + NEIGHBOR_DY[k], nz = z + NEIGHBOR_DZ[k];
            if (nx < 0 || nx >= LUT_SIZE || ny < 0 || ny >= LUT_SIZE ||
                nz < 0 || nz >= LUT_SIZE) continue;
            int ni = idxOf(nx, ny, nz);
            if (filled[ni]) continue;
            cand.erase({score[ni], ni});
            score[ni] += NEIGHBOR_W[k];
            cand.insert({score[ni], ni});
        }
    }

    // ── 3.5 多轮高斯平滑：种子体素始终保留真实值，但其梯度经多轮
    //    26 邻域反距离加权平均（雅可比式）逐步扩散到空洞，抹平 MLS 残差 ──
    std::vector<char> isSeed(TOTAL_NODES, 0);
    for (int i = 0; i < TOTAL_NODES; ++i)
        if (wsum[i] > 0.0f) isSeed[i] = 1;

    const int SMOOTH_PASSES = 3;
    std::vector<Vec3> smoothBuf(TOTAL_NODES);
    for (int pass = 0; pass < SMOOTH_PASSES; ++pass) {
        for (int i = 0; i < TOTAL_NODES; ++i) {
            if (isSeed[i]) continue;
            int x = i % LUT_SIZE, y = (i / LUT_SIZE) % LUT_SIZE, z = i / (LUT_SIZE * LUT_SIZE);
            Vec3 a = {0, 0, 0}; float ws = 0.0f;
            for (int k = 0; k < 26; ++k) {
                int nx = x + NEIGHBOR_DX[k], ny = y + NEIGHBOR_DY[k], nz = z + NEIGHBOR_DZ[k];
                if (nx < 0 || nx >= LUT_SIZE || ny < 0 || ny >= LUT_SIZE ||
                    nz < 0 || nz >= LUT_SIZE) continue;
                int ni = nx + ny * LUT_SIZE + nz * LUT_SIZE * LUT_SIZE;
                float w = NEIGHBOR_W[k];
                a.r += w * outVal[ni].r; a.g += w * outVal[ni].g; a.b += w * outVal[ni].b;
                ws += w;
            }
            if (ws > 0.0f)
                smoothBuf[i] = {clamp01(a.r / ws), clamp01(a.g / ws), clamp01(a.b / ws)};
        }
        for (int i = 0; i < TOTAL_NODES; ++i)
            if (!isSeed[i]) outVal[i] = smoothBuf[i];
    }

    env->ReleaseIntArrayElements(orig_pixels, orig, 0);
    env->ReleaseIntArrayElements(filt_pixels, filt, 0);

    jsize outLen = env->GetArrayLength(out_lut_array);
    std::vector<jfloat> lutOut(outLen);
    for (int i = 0; i < TOTAL_NODES; ++i) {
        lutOut[i * 3]     = outVal[i].r;
        lutOut[i * 3 + 1] = outVal[i].g;
        lutOut[i * 3 + 2] = outVal[i].b;
    }
    env->SetFloatArrayRegion(out_lut_array, 0, (jsize)outLen, lutOut.data());

    // ── 4. 误差统计：用最终 LUT 三线性采样预测 vs 实际滤镜输出 ──
    float sum = 0.0f; int cnt = 0; float mx = 0.0f;
    int worstR = 0, worstG = 0, worstB = 0;
    int step = std::max(1, nPts / EVAL_SAMPLE_LIMIT);
    for (int i = 0; i < nPts; i += step) {
        const Point& p = pts[i];
        Vec3 pred;
        sampleLut(outVal, p.r, p.g, p.b, &pred);
        float actR = clamp01(p.r + p.dr);
        float actG = clamp01(p.g + p.dg);
        float actB = clamp01(p.b + p.db);
        float err = fmaxf(fabsf(pred.r - actR), fmaxf(fabsf(pred.g - actG), fabsf(pred.b - actB)));
        sum += err; ++cnt;
        if (err > mx) {
            mx = err;
            worstR = (int)(p.r * 255.0f + 0.5f);
            worstG = (int)(p.g * 255.0f + 0.5f);
            worstB = (int)(p.b * 255.0f + 0.5f);
        }
    }
    float avg = cnt ? sum / cnt : 0.0f;

    jfloat stats[9] = {avg, avg, mx, mx, (float)worstR, (float)worstG, (float)worstB,
                       (float)fitQuadCount, (float)fitLinCount};
    env->SetFloatArrayRegion(out_stats, 0, 9, stats);

    return mx;
}

