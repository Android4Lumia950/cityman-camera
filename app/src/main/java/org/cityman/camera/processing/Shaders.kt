package org.cityman.camera.processing

/**
 * GLSL ES 3.10 compute shaders of the multi-frame pipeline.
 *
 * "Quad" images hold one 2×2 Bayer block per texel: channels R, G (even row), G (odd row), B,
 * black-subtracted and normalised to 0..1. Alignment works on a grey pyramid built from them;
 * offsets are whole quad pixels, so the colour pattern never shifts.
 */
object Shaders {
    private const val HEADER = """#version 310 es
precision highp float;
precision highp int;
precision highp image2D;
precision highp iimage2D;
precision highp sampler2D;
precision highp usampler2D;
"""
    private const val GRID = HEADER + "layout(local_size_x = 8, local_size_y = 8) in;\n"

    /**
     * Bayer RAW16 → quad, black level removed. Hot and dead pixels are replaced on the way: a
     * sample far above (or below) its four nearest same-colour neighbours takes their median.
     */
    val PACK = GRID + """
uniform highp usampler2D uRaw;
layout(rgba16f, binding = 0) writeonly uniform image2D uQuad;
uniform ivec2 uOff[4];
uniform vec4 uBlack;
uniform float uWhite;
float raw(ivec2 p, ivec2 lim) {
    return float(texelFetch(uRaw, clamp(p, ivec2(0), lim), 0).r);
}
void main() {
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(q, imageSize(uQuad)))) return;
    ivec2 lim = textureSize(uRaw, 0) - 1;
    ivec2 b = q * 2;
    vec4 v;
    for (int c = 0; c < 4; c++) {
        ivec2 p = b + uOff[c];
        float x = raw(p, lim);
        float n0 = raw(p + ivec2(2, 0), lim), n1 = raw(p - ivec2(2, 0), lim);
        float n2 = raw(p + ivec2(0, 2), lim), n3 = raw(p - ivec2(0, 2), lim);
        float hi = max(max(n0, n1), max(n2, n3));
        float lo = min(min(n0, n1), min(n2, n3));
        float range = uWhite - uBlack[c];
        if (x > hi + 0.5 * (hi - uBlack[c]) + 0.03 * range || x < lo - 0.5 * (lo - uBlack[c]) - 0.03 * range) {
            // median of four = mean of the two middle values
            x = 0.5 * (n0 + n1 + n2 + n3 - hi - lo);
        }
        v[c] = x;
    }
    v = (v - uBlack) / (uWhite - uBlack);
    imageStore(uQuad, q, clamp(v, -0.1, 1.0));
}
"""

    /** Quad → grey (mean of the four samples). */
    val GRAY = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uQuad;
layout(r32f, binding = 1) writeonly uniform image2D uGray;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, imageSize(uGray)))) return;
    imageStore(uGray, p, vec4(dot(imageLoad(uQuad, p), vec4(0.25))));
}
"""

    /** Box downsample by an integer factor. */
    val DOWN = GRID + """
layout(r32f, binding = 0) readonly uniform image2D uSrc;
layout(r32f, binding = 1) writeonly uniform image2D uDst;
uniform int uF;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, imageSize(uDst)))) return;
    ivec2 s = imageSize(uSrc) - 1;
    float acc = 0.0;
    for (int y = 0; y < uF; y++)
        for (int x = 0; x < uF; x++)
            acc += imageLoad(uSrc, min(p * uF + ivec2(x, y), s)).r;
    imageStore(uDst, p, vec4(acc / float(uF * uF)));
}
"""

    /**
     * Tile alignment on one pyramid level: start from the coarser level's offset, search ±uRadius,
     * keep the offset with the lowest L1 or L2 difference over the tile.
     */
    val ALIGN = GRID + """
layout(r32f, binding = 0) readonly uniform image2D uRef;
layout(r32f, binding = 1) readonly uniform image2D uAlt;
layout(rgba16i, binding = 2) readonly uniform iimage2D uPrev;
layout(rgba16i, binding = 3) writeonly uniform iimage2D uOut;
uniform int uTile;
uniform int uRadius;
uniform int uFactor;
uniform int uPrevTile;
uniform int uL2;
uniform int uStep;
uniform int uSub;
void main() {
    ivec2 t = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(t, imageSize(uOut)))) return;
    ivec2 sz = imageSize(uRef) - 1;
    ivec2 origin = t * uTile;
    ivec2 init = ivec2(0);
    if (uFactor > 0) {
        ivec2 c = (origin + uTile / 2) / uFactor;
        ivec2 pt = clamp(c / uPrevTile, ivec2(0), imageSize(uPrev) - 1);
        init = imageLoad(uPrev, pt).xy * uFactor;
    }
    float best = 3.0e38;
    ivec2 bestD = init;
    float cs[9];
    for (int dy = -uRadius; dy <= uRadius; dy++) {
        for (int dx = -uRadius; dx <= uRadius; dx++) {
            ivec2 d = init + ivec2(dx, dy);
            float cost = 0.0;
            for (int y = 0; y < uTile; y += uStep) {
                for (int x = 0; x < uTile; x += uStep) {
                    ivec2 p = origin + ivec2(x, y);
                    float e = imageLoad(uRef, min(p, sz)).r - imageLoad(uAlt, clamp(p + d, ivec2(0), sz)).r;
                    cost += (uL2 == 1) ? e * e : abs(e);
                }
            }
            if (uRadius == 1) cs[(dy + 1) * 3 + dx + 1] = cost;
            // Prefer no extra motion on ties (flat areas).
            if (cost < best - 1.0e-6 || (dx == 0 && dy == 0 && cost <= best)) { best = cost; bestD = d; }
        }
    }
    ivec2 sub = ivec2(0);
    if (uSub == 1 && uRadius == 1 && bestD == init) {
        // Parabola through the costs left/centre/right (and up/centre/down): sub-pixel offset.
        float dxx = cs[3] - 2.0 * cs[4] + cs[5];
        float dyy = cs[1] - 2.0 * cs[4] + cs[7];
        float sx = dxx > 1.0e-9 ? clamp(0.5 * (cs[3] - cs[5]) / dxx, -0.5, 0.5) : 0.0;
        float sy = dyy > 1.0e-9 ? clamp(0.5 * (cs[1] - cs[7]) / dyy, -0.5, 0.5) : 0.0;
        sub = ivec2(round(vec2(sx, sy) * 256.0));
    }
    imageStore(uOut, t, ivec4(bestD, sub));
}
"""

    /**
     * Noise estimate: histogram of |alt − ref| (aligned) per brightness bin, sampled on a sparse
     * grid. 16 brightness bins (sqrt spacing) × 64 difference bins (sqrt spacing up to 0.12).
     */
    val NOISE = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uRef;
layout(rgba16f, binding = 1) readonly uniform image2D uAlt;
layout(rgba16i, binding = 2) readonly uniform iimage2D uAlign;
layout(std430, binding = 3) buffer Hist { uint h[]; };
uniform int uTile;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy) * 4;
    ivec2 sz = imageSize(uRef);
    if (any(greaterThanEqual(p, sz))) return;
    ivec2 off = imageLoad(uAlign, min(p / uTile, imageSize(uAlign) - 1)).xy;
    vec4 r = imageLoad(uRef, p);
    vec4 a = imageLoad(uAlt, clamp(p + off, ivec2(0), sz - 1));
    for (int c = 0; c < 4; c++) {
        int bb = clamp(int(sqrt(max(r[c], 0.0)) * 16.0), 0, 15);
        int db = min(int(sqrt(abs(a[c] - r[c]) / 0.12) * 64.0), 63);
        atomicAdd(h[bb * 64 + db], 1u);
    }
}
"""

    /** Accumulator = reference, weight 1. */
    val INIT = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uRef;
layout(rgba16f, binding = 1) writeonly uniform image2D uAcc;
layout(r32f, binding = 2) writeonly uniform image2D uW;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, imageSize(uRef)))) return;
    imageStore(uAcc, p, imageLoad(uRef, p));
    imageStore(uW, p, vec4(1.0));
}
"""

    /**
     * Robust temporal merge, pass 1, per 2×2 block of quad pixels: among the block's tile offset
     * and the two nearest neighbouring tiles' offsets, pick the one that matches the reference
     * best over a 4×4 grey window, and turn the remaining difference into a weight (1 when it
     * looks like noise, towards 0 when it looks like motion or misalignment).
     */
    val MERGE_DECIDE = GRID + """
layout(r32f, binding = 0) readonly uniform image2D uRefGray;
layout(r32f, binding = 1) readonly uniform image2D uAltGray;
layout(rgba16i, binding = 2) readonly uniform iimage2D uAlign;
layout(rgba16f, binding = 3) writeonly uniform image2D uDec;
uniform int uTile;
uniform vec2 uNoise;
uniform float uStrength;
float r[16];
float sad(ivec2 p0, ivec2 o, ivec2 sz) {
    float s = 0.0;
    for (int i = 0; i < 16; i++)
        s += abs(imageLoad(uAltGray, clamp(p0 + ivec2(i % 4 - 1, i / 4 - 1) + o, ivec2(0), sz)).r - r[i]);
    return s / 16.0;
}
void main() {
    ivec2 b = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(b, imageSize(uDec)))) return;
    ivec2 sz = imageSize(uRefGray) - 1;
    ivec2 p0 = b * 2;
    for (int i = 0; i < 16; i++) r[i] = imageLoad(uRefGray, clamp(p0 + ivec2(i % 4 - 1, i / 4 - 1), ivec2(0), sz)).r;
    ivec2 nt = imageSize(uAlign) - 1;
    ivec2 c = p0 + 1;
    ivec2 tile = min(c / uTile, nt);
    ivec2 within = c - tile * uTile;
    ivec2 tx = clamp(tile + ivec2(within.x < uTile / 2 ? -1 : 1, 0), ivec2(0), nt);
    ivec2 ty = clamp(tile + ivec2(0, within.y < uTile / 2 ? -1 : 1), ivec2(0), nt);
    ivec4 a0 = imageLoad(uAlign, tile);
    ivec4 a1 = imageLoad(uAlign, tx);
    ivec4 a2 = imageLoad(uAlign, ty);
    ivec2 o0 = a0.xy, o1 = a1.xy, o2 = a2.xy;
    float s0 = sad(p0, o0, sz);
    float s1 = (o1 == o0) ? s0 : sad(p0, o1, sz);
    float s2 = (o2 == o0 || o2 == o1) ? s0 : sad(p0, o2, sz);
    vec2 o = vec2(o0) + vec2(a0.zw) / 256.0;
    float s = s0;
    if (s1 < s) { s = s1; o = vec2(o1) + vec2(a1.zw) / 256.0; }
    if (s2 < s) { s = s2; o = vec2(o2) + vec2(a2.zw) / 256.0; }
    float x = max(0.25 * (r[5] + r[6] + r[9] + r[10]), 0.0);
    float sigma = sqrt(uNoise.x * x + uNoise.y);
    // Grey averages four samples, so its noise is half the per-channel sigma.
    float dn = s / (0.5 * sigma);
    float w = exp(-pow(max(dn - 1.3, 0.0) / uStrength, 2.0));
    imageStore(uDec, b, vec4(o, w, 0.0));
}
"""

    /** Merge pass 2, per quad pixel: add the aligned frame with its block's weight. */
    val MERGE_ADD = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uAlt;
layout(rgba16f, binding = 1) readonly uniform image2D uDec;
layout(rgba16f, binding = 2) readonly uniform image2D uAccIn;
layout(rgba16f, binding = 3) writeonly uniform image2D uAccOut;
layout(r32f, binding = 4) uniform image2D uW;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uAlt);
    if (any(greaterThanEqual(p, size))) return;
    vec4 d = imageLoad(uDec, p / 2);
    float w = d.z;
    // Bilinear between quad pixels: each colour plane is a regular grid, so this keeps the
    // pattern while following sub-pixel motion.
    vec2 q = vec2(p) + d.xy;
    ivec2 i0 = ivec2(floor(q));
    vec2 f = q - vec2(i0);
    ivec2 m = size - 1;
    vec4 a = mix(mix(imageLoad(uAlt, clamp(i0, ivec2(0), m)), imageLoad(uAlt, clamp(i0 + ivec2(1, 0), ivec2(0), m)), f.x),
                 mix(imageLoad(uAlt, clamp(i0 + ivec2(0, 1), ivec2(0), m)), imageLoad(uAlt, clamp(i0 + ivec2(1, 1), ivec2(0), m)), f.x), f.y);
    imageStore(uAccOut, p, imageLoad(uAccIn, p) + w * a);
    imageStore(uW, p, vec4(imageLoad(uW, p).r + w));
}
"""

    /**
     * Merged quad → lens shading and white balance applied (uMq, for demosaicing) and a half
     * resolution RGB image (uHalf, for colour noise reduction and the tone-mapping base).
     */
    val PREP = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uAcc;
layout(r32f, binding = 1) readonly uniform image2D uW;
layout(rgba16f, binding = 2) writeonly uniform image2D uMq;
layout(rgba16f, binding = 3) writeonly uniform image2D uHalf;
uniform sampler2D uLsc;
uniform vec2 uLscSize;
uniform vec4 uGains;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uMq);
    if (any(greaterThanEqual(p, size))) return;
    float w = imageLoad(uW, p).r;
    vec4 v = imageLoad(uAcc, p) / w;
    vec2 pos = (vec2(p) + 0.5) / vec2(size);
    vec2 uv = (pos * (uLscSize - 1.0) + 0.5) / uLscSize;
    vec4 lsc = clamp(texture(uLsc, uv), 1.0, 8.0);
    vec4 k = lsc * uGains;
    // Sensor saturation lands at a different level in each channel once shading and white
    // balance gains are applied; clip all of them to the lowest so blown highlights stay white.
    float clipLevel = min(min(k.x, k.y), min(k.z, k.w));
    v = min(max(v, 0.0) * k, vec4(clipLevel));
    imageStore(uMq, p, v);
    imageStore(uHalf, p, vec4(v.x, 0.5 * (v.y + v.z), v.w, 1.0));
}
"""

    /**
     * Spatial noise reduction of the merged quad image: a 5×5 bilateral filter guided by the
     * grey level, its range set from the noise model, the lens shading and white balance gains
     * and the number of frames that went into each pixel.
     */
    val DENOISE = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uMq;
layout(r32f, binding = 1) readonly uniform image2D uW;
layout(rgba16f, binding = 2) writeonly uniform image2D uOut;
uniform sampler2D uLsc;
uniform vec2 uLscSize;
uniform vec4 uGains;
uniform vec2 uNoise;
uniform float uStrength;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uOut);
    if (any(greaterThanEqual(p, size))) return;
    vec2 pos = (vec2(p) + 0.5) / vec2(size);
    vec2 uv = (pos * (uLscSize - 1.0) + 0.5) / uLscSize;
    vec4 k = clamp(texture(uLsc, uv), 1.0, 8.0) * uGains;
    vec4 c = imageLoad(uMq, p);
    float frames = max(imageLoad(uW, p).r, 1.0);
    vec4 raw = max(c / k, 0.0);
    // Noise of the grey guide (mean of the four channels) after merging.
    float varG = dot(k * k, uNoise.x * raw + uNoise.y) / (16.0 * frames);
    float h = uStrength * sqrt(varG);
    float inv = 1.0 / max(2.0 * h * h, 1.0e-12);
    float g0 = dot(c, vec4(0.25));
    vec4 acc = vec4(0.0);
    float wsum = 0.0;
    ivec2 s = size - 1;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            vec4 v = imageLoad(uMq, clamp(p + ivec2(x, y), ivec2(0), s));
            float dg = dot(v, vec4(0.25)) - g0;
            float w = exp(-float(x * x + y * y) / 4.5 - dg * dg * inv);
            acc += w * v;
            wsum += w;
        }
    }
    imageStore(uOut, p, acc / wsum);
}
"""

    /** Separable Gaussian (9 taps, sigma about 2) along uDir. */
    val BLUR = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uSrc;
layout(rgba16f, binding = 1) writeonly uniform image2D uDst;
uniform ivec2 uDir;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uDst);
    if (any(greaterThanEqual(p, size))) return;
    const float k[5] = float[5](0.2042, 0.1802, 0.1238, 0.0663, 0.0276);
    vec4 acc = imageLoad(uSrc, p) * k[0];
    for (int i = 1; i < 5; i++) {
        acc += imageLoad(uSrc, clamp(p + uDir * i, ivec2(0), size - 1)) * k[i];
        acc += imageLoad(uSrc, clamp(p - uDir * i, ivec2(0), size - 1)) * k[i];
    }
    imageStore(uDst, p, acc);
}
"""

    /** Mean log2 luminance over uF×uF blocks of the half image: the base layer for local tone mapping. */
    val BASE = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uHalf;
layout(rgba16f, binding = 1) writeonly uniform image2D uBase;
uniform int uF;
uniform mat3 uCcm;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, imageSize(uBase)))) return;
    ivec2 s = imageSize(uHalf) - 1;
    float acc = 0.0;
    float peak = 0.0;
    for (int y = 0; y < uF; y += 2)
        for (int x = 0; x < uF; x += 2) {
            vec3 c = uCcm * imageLoad(uHalf, min(p * uF + ivec2(x, y), s)).rgb;
            float l = max(dot(c, vec3(0.2126, 0.7152, 0.0722)), 1.0e-5);
            acc += log2(l);
            peak = max(peak, l);
        }
    float n = float(((uF + 1) / 2) * ((uF + 1) / 2));
    imageStore(uBase, p, vec4(acc / n, log2(peak), 0.0, 1.0));
}
"""

    /**
     * Demosaic (Malvar-He-Cutler), colour matrix, colour noise reduction, exposure, local tone
     * mapping, highlight shoulder, saturation and sRGB encoding, one output pixel per invocation.
     */
    val FINISH = GRID + """
layout(rgba16f, binding = 0) readonly uniform image2D uMq;
layout(rgba8, binding = 1) writeonly uniform image2D uOut;
uniform sampler2D uHalfBlur;
uniform sampler2D uBase;
uniform ivec4 uChanAt;   // channel index at (0,0), (1,0), (0,1), (1,1)
uniform mat3 uCcm;
uniform float uGain;
uniform float uChroma;
uniform float uCompress;      // large-scale contrast kept above the anchor (highlights)
uniform float uLift;          // large-scale contrast kept below the anchor (shadows)
uniform float uAnchor;
uniform float uBlack;
uniform float uContrast;
uniform float uSat;
uniform float uLocal;
ivec2 gSize;
ivec2 gQmax;
float m(ivec2 p) {
    p = clamp(p, ivec2(0), gSize - 1);
    ivec2 par = p & 1;
    int idx = par.y == 0 ? (par.x == 0 ? uChanAt.x : uChanAt.y) : (par.x == 0 ? uChanAt.z : uChanAt.w);
    return imageLoad(uMq, min(p >> 1, gQmax))[idx];
}
int chanAt(ivec2 p) {
    ivec2 par = p & 1;
    return par.y == 0 ? (par.x == 0 ? uChanAt.x : uChanAt.y) : (par.x == 0 ? uChanAt.z : uChanAt.w);
}
float srgb(float x) {
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}
float shoulder(float x) {
    const float t = 0.62;
    return x < t ? x : t + (1.0 - t) * (1.0 - exp(-(x - t) / (1.0 - t)));
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    gSize = imageSize(uOut);
    gQmax = imageSize(uMq) - 1;
    if (any(greaterThanEqual(p, gSize))) return;
    int c = chanAt(p);
    float C = m(p);
    float N = m(p + ivec2(0, -1)), S = m(p + ivec2(0, 1)), W = m(p + ivec2(-1, 0)), E = m(p + ivec2(1, 0));
    float NN = m(p + ivec2(0, -2)), SS = m(p + ivec2(0, 2)), WW = m(p + ivec2(-2, 0)), EE = m(p + ivec2(2, 0));
    float NW = m(p + ivec2(-1, -1)), NE = m(p + ivec2(1, -1)), SW = m(p + ivec2(-1, 1)), SE = m(p + ivec2(1, 1));
    float axis2 = NN + SS + WW + EE;
    float diag = NW + NE + SW + SE;
    vec3 rgb;
    if (c == 0 || c == 3) {
        // red or blue site
        float g = (4.0 * C + 2.0 * (N + S + W + E) - axis2) / 8.0;
        float o = (6.0 * C + 2.0 * diag - 1.5 * axis2) / 8.0;
        rgb = c == 0 ? vec3(C, g, o) : vec3(o, g, C);
    } else {
        // green site: is red in this row (left/right) or in this column (above/below)?
        bool redRow = chanAt(p + ivec2(1, 0)) == 0;
        float h = (5.0 * C + 4.0 * (W + E) - (WW + EE) - diag + 0.5 * (NN + SS)) / 8.0;
        float v = (5.0 * C + 4.0 * (N + S) - (NN + SS) - diag + 0.5 * (WW + EE)) / 8.0;
        rgb = redRow ? vec3(h, C, v) : vec3(v, C, h);
    }
    rgb = max(rgb, 0.0);

    vec2 uv = (vec2(p) + 0.5) / vec2(gSize);
    vec3 lin = uCcm * rgb;
    vec3 blur = uCcm * texture(uHalfBlur, uv).rgb;
    const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);
    float y = max(dot(lin, LUM), 0.0);
    float yb = max(dot(blur, LUM), 1.0e-6);
    lin = max(mix(lin, blur * (y / yb), uChroma), 0.0);

    lin *= uGain;
    float l = max(dot(lin, LUM), 1.0e-6);
    if (uLocal > 0.5) {
        float base = texture(uBase, uv).r + log2(uGain);
        float logL = log2(l);
        // Compress bright regions more than dark ones are lifted, so shadows keep their depth.
        float c = base > uAnchor ? uCompress : uLift;
        float newLog = logL + (c - 1.0) * (base - uAnchor);
        lin *= exp2(newLog - logL);
        l = max(dot(lin, LUM), 1.0e-6);
    }
    // Black point: a little crush gives the shadows weight again.
    float lb = max(l - uBlack, 0.0) / (1.0 - uBlack);
    lin *= lb / l;
    l = max(lb, 1.0e-6);
    float lt = shoulder(l);
    lin *= lt / l;
    float mx = max(lin.r, max(lin.g, lin.b));
    if (mx > 1.0) lin = mix(lin, vec3(lt), clamp((mx - 1.0) / mx, 0.0, 1.0));
    lin = max(vec3(lt) + (lin - vec3(lt)) * uSat, 0.0);
    vec3 outc = vec3(srgb(clamp(lin.r, 0.0, 1.0)), srgb(clamp(lin.g, 0.0, 1.0)), srgb(clamp(lin.b, 0.0, 1.0)));
    // gentle S curve
    outc = mix(outc, outc * outc * (3.0 - 2.0 * outc), uContrast);
    imageStore(uOut, p, vec4(outc, 1.0));
}
"""

    /**
     * Luma unsharp mask with a small noise threshold, written rotated clockwise by uRot degrees
     * so the JPEG needs no orientation tag.
     */
    val SHARPEN = GRID + """
layout(rgba8, binding = 0) readonly uniform image2D uSrc;
layout(rgba8, binding = 1) writeonly uniform image2D uDst;
uniform int uRot;
uniform float uAmount;
uniform int uCrop;
void main() {
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uSrc) - 2 * uCrop;
    if (any(greaterThanEqual(o, size))) return;
    ivec2 p = o + ivec2(uCrop);
    ivec2 s = imageSize(uSrc) - 1;
    vec3 c = imageLoad(uSrc, p).rgb;
    vec3 outc = c;
    if (uAmount > 0.0) {
        vec3 b = 4.0 * c;
        b += 2.0 * (imageLoad(uSrc, clamp(p + ivec2(1, 0), ivec2(0), s)).rgb + imageLoad(uSrc, clamp(p - ivec2(1, 0), ivec2(0), s)).rgb
                  + imageLoad(uSrc, clamp(p + ivec2(0, 1), ivec2(0), s)).rgb + imageLoad(uSrc, clamp(p - ivec2(0, 1), ivec2(0), s)).rgb);
        b += imageLoad(uSrc, clamp(p + ivec2(1, 1), ivec2(0), s)).rgb + imageLoad(uSrc, clamp(p + ivec2(-1, 1), ivec2(0), s)).rgb
           + imageLoad(uSrc, clamp(p + ivec2(1, -1), ivec2(0), s)).rgb + imageLoad(uSrc, clamp(p + ivec2(-1, -1), ivec2(0), s)).rgb;
        b /= 16.0;
        const vec3 LUM = vec3(0.299, 0.587, 0.114);
        float d = dot(c - b, LUM);
        d = sign(d) * max(abs(d) - 0.004, 0.0);
        outc = clamp(c + uAmount * d, 0.0, 1.0);
    }
    ivec2 m = size - 1;
    ivec2 q = o;
    if (uRot == 90) q = ivec2(m.y - o.y, o.x);
    else if (uRot == 180) q = ivec2(m.x - o.x, m.y - o.y);
    else if (uRot == 270) q = ivec2(o.y, m.x - o.x);
    imageStore(uDst, q, vec4(outc, 1.0));
}
"""
}
