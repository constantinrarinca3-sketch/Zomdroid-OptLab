#include "internal.h"
#include "zomdroid.h"
#include "zomdroid_globals.h"

GLFWbool _glfwConnectZomdroid(int platformID, _GLFWplatform* platform)
{
    const _GLFWplatform zomdroid =
    {
        .platformID = GLFW_PLATFORM_ZOMDROID,
        .init = _glfwInitZomdroid,
        .terminate = _glfwTerminateZomdroid,
        .getCursorPos = _glfwGetCursorPosZomdroid,
        .setCursorPos = _glfwSetCursorPosZomdroid,
        .setCursorMode = _glfwSetCursorModeZomdroid,
        .setRawMouseMotion = _glfwSetRawMouseMotionZomdroid,
        .rawMouseMotionSupported = _glfwRawMouseMotionSupportedZomdroid,
        .createCursor = _glfwCreateCursorZomdroid,
        .createStandardCursor = _glfwCreateStandardCursorZomdroid,
        .destroyCursor = _glfwDestroyCursorZomdroid,
        .setCursor = _glfwSetCursorZomdroid,
        .getScancodeName = _glfwGetScancodeNameZomdroid,
        .getKeyScancode = _glfwGetKeyScancodeZomdroid,
        .setClipboardString = _glfwSetClipboardStringZomdroid,
        .getClipboardString = _glfwGetClipboardStringZomdroid,
        .initJoysticks = _glfwInitJoysticksZomdroid,
        .terminateJoysticks = _glfwTerminateJoysticksZomdroid,
        .pollJoystick = _glfwPollJoystickZomdroid,
        .getMappingName = _glfwGetMappingNameZomdroid,
        .updateGamepadGUID = _glfwUpdateGamepadGUIDZomdroid,
        .freeMonitor = _glfwFreeMonitorZomdroid,
        .getMonitorPos = _glfwGetMonitorPosZomdroid,
        .getMonitorContentScale = _glfwGetMonitorContentScaleZomdroid,
        .getMonitorWorkarea = _glfwGetMonitorWorkareaZomdroid,
        .getVideoModes = _glfwGetVideoModesZomdroid,
        .getVideoMode = _glfwGetVideoModeZomdroid,
        .getGammaRamp = _glfwGetGammaRampZomdroid,
        .setGammaRamp = _glfwSetGammaRampZomdroid,
        .createWindow = _glfwCreateWindowZomdroid,
        .destroyWindow = _glfwDestroyWindowZomdroid,
        .setWindowTitle = _glfwSetWindowTitleZomdroid,
        .setWindowIcon = _glfwSetWindowIconZomdroid,
        .getWindowPos = _glfwGetWindowPosZomdroid,
        .setWindowPos = _glfwSetWindowPosZomdroid,
        .getWindowSize = _glfwGetWindowSizeZomdroid,
        .setWindowSize = _glfwSetWindowSizeZomdroid,
        .setWindowSizeLimits = _glfwSetWindowSizeLimitsZomdroid,
        .setWindowAspectRatio = _glfwSetWindowAspectRatioZomdroid,
        .getFramebufferSize = _glfwGetFramebufferSizeZomdroid,
        .getWindowFrameSize = _glfwGetWindowFrameSizeZomdroid,
        .getWindowContentScale = _glfwGetWindowContentScaleZomdroid,
        .iconifyWindow = _glfwIconifyWindowZomdroid,
        .restoreWindow = _glfwRestoreWindowZomdroid,
        .maximizeWindow = _glfwMaximizeWindowZomdroid,
        .showWindow = _glfwShowWindowZomdroid,
        .hideWindow = _glfwHideWindowZomdroid,
        .requestWindowAttention = _glfwRequestWindowAttentionZomdroid,
        .focusWindow = _glfwFocusWindowZomdroid,
        .setWindowMonitor = _glfwSetWindowMonitorZomdroid,
        .windowFocused = _glfwWindowFocusedZomdroid,
        .windowIconified = _glfwWindowIconifiedZomdroid,
        .windowVisible = _glfwWindowVisibleZomdroid,
        .windowMaximized = _glfwWindowMaximizedZomdroid,
        .windowHovered = _glfwWindowHoveredZomdroid,
        .framebufferTransparent = _glfwFramebufferTransparentZomdroid,
        .getWindowOpacity = _glfwGetWindowOpacityZomdroid,
        .setWindowResizable = _glfwSetWindowResizableZomdroid,
        .setWindowDecorated = _glfwSetWindowDecoratedZomdroid,
        .setWindowFloating = _glfwSetWindowFloatingZomdroid,
        .setWindowOpacity = _glfwSetWindowOpacityZomdroid,
        .setWindowMousePassthrough = _glfwSetWindowMousePassthroughZomdroid,
        .pollEvents = _glfwPollEventsZomdroid,
        .waitEvents = _glfwWaitEventsZomdroid,
        .waitEventsTimeout = _glfwWaitEventsTimeoutZomdroid,
        .postEmptyEvent = _glfwPostEmptyEventZomdroid,
        .getEGLPlatform = _glfwGetEGLPlatformZomdroid,
        .getEGLNativeDisplay = _glfwGetEGLNativeDisplayZomdroid,
        .getEGLNativeWindow = _glfwGetEGLNativeWindowZomdroid
    };
    *platform = zomdroid;
    return GLFW_TRUE;
}
int _glfwInitZomdroid(void)
{
    pthread_mutex_lock(&g_zomdroid_surface.mutex);
    _glfw.zomdroid.aNativeWindow = g_zomdroid_surface.native_window;
    _glfw.zomdroid.aNativeWindowWidth = g_zomdroid_surface.width;
    _glfw.zomdroid.aNativeWindowHeight = g_zomdroid_surface.height;
    g_zomdroid_surface.is_dirty = false;
    g_zomdroid_surface.is_used = true;
    if (g_zomdroid_surface_gen_ack) {
        g_zomdroid_surface.acknowledged_generation = g_zomdroid_surface.generation;
        pthread_cond_broadcast(&g_zomdroid_surface.ready_for_destroy_cond);
    }
    pthread_mutex_unlock(&g_zomdroid_surface.mutex);

    _glfw.zomdroid.aNativeWindowBuffer = _glfw_calloc(1, sizeof(ANativeWindow_Buffer));
    _glfwPollMonitorsZomdroid();
    return GLFW_TRUE;
}

void _glfwTerminateZomdroid(void)
{
    if (g_zomdroid_surface_gen_ack && _glfw.zomdroid.aNativeWindow) {
        ANativeWindow_release(_glfw.zomdroid.aNativeWindow);
        _glfw.zomdroid.aNativeWindow = NULL;
    }
    _glfw_free(_glfw.zomdroid.aNativeWindowBuffer);
    _glfw.zomdroid.aNativeWindowBuffer = NULL;
}

