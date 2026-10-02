/*
 * angrylion-rdp-plus, without a window.
 *
 * The plugin's own output (src/output/vdac.c + src/plugin/mupen64plus/
 * screen.c) uploads every frame to an OpenGL texture and draws it. Nothing
 * here has a display, and nothing needs one: the frame angrylion hands to
 * vdac_write() is already the finished VI output, pixel for pixel. So this
 * file replaces both with a copy into memory, which ReadScreen2 (the core's
 * screenshot path) and headless_frame() (our frontend, emu/n64emu.py) read.
 *
 * Built in place of vdac.c, screen.c and gl_core_3_3.c by emu/build.sh.
 */
#include "core/n64video.h"
#include "output/screen.h"
#include "output/vdac.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define HEADLESS_EXPORT __declspec(dllexport)
#else
#define HEADLESS_EXPORT __attribute__((visibility("default")))
#endif

static struct n64video_pixel* frame;
static uint32_t frame_width, frame_height, frame_height_out;
static uint32_t frame_count;

/* The rest of the plugin still refers to these (gfx_m64p.c sets them from
 * the Video-General config section). */
int32_t win_width = 640;
int32_t win_height = 480;
int32_t win_fullscreen;

void screen_init(struct n64video_config* config) { (void)config; }
void screen_adjust(int32_t width_out, int32_t height_out, int32_t* width, int32_t* height, int32_t* x, int32_t* y)
{
    *width = width_out;
    *height = height_out;
    *x = 0;
    *y = 0;
}
void screen_update(void) {}
void screen_toggle_fullscreen(void) {}
void screen_close(void) {}

void vdac_init(struct n64video_config* config) { (void)config; }

void vdac_write(struct n64video_frame_buffer* fb)
{
    if (fb->width != frame_width || fb->height != frame_height) {
        free(frame);
        frame = malloc((size_t)fb->width * fb->height * sizeof(*frame));
        frame_width = frame ? fb->width : 0;
        frame_height = frame ? fb->height : 0;
    }
    if (!frame) {
        return;
    }
    for (uint32_t y = 0; y < fb->height; y++) {
        memcpy(&frame[y * fb->width], &fb->pixels[y * fb->pitch], fb->width * sizeof(*frame));
    }
    frame_height_out = fb->height_out;
    frame_count++;
}

void vdac_read(struct n64video_frame_buffer* fb, bool alpha)
{
    fb->width = frame_width;
    fb->height = fb->height_out = frame_height;
    fb->pitch = frame_width;
    if (!fb->pixels || !frame) {
        return;
    }
    /* ReadScreen2 wants RGB, bottom row first (it came from glReadPixels). */
    uint8_t* out = (uint8_t*)fb->pixels;
    for (uint32_t y = 0; y < frame_height; y++) {
        const struct n64video_pixel* row = &frame[(frame_height - 1 - y) * frame_width];
        for (uint32_t x = 0; x < frame_width; x++) {
            *out++ = row[x].r;
            *out++ = row[x].g;
            *out++ = row[x].b;
            if (alpha) {
                *out++ = 0xff;
            }
        }
    }
}

void vdac_sync(bool valid) { (void)valid; }

void vdac_close(void)
{
    free(frame);
    frame = NULL;
    frame_width = frame_height = frame_height_out = 0;
}

/*
 * The last finished frame, top row first, as angrylion's RGBA pixels (the
 * alpha byte is not meaningful). *height_out is the height the VI scales it
 * to on a television; returns how many frames have been written so far, so a
 * caller can tell a new frame from the same one read twice.
 */
HEADLESS_EXPORT uint32_t headless_frame(const uint8_t** pixels, uint32_t* width, uint32_t* height, uint32_t* height_out)
{
    *pixels = (const uint8_t*)frame;
    *width = frame_width;
    *height = frame_height;
    *height_out = frame_height_out;
    return frame_count;
}
