#version 330

// Converts the game picture to NV12 (BT.601, limited range) on the GPU, the way Medal and OBS do, so each
// frame copied to the CPU is 1.5 bytes per pixel instead of 4 and FFmpeg has no colour conversion left to do.
// Each output texel holds four bytes: rows below the picture height are the Y plane (top-down, so no flip is
// needed later) and the rows after it are the interleaved U/V plane at half resolution.

uniform sampler2D InSampler;

out vec4 fragColor;

vec3 pixel(int x, int y) {
    return texelFetch(InSampler, ivec2(x, y), 0).rgb;
}

float luma(vec3 c) {
    return (16.0 + 65.481 * c.r + 128.553 * c.g + 24.966 * c.b) / 255.0;
}

vec2 chroma(vec3 c) {
    return vec2(128.0 - 37.797 * c.r - 74.203 * c.g + 112.0 * c.b, 128.0 + 112.0 * c.r - 93.786 * c.g - 18.214 * c.b) / 255.0;
}

void main() {
    ivec2 size = textureSize(InSampler, 0);
    int height = size.y & ~1;
    int x = int(gl_FragCoord.x) * 4;
    int row = int(gl_FragCoord.y);
    if (row < height) {
        int y = size.y - 1 - row;
        fragColor = vec4(luma(pixel(x, y)), luma(pixel(x + 1, y)), luma(pixel(x + 2, y)), luma(pixel(x + 3, y)));
    } else {
        int y = size.y - 1 - (row - height) * 2;
        vec3 left = (pixel(x, y) + pixel(x + 1, y) + pixel(x, y - 1) + pixel(x + 1, y - 1)) * 0.25;
        vec3 right = (pixel(x + 2, y) + pixel(x + 3, y) + pixel(x + 2, y - 1) + pixel(x + 3, y - 1)) * 0.25;
        fragColor = vec4(chroma(left), chroma(right));
    }
}
