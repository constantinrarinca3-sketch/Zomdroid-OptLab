#include "internal.h"
#include "zomdroid_globals.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

#define NOOP
#define UNIMPLEMENTED_API     _glfwInputError(GLFW_FEATURE_UNIMPLEMENTED, "Zomdroid: %s not implemented", __func__);
//ANativeWindow* native_window;
//int native_window_width;
//int native_window_height;

static GLFWvidmode getVideoMode(void)
{
    GLFWvidmode mode;
    mode.width = _glfw.zomdroid.aNativeWindowWidth;
    mode.height = _glfw.zomdroid.aNativeWindowHeight;
    mode.redBits = 8;
    mode.greenBits = 8;
    mode.blueBits = 8;
    pthread_mutex_lock(&g_zomdroid_surface.mutex);
    const float refresh = g_zomdroid_surface.refresh_rate;
    pthread_mutex_unlock(&g_zomdroid_surface.mutex);
    mode.refreshRate = refresh >= 1.0f ? (int)lroundf(refresh) : 60;
    return mode;
}

//////////////////////////////////////////////////////////////////////////
//////                       GLFW internal API                      //////
//////////////////////////////////////////////////////////////////////////

void _glfwPollMonitorsZomdroid(void)
{
    _GLFWmonitor* monitor = _glfwAllocMonitor("Zomdroid Dummy Monitor",1,1);
    _glfwInputMonitor(monitor, GLFW_CONNECTED, _GLFW_INSERT_FIRST);
}

//////////////////////////////////////////////////////////////////////////
//////                       GLFW platform API                      //////
//////////////////////////////////////////////////////////////////////////

void _glfwFreeMonitorZomdroid(_GLFWmonitor* monitor)
{
    NOOP
}

void _glfwGetMonitorPosZomdroid(_GLFWmonitor* monitor, int* xpos, int* ypos)
{
    if (xpos)
        *xpos = 0;
    if (ypos)
        *ypos = 0;
}

void _glfwGetMonitorContentScaleZomdroid(_GLFWmonitor* monitor,
                                     float* xscale, float* yscale)
{
    if (xscale)
        *xscale = 1.f;
    if (yscale)
        *yscale = 1.f;
}

void _glfwGetMonitorWorkareaZomdroid(_GLFWmonitor* monitor,
                                 int* xpos, int* ypos,
                                 int* width, int* height)
{
    const GLFWvidmode mode = getVideoMode();

    if (xpos)
        *xpos = 0;
    if (ypos)
        *ypos = 0;
    if (width)
        *width = mode.width;
    if (height)
        *height = mode.height;
}

GLFWvidmode* _glfwGetVideoModesZomdroid(_GLFWmonitor* monitor, int* found)
{
    GLFWvidmode* mode = _glfw_calloc(1, sizeof(GLFWvidmode));
    *mode = getVideoMode();
    *found = 1;
    return mode;
}

GLFWbool _glfwGetVideoModeZomdroid(_GLFWmonitor* monitor, GLFWvidmode* mode)
{
    *mode = getVideoMode();
    return GLFW_TRUE;
}

GLFWbool _glfwGetGammaRampZomdroid(_GLFWmonitor* monitor, GLFWgammaramp* ramp)
{
    return GLFW_FALSE;
}

void _glfwSetGammaRampZomdroid(_GLFWmonitor* monitor, const GLFWgammaramp* ramp)
{
    NOOP
}
