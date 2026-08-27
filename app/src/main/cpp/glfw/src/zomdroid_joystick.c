#include "internal.h"

#define UNIMPLEMENTED_API _glfwInputError(GLFW_FEATURE_UNIMPLEMENTED, "Zomdroid: %s not implemented", __func__);

GLFWbool _glfwInitJoysticksZomdroid(void)
{
    return GLFW_TRUE;
}

void _glfwTerminateJoysticksZomdroid(void)
{
    UNIMPLEMENTED_API
}

GLFWbool _glfwPollJoystickZomdroid(_GLFWjoystick* js, int mode)
{
    return GLFW_TRUE;
}

const char* _glfwGetMappingNameZomdroid(void)
{
    return "Zomdroid";
}

void _glfwUpdateGamepadGUIDZomdroid(char* guid)
{

}