// GBA Circle Demo - circle changes color/size with button presses
// Screen: 240x160, Mode 3 (16-bit bitmap)

typedef unsigned int   u32;
typedef unsigned short u16;
typedef unsigned char  u8;

// GBA memory-mapped registers
#define REG_DISPCNT  (*(volatile u16*)0x04000000)
#define REG_VCOUNT   (*(volatile u16*)0x04000006)
#define VIDEORAM     ((volatile u16*)0x06000000)
#define REG_KEYINPUT (*(volatile u16*)0x04000130)

// Display control
#define MODE3        0x0003
#define BG2_ENABLE   0x0400

// Screen dimensions
#define SCREEN_W  240
#define SCREEN_H  160
#define CENTER_X  120
#define CENTER_Y   80

// Key bits (0 = pressed)
#define KEY_A      0x0001
#define KEY_B      0x0002
#define KEY_SELECT 0x0004
#define KEY_START  0x0008
#define KEY_RIGHT  0x0010
#define KEY_LEFT   0x0020
#define KEY_UP     0x0040
#define KEY_DOWN   0x0080
#define KEY_R      0x0100
#define KEY_L      0x0200

#define KEY_PRESSED(k)  (!(REG_KEYINPUT & (k)))

// RGB15 color macro
#define RGB15(r,g,b) ((r) | ((g)<<5) | ((b)<<10))

// Color palette: 8 vivid colors
static const u16 COLORS[] = {
    RGB15(31,  0,  0),  // red
    RGB15(31, 16,  0),  // orange
    RGB15(31, 31,  0),  // yellow
    RGB15( 0, 31,  0),  // green
    RGB15( 0, 31, 31),  // cyan
    RGB15( 0,  0, 31),  // blue
    RGB15(16,  0, 31),  // violet
    RGB15(31,  0, 31),  // magenta
};
#define NUM_COLORS 8

#define BG_COLOR  RGB15(2, 2, 4)
#define MIN_RADIUS  5
#define MAX_RADIUS 70

static void vsync(void) {
    while (REG_VCOUNT >= 160);
    while (REG_VCOUNT < 160);
}

static inline void pixel(int x, int y, u16 color) {
    if ((unsigned)x < SCREEN_W && (unsigned)y < SCREEN_H)
        VIDEORAM[y * SCREEN_W + x] = color;
}

static void fill_screen(u16 color) {
    volatile u16 *p = VIDEORAM;
    for (int i = 0; i < SCREEN_W * SCREEN_H; i++)
        p[i] = color;
}

// Draw a filled circle using x^2 + y^2 <= r^2
static void draw_circle(int cx, int cy, int r, u16 color) {
    int r2 = r * r;
    for (int dy = -r; dy <= r; dy++) {
        int dx_max_sq = r2 - dy * dy;
        // find dx where dx^2 <= dx_max_sq
        int dx = 0;
        while ((dx + 1) * (dx + 1) <= dx_max_sq) dx++;
        for (int dx2 = -dx; dx2 <= dx; dx2++)
            pixel(cx + dx2, cy + dy, color);
    }
}

// Erase circle (paint over with background)
static void erase_circle(int cx, int cy, int r) {
    draw_circle(cx, cy, r + 1, BG_COLOR);
}

int main(void) {
    REG_DISPCNT = MODE3 | BG2_ENABLE;
    fill_screen(BG_COLOR);

    int radius     = 30;
    int color_idx  = 0;
    int prev_r     = radius;
    int prev_ci    = color_idx;

    // Debounce: track previous key state
    u16 prev_keys  = REG_KEYINPUT;

    draw_circle(CENTER_X, CENTER_Y, radius, COLORS[color_idx]);

    while (1) {
        vsync();

        u16 keys = REG_KEYINPUT;
        // Detect newly pressed keys (transition 1->0)
        u16 just_pressed = (~keys) & prev_keys;
        prev_keys = keys;

        if (just_pressed & KEY_UP) {
            if (radius < MAX_RADIUS) radius += 5;
        }
        if (just_pressed & KEY_DOWN) {
            if (radius > MIN_RADIUS) radius -= 5;
        }
        if (just_pressed & KEY_RIGHT) {
            color_idx = (color_idx + 1) % NUM_COLORS;
        }
        if (just_pressed & KEY_LEFT) {
            color_idx = (color_idx + NUM_COLORS - 1) % NUM_COLORS;
        }
        if (just_pressed & KEY_A) {
            if (radius < MAX_RADIUS) radius += 10;
        }
        if (just_pressed & KEY_B) {
            if (radius > MIN_RADIUS) radius -= 10;
        }
        if (just_pressed & KEY_L) {
            color_idx = (color_idx + NUM_COLORS - 1) % NUM_COLORS;
        }
        if (just_pressed & KEY_R) {
            color_idx = (color_idx + 1) % NUM_COLORS;
        }

        if (radius != prev_r || color_idx != prev_ci) {
            erase_circle(CENTER_X, CENTER_Y, prev_r > radius ? prev_r : radius);
            draw_circle(CENTER_X, CENTER_Y, radius, COLORS[color_idx]);
            prev_r  = radius;
            prev_ci = color_idx;
        }
    }
}
