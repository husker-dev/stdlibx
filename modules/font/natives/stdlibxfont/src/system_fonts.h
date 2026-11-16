#pragma once

#if defined(__APPLE__)
#include <CoreText/CoreText.h>

static void append_macos_system_fonts(KArray *result) {
    CTFontCollectionRef collection = CTFontCollectionCreateFromAvailableFonts(NULL);
    CFArrayRef descriptors = CTFontCollectionCreateMatchingFontDescriptors(collection);
    CFMutableSetRef seen = CFSetCreateMutable(NULL, 0, &kCFTypeSetCallBacks);
    for (CFIndex i = 0; i < CFArrayGetCount(descriptors); i++) {

        // Get file URL and check if it was already loaded
        CTFontDescriptorRef descriptor = CFArrayGetValueAtIndex(descriptors, i);
        CFURLRef url = CTFontDescriptorCopyAttribute(descriptor, kCTFontURLAttribute);
        if (url == NULL) continue;
        if (CFSetContainsValue(seen, url)) {
            CFRelease(url);
            continue;
        }
        CFSetAddValue(seen, url);

        // Create path string
        CFStringRef path = CFURLCopyFileSystemPath(url, kCFURLPOSIXPathStyle);
        CFIndex capacity = CFStringGetMaximumSizeOfFileSystemRepresentation(path);
        char* c_path = malloc((size_t) capacity);
        CFStringGetFileSystemRepresentation(path, c_path, capacity);

        // Load face
        FT_Face face;
        if (!FT_New_Face(library, c_path, 0, &face)) {
            k_array_push(result, create_font_face(NULL, face));
            FT_Long num_faces = face->num_faces;
            for (int r = 1; r < num_faces; r++) {
                if (!FT_New_Face(library, c_path, r, &face))
                    k_array_push(result, create_font_face(NULL, face));
            }
        }

        free(c_path);
        CFRelease(path);
        CFRelease(url);
    }
    CFRelease(seen);
    CFRelease(descriptors);
    CFRelease(collection);
}

#elif defined(_WIN32)

#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <limits.h>
#include <wchar.h>

/* The external FreeType stream owns the mapping until the face's last reference. */
static void system_font_stream_close(FT_Stream stream) {
    UnmapViewOfFile(stream->base);
    CloseHandle((HANDLE) stream->descriptor.pointer);
    free(stream);
}

static FT_Error open_system_font(const wchar_t *path, FT_Long index, FT_Face *face) {
    HANDLE file = CreateFileW(path, GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL
    );
    if (file == INVALID_HANDLE_VALUE)
        return FT_Err_Cannot_Open_Resource;

    LARGE_INTEGER size;
    if (!GetFileSizeEx(file, &size) || size.QuadPart <= 0 || (ULONGLONG) size.QuadPart > ULONG_MAX) {
        CloseHandle(file);
        return FT_Err_Cannot_Open_Resource;
    }
    HANDLE mapping = CreateFileMappingW(file, NULL, PAGE_READONLY, 0, 0, NULL);
    CloseHandle(file);
    if (mapping == NULL)
        return FT_Err_Cannot_Open_Resource;
    void *data = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 0);
    if (data == NULL) {
        CloseHandle(mapping);
        return FT_Err_Cannot_Open_Resource;
    }

    FT_Stream stream = calloc(1, sizeof(*stream));
    stream->base = data;
    stream->size = (FT_ULong) size.QuadPart;
    stream->descriptor.pointer = mapping;
    stream->close = system_font_stream_close;

    FT_Open_Args args = {0};
    args.flags = FT_OPEN_STREAM;
    args.stream = stream;
    return FT_Open_Face(library, &args, index, face);
}

static wchar_t *system_font_path(const wchar_t *base, const wchar_t *name) {
    size_t length = wcslen(name);
    bool absolute = name[0] == L'\\' || name[0] == L'/' || (length >= 3 && name[1] == L':' && (name[2] == L'\\' || name[2] == L'/'));
    if (!absolute && base == NULL)
        return NULL;

    size_t base_length = absolute ? 0 : wcslen(base);
    wchar_t *joined = malloc((base_length + length + 2) * sizeof(*joined));
    if (absolute) {
        wcscpy(joined, name);
    } else {
        wcscpy(joined, base);
        joined[base_length] = L'\\';
        wcscpy(joined + base_length + 1, name);
    }

    DWORD capacity = GetFullPathNameW(joined, 0, NULL, NULL);
    wchar_t *path = NULL;
    if (capacity != 0) {
        path = malloc((size_t) capacity * sizeof(*path));
        DWORD written = GetFullPathNameW(joined, capacity, path, NULL);
        if (written == 0 || written >= capacity) {
            free(path);
            path = NULL;
        }
    }
    free(joined);
    return path;
}

/* Collect persistent machine/user registrations; the caller owns every path. */
static void collect_system_font_paths(
    HKEY root,
    const wchar_t *base,
    wchar_t ***paths,
    size_t *count,
    size_t *capacity
) {
    HKEY key;
    if (RegOpenKeyExW(root, L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", 0, KEY_QUERY_VALUE, &key) != ERROR_SUCCESS)
        return;

    DWORD max_name;
    if (RegQueryInfoKeyW(key, NULL, NULL, NULL, NULL, NULL, NULL, NULL, &max_name, NULL, NULL, NULL) != ERROR_SUCCESS) {
        RegCloseKey(key);
        return;
    }
    DWORD name_capacity = max_name + 1;
    wchar_t *name = malloc((size_t) name_capacity * sizeof(*name));
    for (DWORD index = 0;; index++) {
        DWORD name_length = name_capacity;
        LSTATUS status = RegEnumValueW(key, index, name, &name_length, NULL, NULL, NULL, NULL);
        if (status == ERROR_NO_MORE_ITEMS)
            break;

        if (status == ERROR_MORE_DATA) {
            name_capacity *= 2;
            name = realloc(name, (size_t) name_capacity * sizeof(*name));
            index--;
            continue;
        }
        if (status != ERROR_SUCCESS)
            continue;

        DWORD flags = RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ;
        DWORD bytes = 0;
        if (RegGetValueW(key, NULL, name, flags, NULL, NULL, &bytes) != ERROR_SUCCESS)
            continue;

        wchar_t *value = malloc((size_t) bytes + sizeof(*value));
        status = RegGetValueW(key, NULL, name, flags, NULL, value, &bytes);
        if (status == ERROR_SUCCESS && bytes % sizeof(*value) == 0) {
            value[bytes / sizeof(*value)] = 0;
            if (value[0] != 0) {
                wchar_t *path = system_font_path(base, value);
                if (path != NULL) {
                    if (*count == *capacity) {
                        *capacity = *capacity == 0 ? 32 : *capacity * 2;
                        *paths = realloc(*paths, *capacity * sizeof(**paths));
                    }
                    (*paths)[(*count)++] = path;
                }
            }
        }
        free(value);
    }
    free(name);
    RegCloseKey(key);
}

static int compare_system_font_paths(const void *left, const void *right) {
    return _wcsicmp(*(const wchar_t *const *) left, *(const wchar_t *const *) right);
}

static void append_windows_system_fonts(KArray *result) {
    wchar_t *machine_base = NULL;
    DWORD capacity = GetWindowsDirectoryW(NULL, 0);
    if (capacity != 0) {
        wchar_t *directory = malloc((size_t) capacity * sizeof(*directory));
        DWORD written = GetWindowsDirectoryW(directory, capacity);
        if (written > 0 && written < capacity)
            machine_base = system_font_path(directory, L"Fonts");
        free(directory);
    }

    wchar_t *user_base = NULL;
    capacity = GetEnvironmentVariableW(L"LOCALAPPDATA", NULL, 0);
    if (capacity != 0) {
        wchar_t *directory = malloc((size_t) capacity * sizeof(*directory));
        DWORD written = GetEnvironmentVariableW(L"LOCALAPPDATA", directory, capacity);
        if (written > 0 && written < capacity)
            user_base = system_font_path(directory, L"Microsoft\\Windows\\Fonts");
        free(directory);
    }

    wchar_t **paths = NULL;
    size_t count = 0, path_capacity = 0;
    collect_system_font_paths(HKEY_LOCAL_MACHINE, machine_base, &paths, &count, &path_capacity);
    collect_system_font_paths(HKEY_CURRENT_USER, user_base, &paths, &count, &path_capacity);
    if (count > 1)
        qsort(paths, count, sizeof(*paths), compare_system_font_paths);
    for (size_t i = 0; i < count; i++) {
        if (i > 0 && _wcsicmp(paths[i - 1], paths[i]) == 0)
            continue;

        FT_Face face;
        if (open_system_font(paths[i], 0, &face))
            continue;

        FT_Long num_faces = face->num_faces;
        k_array_push(result, create_font_face(NULL, face));
        for (FT_Long index = 1; index < num_faces; index++) {
            if (!open_system_font(paths[i], index, &face))
                k_array_push(result, create_font_face(NULL, face));
        }
    }
    for (size_t i = 0; i < count; i++)
        free(paths[i]);
    free(paths);
    free(user_base);
    free(machine_base);
}

#elif defined(__linux__) && !defined(__ANDROID__) && !defined(__EMSCRIPTEN__)

#include <fontconfig/fontconfig.h>
#include <string.h>

static int compare_system_font_paths(const void *left, const void *right) {
    return strcmp(*(const char *const *) left, *(const char *const *) right);
}

static void append_linux_system_fonts(KArray *result) {
    /* Keep a private configuration; do not finalize another library's Fontconfig. */
    FcConfig *config = FcInitLoadConfigAndFonts();
    if (config == NULL)
        return;

    FcPattern *pattern = FcPatternCreate();
    FcObjectSet *objects = FcObjectSetBuild(FC_FILE, NULL);
    FcFontSet *fonts = FcFontList(config, pattern, objects);
    if (fonts != NULL) {
        const char **paths = fonts->nfont > 0 ? malloc((size_t) fonts->nfont * sizeof(*paths)) : NULL;
        size_t count = 0;
        for (int i = 0; i < fonts->nfont; i++) {
            FcChar8 *path;
            if (FcPatternGetString(fonts->fonts[i], FC_FILE, 0, &path) == FcResultMatch)
                paths[count++] = (const char *) path;
        }
        if (count > 1)
            qsort(paths, count, sizeof(*paths), compare_system_font_paths);

        for (size_t i = 0; i < count; i++) {
            if (i > 0 && strcmp(paths[i - 1], paths[i]) == 0)
                continue;

            FT_Face face;
            if (FT_New_Face(library, paths[i], 0, &face))
                continue;

            FT_Long num_faces = face->num_faces;
            k_array_push(result, create_font_face(NULL, face));
            for (FT_Long index = 1; index < num_faces; index++) {
                if (!FT_New_Face(library, paths[i], index, &face))
                    k_array_push(result, create_font_face(NULL, face));
            }
        }
        free(paths);
        FcFontSetDestroy(fonts);
    }
    FcObjectSetDestroy(objects);
    FcPatternDestroy(pattern);
    FcConfigDestroy(config);
}

#endif
