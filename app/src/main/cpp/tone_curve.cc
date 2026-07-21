#include "tone_curve.h"

namespace {

inline float pow2(float x) { return x * x; }

inline float catmull_rom_tj(float ti,
                            float xi, float yi,
                            float xj, float yj) {
    constexpr float alpha = 0.375f;
    float dist = std::sqrt(pow2(xj - xi) + pow2(yj - yi));
    return std::pow(dist, alpha) + ti;
}

inline void catmull_rom_reflect(
    float px, float py,
    float cx, float cy,
    float& rx, float& ry)
{
    constexpr float epsilon = 1e-5f;
    float dx = px - cx;
    float dy = py - cy;
    rx = cx - dx * 0.01f;
    ry = (dx > epsilon) ? (dy / dx) * (rx - cx) + cy : cy;
}

inline void catmull_rom_spline(
    int n_points,
    float p0_x, float p0_y,
    float p1_x, float p1_y,
    float p2_x, float p2_y,
    float p3_x, float p3_y,
    std::vector<float>& out_x,
    std::vector<float>& out_y)
{
    float t0 = 0.f;
    float t1 = catmull_rom_tj(t0, p0_x, p0_y, p1_x, p1_y);
    float t2 = catmull_rom_tj(t1, p1_x, p1_y, p2_x, p2_y);
    float t3 = catmull_rom_tj(t2, p2_x, p2_y, p3_x, p3_y);
    float space = (t2 - t1) / n_points;

    out_x.push_back(p1_x);
    out_y.push_back(p1_y);

    if (p1_y == p2_y && (p1_y == 0.f || p1_y == 1.f)) {
        for (int i = 1; i < n_points - 1; ++i) {
            float t = p1_x + space * i;
            if (t >= p2_x) break;
            out_x.push_back(t);
            out_y.push_back(p1_y);
        }
    } else {
        for (int i = 1; i < n_points - 1; ++i) {
            float t_param = t1 + space * i;

            float c_a1 = (t1 - t_param) / (t1 - t0);
            float d_a1 = (t_param - t0) / (t1 - t0);
            float A1_x = c_a1 * p0_x + d_a1 * p1_x;
            float A1_y = c_a1 * p0_y + d_a1 * p1_y;

            float c_a2 = (t2 - t_param) / (t2 - t1);
            float d_a2 = (t_param - t1) / (t2 - t1);
            float A2_x = c_a2 * p1_x + d_a2 * p2_x;
            float A2_y = c_a2 * p1_y + d_a2 * p2_y;

            float c_a3 = (t3 - t_param) / (t3 - t2);
            float d_a3 = (t_param - t2) / (t3 - t2);
            float A3_x = c_a3 * p2_x + d_a3 * p3_x;
            float A3_y = c_a3 * p2_y + d_a3 * p3_y;

            float c_b1 = (t2 - t_param) / (t2 - t0);
            float d_b1 = (t_param - t0) / (t2 - t0);
            float B1_x = c_b1 * A1_x + d_b1 * A2_x;
            float B1_y = c_b1 * A1_y + d_b1 * A2_y;

            float c_b2 = (t3 - t_param) / (t3 - t1);
            float d_b2 = (t_param - t1) / (t3 - t1);
            float B2_x = c_b2 * A2_x + d_b2 * A3_x;
            float B2_y = c_b2 * A2_y + d_b2 * A3_y;

            float c_c = (t2 - t_param) / (t2 - t1);
            float d_c = (t_param - t1) / (t2 - t1);
            out_x.push_back(c_c * B1_x + d_c * B2_x);
            out_y.push_back(c_c * B1_y + d_c * B2_y);
        }
    }

    out_x.push_back(p2_x);
    out_y.push_back(p2_y);
}

void catmull_rom_chain(
    int n_total, int n_cp,
    const float* x, const float* y,
    std::vector<float>& poly_x,
    std::vector<float>& poly_y)
{
    float x_first, y_first, x_last, y_last;
    catmull_rom_reflect(x[1], y[1], x[0], y[0], x_first, y_first);
    catmull_rom_reflect(x[n_cp - 2], y[n_cp - 2],
                        x[n_cp - 1], y[n_cp - 1],
                        x_last, y_last);

    int segments = n_cp - 1;
    poly_x.reserve(n_total);
    poly_y.reserve(n_total);

    for (int i = 0; i < segments; ++i) {
        float span = x[i + 1] - x[i];
        int n = std::max(int(n_total * span + 0.5f), 2);

        catmull_rom_spline(
            n,
            i == 0 ? x_first : x[i - 1],
            i == 0 ? y_first : y[i - 1],
            x[i], y[i],
            x[i + 1], y[i + 1],
            i == segments - 1 ? x_last : x[i + 2],
            i == segments - 1 ? y_last : y[i + 2],
            poly_x, poly_y);
    }
}

} // anonymous namespace

void ToneCurveLUT::generate(
    const float* points, int num_points,
    float* output, int lut_size)
{
    if (num_points < 5) {
        for (int i = 0; i < lut_size; ++i) {
            output[i] = (float)i / (lut_size - 1);
        }
        return;
    }

    int N = (num_points - 1) / 2;

    if (N < 3) {
        for (int i = 0; i < lut_size; ++i) {
            float t = (float)i / (lut_size - 1);
            float prev_x = points[1], prev_y = points[2];
            for (int j = 3; j < num_points; j += 2) {
                float cur_x = points[j], cur_y = points[j + 1];
                if (t >= prev_x && t <= cur_x) {
                    float r = (cur_x > prev_x) ? (t - prev_x) / (cur_x - prev_x) : 0.f;
                    output[i] = prev_y + r * (cur_y - prev_y);
                    break;
                }
                prev_x = cur_x;
                prev_y = cur_y;
            }
        }
        return;
    }

    std::vector<float> x(N), y(N);
    int idx = 1;
    for (int i = 0; i < N; ++i) {
        x[i] = points[idx++];
        y[i] = points[idx++];
    }

    constexpr int POLY_POINTS = 65000;
    std::vector<float> poly_x, poly_y;
    catmull_rom_chain(POLY_POINTS, N, x.data(), y.data(), poly_x, poly_y);

    for (int i = 0; i < lut_size; ++i) {
        float t = (float)i / (lut_size - 1);

        if (t <= poly_x.front()) {
            output[i] = std::max(0.0f, std::min(1.0f, poly_y.front()));
            continue;
        }
        if (t >= poly_x.back()) {
            output[i] = std::max(0.0f, std::min(1.0f, poly_y.back()));
            continue;
        }

        auto it = std::lower_bound(poly_x.begin(), poly_x.end(), t);
        int d = (int)(it - poly_x.begin());

        if (it != poly_x.begin() &&
            (it == poly_x.end() || t - poly_x[d - 1] < poly_x[d] - t)) {
            --d;
        }

        output[i] = std::max(0.0f, std::min(1.0f, poly_y[d]));
    }
}
