#include "align_merge.h"

// ===================== 金字塔 =====================

static void build_pyramid(const uint16_t* src, int w, int h, Pyramid& pyr) {
    box_down2(src, w, h, pyr.layer0, pyr.w0, pyr.h0);
    gauss_down4(pyr.layer0.data(), pyr.w0, pyr.h0, pyr.layer1, pyr.w1, pyr.h1);
    gauss_down4(pyr.layer1.data(), pyr.w1, pyr.h1, pyr.layer2, pyr.w2, pyr.h2);
}

// ===================== 对齐一层 =====================

static void align_layer(
    const uint16_t* ref_layer, const uint16_t* alt_layer,
    int lw, int lh,
    int num_tx, int num_ty,
    const std::vector<int>& prev_offsets, int prev_num_tx, int prev_num_ty,
    int scale, int prev_min, int prev_max,
    std::vector<int>& out_offsets)
{
    for (int ty = 0; ty < num_ty; ty++) {
        for (int tx = 0; tx < num_tx; tx++) {
            int pt = std::max(0, std::min(prev_num_tx - 1, (tx - 1) / DOWNSAMPLE_RATE));
            int pp = std::max(0, std::min(prev_num_ty - 1, (ty - 1) / DOWNSAMPLE_RATE));
            int base_x = clamp_offset(
                prev_offsets[(pp * prev_num_tx + pt) * 2 + 0] * scale,
                prev_min, prev_max);
            int base_y = clamp_offset(
                prev_offsets[(pp * prev_num_tx + pt) * 2 + 1] * scale,
                prev_min, prev_max);

            int best_dx = 0, best_dy = 0;
            int best_sad = INT32_MAX;

            int ref_ox = tx * T_SIZE_2;
            int ref_oy = ty * T_SIZE_2;

            for (int dy = -SEARCH_RADIUS; dy < SEARCH_RADIUS; dy++) {
                for (int dx = -SEARCH_RADIUS; dx < SEARCH_RADIUS; dx++) {
                    int off_x = base_x + dx;
                    int off_y = base_y + dy;
                    int sad = 0;
                    int count = 0;
                    for (int iy = 0; iy < T_SIZE_2; iy++) {
                        for (int ix = 0; ix < T_SIZE_2; ix++) {
                            int rx = ref_ox + ix;
                            int ry = ref_oy + iy;
                            int ax = rx + off_x;
                            int ay = ry + off_y;
                            if (rx >= 0 && rx < lw && ry >= 0 && ry < lh &&
                                ax >= 0 && ax < lw && ay >= 0 && ay < lh) {
                                int rv = ref_layer[ry * lw + rx];
                                int av = alt_layer[ay * lw + ax];
                                sad += std::abs(rv - av);
                                count++;
                            }
                        }
                    }
                    if (count > 0) {
                        sad = (sad * 256) / count;
                        if (sad < best_sad) {
                            best_sad = sad;
                            best_dx = off_x;
                            best_dy = off_y;
                        }
                    }
                }
            }
            int idx = (ty * num_tx + tx) * 2;
            out_offsets[idx + 0] = best_dx;
            out_offsets[idx + 1] = best_dy;
        }
    }
}

// ===================== 对齐主入口 =====================

static void compute_alignment(
    const std::vector<Pyramid>& pyrs, int num_frames,
    int num_tx, int num_ty,
    std::vector<int>& offsets)
{
    // 为每对 (ref=0, alt=n) 计算偏移
    offsets.resize((num_frames - 1) * num_ty * num_tx * 2);

    for (int n = 1; n < num_frames; n++) {
        // Layer 2 (1/32)
        int nt2_x = (pyrs[0].w2 + T_SIZE_2 - 1) / T_SIZE_2;
        int nt2_y = (pyrs[0].h2 + T_SIZE_2 - 1) / T_SIZE_2;
        std::vector<int> off2(nt2_y * nt2_x * 2, 0);
        std::vector<int> zeros(nt2_y * nt2_x * 2, 0);

        align_layer(pyrs[0].layer2.data(), pyrs[n].layer2.data(),
                    pyrs[0].w2, pyrs[0].h2,
                    nt2_x, nt2_y, zeros, nt2_x, nt2_y,
                    DOWNSAMPLE_RATE, 0, 0, off2);

        // Layer 1 (1/8)
        int nt1_x = (pyrs[0].w1 + T_SIZE_2 - 1) / T_SIZE_2;
        int nt1_y = (pyrs[0].h1 + T_SIZE_2 - 1) / T_SIZE_2;
        std::vector<int> off1(nt1_y * nt1_x * 2, 0);

        align_layer(pyrs[0].layer1.data(), pyrs[n].layer1.data(),
                    pyrs[0].w1, pyrs[0].h1,
                    nt1_x, nt1_y, off2, nt2_x, nt2_y,
                    DOWNSAMPLE_RATE, -20, 15, off1);

        // Layer 0 (1/2) — 使用 merge 图块网格
        int nt0_x = num_tx;
        int nt0_y = num_ty;
        std::vector<int> off0(nt0_y * nt0_x * 2, 0);

        align_layer(pyrs[0].layer0.data(), pyrs[n].layer0.data(),
                    pyrs[0].w0, pyrs[0].h0,
                    nt0_x, nt0_y, off1, nt1_x, nt1_y,
                    DOWNSAMPLE_RATE, -84, 63, off0);

        // 写入最终结果: 2 × layer_0 → full res
        int frame_off = (n - 1) * num_ty * num_tx * 2;
        for (int i = 0; i < nt0_y * nt0_x * 2; i++) {
            offsets[frame_off + i] = clamp_offset(off0[i] * 2, MIN_OFFSET, MAX_OFFSET);
        }
    }
}

// ===================== 时域融合 =====================

static void merge_temporal(
    const uint16_t* const* frames, int w, int h, int num_frames,
    const std::vector<int>& offsets, int num_tx, int num_ty,
    std::vector<uint16_t>& output)
{
    output.resize(num_ty * num_tx * T_SIZE * T_SIZE);

    std::vector<Pyramid> pyrs(num_frames);
    for (int i = 0; i < num_frames; i++) {
        build_pyramid(frames[i], w, h, pyrs[i]);
    }

    auto& ref_layer = pyrs[0].layer0;
    int lw = pyrs[0].w0;
    int lh = pyrs[0].h0;

    for (int ty = 0; ty < num_ty; ty++) {
        for (int tx = 0; tx < num_tx; tx++) {
            float total_weight = 1.0f;
            std::vector<float> frame_weights(num_frames, 0.0f);
            frame_weights[0] = 1.0f;

            for (int n = 1; n < num_frames; n++) {
                int ox = offsets[((n - 1) * num_ty + ty) * num_tx * 2 + tx * 2 + 0];
                int oy = offsets[((n - 1) * num_ty + ty) * num_tx * 2 + tx * 2 + 1];

                int l1_sum = 0, l1_cnt = 0;
                for (int iy = 0; iy < T_SIZE_2; iy++) {
                    for (int ix = 0; ix < T_SIZE_2; ix++) {
                        int ref_x = idx_layer(tx, ix);
                        int ref_y = idx_layer(ty, iy);
                        int alt_x = ref_x + ox / 2;
                        int alt_y = ref_y + oy / 2;
                        if (ref_x >= 0 && ref_x < lw && ref_y >= 0 && ref_y < lh &&
                            alt_x >= 0 && alt_x < lw && alt_y >= 0 && alt_y < lh) {
                            int diff = (int)ref_layer[ref_y * lw + ref_x]
                                     - (int)pyrs[n].layer0[alt_y * lw + alt_x];
                            l1_sum += std::abs(diff);
                            l1_cnt++;
                        }
                    }
                }
                float avg_l1 = (l1_cnt > 0) ? (float)l1_sum / l1_cnt : 999.0f;

                const float factor = 8.0f;
                const float min_dist = 10.0f;
                float norm_dist = std::max(1.0f, avg_l1 / factor - min_dist / factor);
                float weight = 0.0f;
                if (norm_dist <= (300.0f - min_dist) / factor) {
                    weight = 1.0f / norm_dist;
                    weight = std::min(weight, 10.0f);
                }
                frame_weights[n] = weight;
                total_weight += weight;
            }

            int tile_base = (ty * num_tx + tx) * T_SIZE * T_SIZE;
            for (int iy = 0; iy < T_SIZE; iy++) {
                for (int ix = 0; ix < T_SIZE; ix++) {
                    int gx = idx_im(tx, ix);
                    int gy = idx_im(ty, iy);
                    // 边界 clamp
                    gx = std::max(0, std::min(w - 1, gx));
                    gy = std::max(0, std::min(h - 1, gy));

                    float sum = frames[0][gy * w + gx] * 1.0f;

                    for (int n = 1; n < num_frames; n++) {
                        float wgt = frame_weights[n];
                        if (wgt <= 0.001f) continue;
                        int ox = offsets[((n - 1) * num_ty + ty) * num_tx * 2 + tx * 2 + 0];
                        int oy = offsets[((n - 1) * num_ty + ty) * num_tx * 2 + tx * 2 + 1];
                        int ax = std::max(0, std::min(w - 1, gx + ox));
                        int ay = std::max(0, std::min(h - 1, gy + oy));
                        sum += frames[n][ay * w + ax] * wgt;
                    }
                    int out_val = (int)(sum / total_weight + 0.5f);
                    output[tile_base + iy * T_SIZE + ix] = (uint16_t)std::max(0, std::min(65535, out_val));
                }
            }
        }
    }
}

// ===================== 空域融合 =====================

static void merge_spatial(
    const std::vector<uint16_t>& temporal_output,
    int w, int h, int num_tx, int num_ty,
    std::vector<uint16_t>& result)
{
    result.resize(w * h);

    float hann[T_SIZE];
    for (int i = 0; i < T_SIZE; i++) {
        hann[i] = 0.5f - 0.5f * (float)std::cos(2.0 * M_PI * (i + 0.5) / T_SIZE);
    }

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            // 原始 tile 索引（可能 -1 或 num_tx）
            int r0_x = tile_0(x);
            int r1_x = tile_1(x);
            int r0_y = tile_0(y);
            int r1_y = tile_1(y);

            // clamp 到有效范围
            int t0_x = tile_clamp(r0_x, num_tx);
            int t1_x = tile_clamp(r1_x, num_tx);
            int t0_y = tile_clamp(r0_y, num_ty);
            int t1_y = tile_clamp(r1_y, num_ty);

            // 局部坐标：始终由像素 x 位置决定，不受 clamp 影响
            int p0_x = idx_0_inner(x);
            int p1_x = idx_1_inner(x);
            int p0_y = idx_0_inner(y);
            int p1_y = idx_1_inner(y);

            float wx0 = hann[p0_x];
            float wx1 = hann[p1_x];
            float wy0 = hann[p0_y];
            float wy1 = hann[p1_y];

            auto get = [&](int tx, int ty, int lx, int ly) -> float {
                return (float)temporal_output[(ty * num_tx + tx) * T_SIZE * T_SIZE + ly * T_SIZE + lx];
            };

            float v = 0.0f;
            v += wx0 * wy0 * get(t0_x, t0_y, p0_x, p0_y);
            v += wx1 * wy0 * get(t1_x, t0_y, p1_x, p0_y);
            v += wx0 * wy1 * get(t0_x, t1_y, p0_x, p1_y);
            v += wx1 * wy1 * get(t1_x, t1_y, p1_x, p1_y);

            result[y * w + x] = (uint16_t)std::max(0, std::min(65535, (int)(v + 0.5f)));
        }
    }
}

// ===================== JNI 入口 =====================

extern "C" JNIEXPORT jintArray JNICALL
Java_com_classic_camera_AlignMergeEngine_alignFrames(
    JNIEnv* env, jclass clazz,
    jobjectArray frames, jint w, jint h, jint num_frames)
{
    std::vector<std::vector<uint16_t>> frame_data(num_frames);
    for (int i = 0; i < num_frames; i++) {
        auto arr = (jshortArray)env->GetObjectArrayElement(frames, i);
        jsize len = env->GetArrayLength(arr);
        jshort* elems = env->GetShortArrayElements(arr, nullptr);
        frame_data[i].resize(len);
        for (int j = 0; j < len; j++) {
            frame_data[i][j] = (uint16_t)(elems[j] & 0xFFFF);
        }
        env->ReleaseShortArrayElements(arr, elems, 0);
    }

    std::vector<Pyramid> pyrs(num_frames);
    for (int i = 0; i < num_frames; i++) {
        build_pyramid(frame_data[i].data(), w, h, pyrs[i]);
    }

    int num_tx = (w + T_SIZE_2 - 1) / T_SIZE_2;
    int num_ty = (h + T_SIZE_2 - 1) / T_SIZE_2;

    std::vector<int> offsets;
    compute_alignment(pyrs, num_frames, num_tx, num_ty, offsets);

    jintArray result = env->NewIntArray((jint)offsets.size());
    env->SetIntArrayRegion(result, 0, (jint)offsets.size(), (const jint*)offsets.data());
    return result;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_classic_camera_AlignMergeEngine_mergeFrames(
    JNIEnv* env, jclass clazz,
    jobjectArray frames, jintArray offsets_arr,
    jint w, jint h, jint num_frames, jint num_tx, jint num_ty)
{
    const int MAX_FRAMES = 10;
    int nf = std::min(num_frames, MAX_FRAMES);

    std::vector<std::vector<uint16_t>> frame_data(nf);
    const uint16_t* frame_ptrs[MAX_FRAMES];
    for (int i = 0; i < nf; i++) {
        auto arr = (jshortArray)env->GetObjectArrayElement(frames, i);
        jsize len = env->GetArrayLength(arr);
        jshort* elems = env->GetShortArrayElements(arr, nullptr);
        frame_data[i].resize(len);
        for (int j = 0; j < len; j++) {
            frame_data[i][j] = (uint16_t)(elems[j] & 0xFFFF);
        }
        env->ReleaseShortArrayElements(arr, elems, 0);
        frame_ptrs[i] = frame_data[i].data();
    }

    jint* off_elems = env->GetIntArrayElements(offsets_arr, nullptr);
    int off_len = env->GetArrayLength(offsets_arr);
    std::vector<int> offsets(off_len);
    memcpy(offsets.data(), off_elems, off_len * sizeof(jint));
    env->ReleaseIntArrayElements(offsets_arr, off_elems, 0);

    std::vector<uint16_t> temporal_out;
    merge_temporal(frame_ptrs, w, h, nf, offsets, num_tx, num_ty, temporal_out);

    std::vector<uint16_t> merged;
    merge_spatial(temporal_out, w, h, num_tx, num_ty, merged);

    jshortArray jresult = env->NewShortArray(w * h);
    std::vector<jshort> shorts(w * h);
    for (int i = 0; i < w * h; i++) {
        shorts[i] = (jshort)(merged[i] & 0xFFFF);
    }
    env->SetShortArrayRegion(jresult, 0, w * h, shorts.data());
    return jresult;
}
