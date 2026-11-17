#version 330

// Shrinks the game picture to the clip size on the GPU before it is copied to the CPU, so a 1440p or 4K
// window only sends 1080p worth of data to the encoder. Four bilinear taps spread over the output pixel's
// footprint average the source pixels it covers, which keeps edges clean without a uniform for the sizes.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 footprint = vec2(dFdx(texCoord.x), dFdy(texCoord.y)) * 0.25;
    vec3 sum = texture(InSampler, texCoord + vec2(-footprint.x, -footprint.y)).rgb
        + texture(InSampler, texCoord + vec2(footprint.x, -footprint.y)).rgb
        + texture(InSampler, texCoord + vec2(-footprint.x, footprint.y)).rgb
        + texture(InSampler, texCoord + vec2(footprint.x, footprint.y)).rgb;
    fragColor = vec4(sum * 0.25, 1.0);
}
