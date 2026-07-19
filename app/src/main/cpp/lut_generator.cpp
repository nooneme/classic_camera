#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>

static const int LUT_SIZE = 33;
static const int TOTAL_NODES = LUT_SIZE * LUT_SIZE * LUT_SIZE;

struct Vec3 { float r, g, b; };

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
