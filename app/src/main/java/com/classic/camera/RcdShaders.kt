package com.classic.camera

object RcdShaders {
    const val OUTPUT_MARGIN = 9
    const val PPG_RADIUS = 4

    private val PPG_KERNEL = """
float ppgGreenAt(ivec2 coord) {
    ivec2 center = coord;
    int ownColor = colorAt(center);
    float pc = rawAt(center);
    if (ownColor == GREEN) return max(0.0, pc);
    float pym = rawAt(center + ivec2(0, -1));
    float pym2 = rawAt(center + ivec2(0, -2));
    float pym3 = rawAt(center + ivec2(0, -3));
    float pyM = rawAt(center + ivec2(0, 1));
    float pyM2 = rawAt(center + ivec2(0, 2));
    float pyM3 = rawAt(center + ivec2(0, 3));
    float pxm = rawAt(center + ivec2(-1, 0));
    float pxm2 = rawAt(center + ivec2(-2, 0));
    float pxm3 = rawAt(center + ivec2(-3, 0));
    float pxM = rawAt(center + ivec2(1, 0));
    float pxM2 = rawAt(center + ivec2(2, 0));
    float pxM3 = rawAt(center + ivec2(3, 0));
    float guessx = (pxm + pc + pxM) * 2.0 - pxM2 - pxm2;
    float diffx = (abs(pxm2 - pc) + abs(pxM2 - pc) + abs(pxm - pxM)) * 3.0 +
        (abs(pxM3 - pxM) + abs(pxm3 - pxm)) * 2.0;
    float guessy = (pym + pc + pyM) * 2.0 - pyM2 - pym2;
    float diffy = (abs(pym2 - pc) + abs(pyM2 - pc) + abs(pym - pyM)) * 3.0 +
        (abs(pyM3 - pyM) + abs(pym3 - pym)) * 2.0;
    float green;
    if (diffx > diffy) green = clamp(guessy * 0.25, min(pym, pyM), max(pym, pyM));
    else green = clamp(guessx * 0.25, min(pxm, pxM), max(pxm, pxM));
    return max(0.0, green);
}

vec3 ppgColorAt(ivec2 coord) {
    ivec2 center = coord;
    int ownColor = colorAt(center);
    float pc = max(0.0, rawAt(center));
    float green = ppgGreenAt(center);
    vec3 color = vec3(0.0, green, 0.0);
    if (ownColor == RED) {
        color.r = pc;
        ivec2 nw = center + ivec2(-1, -1);
        ivec2 ne = center + ivec2(1, -1);
        ivec2 sw = center + ivec2(-1, 1);
        ivec2 se = center + ivec2(1, 1);
        float diff1 = abs(rawAt(nw) - rawAt(se)) + abs(ppgGreenAt(nw) - green) + abs(ppgGreenAt(se) - green);
        float guess1 = rawAt(nw) + rawAt(se) + 2.0*green - ppgGreenAt(nw) - ppgGreenAt(se);
        float diff2 = abs(rawAt(ne) - rawAt(sw)) + abs(ppgGreenAt(ne) - green) + abs(ppgGreenAt(sw) - green);
        float guess2 = rawAt(ne) + rawAt(sw) + 2.0*green - ppgGreenAt(ne) - ppgGreenAt(sw);
        if (diff1 > diff2) color.b = guess2 * 0.5;
        else if (diff1 < diff2) color.b = guess1 * 0.5;
        else color.b = (guess1 + guess2) * 0.25;
    } else if (ownColor == BLUE) {
        color.b = pc;
        ivec2 nw = center + ivec2(-1, -1);
        ivec2 ne = center + ivec2(1, -1);
        ivec2 sw = center + ivec2(-1, 1);
        ivec2 se = center + ivec2(1, 1);
        float diff1 = abs(rawAt(nw) - rawAt(se)) + abs(ppgGreenAt(nw) - green) + abs(ppgGreenAt(se) - green);
        float guess1 = rawAt(nw) + rawAt(se) + 2.0*green - ppgGreenAt(nw) - ppgGreenAt(se);
        float diff2 = abs(rawAt(ne) - rawAt(sw)) + abs(ppgGreenAt(ne) - green) + abs(ppgGreenAt(sw) - green);
        float guess2 = rawAt(ne) + rawAt(sw) + 2.0*green - ppgGreenAt(ne) - ppgGreenAt(sw);
        if (diff1 > diff2) color.r = guess2 * 0.5;
        else if (diff1 < diff2) color.r = guess1 * 0.5;
        else color.r = (guess1 + guess2) * 0.25;
    } else {
        color.g = pc;
        if (colorAt(center + ivec2(1, 0)) == RED) {
            color.b = (rawAt(center + ivec2(0, -1)) + rawAt(center + ivec2(0, 1)) + 2.0*color.g - ppgGreenAt(center + ivec2(0, -1)) - ppgGreenAt(center + ivec2(0, 1))) * 0.5;
            color.r = (rawAt(center + ivec2(-1, 0)) + rawAt(center + ivec2(1, 0)) + 2.0*color.g - ppgGreenAt(center + ivec2(-1, 0)) - ppgGreenAt(center + ivec2(1, 0))) * 0.5;
        } else {
            color.r = (rawAt(center + ivec2(0, -1)) + rawAt(center + ivec2(0, 1)) + 2.0*color.g - ppgGreenAt(center + ivec2(0, -1)) - ppgGreenAt(center + ivec2(0, 1))) * 0.5;
            color.b = (rawAt(center + ivec2(-1, 0)) + rawAt(center + ivec2(1, 0)) + 2.0*color.g - ppgGreenAt(center + ivec2(-1, 0)) - ppgGreenAt(center + ivec2(1, 0))) * 0.5;
        }
    }
    return max(color, vec3(0.0));
}
"""

    val POPULATE = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout (binding = 0) uniform highp usampler2D uRawTexture;
layout(std430, binding = 0) buffer CFA_Buf    { float cfa[]; };
layout(std430, binding = 1) buffer RGB0_Buf   { float rgb0[]; };
layout(std430, binding = 2) buffer RGB1_Buf   { float rgb1[]; };
layout(std430, binding = 3) buffer RGB2_Buf   { float rgb2[]; };
uniform ivec2 uImageSize;
uniform int uCfaPattern;
uniform vec4 uBlackLevel;
uniform float uWhiteLevel;
uniform vec4 uWhiteBalanceGains;
#define RED 0
#define GREEN 1
#define BLUE 2
int getBayerColor(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? 0 : 1; else return (c == 0) ? 1 : 2; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? 1 : 0; else return (c == 0) ? 2 : 1; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? 1 : 2; else return (c == 0) ? 0 : 1; }
    else { if (r == 0) return (c == 0) ? 2 : 1; else return (c == 0) ? 1 : 0; }
}
int getBlackLevelIndex(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? 0 : 1; else return (c == 0) ? 2 : 3; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? 1 : 0; else return (c == 0) ? 3 : 2; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? 2 : 3; else return (c == 0) ? 0 : 1; }
    else { if (r == 0) return (c == 0) ? 3 : 2; else return (c == 0) ? 1 : 0; }
}
ivec2 clampCoord(ivec2 coord) { return clamp(coord, ivec2(0), uImageSize - ivec2(1)); }
void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;
    int idx = coord.y * uImageSize.x + coord.x;
    int blIdx = getBlackLevelIndex(uCfaPattern, coord.x, coord.y);
    int color = getBayerColor(uCfaPattern, coord.x, coord.y);
    ivec2 sc = clampCoord(coord);
    uint rawVal = texelFetch(uRawTexture, sc, 0).r;
    float bl = uBlackLevel[blIdx];
    float wl = max(uWhiteLevel, bl + 1.0);
    float val = max(float(rawVal) - bl, 0.0) / max(wl - bl, 1.0);
    val *= max(uWhiteBalanceGains[blIdx], 1e-6);
    cfa[idx] = val;
    if (color == RED) { rgb0[idx] = val; rgb1[idx] = 0.0; rgb2[idx] = 0.0; }
    else if (color == GREEN) { rgb0[idx] = 0.0; rgb1[idx] = val; rgb2[idx] = 0.0; }
    else { rgb0[idx] = 0.0; rgb1[idx] = 0.0; rgb2[idx] = val; }
}
"""

    val STEP_1 = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer CFA_Buf    { float cfa[]; };
layout(std430, binding = 4) buffer VH_Dir_Buf { float VH_dir[]; };
uniform ivec2 uImageSize;
#define epssq 1e-10f
shared float sh_buffer[576];
float fsquare(float x) { return x * x; }
float rcd_vdiff_local(int offset, int stride) {
    float val = sh_buffer[offset - 3*stride] - sh_buffer[offset - stride] - sh_buffer[offset + stride] + sh_buffer[offset + 3*stride] - 3.0*(sh_buffer[offset - 2*stride] + sh_buffer[offset + 2*stride]) + 6.0*sh_buffer[offset];
    return fsquare(val);
}
float rcd_hdiff_local(int offset) {
    float val = sh_buffer[offset - 3] - sh_buffer[offset - 1] - sh_buffer[offset + 1] + sh_buffer[offset + 3] - 3.0*(sh_buffer[offset - 2] + sh_buffer[offset + 2]) + 6.0*sh_buffer[offset];
    return fsquare(val);
}
void main() {
    int xlsz = 16, ylsz = 16;
    int xlid = int(gl_LocalInvocationID.x), ylid = int(gl_LocalInvocationID.y);
    int xgid = int(gl_WorkGroupID.x), ygid = int(gl_WorkGroupID.y);
    int l = ylid * xlsz + xlid, lsz = xlsz * ylsz, stride = 24, maxbuf = 576;
    int xul = xgid * xlsz - 2, yul = ygid * ylsz - 2;
    for (int n = 0; n <= maxbuf / lsz; n++) {
        int bufidx = n * lsz + l;
        if (bufidx >= maxbuf) continue;
        int xx = clamp(xul + bufidx % stride, 0, uImageSize.x - 1);
        int yy = clamp(yul + bufidx / stride, 0, uImageSize.y - 1);
        sh_buffer[bufidx] = cfa[yy * uImageSize.x + xx];
    }
    memoryBarrierShared(); barrier();
    int col = 2 + int(gl_GlobalInvocationID.x), row = 2 + int(gl_GlobalInvocationID.y);
    if (row >= uImageSize.y - 2 || col >= uImageSize.x - 2) return;
    int idx = row * uImageSize.x + col;
    int buf_offset = (ylid + 4) * stride + (xlid + 4);
    float V_Stat = max(epssq, rcd_vdiff_local(buf_offset - stride, stride) + rcd_vdiff_local(buf_offset, stride) + rcd_vdiff_local(buf_offset + stride, stride));
    float H_Stat = max(epssq, rcd_hdiff_local(buf_offset - 1) + rcd_hdiff_local(buf_offset) + rcd_hdiff_local(buf_offset + 1));
    VH_dir[idx] = V_Stat / (V_Stat + H_Stat);
}
"""

    val STEP_2 = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer CFA_Buf { float cfa[]; };
layout(std430, binding = 5) buffer LPF_Buf { float lpf[]; };
uniform ivec2 uImageSize;
uniform int uCfaPattern;
int getBayerColor(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? 0 : 1; else return (c == 0) ? 1 : 2; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? 1 : 0; else return (c == 0) ? 2 : 1; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? 1 : 2; else return (c == 0) ? 0 : 1; }
    else { if (r == 0) return (c == 0) ? 2 : 1; else return (c == 0) ? 1 : 0; }
}
void main() {
    int row = 2 + int(gl_GlobalInvocationID.y);
    int col = 2 + (getBayerColor(uCfaPattern, 0, row) & 1) + 2 * int(gl_GlobalInvocationID.x);
    if (col >= uImageSize.x - 2 || row >= uImageSize.y - 2) return;
    int idx = row * uImageSize.x + col, w = uImageSize.x;
    lpf[idx / 2] = cfa[idx] + 0.5*(cfa[idx - w] + cfa[idx + w] + cfa[idx - 1] + cfa[idx + 1]) + 0.25*(cfa[idx - w - 1] + cfa[idx - w + 1] + cfa[idx + w - 1] + cfa[idx + w + 1]);
}
"""

    val STEP_3 = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer CFA_Buf    { float cfa[]; };
layout(std430, binding = 2) buffer RGB1_Buf   { float rgb1[]; };
layout(std430, binding = 4) buffer VH_Dir_Buf { float VH_dir[]; };
layout(std430, binding = 5) buffer LPF_Buf    { float lpf[]; };
uniform ivec2 uImageSize;
uniform int uCfaPattern;
#define eps 1e-5f
int getBayerColor(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? 0 : 1; else return (c == 0) ? 1 : 2; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? 1 : 0; else return (c == 0) ? 2 : 1; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? 1 : 2; else return (c == 0) ? 0 : 1; }
    else { if (r == 0) return (c == 0) ? 2 : 1; else return (c == 0) ? 1 : 0; }
}
void main() {
    int row = 4 + int(gl_GlobalInvocationID.y);
    int col = 4 + (getBayerColor(uCfaPattern, 0, row) & 1) + 2 * int(gl_GlobalInvocationID.x);
    if (col >= uImageSize.x - 5 || row >= uImageSize.y - 5) return;
    int w = uImageSize.x, idx = row * w + col, lidx = idx / 2;
    float VH_Central_Value = VH_dir[idx];
    float VH_Neighbourhood_Value = 0.25*(VH_dir[idx - w - 1] + VH_dir[idx - w + 1] + VH_dir[idx + w - 1] + VH_dir[idx + w + 1]);
    float VH_Disc = (abs(0.5 - VH_Central_Value) < abs(0.5 - VH_Neighbourhood_Value)) ? VH_Neighbourhood_Value : VH_Central_Value;
    float cfai = cfa[idx];
    float N_Grad = eps + abs(cfa[idx - w] - cfa[idx + w]) + abs(cfai - cfa[idx - 2*w]) + abs(cfa[idx - w] - cfa[idx - 3*w]) + abs(cfa[idx - 2*w] - cfa[idx - 4*w]);
    float S_Grad = eps + abs(cfa[idx + w] - cfa[idx - w]) + abs(cfai - cfa[idx + 2*w]) + abs(cfa[idx + w] - cfa[idx + 3*w]) + abs(cfa[idx + 2*w] - cfa[idx + 4*w]);
    float W_Grad = eps + abs(cfa[idx - 1] - cfa[idx + 1]) + abs(cfai - cfa[idx - 2]) + abs(cfa[idx - 1] - cfa[idx - 3]) + abs(cfa[idx - 2] - cfa[idx - 4]);
    float E_Grad = eps + abs(cfa[idx + 1] - cfa[idx - 1]) + abs(cfai - cfa[idx + 2]) + abs(cfa[idx + 1] - cfa[idx + 3]) + abs(cfa[idx + 2] - cfa[idx + 4]);
    float lfpi = lpf[lidx];
    float N_Est = cfa[idx - w] * (lfpi + lfpi) / (eps + lfpi + lpf[lidx - w]);
    float S_Est = cfa[idx + w] * (lfpi + lfpi) / (eps + lfpi + lpf[lidx + w]);
    float W_Est = cfa[idx - 1] * (lfpi + lfpi) / (eps + lfpi + lpf[lidx - 1]);
    float E_Est = cfa[idx + 1] * (lfpi + lfpi) / (eps + lfpi + lpf[lidx + 1]);
    float V_Est = (S_Grad * N_Est + N_Grad * S_Est) / (N_Grad + S_Grad);
    float H_Est = (W_Grad * E_Est + E_Grad * W_Est) / (E_Grad + W_Grad);
    rgb1[idx] = mix(V_Est, H_Est, clamp(VH_Disc, 0.0, 1.0));
}
"""

    val STEP_4_0 = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer CFA_Buf    { float cfa[]; };
layout(std430, binding = 6) buffer P_Diff_Buf { float p_diff[]; };
layout(std430, binding = 7) buffer Q_Diff_Buf { float q_diff[]; };
uniform ivec2 uImageSize;
float fsquare(float x) { return x * x; }
void main() {
    int row = 3 + int(gl_GlobalInvocationID.y), col = 3 + 2 * int(gl_GlobalInvocationID.x);
    if (col >= uImageSize.x - 4 || row >= uImageSize.y - 4) return;
    int w = uImageSize.x, idx = row * w + col, idx2 = idx / 2;
    p_diff[idx2] = fsquare((cfa[idx - 3*w - 3] - cfa[idx - w - 1] - cfa[idx + w + 1] + cfa[idx + 3*w + 3]) - 3.0*(cfa[idx - 2*w - 2] + cfa[idx + 2*w + 2]) + 6.0*cfa[idx]);
    q_diff[idx2] = fsquare((cfa[idx - 3*w + 3] - cfa[idx - w + 1] - cfa[idx + w - 1] + cfa[idx + 3*w - 3]) - 3.0*(cfa[idx - 2*w + 2] + cfa[idx + 2*w - 2]) + 6.0*cfa[idx]);
}
"""

    val STEP_4_1 = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 6) buffer P_Diff_Buf { float p_diff[]; };
layout(std430, binding = 7) buffer Q_Diff_Buf { float q_diff[]; };
layout(std430, binding = 5) buffer PQ_Dir_Buf { float PQ_dir[]; };
uniform ivec2 uImageSize;
uniform int uCfaPattern;
#define epssq 1e-10f
int getBayerColor(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? 0 : 1; else return (c == 0) ? 1 : 2; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? 1 : 0; else return (c == 0) ? 2 : 1; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? 1 : 2; else return (c == 0) ? 0 : 1; }
    else { if (r == 0) return (c == 0) ? 2 : 1; else return (c == 0) ? 1 : 0; }
}
void main() {
    int row = 2 + int(gl_GlobalInvocationID.y);
    int col = 2 + (getBayerColor(uCfaPattern, 0, row) & 1) + 2 * int(gl_GlobalInvocationID.x);
    if (col >= uImageSize.x - 3 || row >= uImageSize.y - 3) return;
    int w = uImageSize.x, idx = row * w + col, idx2 = idx / 2;
    int idx3 = (idx - w - 1) / 2, idx4 = (idx + w - 1) / 2;
    float P_Stat = max(epssq, p_diff[idx3] + p_diff[idx2] + p_diff[idx4 + 1]);
    float Q_Stat = max(epssq, q_diff[idx3 + 1] + q_diff[idx2] + q_diff[idx4]);
    PQ_dir[idx2] = P_Stat / (P_Stat + Q_Stat);
}
"""

    val STEP_4_2 = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer CFA_Buf    { float cfa[]; };
layout(std430, binding = 1) buffer RGB0_Buf   { float rgb0[]; };
layout(std430, binding = 2) buffer RGB1_Buf   { float rgb1[]; };
layout(std430, binding = 3) buffer RGB2_Buf   { float rgb2[]; };
layout(std430, binding = 4) buffer PQ_Dir_Buf { float PQ_dir[]; };
uniform ivec2 uImageSize;
uniform int uCfaPattern;
#define eps 1e-5f
#define RED 0
#define GREEN 1
#define BLUE 2
int getBayerColor(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? 0 : 1; else return (c == 0) ? 1 : 2; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? 1 : 0; else return (c == 0) ? 2 : 1; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? 1 : 2; else return (c == 0) ? 0 : 1; }
    else { if (r == 0) return (c == 0) ? 2 : 1; else return (c == 0) ? 1 : 0; }
}
void main() {
    int row = 4 + int(gl_GlobalInvocationID.y);
    int col = 4 + (getBayerColor(uCfaPattern, 0, row) & 1) + 2 * int(gl_GlobalInvocationID.x);
    if (col >= uImageSize.x - 4 || row >= uImageSize.y - 4) return;
    int w = uImageSize.x, idx = row * w + col, pqidx = idx / 2;
    int pqidx2 = (idx - w - 1) / 2, pqidx3 = (idx + w - 1) / 2;
    int targetColor = 2 - getBayerColor(uCfaPattern, col, row);
    float PQ_Central = PQ_dir[pqidx];
    float PQ_Neighbour = 0.25*(PQ_dir[pqidx2] + PQ_dir[pqidx2 + 1] + PQ_dir[pqidx3] + PQ_dir[pqidx3 + 1]);
    float PQ_Disc = (abs(0.5 - PQ_Central) < abs(0.5 - PQ_Neighbour)) ? PQ_Neighbour : PQ_Central;
    float PQC = clamp(PQ_Disc, 0.0, 1.0);
    if (targetColor == RED) {
        float NW_Grad = eps + abs(rgb0[idx - w - 1] - rgb0[idx + w + 1]) + abs(rgb0[idx - w - 1] - rgb0[idx - 3*w - 3]) + abs(rgb1[idx] - rgb1[idx - 2*w - 2]);
        float NE_Grad = eps + abs(rgb0[idx - w + 1] - rgb0[idx + w - 1]) + abs(rgb0[idx - w + 1] - rgb0[idx - 3*w + 3]) + abs(rgb1[idx] - rgb1[idx - 2*w + 2]);
        float SW_Grad = eps + abs(rgb0[idx - w + 1] - rgb0[idx + w - 1]) + abs(rgb0[idx + w - 1] - rgb0[idx + 3*w - 3]) + abs(rgb1[idx] - rgb1[idx + 2*w - 2]);
        float SE_Grad = eps + abs(rgb0[idx - w - 1] - rgb0[idx + w + 1]) + abs(rgb0[idx + w + 1] - rgb0[idx + 3*w + 3]) + abs(rgb1[idx] - rgb1[idx + 2*w + 2]);
        float NW_Est = rgb0[idx - w - 1] - rgb1[idx - w - 1];
        float NE_Est = rgb0[idx - w + 1] - rgb1[idx - w + 1];
        float SW_Est = rgb0[idx + w - 1] - rgb1[idx + w - 1];
        float SE_Est = rgb0[idx + w + 1] - rgb1[idx + w + 1];
        float P_Est = (NW_Grad * SE_Est + SE_Grad * NW_Est) / (NW_Grad + SE_Grad);
        float Q_Est = (NE_Grad * SW_Est + SW_Grad * NE_Est) / (NE_Grad + SW_Grad);
        rgb0[idx] = rgb1[idx] + mix(P_Est, Q_Est, PQC);
    } else if (targetColor == BLUE) {
        float NW_Grad = eps + abs(rgb2[idx - w - 1] - rgb2[idx + w + 1]) + abs(rgb2[idx - w - 1] - rgb2[idx - 3*w - 3]) + abs(rgb1[idx] - rgb1[idx - 2*w - 2]);
        float NE_Grad = eps + abs(rgb2[idx - w + 1] - rgb2[idx + w - 1]) + abs(rgb2[idx - w + 1] - rgb2[idx - 3*w + 3]) + abs(rgb1[idx] - rgb1[idx - 2*w + 2]);
        float SW_Grad = eps + abs(rgb2[idx - w + 1] - rgb2[idx + w - 1]) + abs(rgb2[idx + w - 1] - rgb2[idx + 3*w - 3]) + abs(rgb1[idx] - rgb1[idx + 2*w - 2]);
        float SE_Grad = eps + abs(rgb2[idx - w - 1] - rgb2[idx + w + 1]) + abs(rgb2[idx + w + 1] - rgb2[idx + 3*w + 3]) + abs(rgb1[idx] - rgb1[idx + 2*w + 2]);
        float NW_Est = rgb2[idx - w - 1] - rgb1[idx - w - 1];
        float NE_Est = rgb2[idx - w + 1] - rgb1[idx - w + 1];
        float SW_Est = rgb2[idx + w - 1] - rgb1[idx + w - 1];
        float SE_Est = rgb2[idx + w + 1] - rgb1[idx + w + 1];
        float P_Est = (NW_Grad * SE_Est + SE_Grad * NW_Est) / (NW_Grad + SE_Grad);
        float Q_Est = (NE_Grad * SW_Est + SW_Grad * NE_Est) / (NE_Grad + SW_Grad);
        rgb2[idx] = rgb1[idx] + mix(P_Est, Q_Est, PQC);
    }
}
"""

    val STEP_4_3 = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer CFA_Buf    { float cfa[]; };
layout(std430, binding = 1) buffer RGB0_Buf   { float rgb0[]; };
layout(std430, binding = 2) buffer RGB1_Buf   { float rgb1[]; };
layout(std430, binding = 3) buffer RGB2_Buf   { float rgb2[]; };
layout(std430, binding = 4) buffer VH_Dir_Buf { float VH_dir[]; };
uniform ivec2 uImageSize;
uniform int uCfaPattern;
#define eps 1e-5f
int getBayerColor(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? 0 : 1; else return (c == 0) ? 1 : 2; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? 1 : 0; else return (c == 0) ? 2 : 1; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? 1 : 2; else return (c == 0) ? 0 : 1; }
    else { if (r == 0) return (c == 0) ? 2 : 1; else return (c == 0) ? 1 : 0; }
}
void main() {
    int row = 4 + int(gl_GlobalInvocationID.y);
    int col = 4 + (getBayerColor(uCfaPattern, 1, row) & 1) + 2 * int(gl_GlobalInvocationID.x);
    if (col >= uImageSize.x - 4 || row >= uImageSize.y - 4) return;
    int w = uImageSize.x, idx = row * w + col;
    float VH_Central = VH_dir[idx];
    float VH_Neighbour = 0.25*(VH_dir[idx - w - 1] + VH_dir[idx - w + 1] + VH_dir[idx + w - 1] + VH_dir[idx + w + 1]);
    float VH_Disc = (abs(0.5 - VH_Central) < abs(0.5 - VH_Neighbour)) ? VH_Neighbour : VH_Central;
    float VHC = clamp(VH_Disc, 0.0, 1.0);
    float rgbi1 = rgb1[idx];
    float N1 = eps + abs(rgbi1 - rgb1[idx - 2*w]);
    float S1 = eps + abs(rgbi1 - rgb1[idx + 2*w]);
    float W1 = eps + abs(rgbi1 - rgb1[idx - 2]);
    float E1 = eps + abs(rgbi1 - rgb1[idx + 2]);
    {
        float SNabs = abs(rgb0[idx - w] - rgb0[idx + w]);
        float EWabs = abs(rgb0[idx - 1] - rgb0[idx + 1]);
        float N_Grad = N1 + SNabs + abs(rgb0[idx - w] - rgb0[idx - 3*w]);
        float S_Grad = S1 + SNabs + abs(rgb0[idx + w] - rgb0[idx + 3*w]);
        float W_Grad = W1 + EWabs + abs(rgb0[idx - 1] - rgb0[idx - 3]);
        float E_Grad = E1 + EWabs + abs(rgb0[idx + 1] - rgb0[idx + 3]);
        float N_Est = rgb0[idx - w] - rgb1[idx - w];
        float S_Est = rgb0[idx + w] - rgb1[idx + w];
        float W_Est = rgb0[idx - 1] - rgb1[idx - 1];
        float E_Est = rgb0[idx + 1] - rgb1[idx + 1];
        float V_Est = (N_Grad * S_Est + S_Grad * N_Est) / (N_Grad + S_Grad);
        float H_Est = (E_Grad * W_Est + W_Grad * E_Est) / (E_Grad + W_Grad);
        rgb0[idx] = rgb1[idx] + mix(V_Est, H_Est, VHC);
    }
    {
        float SNabs = abs(rgb2[idx - w] - rgb2[idx + w]);
        float EWabs = abs(rgb2[idx - 1] - rgb2[idx + 1]);
        float N_Grad = N1 + SNabs + abs(rgb2[idx - w] - rgb2[idx - 3*w]);
        float S_Grad = S1 + SNabs + abs(rgb2[idx + w] - rgb2[idx + 3*w]);
        float W_Grad = W1 + EWabs + abs(rgb2[idx - 1] - rgb2[idx - 3]);
        float E_Grad = E1 + EWabs + abs(rgb2[idx + 1] - rgb2[idx + 3]);
        float N_Est = rgb2[idx - w] - rgb1[idx - w];
        float S_Est = rgb2[idx + w] - rgb1[idx + w];
        float W_Est = rgb2[idx - 1] - rgb1[idx - 1];
        float E_Est = rgb2[idx + 1] - rgb1[idx + 1];
        float V_Est = (N_Grad * S_Est + S_Grad * N_Est) / (N_Grad + S_Grad);
        float H_Est = (E_Grad * W_Est + W_Grad * E_Est) / (E_Grad + W_Grad);
        rgb2[idx] = rgb1[idx] + mix(V_Est, H_Est, VHC);
    }
}
"""

    val WRITE_OUTPUT = """
#version 310 es
precision highp float;
precision highp int;
layout (local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer CFA_Buf    { float cfa[]; };
layout(std430, binding = 1) buffer RGB0_Buf   { float rgb0[]; };
layout(std430, binding = 2) buffer RGB1_Buf   { float rgb1[]; };
layout(std430, binding = 3) buffer RGB2_Buf   { float rgb2[]; };
layout (rgba16f, binding = 0) writeonly uniform highp image2D uOutputImage;
uniform ivec2 uImageSize;
uniform int uCfaPattern;
uniform vec3 uCalculationGains;
#define RED 0
#define GREEN 1
#define BLUE 2
const int RCD_OUTPUT_MARGIN = $OUTPUT_MARGIN;
int getBayerColor(int cfaPattern, int col, int row) {
    int r = row % 2; int c = col % 2;
    if (cfaPattern == 0) { if (r == 0) return (c == 0) ? RED : GREEN; return (c == 0) ? GREEN : BLUE; }
    else if (cfaPattern == 1) { if (r == 0) return (c == 0) ? GREEN : RED; return (c == 0) ? BLUE : GREEN; }
    else if (cfaPattern == 2) { if (r == 0) return (c == 0) ? GREEN : BLUE; return (c == 0) ? RED : GREEN; }
    else { if (r == 0) return (c == 0) ? BLUE : GREEN; return (c == 0) ? GREEN : RED; }
}
int mirrorIndex(int value, int size) {
    if (size <= 1) return 0;
    int period = 2 * (size - 1);
    int wrapped = value % period;
    if (wrapped < 0) wrapped += period;
    return (wrapped < size) ? wrapped : period - wrapped;
}
ivec2 mirrorCoord(ivec2 coord) { return ivec2(mirrorIndex(coord.x, uImageSize.x), mirrorIndex(coord.y, uImageSize.y)); }
int indexAt(ivec2 coord) { ivec2 safe = mirrorCoord(coord); return safe.y * uImageSize.x + safe.x; }
int colorAt(ivec2 coord) { ivec2 safe = mirrorCoord(coord); return getBayerColor(uCfaPattern, safe.x, safe.y); }
float rawAt(ivec2 coord) { return cfa[indexAt(coord)]; }
$PPG_KERNEL
void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;
    vec3 color;
    if (coord.x >= RCD_OUTPUT_MARGIN && coord.x < uImageSize.x - RCD_OUTPUT_MARGIN && coord.y >= RCD_OUTPUT_MARGIN && coord.y < uImageSize.y - RCD_OUTPUT_MARGIN) {
        int idx = coord.y * uImageSize.x + coord.x;
        color = max(vec3(rgb0[idx], rgb1[idx], rgb2[idx]), vec3(0.0));
    } else {
        color = ppgColorAt(coord);
    }
    color /= max(uCalculationGains, vec3(1e-6));
    imageStore(uOutputImage, coord, vec4(color, 1.0));
}
"""
}
