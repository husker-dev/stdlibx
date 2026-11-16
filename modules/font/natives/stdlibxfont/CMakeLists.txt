cmake_minimum_required(VERSION 3.24)

project("stdlibxfont")

add_compile_options(
        "$<$<CONFIG:Release>:-Oz>"
        "$<$<CONFIG:Release>:-flto>"
)

# Install native-kt
include(FetchContent)
FetchContent_Declare(nativekt URL "https://github.com/husker-dev/native-kt/releases/download/4.0.8/nativekt-cmake.tar.gz")
FetchContent_MakeAvailable(nativekt)
nativekt_generate("nativekt.toml")

# Install Brotli
set(BUILD_SHARED_LIBS OFF)
set(BROTLI_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BROTLI_DISABLE_TESTS ON CACHE BOOL "" FORCE)
FetchContent_Declare(
        brotli
        GIT_REPOSITORY https://github.com/google/brotli.git
        GIT_TAG        v1.2.1
)
FetchContent_MakeAvailable(brotli)
set(BROTLIDEC_INCLUDE_DIRS "${brotli_SOURCE_DIR}/c/include")
set(BROTLIDEC_LIBRARIES brotlidec)

# Install FreeType
FetchContent_Declare(
        freetype
        GIT_REPOSITORY https://github.com/freetype/freetype.git
        GIT_TAG        VER-2-15-0
)
if(EMSCRIPTEN)
    set(FT_DISABLE_STREAM_SUPPORT ON CACHE BOOL "" FORCE)
endif()
set(FT_DISABLE_ZLIB OFF CACHE BOOL "Disable system zlib" FORCE)
set(FT_REQUIRE_ZLIB ON CACHE BOOL "Require system zlib" FORCE)
set(FT_DISABLE_PNG OFF CACHE BOOL "Disable PNG bitmap glyphs" FORCE)
set(FT_REQUIRE_PNG ON CACHE BOOL "Require PNG bitmap glyphs" FORCE)
set(FT_DISABLE_BROTLI OFF CACHE BOOL "Disable WOFF2 support" FORCE)
set(FT_REQUIRE_BROTLI ON CACHE BOOL "Require WOFF2 support" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "Disable BZip2 support" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "Disable FreeType's HarfBuzz auto-hinting dependency" FORCE)
set(SKIP_INSTALL_ALL ON)
FetchContent_MakeAvailable(freetype)
set(MINIMAL_FT_MODULES [=[
#include <freetype/config/ftheader.h>
FT_USE_MODULE( FT_Driver_ClassRec, tt_driver_class )
FT_USE_MODULE( FT_Driver_ClassRec, cff_driver_class )

FT_USE_MODULE( FT_Module_Class, sfnt_module_class )
FT_USE_MODULE( FT_Module_Class, psaux_module_class )
FT_USE_MODULE( FT_Module_Class, psnames_module_class )
FT_USE_MODULE( FT_Module_Class, pshinter_module_class )

FT_USE_MODULE( FT_Renderer_Class, ft_smooth_renderer_class )
]=])
file(WRITE "${freetype_SOURCE_DIR}/include/freetype/config/ftmodule.h" "${MINIMAL_FT_MODULES}")

# Install HarfBuzz
FetchContent_Declare(
        harfbuzz
        GIT_REPOSITORY https://github.com/harfbuzz/harfbuzz.git
        GIT_TAG        15.1.0
)
set(HB_HAVE_FREETYPE OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GLIB OFF CACHE BOOL "" FORCE)
set(HB_HAVE_ICU OFF CACHE BOOL "" FORCE)
set(HB_BUILD_UTILS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_SUBSET OFF CACHE BOOL "" FORCE)
set(HB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(HB_HAVE_CORETEXT OFF CACHE BOOL "" FORCE)
set(HB_HAVE_DIRECTWRITE OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GDI OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(harfbuzz)
unset(SKIP_INSTALL_ALL)

# Install SheenBidi
FetchContent_Declare(
        sheenbidi
        GIT_REPOSITORY https://github.com/Tehreer/SheenBidi.git
        GIT_TAG        v3.0.0
)
FetchContent_MakeAvailable(sheenbidi)

# Create library
add_library(stdlibxfont OBJECT
        src/source.c
)
target_compile_options(stdlibxfont PRIVATE -Wno-initializer-overrides)

# Link dependencies
target_link_libraries(stdlibxfont PRIVATE NativeKt::bindings)
target_link_libraries(stdlibxfont PRIVATE freetype)
target_link_libraries(stdlibxfont PRIVATE harfbuzz)
target_link_libraries(stdlibxfont PRIVATE SheenBidi::SheenBidi)

if(APPLE)
    # CoreText + CoreFoundation
    find_library(CORETEXT_FRAMEWORK CoreText REQUIRED)
    find_library(COREFOUNDATION_FRAMEWORK CoreFoundation REQUIRED)
    target_link_libraries(stdlibxfont PRIVATE
        "${CORETEXT_FRAMEWORK}"
        "${COREFOUNDATION_FRAMEWORK}"
    )
elseif(WIN32)
    target_link_libraries(stdlibxfont PRIVATE advapi32)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Fontconfig REQUIRED)
    target_link_libraries(stdlibxfont PRIVATE Fontconfig::Fontconfig)
elseif(EMSCRIPTEN)
    target_link_options(stdlibxfont INTERFACE "-sFILESYSTEM=0")
endif()
