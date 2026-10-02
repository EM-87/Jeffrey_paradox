/*
 * A mupen64plus input plugin with no device behind it: the buttons are
 * whatever the frontend last put in headless_input_set(). emu/n64emu.py sets
 * them between frames, so a test presses A on exactly the frame it means to.
 *
 * Controller 1 is plugged in with a Controller Pak (Animal Forest keeps
 * letters and travel data there, and the AF Project documents a crash at the
 * train station without one); 2-4 are absent.
 */
#define M64P_PLUGIN_PROTOTYPES 1
#include "m64p_common.h"
#include "m64p_plugin.h"
#include "m64p_types.h"

#include <string.h>

#ifdef _WIN32
#define HEADLESS_EXPORT __declspec(dllexport)
#else
#define HEADLESS_EXPORT __attribute__((visibility("default")))
#endif

static unsigned int buttons[4];
static unsigned int polls[4];
static CONTROL* controls;

EXPORT m64p_error CALL PluginStartup(m64p_dynlib_handle core, void* context, void (*debug)(void*, int, const char*))
{
    (void)core;
    (void)context;
    (void)debug;
    memset(buttons, 0, sizeof(buttons));
    memset(polls, 0, sizeof(polls));
    return M64ERR_SUCCESS;
}

EXPORT m64p_error CALL PluginShutdown(void)
{
    return M64ERR_SUCCESS;
}

EXPORT m64p_error CALL PluginGetVersion(m64p_plugin_type* type, int* version, int* api, const char** name, int* caps)
{
    if (type) *type = M64PLUGIN_INPUT;
    if (version) *version = 0x010000;
    if (api) *api = 0x020101;
    if (name) *name = "headless input";
    if (caps) *caps = 0;
    return M64ERR_SUCCESS;
}

EXPORT void CALL InitiateControllers(CONTROL_INFO info)
{
    controls = info.Controls;
    for (int i = 0; i < 4; i++) {
        controls[i].Present = i == 0;
        controls[i].RawData = 0;
        controls[i].Plugin = i == 0 ? PLUGIN_MEMPAK : PLUGIN_NONE;
        controls[i].Type = CONT_TYPE_STANDARD;
    }
}

EXPORT void CALL GetKeys(int control, BUTTONS* keys)
{
    if (control < 0 || control > 3) {
        return;
    }
    keys->Value = buttons[control];
    polls[control]++;
}

EXPORT void CALL ControllerCommand(int control, unsigned char* command) { (void)control; (void)command; }
EXPORT void CALL ReadController(int control, unsigned char* command) { (void)control; (void)command; }
EXPORT int CALL RomOpen(void) { return 1; }
EXPORT void CALL RomClosed(void) {}
EXPORT void CALL SDL_KeyDown(int keymod, int keysym) { (void)keymod; (void)keysym; }
EXPORT void CALL SDL_KeyUp(int keymod, int keysym) { (void)keymod; (void)keysym; }

/* value is a BUTTONS word: the low 16 bits are buttons, then X, then Y. */
HEADLESS_EXPORT void headless_input_set(int control, unsigned int value)
{
    if (control >= 0 && control < 4) {
        buttons[control] = value;
    }
}

/* How many times the game has read this controller: one read per game frame
 * in Animal Forest, so it doubles as an input-frame counter. */
HEADLESS_EXPORT unsigned int headless_input_polls(int control)
{
    return control >= 0 && control < 4 ? polls[control] : 0;
}
