#version 120

#include "/lib/vibris_fixture.glsl"

/* DRAWBUFFERS:0 */

uniform sampler2D gtexture;
varying vec2 texcoord;
varying vec4 vertexColor;

void main() {
    gl_FragData[0] = texture2D(gtexture, texcoord) * vertexColor * VIBRIS_FIXTURE_COLOR;
}