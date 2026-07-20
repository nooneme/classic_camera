#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <cfloat>

static const int LUT_SIZE = 33;
static const int TOTAL_NODES = LUT_SIZE * LUT_SIZE * LUT_SIZE;

struct Vec3 { float r, g, b; };

// ── 三次多项式拟合 (20 项) ──────────────────────────────────────
static const int POLY_TERMS = 20;

// 1, r, g, b, r², g², b², rg, rb, gb, r³, g³, b³, r²g, r²b, g²r, g²b, b²r, b²g, rgb
static void polyBasis(float r, float g, float b, float* out) {
    float r2 = r * r, g2 = g * g, b2 = b * b;
    out[0]  = 1.0f;
    out[1]  = r;        out[2]  = g;        out[3]  = b;
    out[4]  = r2;       out[5]  = g2;       out[6]  = b2;
    out[7]  = r * g;    out[8]  = r * b;    out[9]  = g * b;
    out[10] = r2 * r;   out[11] = g2 * g;   out[12] = b2 * b;
    out[13] = r2 * g;   out[14] = r2 * b;
    out[15] = g2 * r;   out[16] = g2 * b;
    out[17] = b2 * r;   out[18] = b2 * g;
    out[19] = r * g * b;
}

// 高斯消元（列主元）求解 n×n 线性方程组，A 列主序，原地修改
static bool gaussSolve(int n, float* A, float* b) {
    // 转置为行主序方便消元
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

extern "C" JNIEXPORT jfloat JNICALL
Java_com_classic_camera_LutEngine_fitPolynomialLut(
    JNIEnv* env, jobject thiz,
    jintArray orig_pixels, jintArray filt_pixels,
    jint num_pixels, jfloatArray out_lut_array,
    jfloatArray out_stats) {

    jint* orig = env->GetIntArrayElements(orig_pixels, nullptr);
    jint* filt = env->GetIntArrayElements(filt_pixels, nullptr);

    // 80/20 交叉验证拆分（按索引取模，简单均匀）
    int trainCount = 0;
    for (int i = 0; i < num_pixels; ++i)
        if (i % 5 != 0) trainCount++;  // 80%

    // 用 double 累加避免大量像素时的精度损失
    double ATA[POLY_TERMS * POLY_TERMS] = {0};
    double ATb_R[POLY_TERMS] = {0};
    double ATb_G[POLY_TERMS] = {0};
    double ATb_B[POLY_TERMS] = {0};

    for (int i = 0; i < num_pixels; ++i) {
        if (i % 5 == 0) continue;  // 留 20% 做验证

        double r = ((orig[i] >> 16) & 0xFF) / 255.0;
        double g = ((orig[i] >> 8) & 0xFF) / 255.0;
        double b = (orig[i]       & 0xFF) / 255.0;

        float fb[POLY_TERMS];
        polyBasis((float)r, (float)g, (float)b, fb);
        double f[POLY_TERMS];
        for (int t = 0; t < POLY_TERMS; ++t) f[t] = fb[t];

        double fR = ((filt[i] >> 16) & 0xFF) / 255.0;
        double fG = ((filt[i] >> 8)  & 0xFF) / 255.0;
        double fB = (filt[i]        & 0xFF) / 255.0;

        for (int j = 0; j < POLY_TERMS; ++j)
            for (int k = j; k < POLY_TERMS; ++k)
                ATA[j * POLY_TERMS + k] += f[j] * f[k];

        double dR = fR - r, dG = fG - g, dB = fB - b;
        for (int j = 0; j < POLY_TERMS; ++j) {
            ATb_R[j] += f[j] * dR;
            ATb_G[j] += f[j] * dG;
            ATb_B[j] += f[j] * dB;
        }
    }

    for (int j = 0; j < POLY_TERMS; ++j)
        for (int k = 0; k < j; ++k)
            ATA[j * POLY_TERMS + k] = ATA[k * POLY_TERMS + j];

    // 转回 float 求解，double ATA 直接按 bit 拷贝到 float 会截断
    float ATAf[POLY_TERMS * POLY_TERMS];
    float ATb_Rf[POLY_TERMS], ATb_Gf[POLY_TERMS], ATb_Bf[POLY_TERMS];
    for (int i = 0; i < POLY_TERMS * POLY_TERMS; ++i) ATAf[i] = (float)ATA[i];
    for (int i = 0; i < POLY_TERMS; ++i) {
        ATb_Rf[i] = (float)ATb_R[i];
        ATb_Gf[i] = (float)ATb_G[i];
        ATb_Bf[i] = (float)ATb_B[i];
    }

    // Ridge 正则化：当前已禁用
    // float lambda = 10.0f;
    // for (int i = 0; i < POLY_TERMS; ++i)
    //     ATAf[i * POLY_TERMS + i] += lambda;

    float coeffsR[POLY_TERMS], coeffsG[POLY_TERMS], coeffsB[POLY_TERMS];
    float ATA_R[POLY_TERMS * POLY_TERMS], ATA_G[POLY_TERMS * POLY_TERMS], ATA_B[POLY_TERMS * POLY_TERMS];

    auto copySolve = [&](float* coeffs, float* ATb, float* ATA_copy) {
        memcpy(ATA_copy, ATAf, sizeof(float) * POLY_TERMS * POLY_TERMS);
        memcpy(coeffs, ATb, sizeof(float) * POLY_TERMS);
        gaussSolve(POLY_TERMS, ATA_copy, coeffs);
    };
    copySolve(coeffsR, ATb_Rf, ATA_R);
    copySolve(coeffsG, ATb_Gf, ATA_G);
    copySolve(coeffsB, ATb_Bf, ATA_B);

    // ── 交叉验证：分别在训练集和验证集上评估 ──
    float trainSum = 0, trainMax = 0, trainCountF = 0;
    float validSum = 0, validMax = 0, validCountF = 0;
    int worstR = 0, worstG = 0, worstB = 0;
    float worstPredR = 0, worstPredG = 0, worstPredB = 0;
    float worstActualR = 0, worstActualG = 0, worstActualB = 0;

    for (int i = 0; i < num_pixels; ++i) {
        float r = ((orig[i] >> 16) & 0xFF) / 255.0f;
        float g = ((orig[i] >> 8) & 0xFF) / 255.0f;
        float b = (orig[i]       & 0xFF) / 255.0f;

        float f[POLY_TERMS];
        polyBasis(r, g, b, f);

        float predR = 0, predG = 0, predB = 0;
        for (int t = 0; t < POLY_TERMS; ++t) {
            predR += coeffsR[t] * f[t];
            predG += coeffsG[t] * f[t];
            predB += coeffsB[t] * f[t];
        }
        predR = fmaxf(0.0f, fminf(1.0f, r + predR));
        predG = fmaxf(0.0f, fminf(1.0f, g + predG));
        predB = fmaxf(0.0f, fminf(1.0f, b + predB));

        float fR = ((filt[i] >> 16) & 0xFF) / 255.0f;
        float fG = ((filt[i] >> 8)  & 0xFF) / 255.0f;
        float fB = (filt[i]        & 0xFF) / 255.0f;

        float errR = fabsf(predR - fR);
        float errG = fabsf(predG - fG);
        float errB = fabsf(predB - fB);
        float pixelErr = fmaxf(errR, fmaxf(errG, errB));

        if (i % 5 == 0) {  // 验证集
            validSum += pixelErr;
            if (pixelErr > validMax) {
                validMax = pixelErr;
                worstR = (orig[i] >> 16) & 0xFF; worstG = (orig[i] >> 8) & 0xFF; worstB = orig[i] & 0xFF;
                worstPredR = predR * 255; worstPredG = predG * 255; worstPredB = predB * 255;
                worstActualR = fR * 255; worstActualG = fG * 255; worstActualB = fB * 255;
            }
            validCountF += 1.0f;
        } else {           // 训练集
            trainSum += pixelErr;
            if (pixelErr > trainMax) trainMax = pixelErr;
            trainCountF += 1.0f;
        }
    }

    float trainAvg = trainSum / trainCountF;
    float validAvg = validSum / validCountF;

    // ── 在 33³ 网格上求值得到最终 LUT ──
    jsize outLen = env->GetArrayLength(out_lut_array);
    std::vector<jfloat> lutOut(outLen);

    for (int bz = 0; bz < LUT_SIZE; ++bz) {
        for (int gy = 0; gy < LUT_SIZE; ++gy) {
            for (int rx = 0; rx < LUT_SIZE; ++rx) {
                float nr = rx / (float)(LUT_SIZE - 1);
                float ng = gy / (float)(LUT_SIZE - 1);
                float nb = bz / (float)(LUT_SIZE - 1);

                float terms[POLY_TERMS];
                polyBasis(nr, ng, nb, terms);

                float dr = 0, dg = 0, db = 0;
                for (int t = 0; t < POLY_TERMS; ++t) {
                    dr += coeffsR[t] * terms[t];
                    dg += coeffsG[t] * terms[t];
                    db += coeffsB[t] * terms[t];
                }

                int idx = (bz * LUT_SIZE * LUT_SIZE + gy * LUT_SIZE + rx) * 3;
                lutOut[idx]     = fmaxf(0.0f, fminf(1.0f, nr + dr));
                lutOut[idx + 1] = fmaxf(0.0f, fminf(1.0f, ng + dg));
                lutOut[idx + 2] = fmaxf(0.0f, fminf(1.0f, nb + db));
            }
        }
    }

    env->ReleaseIntArrayElements(orig_pixels, orig, 0);
    env->ReleaseIntArrayElements(filt_pixels, filt, 0);
    env->SetFloatArrayRegion(out_lut_array, 0, (jsize)outLen, lutOut.data());

    // 写回统计: [训练平均, 验证平均, 训练最大, 验证最大, 最差输入R, G, B]
    jfloat stats[7] = {trainAvg, validAvg, trainMax, validMax,
                       (float)worstR, (float)worstG, (float)worstB};
    env->SetFloatArrayRegion(out_stats, 0, 7, stats);

    return validMax;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_classic_camera_LutEngine_generateLutAndCheckCoverage(
    JNIEnv* env, jobject thiz,
    jintArray orig_pixels, jintArray filt_pixels,
    jint num_pixels, jfloatArray out_lut_array,
    jbooleanArray out_covered_array) {

    jint* orig = env->GetIntArrayElements(orig_pixels, nullptr);
    jint* filt = env->GetIntArrayElements(filt_pixels, nullptr);

    std::vector<Vec3> colorSum(TOTAL_NODES, {0, 0, 0});
    std::vector<float> weightSum(TOTAL_NODES, 0.0f);
    std::vector<bool> covered(TOTAL_NODES, false);

    // 遍历像素对
    for (int i = 0; i < num_pixels; ++i) {
        int oR = (orig[i] >> 16) & 0xFF;
        int oG = (orig[i] >> 8) & 0xFF;
        int oB = orig[i] & 0xFF;
        int fR = (filt[i] >> 16) & 0xFF;
        int fG = (filt[i] >> 8) & 0xFF;
        int fB = filt[i] & 0xFF;

        // 记录体素命中
        int vx = std::min(LUT_SIZE - 1, oR * LUT_SIZE / 256);
        int vy = std::min(LUT_SIZE - 1, oG * LUT_SIZE / 256);
        int vz = std::min(LUT_SIZE - 1, oB * LUT_SIZE / 256);
        covered[vx + vy * LUT_SIZE + vz * LUT_SIZE * LUT_SIZE] = true;

        // 三线性插值分配
        float fx = oR / 255.0f * (LUT_SIZE - 1);
        float fy = oG / 255.0f * (LUT_SIZE - 1);
        float fz = oB / 255.0f * (LUT_SIZE - 1);
        int x0 = (int)floor(fx), y0 = (int)floor(fy), z0 = (int)floor(fz);
        int x1 = std::min(x0 + 1, LUT_SIZE - 1);
        int y1 = std::min(y0 + 1, LUT_SIZE - 1);
        int z1 = std::min(z0 + 1, LUT_SIZE - 1);

        float dx = fx - x0, dy = fy - y0, dz = fz - z0;
        float weights[8] = {
            (1-dx)*(1-dy)*(1-dz), dx*(1-dy)*(1-dz),
            (1-dx)*dy*(1-dz),     dx*dy*(1-dz),
            (1-dx)*(1-dy)*dz,     dx*(1-dy)*dz,
            (1-dx)*dy*dz,         dx*dy*dz
        };
        int indices[8] = {
            x0 + y0*LUT_SIZE + z0*LUT_SIZE*LUT_SIZE,
            x1 + y0*LUT_SIZE + z0*LUT_SIZE*LUT_SIZE,
            x0 + y1*LUT_SIZE + z0*LUT_SIZE*LUT_SIZE,
            x1 + y1*LUT_SIZE + z0*LUT_SIZE*LUT_SIZE,
            x0 + y0*LUT_SIZE + z1*LUT_SIZE*LUT_SIZE,
            x1 + y0*LUT_SIZE + z1*LUT_SIZE*LUT_SIZE,
            x0 + y1*LUT_SIZE + z1*LUT_SIZE*LUT_SIZE,
            x1 + y1*LUT_SIZE + z1*LUT_SIZE*LUT_SIZE
        };

        float fRf = fR / 255.0f, fGf = fG / 255.0f, fBf = fB / 255.0f;
        for (int j = 0; j < 8; ++j) {
            int idx = indices[j];
            float w = weights[j];
            colorSum[idx].r += w * fRf;
            colorSum[idx].g += w * fGf;
            colorSum[idx].b += w * fBf;
            weightSum[idx] += w;
        }
    }

    env->ReleaseIntArrayElements(orig_pixels, orig, 0);
    env->ReleaseIntArrayElements(filt_pixels, filt, 0);

    // 计算最终颜色与覆盖率
    int coveredCount = 0;
    jsize outLen = env->GetArrayLength(out_lut_array);
    std::vector<Vec3> finalLut(TOTAL_NODES);

    for (int i = 0; i < TOTAL_NODES; ++i) {
        if (covered[i]) coveredCount++;

        int z = i / (LUT_SIZE * LUT_SIZE);
        int y = (i % (LUT_SIZE * LUT_SIZE)) / LUT_SIZE;
        int x = i % LUT_SIZE;
        Vec3 identity = {x / (float)(LUT_SIZE - 1),
                         y / (float)(LUT_SIZE - 1),
                         z / (float)(LUT_SIZE - 1)};

        if (weightSum[i] > 0.001f) {
            float confidence = std::min(1.0f, weightSum[i] / 5.0f);
            Vec3 sampled = {colorSum[i].r / weightSum[i],
                            colorSum[i].g / weightSum[i],
                            colorSum[i].b / weightSum[i]};
            finalLut[i].r = confidence * sampled.r + (1 - confidence) * identity.r;
            finalLut[i].g = confidence * sampled.g + (1 - confidence) * identity.g;
            finalLut[i].b = confidence * sampled.b + (1 - confidence) * identity.b;
        } else {
            finalLut[i] = identity;
        }
    }

    // 3 次拉普拉斯迭代平滑，磨平 LUT 毛刺防止映射斜率突变放大噪点
    for (int iter = 0; iter < 3; ++iter) {
        std::vector<Vec3> tempLut = finalLut;

        for (int z = 0; z < LUT_SIZE; ++z) {
            for (int y = 0; y < LUT_SIZE; ++y) {
                for (int x = 0; x < LUT_SIZE; ++x) {
                    Vec3 sum = {0.0f, 0.0f, 0.0f};
                    int count = 0;

                    for (int dz = -1; dz <= 1; ++dz) {
                        for (int dy = -1; dy <= 1; ++dy) {
                            for (int dx = -1; dx <= 1; ++dx) {
                                int nx = std::max(0, std::min(LUT_SIZE - 1, x + dx));
                                int ny = std::max(0, std::min(LUT_SIZE - 1, y + dy));
                                int nz = std::max(0, std::min(LUT_SIZE - 1, z + dz));

                                int nIdx = nx + ny * LUT_SIZE + nz * LUT_SIZE * LUT_SIZE;
                                sum.r += tempLut[nIdx].r;
                                sum.g += tempLut[nIdx].g;
                                sum.b += tempLut[nIdx].b;
                                count++;
                            }
                        }
                    }

                    int idx = x + y * LUT_SIZE + z * LUT_SIZE * LUT_SIZE;
                    finalLut[idx].r = tempLut[idx].r * 0.6f + (sum.r / count) * 0.4f;
                    finalLut[idx].g = tempLut[idx].g * 0.6f + (sum.g / count) * 0.4f;
                    finalLut[idx].b = tempLut[idx].b * 0.6f + (sum.b / count) * 0.4f;
                }
            }
        }
    }

    std::vector<jfloat> lutOut(outLen);
    for (int i = 0; i < TOTAL_NODES; ++i) {
        lutOut[i * 3]     = finalLut[i].r;
        lutOut[i * 3 + 1] = finalLut[i].g;
        lutOut[i * 3 + 2] = finalLut[i].b;
    }

    env->SetFloatArrayRegion(out_lut_array, 0, (jsize)outLen, lutOut.data());

    // 写回 coverage 布尔数组
    jboolean* covOut = env->GetBooleanArrayElements(out_covered_array, nullptr);
    for (int i = 0; i < TOTAL_NODES; ++i) {
        covOut[i] = covered[i] ? JNI_TRUE : JNI_FALSE;
    }
    env->ReleaseBooleanArrayElements(out_covered_array, covOut, 0);

    return (jfloat)coveredCount / (jfloat)TOTAL_NODES;
}
