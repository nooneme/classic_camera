#pragma once
#include <vector>
#include <algorithm>
#include <cmath>

class ToneCurveLUT {
public:
    static void generate(const float* points, int num_points,
                         float* output, int lut_size);
};
