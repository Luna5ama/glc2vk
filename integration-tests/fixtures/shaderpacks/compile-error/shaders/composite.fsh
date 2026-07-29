#version 120

#error VIBRIS_AUTOMATION_ROLLBACK

/* DRAWBUFFERS:0 */

void main() {
    gl_FragData[0] = VIBRIS_AUTOMATION_ROLLBACK;
}