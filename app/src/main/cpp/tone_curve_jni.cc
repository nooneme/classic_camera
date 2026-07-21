#include <jni.h>
#include <vector>
#include "tone_curve.h"

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_classic_camera_ToneCurveEngine_nativeGenerateLUT(
    JNIEnv* env, jclass, jfloatArray j_points, jint lut_size)
{
    jfloat* elements = env->GetFloatArrayElements(j_points, nullptr);
    jsize len = env->GetArrayLength(j_points);

    std::vector<float> points(elements, elements + len);
    env->ReleaseFloatArrayElements(j_points, elements, JNI_ABORT);

    std::vector<float> lut(lut_size);
    ToneCurveLUT::generate(points.data(), (int)points.size(),
                           lut.data(), lut_size);

    jfloatArray result = env->NewFloatArray(lut_size);
    env->SetFloatArrayRegion(result, 0, lut_size, lut.data());
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_classic_camera_ToneCurveEngine_nativeEvalAt(
    JNIEnv* env, jclass, jfloatArray j_points, jfloatArray j_inputs)
{
    jfloat* points = env->GetFloatArrayElements(j_points, nullptr);
    jsize pt_len = env->GetArrayLength(j_points);

    std::vector<float> pts(points, points + pt_len);
    env->ReleaseFloatArrayElements(j_points, points, JNI_ABORT);

    constexpr int LUT_SIZE = 1024;
    std::vector<float> lut(LUT_SIZE);
    ToneCurveLUT::generate(pts.data(), (int)pts.size(),
                           lut.data(), LUT_SIZE);

    jfloat* inputs = env->GetFloatArrayElements(j_inputs, nullptr);
    jsize in_len = env->GetArrayLength(j_inputs);

    jfloatArray result = env->NewFloatArray(in_len);
    jfloat* out = env->GetFloatArrayElements(result, nullptr);

    for (int i = 0; i < in_len; ++i) {
        float t = std::max(0.0f, std::min(1.0f, inputs[i]));
        int idx = (int)(t * (LUT_SIZE - 1) + 0.5f);
        out[i] = lut[std::max(0, std::min(LUT_SIZE - 1, idx))];
    }

    env->ReleaseFloatArrayElements(j_inputs, inputs, JNI_ABORT);
    env->ReleaseFloatArrayElements(result, out, 0);
    return result;
}

}
