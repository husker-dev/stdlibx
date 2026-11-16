#pragma once

#include "types.h"

/* Applies an instance's settings to its shared face. The caller serializes access to
 * FreeType; neither the face nor source bytes are copied or owned by this helper. */
static FT_Error prepare_render_font(Font* font, double* scale_x, double* scale_y, int* preferred_strike) {
    FT_Face face = font->face->ft_face;
    *preferred_strike = -1;
    FT_Error error = FT_Activate_Size(font->ft_size);
    if (error) return error;
    FT_Set_Transform(face, NULL, NULL);

    // Restore all axes, including defaults; another Font may have changed this face.
    unsigned int axis_count = hb_ot_var_get_axis_count(font->face->hb_face);
    if (axis_count > 0) {
        hb_ot_var_axis_info_t* axes = malloc(axis_count * sizeof(*axes));
        hb_ot_var_get_axis_infos(font->face->hb_face, 0, &axis_count, axes);
        unsigned int coord_count;
        const float* design = hb_font_get_var_coords_design(font->hb_font, &coord_count);
        FT_Fixed* coords = malloc(axis_count * sizeof(FT_Fixed));

        for (unsigned int i = 0; i < axis_count; i++)
            coords[i] = (FT_Fixed) llround((i < coord_count ? design[i] : axes[i].default_value) * 65536.0);
        error = FT_Set_Var_Design_Coordinates(face, axis_count, coords);
        free(coords);
        free(axes);
        if (error) return error;
    }

    int x_scale, y_scale;
    hb_font_get_scale(font->hb_font, &x_scale, &y_scale);
    if (x_scale <= 0 || y_scale <= 0) {
        return FT_Err_Invalid_Pixel_Size;
    }

    *scale_x = 1;
    *scale_y = 1;
    if (FT_IS_SCALABLE(face)) {
        error = FT_Set_Char_Size(face, x_scale, y_scale, 72, 72);
    } else if (FT_HAS_FIXED_SIZES(face)) {
        // Skia's policy: exact strike, otherwise smallest larger, otherwise largest.
        int strike = -1;
        FT_Pos chosen = 0;
        for (int i = 0; i < face->num_fixed_sizes; i++) {
            FT_Pos ppem = face->available_sizes[i].y_ppem;
            if (ppem <= 0 || face->available_sizes[i].x_ppem <= 0)
                continue;
            if (ppem == y_scale){
                strike = i;
                break;
            }
            if (strike < 0 || (chosen < y_scale ? ppem > chosen : ppem > y_scale && ppem < chosen)) {
                strike = i;
                chosen = ppem;
            }
        }
        if (strike < 0) {
            return FT_Err_Invalid_Pixel_Size;
        }
        error = FT_Select_Size(face, strike);
        if (!error) {
            *preferred_strike = strike;
            *scale_x = x_scale / (double) face->available_sizes[strike].x_ppem;
            *scale_y = y_scale / (double) face->available_sizes[strike].y_ppem;
        }
    } else error = FT_Err_Invalid_Pixel_Size;
    return error;
}

static NativeRenderedGlyph* copy_glyph_bitmap(FT_GlyphSlot slot, double scale_x, double scale_y) {
    const FT_Bitmap* bitmap = &slot->bitmap;
    PixelMode mode;
    int channels = 1;
    switch (bitmap->pixel_mode) {
        case FT_PIXEL_MODE_MONO:
        case FT_PIXEL_MODE_GRAY2:
        case FT_PIXEL_MODE_GRAY4:
        case FT_PIXEL_MODE_NONE:
            if (bitmap->width != 0 && bitmap->rows != 0) {
                ft_error = FT_Err_Invalid_Glyph_Format;
                return NULL;
            }
            mode = PixelMode_GRAY;
            break;
        case FT_PIXEL_MODE_GRAY: mode = PixelMode_GRAY; break;
        case FT_PIXEL_MODE_LCD: mode = PixelMode_LCD; break;
        case FT_PIXEL_MODE_LCD_V: mode = PixelMode_LCD_V; break;
        case FT_PIXEL_MODE_BGRA: mode = PixelMode_BGRA; channels = 4; break;
        default:
            ft_error = FT_Err_Invalid_Glyph_Format;
            return NULL;
    }
    if (bitmap->width == 0 || bitmap->rows == 0)
        return NativeRenderedGlyph_new(k_byte_array_of_n(0), mode, 0, 0, 0, 0);

    if (bitmap->width > INT32_MAX || bitmap->rows > INT32_MAX || (uint64_t) bitmap->width * bitmap->rows * channels > INT32_MAX) {
        ft_error = FT_Err_Array_Too_Large;
        return NULL;
    }

    int32_t width = (int32_t) bitmap->width;
    int32_t height = (int32_t) bitmap->rows;
    size_t stride = (size_t) width * channels;
    const unsigned char* row = bitmap->buffer;
    unsigned char* pixels = malloc(stride * height);

    bool normalize_gray = bitmap->pixel_mode == FT_PIXEL_MODE_GRAY && bitmap->num_grays > 1 && bitmap->num_grays != 256;
    if (!normalize_gray && bitmap->pitch >= 0 && (size_t) bitmap->pitch == stride) {
        // Copy FreeType memory if it is normalized
        memcpy(pixels, row, stride * height);
    } else {
        if (bitmap->pitch < 0)
            row -= (ptrdiff_t) bitmap->pitch * (height - 1);
        for (int32_t y = 0; y < height; y++) {
            unsigned char* dst = pixels + (size_t) y * stride;
            if (normalize_gray) {
                for (int32_t x = 0; x < width; x++)
                    dst[x] = row[x] * 255 / (bitmap->num_grays - 1);
            } else memcpy(dst, row, stride);

            if (y + 1 < height)
                row += bitmap->pitch;
        }
    }

    int32_t left = slot->bitmap_left;
    int32_t top = -slot->bitmap_top;

    // Scale if needed (e.g. emoji)
    // Bi-linear (todo: optimize)
    if (scale_x != 1 || scale_y != 1) {
        double dst_left = floor(left * scale_x);
        double dst_top = floor(top * scale_y);
        double dst_right = ceil(((double) left + width) * scale_x);
        double dst_bottom = ceil(((double) top + height) * scale_y);
        double w = dst_right - dst_left;
        double h = dst_bottom - dst_top;

        if (dst_left < INT32_MIN || dst_left > INT32_MAX ||
            dst_top < INT32_MIN || dst_top > INT32_MAX ||
            w > INT32_MAX || h > INT32_MAX ||
            w * h * channels > INT32_MAX
        ) {
            free(pixels);
            ft_error = FT_Err_Array_Too_Large;
            return NULL;
        }

        int32_t dst_width = (int32_t) w;
        int32_t dst_height = (int32_t) h;
        unsigned char* scaled = calloc((size_t) dst_width * dst_height, channels);
        for (int32_t y = 0; y < dst_height; y++) {
            double src_y = (dst_top + y + 0.5) / scale_y - top - 0.5;
            double iy = floor(src_y), fy = src_y - iy;
            for (int32_t x = 0; x < dst_width; x++) {
                double src_x = (dst_left + x + 0.5) / scale_x - left - 0.5;
                double ix = floor(src_x), fx = src_x - ix;
                for (int c = 0; c < channels; c++) {
                    double value = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        double sy = iy + dy;
                        if (sy < 0 || sy >= height) continue;
                        for (int dx = 0; dx < 2; dx++) {
                            double sx = ix + dx;
                            if (sx < 0 || sx >= width) continue;
                            value += pixels[(size_t) sy * stride + (size_t) sx * channels + c] *
                                (dx ? fx : 1 - fx) * (dy ? fy : 1 - fy);
                        }
                    }
                    scaled[((size_t) y * dst_width + x) * channels + c] = (unsigned char) (value + 0.5);
                }
            }
        }
        free(pixels);
        pixels = scaled;
        width = dst_width;
        height = dst_height;
        left = (int32_t) dst_left;
        top = (int32_t) dst_top;
    }
    return NativeRenderedGlyph_new(
        k_byte_array_new((const int8_t*) pixels, (int32_t) ((size_t) width * height * channels), false),
        mode, width, height, left, top
    );
}

/* Some sbix strikes omit individual glyphs without reporting a loading error.
 * Retry larger strikes first, then smaller ones, while preserving empty spaces. */
static NativeRenderedGlyph* render_missing_bitmap(
    Font* font,
    FT_UInt glyph_id,
    FT_Int32 load_flags,
    int preferred_strike
) {
    FT_Face face = font->face->ft_face;
    NativeRenderedGlyph* image = copy_glyph_bitmap(face->glyph, 1, 1);
    int x_scale, y_scale;
    hb_font_get_scale(font->hb_font, &x_scale, &y_scale);

    int* strikes = malloc((size_t) face->num_fixed_sizes * sizeof(*strikes));
    int count = 0;
    for (int i = 0; i < face->num_fixed_sizes; i++) {
        const FT_Bitmap_Size* candidate = &face->available_sizes[i];
        if (i == preferred_strike || candidate->x_ppem <= 0 || candidate->y_ppem <= 0)
            continue;

        int position = count;
        while (position > 0) {
            FT_Pos previous = face->available_sizes[strikes[position - 1]].y_ppem;
            bool larger = candidate->y_ppem >= y_scale;
            bool previous_larger = previous >= y_scale;
            bool better = larger != previous_larger
                ? larger
                : (larger ? candidate->y_ppem < previous : candidate->y_ppem > previous);
            if (!better) break;
            strikes[position] = strikes[position - 1];
            position--;
        }
        strikes[position] = i;
        count++;
    }

    for (int i = 0; i < count; i++) {
        int strike = strikes[i];
        FT_Error error = FT_Select_Size(face, strike);
        if (!error) error = FT_Load_Glyph(face, glyph_id, load_flags);
        if (error || face->glyph->format != FT_GLYPH_FORMAT_BITMAP ||
            face->glyph->bitmap.width == 0 || face->glyph->bitmap.rows == 0)
            continue;

        double scale_x = x_scale / (double) face->available_sizes[strike].x_ppem;
        double scale_y = y_scale / (double) face->available_sizes[strike].y_ppem;
        NativeRenderedGlyph_free(image);
        image = copy_glyph_bitmap(face->glyph, scale_x, scale_y);
        break;
    }
    free(strikes);

    /* Copying must finish before restoring the face: the slot owns its bitmap. */
    FT_Error error = FT_Select_Size(face, preferred_strike);
    if (error) {
        NativeRenderedGlyph_free(image);
        ft_error = error;
        return NULL;
    }
    return image;
}

/* Input records are [font_index, glyph_id]; output elements preserve that order.
 * Setup/input failures return NULL, individual glyph failures produce NULL elements.
 * Result bitmaps own their bytes independently of the next FreeType glyph load. */
static KArray* render(KArray* fonts, KIntArray* glyphs, RenderMode mode) {
    ft_error = 0;
    if (glyphs->length % 2 != 0) {
        ft_error = FT_Err_Invalid_Argument;
        return NULL;
    }
    if (glyphs->length == 0) return k_array_with_capacity(0);

    FT_Render_Mode render_mode;
    FT_Int32 load_flags = FT_LOAD_COLOR;
    switch (mode) {
        case RenderMode_NORMAL: render_mode = FT_RENDER_MODE_NORMAL; load_flags |= FT_LOAD_TARGET_NORMAL; break;
        case RenderMode_LCD: render_mode = FT_RENDER_MODE_LCD; load_flags |= FT_LOAD_TARGET_LCD; break;
        case RenderMode_LCD_V: render_mode = FT_RENDER_MODE_LCD_V; load_flags |= FT_LOAD_TARGET_LCD_V; break;
        default:
            ft_error = FT_Err_Invalid_Argument;
            return NULL;
    }

    KArray* result = k_array_with_capacity(glyphs->length / 2);
    RC_NativeFont** fonts_rc = (RC_NativeFont**) fonts->elements;
    int32_t current_index = -1;
    int preferred_strike = -1;
    FT_Face face = NULL;
    double scale_x = 1, scale_y = 1;
    for (int32_t i = 0; i < glyphs->length; i += 2) {
        int32_t font_index = glyphs->elements[i];
        if (font_index < 0 || font_index >= fonts->length) {
            ft_error = FT_Err_Invalid_Argument;
            k_array_free(result);
            return NULL;
        }
        if (font_index != current_index) {
            Font* font = fonts_rc[font_index]->pointed;
            FT_Error error = prepare_render_font(font, &scale_x, &scale_y, &preferred_strike);
            if (error) {
                ft_error = error;
                k_array_free(result);
                return NULL;
            }
            face = font->face->ft_face;
            current_index = font_index;
        }
        FT_UInt id = (FT_UInt) glyphs->elements[i + 1];
        FT_Error error = FT_Load_Glyph(face, id, load_flags);

        if (!error && face->glyph->format != FT_GLYPH_FORMAT_BITMAP) {
            // COLRv0 compositing requires NORMAL even when LCD was requested.
            bool probe_color = FT_HAS_COLOR(face) && render_mode != FT_RENDER_MODE_NORMAL;
            error = FT_Render_Glyph(face->glyph, probe_color ? FT_RENDER_MODE_NORMAL : render_mode);

            if (probe_color && (!error && face->glyph->bitmap.pixel_mode != FT_PIXEL_MODE_BGRA)) {
                error = FT_Load_Glyph(face, id, load_flags);
                if (!error && face->glyph->format != FT_GLYPH_FORMAT_BITMAP)
                    error = FT_Render_Glyph(face->glyph, render_mode);
            }
        }
        if (error) ft_error = error;
        if (!error && preferred_strike >= 0 && face->glyph->format == FT_GLYPH_FORMAT_BITMAP &&
            (face->glyph->bitmap.width == 0 || face->glyph->bitmap.rows == 0)) {
            Font* font = fonts_rc[font_index]->pointed;
            k_array_push(result, render_missing_bitmap(font, id, load_flags, preferred_strike));
            // Reapply instance settings after probing alternate strikes.
            current_index = -1;
        } else {
            k_array_push(result, error ? NULL : copy_glyph_bitmap(face->glyph, scale_x, scale_y));
        }
    }
    return result;
}
