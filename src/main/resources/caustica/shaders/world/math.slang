// Stateless math: luminance, GGX (D/G/VNDF), Fresnel, Henyey-Greenstein, the PCG stream, and direction
// sampling. No bindings, no globals, no rays.

module math;

import world_common;
import world_core;

public float luminance(float3 c) { return dot(c, ACESCG_LUMA); }

// GGX normal distribution.
// NOTE: the 1e-7 denominator guard is not merely a divide-by-zero epsilon, and it is load-bearing. At
// the lobe peak d == a2, so the guard dominates PI*d*d until a2 >> 1.8e-4, i.e. rough >> 0.116; every
// smoother material has its NEE highlight peak truncated. That truncation is what keeps the sun from
// being counted twice: a specular continuation sets showCelestial, so the BSDF-sampled ray may hit the
// sun disc in world.rmiss while NEE is also adding a sun term for the same lobe, with no MIS weighting
// between them. Removing the guard restores the physical peak AND the double count, and makes the
// single-sample sun estimator explode wherever the lobe is tighter than the disc (~0.0047 rad). The
// real fix is MIS (or a disc-aware specular light sample), not an epsilon edit — left as-is.
public float ggxD(float ndh, float alpha) {
    float a2 = alpha * alpha;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
public float ggxG1(float ndx, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

// Schlick's grazing limit is white, which is wrong for a surface with no interface at all: a billboard
// authored at specular_ior 1 has F0 = 0 and must stay matte edge-on rather than picking up a rim. Scaling
// the limit by F0 leaves every real material (F0 >= 0.02) at the usual full-white grazing response and
// collapses the lobe only where there is genuinely nothing to reflect off.
public float3 fresnelSchlick(float cosT, float3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    float grazing = clamp(50.0 * f0.g, 0.0, 1.0);
    return f0 + (grazing - f0) * (m2 * m2 * m);
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
public float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// PCG hash RNG.
public uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
public float rndf(inout uint s) {
    // Convert the high 24 bits, which are exactly representable as float. Converting all 32 bits first
    // lets the top 128 uint values round to 2^32, incorrectly returning 1.0 and breaking `< probability`
    // tests (most seriously the F == 1 total-internal-reflection branch).
    return float(pcg(s) >> 8u) * (1.0 / 16777216.0);
}

public float3 primaryRayDir(float2 ndc) {
    float4 nearH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

public float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

/**
 * Tangent frame for a celestial body's square, kept level with the horizon: {@code right} is horizontal
 * and {@code up} lies in the vertical plane through the body, so a body never rolls about its own centre
 * as it crosses the sky. A sky slot that draws a body must build its square in this same frame, or the
 * sampled light and the drawn body will not line up.
 */
void celestialSquareFrame(float3 bodyDir, out float3 right, out float3 up) {
    // Near the zenith the horizontal reference degenerates; the arc's tilt keeps the sun/moon well away
    // from it in practice, so any stable fallback will do.
    float3 reference = abs(bodyDir.y) > 0.9995 ? float3(0.0, 0.0, 1.0) : float3(0.0, 1.0, 0.0);
    right = normalize(cross(reference, bodyDir));
    up = cross(bodyDir, right);
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's sun/moon
// are square quads, so the NEE shadow ray samples a square (not a cone) — the same square, in the same
// horizon-levelled tangent frame (celestialSquareFrame above) a sky slot draws the visible body in.
// Averaged over frames by DLSS-RR this yields soft penumbrae that widen with occluder distance
// (contact-hardening) for free. halfAngle <= 0 ⇒ exact direction (hard).
public float3 sampleSquare(float3 axis, float halfAngle, inout uint s) {
    float3 right;
    float3 up;
    celestialSquareFrame(axis, right, up);
    float t = tan(halfAngle);
    float u = (rndf(s) * 2.0 - 1.0) * t;
    float v = (rndf(s) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. Paired with the separable Smith term, the
// importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
public float3 sampleGGXVNDF(float3 n, float3 ve, float alpha, inout uint s) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = alpha;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                 // -> world space
}
