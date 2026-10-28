cmake_minimum_required(VERSION 3.20)
project(icheme_column_solver LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(icheme_column_solver SHARED
    icheme_column_solver.cpp
)

target_include_directories(icheme_column_solver PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if (MSVC)
    target_compile_options(icheme_column_solver PRIVATE /O2 /W4 /EHsc)
else()
    target_compile_options(icheme_column_solver PRIVATE -O3 -Wall -Wextra -Wpedantic)
endif()

# JNI is optional at configure time. The C ABI remains usable by a future Panama or JNI adapter.
# FindJNI needs JAVA_HOME on some Windows installations. Accept an explicit CMake/JAVA_HOME path as a
# fallback so a Gradle native build can expose the version probe without making JNI a hard dependency.
find_package(JNI QUIET)
if (NOT JNI_FOUND)
    set(ICHEME_JAVA_HOME "${ICHEME_JAVA_HOME}" CACHE PATH "JDK home used for the optional JNI adapter")
    if (NOT ICHEME_JAVA_HOME AND DEFINED JAVA_HOME)
        set(ICHEME_JAVA_HOME "${JAVA_HOME}")
    endif()
    if (ICHEME_JAVA_HOME AND EXISTS "${ICHEME_JAVA_HOME}/include/jni.h")
        set(JNI_FOUND TRUE)
        set(JNI_INCLUDE_DIRS "${ICHEME_JAVA_HOME}/include")
        if (WIN32 AND EXISTS "${ICHEME_JAVA_HOME}/include/win32")
            list(APPEND JNI_INCLUDE_DIRS "${ICHEME_JAVA_HOME}/include/win32")
        elseif (APPLE AND EXISTS "${ICHEME_JAVA_HOME}/include/darwin")
            list(APPEND JNI_INCLUDE_DIRS "${ICHEME_JAVA_HOME}/include/darwin")
        elseif (UNIX AND EXISTS "${ICHEME_JAVA_HOME}/include/linux")
            list(APPEND JNI_INCLUDE_DIRS "${ICHEME_JAVA_HOME}/include/linux")
        endif()
    endif()
endif()
if (JNI_FOUND)
    target_sources(icheme_column_solver PRIVATE icheme_column_solver_jni.cpp)
    target_include_directories(icheme_column_solver PRIVATE ${JNI_INCLUDE_DIRS})
    target_compile_definitions(icheme_column_solver PRIVATE ICHEME_HAS_JNI=1)
endif()

set_target_properties(icheme_column_solver PROPERTIES
    OUTPUT_NAME "icheme_column_solver"
    POSITION_INDEPENDENT_CODE ON
)
