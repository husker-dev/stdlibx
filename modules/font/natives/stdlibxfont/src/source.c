#include "types.h"
#include "layout.h"
#include "render.h"
#include "system_fonts.h"

void init_fonts(void) {
    FT_Init_FreeType(&library);
}

int32_t get_last_error(void) {
    return ft_error;
}

KArray* get_system_font_faces(void) {
    KArray* result = k_array_with_capacity(0);
#if defined(__APPLE__)
    append_macos_system_fonts(result);
#elif defined(_WIN32)
    append_windows_system_fonts(result);
#elif defined(__linux__) && !defined(__ANDROID__) && !defined(__EMSCRIPTEN__)
    append_linux_system_fonts(result);
#endif
    return result;
}

int32_t faces_count_from_bytes(KByteArray* bytes) {
    FT_Face face;
    ft_error = FT_New_Memory_Face(library, (FT_Bytes) bytes->elements, bytes->length, -1, &face);
    if (ft_error) {
        bytes->free(bytes);
        return -1;
    }
    int32_t num_faces = face->num_faces;
    FT_Done_Face(face);
    bytes->free(bytes);
    return num_faces;
}

int32_t faces_count_from_file(KString* path) {
    char* c_path = malloc(path->size + 1);
    memcpy(c_path, path->data, path->size);
    c_path[path->size] = 0;
    path->free(path);

    FT_Face face;
    ft_error = FT_New_Face(library, c_path, -1, &face);
    free(c_path);
    if (ft_error) return -1;

    int32_t num_faces = face->num_faces;
    FT_Done_Face(face);
    return num_faces;
}

RC_NativeFontFace* create_font_face_from_bytes(KByteArray* bytes, int32_t face_index) {
    FT_Face face;
    ft_error = FT_New_Memory_Face(library, (FT_Bytes) bytes->elements, bytes->length, face_index, &face);
    if (ft_error != 0) {
        bytes->free(bytes);
        return NULL;
    }
    return create_font_face(bytes, face);
}

RC_NativeFontFace* create_font_face_from_file(KString* path, int32_t face_index) {
    char* c_path = malloc(path->size + 1);
    memcpy(c_path, path->data, path->size);
    c_path[path->size] = 0;
    path->free(path);

    FT_Face face;
    ft_error = FT_New_Face(library, c_path, face_index, &face);
    free(c_path);
    return ft_error ? NULL : create_font_face(NULL, face);
}

NativeFontFaceInfo* _interface_nativefontface_fn_description(void* self) {
    FontFace* font_face = (FontFace*) self;
    FT_Face ft_face = font_face->ft_face;
    hb_face_t* hb_face = font_face->hb_face;

    // Collect basic info
    KString* family = k_string_new(ft_face->family_name != NULL ? ft_face->family_name : "");
    KString* post_script_name = k_string_new(FT_Get_Postscript_Name(ft_face));
    KString* style_name = k_string_new(ft_face->style_name != NULL ? ft_face->style_name : "");
    bool kerning = FT_HAS_KERNING(ft_face) || hb_aat_layout_has_positioning(hb_face);

    FontFormat format = FontFormat_Unknown;
    const char* ft_format = FT_Get_Font_Format(ft_face);
    if (ft_format == NULL)                          format = FontFormat_Unknown;
    else if (strcmp(ft_format, "TrueType") == 0)    format = FontFormat_TrueType;
    else if (strcmp(ft_format, "Type 1") == 0)      format = FontFormat_Type1;
    else if (strcmp(ft_format, "BDF") == 0)         format = FontFormat_BDF;
    else if (strcmp(ft_format, "PCF") == 0)         format = FontFormat_PCF;
    else if (strcmp(ft_format, "Type 42") == 0)     format = FontFormat_Type42;
    else if (strcmp(ft_format, "CID Type 1") == 0)  format = FontFormat_CIDType1;
    else if (strcmp(ft_format, "PFR") == 0)         format = FontFormat_PFR;
    else if (strcmp(ft_format, "Windows FNT") == 0) format = FontFormat_WindowsFNT;
    else if (strcmp(ft_format, "CFF") == 0) {
        hb_blob_t* table = hb_face_reference_table(font_face->hb_face, HB_TAG('C', 'F', 'F', '2'));
        format = hb_blob_get_length(table) > 0 ? FontFormat_CFF2 : FontFormat_CFF;
        hb_blob_destroy(table);
    }

    hb_font_t* hb_font = hb_font_create(font_face->hb_face);
    double weight = hb_style_get_value(hb_font, HB_STYLE_TAG_WEIGHT);
    double width = hb_style_get_value(hb_font, HB_STYLE_TAG_WIDTH) / 100;
    bool italic = hb_style_get_value(hb_font, HB_STYLE_TAG_ITALIC) != 0;
    float slant = hb_style_get_value(hb_font, HB_STYLE_TAG_SLANT_ANGLE);
    float optical_size = hb_style_get_value(hb_font, HB_STYLE_TAG_OPTICAL_SIZE);
    hb_font_destroy(hb_font);

    // Collect features
    const hb_tag_t table_tags[] = { HB_OT_TAG_GSUB, HB_OT_TAG_GPOS };
    unsigned int table_counts[2];
    for (int i = 0; i < 2; i++)
        table_counts[i] = hb_ot_layout_table_get_feature_tags(hb_face, table_tags[i], 0, NULL, NULL);
    unsigned int features_count = table_counts[0] + table_counts[1];

    int32_t* tags = features_count ? malloc(features_count * sizeof(int32_t)) : NULL;
    KIntArray* features = k_int_array_new(tags, (int32_t) features_count, false);

    unsigned int feature_count = 0;
    for (int i = 0; i < 2; i++) {
        unsigned int count = table_counts[i];
        if (count == 0) continue;

        hb_tag_t* table_features = malloc(count * sizeof(hb_tag_t));
        hb_ot_layout_table_get_feature_tags(hb_face, table_tags[i], 0, &count, table_features);
        for (unsigned int j = 0; j < count; j++) {
            tags[feature_count++] = (int32_t) table_features[j];
        }
        free(table_features);
    }

    // Collect variations
    KArray* variations = NULL;
    unsigned int axis_count = hb_ot_var_get_axis_count(hb_face);
    if (axis_count > 0) {
        hb_ot_var_axis_info_t* axes = malloc(axis_count * sizeof(*axes));
        hb_ot_var_get_axis_infos(hb_face, 0, &axis_count, axes);
        variations = k_array_with_capacity((int32_t) axis_count);
        for (unsigned int i = 0; i < axis_count; i++) {
            const hb_ot_var_axis_info_t* axis = &axes[i];
            k_array_push(variations, NativeVariationInfo_new(
                axis->tag, axis->default_value, axis->min_value, axis->max_value
            ));
        }
        free(axes);
    }
    return NativeFontFaceInfo_new(
        family, post_script_name, style_name,
        ft_face->face_index, format,
        width, italic, weight, kerning, slant, optical_size,
        FT_HAS_MULTIPLE_MASTERS(ft_face),
        features, variations
    );
}

RC_NativeFont* _interface_nativefontface_fn_create_font(
    void* _self,
    float size,
    KIntArray* variation_keys,
    KDoubleArray* variation_values
) {
    FontFace* font_face = (FontFace*) _self;

    FT_Size ft_size;
    FT_New_Size(font_face->ft_face, &ft_size);

    hb_font_t* hb_font = hb_font_create(font_face->hb_face);
    hb_ot_font_set_funcs(hb_font);

    int scale = (int) (size * 64 + 0.5f);
    hb_font_set_scale(hb_font, scale, scale);
    hb_font_set_ptem(hb_font, size);

    int32_t variations = variation_keys->length;
    if (variations > 0) {
        hb_variation_t* coordinates = malloc((size_t) variations * sizeof(*coordinates));
        for (int32_t i = 0; i < variations; i++) {
            coordinates[i] = (hb_variation_t) {
                .tag = (hb_tag_t) variation_keys->elements[i],
                .value = variation_values->elements[i]
            };
        }
        hb_font_set_variations(hb_font, coordinates, (unsigned int) variations);
        free(coordinates);
    }
    variation_keys->free(variation_keys);
    variation_values->free(variation_values);
    return create_font(font_face, ft_size, hb_font);
}


void _interface_nativefontface_free(void* self) {
    FontFace* face = (FontFace*) self;
    hb_face_destroy(face->hb_face);
    FT_Done_Face(face->ft_face);
    if (face->bytes != NULL) face->bytes->free(face->bytes);
    free(face);
}

KDoubleArray* layout_info(
    KArray* fonts,
    int32_t default_fonts_count,
    KString* text,
    KIntArray* utf8_text_layout,
    KIntArray* feature_keys,
    KBooleanArray* feature_values,
    TextDirection direction,
    KString* language
) {
    TextLayout* layout = text_layout_new(fonts, default_fonts_count, text, utf8_text_layout, feature_keys, feature_values, direction, language);
    int32_t length = layout->count * 6;
    double* result = length > 0 ? malloc(length * sizeof(*result)) : NULL;

    for (int32_t i = 0; i < layout->count; i++) {
        LayoutGlyph glyph = layout->glyphs[i];
        int32_t rel = i * 6;
        result[rel + 0] = glyph.font_index;
        result[rel + 1] = glyph.glyph_id;
        result[rel + 2] = glyph.x;
        result[rel + 3] = glyph.y;
        result[rel + 4] = glyph.x_advance;
        result[rel + 5] = glyph.y_advance;
    }

    // Free
    text_layout_free(layout);
    fonts->free(fonts);
    text->free(text);
    utf8_text_layout->free(utf8_text_layout);
    feature_keys->free(feature_keys);
    feature_values->free(feature_values);
    if (language != NULL) language->free(language);
    return k_double_array_new(result, length, false);
}

KDoubleArray* measure(
    KArray* fonts,
    int32_t default_fonts_count,
    KString* text,
    KIntArray* utf8_text_layout,
    KIntArray* feature_keys,
    KBooleanArray* feature_values,
    TextDirection direction,
    KString* language
) {
    TextLayout* layout = text_layout_new(fonts, default_fonts_count, text, utf8_text_layout, feature_keys, feature_values, direction, language);
    RC_NativeFont** fonts_rc = (RC_NativeFont**) fonts->elements;
    bool* used = fonts->length > 0 ? calloc((size_t) fonts->length, sizeof(*used)) : NULL;

    double left = 0, right = 0, bottom = 0, top = 0;
    bool has_ink = false;
    for (size_t i = 0; i < layout->count; i++) {
        const LayoutGlyph* glyph = &layout->glyphs[i];
        Font* font = fonts_rc[glyph->font_index]->pointed;
        used[glyph->font_index] = true;
        hb_glyph_extents_t extents;
        if (hb_font_get_glyph_extents(font->hb_font, glyph->glyph_id, &extents) && extents.width != 0 && extents.height != 0) {
            double x1 = glyph->x + extents.x_bearing / 64.0;
            double y1 = glyph->y - extents.y_bearing / 64.0;
            double x2 = x1 + extents.width / 64.0;
            double y2 = y1 - extents.height / 64.0;
            double glyph_left = x1 < x2 ? x1 : x2;
            double glyph_right = x1 > x2 ? x1 : x2;
            double glyph_top = y1 < y2 ? y1 : y2;
            double glyph_bottom = y1 > y2 ? y1 : y2;
            if (!has_ink || glyph_left < left) left = glyph_left;
            if (!has_ink || glyph_right > right) right = glyph_right;
            if (!has_ink || glyph_bottom < bottom) bottom = glyph_bottom;
            if (!has_ink || glyph_top > top) top = glyph_top;
            has_ink = true;
        }
    }

    // Spaces still count as using their font. With no glyphs, retain primary-font metrics.
    if (layout->count == 0 && fonts->length > 0)
        used[0] = true;

    double ascent = 0, descent = 0;
    bool has_font_extents = false;
    for (int32_t i = 0; i < fonts->length; i++) {
        if (!used[i]) continue;
        Font* font = fonts_rc[i]->pointed;
        hb_font_extents_t extents;
        if (hb_font_get_h_extents(font->hb_font, &extents)) {
            double font_ascent = extents.ascender / 64.0;
            double font_descent = -extents.descender / 64.0;
            if (!has_font_extents || font_ascent > ascent) ascent = font_ascent;
            if (!has_font_extents || font_descent > descent) descent = font_descent;
            has_font_extents = true;
        }
    }
    free(used);

    double hanging = 0, ideographic = 0;
    if (fonts->length > 0) {
        // Baselines belong to the primary font
        Font* primary = fonts_rc[0]->pointed;
        hb_buffer_t* buffer = hb_buffer_create();
        hb_buffer_add_utf8(buffer, text->data, text->size, (unsigned int) utf8_text_layout->elements[0], utf8_text_layout->elements[1]);
        hb_buffer_guess_segment_properties(buffer);

        hb_tag_t script_tag = HB_OT_TAG_DEFAULT_SCRIPT;
        hb_tag_t language_tag = HB_OT_TAG_DEFAULT_LANGUAGE;
        unsigned int script_count = 1, language_count = 1;
        hb_ot_tags_from_script_and_language(hb_buffer_get_script(buffer), hb_buffer_get_language(buffer), &script_count, &script_tag, &language_count, &language_tag);

        hb_position_t roman, hanging_position, ideographic_position;
        hb_ot_layout_get_baseline_with_fallback(primary->hb_font, HB_OT_LAYOUT_BASELINE_TAG_ROMAN, HB_DIRECTION_LTR, script_tag, language_tag, &roman);
        hb_ot_layout_get_baseline_with_fallback(primary->hb_font, HB_OT_LAYOUT_BASELINE_TAG_HANGING, HB_DIRECTION_LTR, script_tag, language_tag, &hanging_position);
        hb_ot_layout_get_baseline_with_fallback(primary->hb_font, HB_OT_LAYOUT_BASELINE_TAG_IDEO_EMBOX_BOTTOM_OR_LEFT, HB_DIRECTION_LTR, script_tag, language_tag, &ideographic_position);

        hanging = ((double) hanging_position - roman) / 64.0;
        ideographic = ((double) ideographic_position - roman) / 64.0;
        hb_buffer_destroy(buffer);
    }

    KDoubleArray* result = k_double_array_of_n(9,
        layout->advance_x,
        -left, right,
        -top, bottom,
        ascent, descent, hanging, ideographic);

    // Free
    text_layout_free(layout);
    fonts->free(fonts);
    text->free(text);
    utf8_text_layout->free(utf8_text_layout);
    feature_keys->free(feature_keys);
    feature_values->free(feature_values);
    if (language != NULL) language->free(language);
    return result;
}

KArray* render_glyphs(KArray* fonts, KIntArray* glyphs, RenderMode mode) {
    KArray* result = render(fonts, glyphs, mode);
    fonts->free(fonts);
    glyphs->free(glyphs);
    return result;
}

void _interface_nativefont_free(void* self) {
    Font* font = (Font*) self;
    hb_font_destroy(font->hb_font);
    FT_Done_Size(font->ft_size);
    free(font);
}
