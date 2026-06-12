package com.marc.parallax3d.gl

// Depth blit: OES packed frame right half -> R8 FBO.
// Vertex shaders can't reliably sample samplerExternalOES (driver lottery),
// so depth goes through this regular-texture hop first. Never skip.
const val BLIT_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uStMatrix;
out vec2 vUv;
void main() {
    // aPos 0..1 quad -> right half of packed frame, through SurfaceTexture matrix
    vec2 packedUv = vec2(0.5 + aPos.x * 0.5, aPos.y);
    vUv = (uStMatrix * vec4(packedUv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
}
"""

const val BLIT_FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uVideo;
in vec2 vUv;
out vec4 outColor;
void main() {
    // Depth stored as gray; any channel works
    outColor = vec4(texture(uVideo, vUv).r);
}
"""

// Parallax warp: grid mesh, vertex displaced by depth around convergence plane.
const val WARP_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;       // grid 0..1
uniform sampler2D uDepth;                // R8 from blit pass
uniform vec2 uEyeOffset;                 // head offset, ~[-1,1]
uniform float uStrength;                 // max parallax in NDC units
uniform float uConvergence;              // depth value at screen plane
uniform vec2 uScale;                     // aspect-fit * overscan
out vec2 vUv;
void main() {
    float depth = texture(uDepth, aPos).r;
    // Near (bright) moves opposite head motion, far moves with it
    vec2 shift = uEyeOffset * (depth - uConvergence) * uStrength;
    vec2 ndc = (aPos * 2.0 - 1.0) * uScale + shift;
    vUv = aPos;
    // z from depth: near wins at mesh fold-overs (depth test LESS).
    // Without this, background triangles can paint over foreground.
    gl_Position = vec4(ndc.x, -ndc.y, 0.5 - depth * 0.99, 1.0);
}
"""

const val WARP_FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uVideo;
uniform mat4 uStMatrix;
in vec2 vUv;
out vec4 outColor;
void main() {
    // Color = left half of packed frame
    vec2 packedUv = (uStMatrix * vec4(vUv.x * 0.5, vUv.y, 0.0, 1.0)).xy;
    outColor = texture(uVideo, packedUv);
}
"""
