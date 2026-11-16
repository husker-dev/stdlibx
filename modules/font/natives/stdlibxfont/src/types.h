#pragma once

#include <nativekt.h>
#include <stdatomic.h>
#include <math.h>

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_SIZES_H
#include FT_MULTIPLE_MASTERS_H
#include FT_FONT_FORMATS_H

#include <hb.h>
#include <hb-ft.h>
#include <hb-ot.h>
#include <hb-aat.h>

static FT_Library library;
static __thread FT_Error ft_error;

typedef struct FontFace {
    KByteArray* bytes;
    FT_Face ft_face;
    hb_face_t* hb_face;
} FontFace;

typedef struct Font {
    FontFace* face;
    FT_Size ft_size;
    hb_font_t* hb_font;
} Font;

typedef struct {
    int32_t font_index;
    uint32_t glyph_id;

    double x;
    double y;

    double x_advance;
    double y_advance;
} LayoutGlyph;

typedef struct {
    LayoutGlyph* glyphs;
    size_t count;
    size_t capacity;

    double advance_x;
    double advance_y;
} TextLayout;

static RC_NativeFontFace* create_font_face(KByteArray* bytes, FT_Face ft_face) {
    FontFace* font_face = (FontFace*) malloc(sizeof(FontFace));
    font_face->bytes = bytes;
    font_face->ft_face = ft_face;
    font_face->hb_face = hb_ft_face_create_referenced(ft_face);
    return rc_nativefontface_new(font_face);
}

static RC_NativeFont* create_font(
    FontFace* face,
    FT_Size ft_size,
    hb_font_t* hb_font
) {
    Font* font_face = (Font*) malloc(sizeof(Font));
    font_face->face = face;
    font_face->ft_size = ft_size;
    font_face->hb_font = hb_font;
    return rc_nativefont_new(font_face);
}