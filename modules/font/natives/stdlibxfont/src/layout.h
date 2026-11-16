#pragma once

/* Single-paragraph layout without wrapping. Positions use pixels, X right/Y down.
 * Text/emoji spans cover the UTF-8 input in logical order; fonts are borrowed.
 * Invalid input returns NULL and sets ft_error. */

#include "types.h"

#include <SheenBidi/SheenBidi.h>

typedef struct {
    int32_t offset;
    int32_t length;
    hb_script_t script;
    bool emoji;
} BidiLayoutSegment;

static TextLayout *create_layout(void) {
    TextLayout *result = malloc(sizeof(*result));
    *result = (TextLayout){0};
    return result;
}

static void text_layout_free(TextLayout *layout) {
    if (layout == NULL) {
        return;
    }
    free(layout->glyphs);
    free(layout);
}

static void glyph_push(TextLayout *layout, LayoutGlyph glyph) {
    if (layout->count >= layout->capacity) {
        layout->capacity = layout->capacity == 0 ? 4 : layout->capacity * 2;
        layout->glyphs = realloc(layout->glyphs, layout->capacity * sizeof(*layout->glyphs));
    }
    layout->glyphs[layout->count++] = glyph;
}

/* Bidi controls affect ordering but must not produce visible fallback glyphs. */
static bool bidi_control_cluster(
    const SBCodepointSequence *sequence,
    int32_t start,
    int32_t end
) {
    SBUInteger cursor = (SBUInteger)start;
    while (cursor < (SBUInteger)end) {
        SBCodepoint cp = SBCodepointSequenceGetCodepointAt(sequence, &cursor);
        if (!(cp == 0x061C || cp == 0x200E || cp == 0x200F ||
              (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069))) {
            return false;
        }
    }
    return true;
}

/* HarfBuzz handles glyph order and mirroring; fallback inherits the run properties. */
static bool layout_part(
    RC_NativeFont **fonts,
    int32_t fonts_first,
    int32_t fonts_last,
    int32_t font_index,
    KString *text,
    int32_t offset,
    int32_t length,
    KIntArray* feature_keys,
    KBooleanArray* feature_values,
    hb_direction_t direction,
    hb_script_t script,
    hb_language_t language,
    const SBCodepointSequence *sequence,
    TextLayout *result
) {
    Font *font = fonts[font_index]->pointed;
    size_t saved_count = result->count;
    double saved_x = result->advance_x, saved_y = result->advance_y;
    hb_buffer_t *buffer = hb_buffer_create();
    hb_buffer_add_utf8(buffer, text->data, text->size, (unsigned int)offset, length);
    hb_buffer_set_direction(buffer, direction);
    hb_buffer_set_script(buffer, script);
    hb_buffer_set_language(buffer, language);
    hb_buffer_set_cluster_level(buffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);

    // Map features
    unsigned int hb_feature_count = (unsigned int) feature_keys->length;
    hb_feature_t* hb_features = NULL;
    if (hb_feature_count > 0) {
        hb_features = malloc((size_t) hb_feature_count * sizeof(hb_feature_t));
        for (int32_t i = 0; i < hb_feature_count; i++) {
            hb_features[i] = (hb_feature_t) {
                .tag = (hb_tag_t) feature_keys->elements[i],
                .value = feature_values->elements[i],
                .start = HB_FEATURE_GLOBAL_START,
                .end = HB_FEATURE_GLOBAL_END
            };
        }
    }
    hb_shape(font->hb_font, buffer, hb_features, hb_feature_count);
    free(hb_features);

    unsigned int count;
    const hb_glyph_info_t *infos = hb_buffer_get_glyph_infos(buffer, &count);
    const hb_glyph_position_t *positions = hb_buffer_get_glyph_positions(buffer, NULL);

    for (unsigned int begin = 0, end; begin < count; begin = end) {
        end = begin;
        bool missing = false;
        while (end < count && infos[end].cluster == infos[begin].cluster) {
            if (infos[end].codepoint == 0) {
                missing = true;
            }
            end++;
        }

        int32_t byte_start = (int32_t)infos[begin].cluster;
        /* RTL cluster offsets decrease, so use the previous visual cluster as the end. */
        int32_t byte_end = direction == HB_DIRECTION_RTL
            ? (begin == 0 ? offset + length : (int32_t)infos[begin - 1].cluster)
            : (end == count ? offset + length : (int32_t)infos[end].cluster);
        if (bidi_control_cluster(sequence, byte_start, byte_end)) {
            continue;
        }

        if (missing) {
            if (font_index + 1 < fonts_last && layout_part(
                    fonts, fonts_first, fonts_last, font_index + 1,
                    text, byte_start, byte_end - byte_start,
                    feature_keys, feature_values,
                    direction, script, language, sequence, result)) {
                continue;
            }
            if (font_index != fonts_first) {
                /* A failed fallback attempt must leave the output unchanged. */
                result->count = saved_count;
                result->advance_x = saved_x;
                result->advance_y = saved_y;
                hb_buffer_destroy(buffer);
                return false;
            }
            /* Keep the primary font's .notdef when no fallback succeeds. */
        }

        for (unsigned int i = begin; i < end; i++) {
            glyph_push(result, (LayoutGlyph){
                .font_index = font_index,
                .glyph_id = infos[i].codepoint,
                .x = result->advance_x + positions[i].x_offset / 64.0,
                .y = result->advance_y - positions[i].y_offset / 64.0,
                .x_advance = positions[i].x_advance / 64.0,
                .y_advance = -positions[i].y_advance / 64.0,
            });
            result->advance_x += positions[i].x_advance / 64.0;
            result->advance_y -= positions[i].y_advance / 64.0;
        }
    }
    hb_buffer_destroy(buffer);
    return true;
}

/* Intersect script runs and text/emoji spans; all offsets are UTF-8 bytes. */
static BidiLayoutSegment *bidi_layout_segments(
    const SBCodepointSequence *sequence,
    const KIntArray *parts,
    size_t *count
) {
    BidiLayoutSegment *segments = NULL;
    size_t capacity = 0;
    *count = 0;
    int32_t part_index = 0;
    SBScriptLocatorRef locator = SBScriptLocatorCreate();
    SBScriptLocatorLoadCodepoints(locator, sequence);

    while (SBScriptLocatorMoveNext(locator)) {
        const SBScriptAgent *agent = SBScriptLocatorGetAgent(locator);
        int32_t cursor = (int32_t)agent->offset;
        int32_t script_end = (int32_t)(agent->offset + agent->length);
        hb_tag_t tag = SBScriptGetUnicodeTag(agent->script);
        hb_script_t script = tag ? hb_script_from_iso15924_tag(tag) : HB_SCRIPT_COMMON;

        while (cursor < script_end) {
            int32_t part_end = parts->elements[part_index] + parts->elements[part_index + 1];
            int32_t end = part_end < script_end ? part_end : script_end;
            if (*count == capacity) {
                capacity = capacity == 0 ? 4 : capacity * 2;
                segments = realloc(segments, capacity * sizeof(*segments));
            }
            segments[(*count)++] = (BidiLayoutSegment){
                cursor, end - cursor, script, parts->elements[part_index + 2] == 1
            };
            cursor = end;
            if (cursor == part_end) {
                part_index += 3;
            }
        }
    }
    SBScriptLocatorRelease(locator);
    return segments;
}

static size_t bidi_layout_segment_at(
    const BidiLayoutSegment *segments,
    size_t count,
    int32_t byte
) {
    size_t low = 0, high = count;
    while (low < high) {
        size_t middle = low + (high - low) / 2;
        if (segments[middle].offset + segments[middle].length <= byte) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    return low;
}

/* Base direction controls ordering, not alignment; the visual line starts at X = 0. */
static TextLayout *text_layout_new(
    KArray *fonts,
    int32_t default_fonts_count,
    KString *text,
    KIntArray *utf8_text_layout,
    KIntArray* feature_keys,
    KBooleanArray* feature_values,
    TextDirection direction,
    KString* language
) {
    SBLevel base_level;
    switch (direction) {
        case TextDirection_AUTO:
            base_level = SBLevelDefaultLTR;
            break;
        case TextDirection_LTR:
            base_level = 0;
            break;
        case TextDirection_RTL:
            base_level = 1;
            break;
        default:
            goto invalid;
    }
    if (default_fonts_count < 0 || default_fonts_count > fonts->length ||
        text->size < 0 || utf8_text_layout->length % 3 != 0) {
        goto invalid;
    }

    /* Validate contiguous spans, UTF-8 boundaries and nonempty font groups. */
    int32_t covered = 0;
    for (int32_t i = 0; i < utf8_text_layout->length; i += 3) {
        int32_t start = utf8_text_layout->elements[i];
        int32_t length = utf8_text_layout->elements[i + 1];
        int32_t type = utf8_text_layout->elements[i + 2];
        if (start != covered || length <= 0 || length > text->size - start ||
            (type != 0 && type != 1)) {
            goto invalid;
        }
        int32_t end = start + length;

        if (((unsigned char)text->data[start] & 0xC0) == 0x80 ||
            (end < text->size && ((unsigned char)text->data[end] & 0xC0) == 0x80)) {
            goto invalid;
        }
        if ((type == 0 && default_fonts_count == 0) ||
            (type == 1 && default_fonts_count == fonts->length)) {
            goto invalid;
        }
        covered = end;
    }
    if (covered != text->size) {
        goto invalid;
    }
    if (text->size == 0) {
        return create_layout();
    }

    SBCodepointSequence sequence = {
        .stringEncoding = SBStringEncodingUTF8,
        .stringBuffer = text->data,
        .stringLength = (SBUInteger)text->size
    };
    /* U+2028 is a line separator, not a bidi paragraph separator. */
    for (SBUInteger cursor = 0; cursor < sequence.stringLength;) {
        if (SBCodepointSequenceGetCodepointAt(&sequence, &cursor) == 0x2028) {
            goto invalid;
        }
    }

    SBAlgorithmRef algorithm = SBAlgorithmCreate(&sequence);
    SBUInteger paragraph_length, separator_length;
    SBAlgorithmGetParagraphBoundary(
        algorithm, 0, sequence.stringLength, &paragraph_length, &separator_length
    );
    if (paragraph_length != sequence.stringLength || separator_length != 0) {
        SBAlgorithmRelease(algorithm);
        goto invalid;
    }
    SBParagraphRef paragraph = SBAlgorithmCreateParagraph(algorithm, 0, paragraph_length, base_level);
    SBLineRef line = SBParagraphCreateLine(paragraph, 0, paragraph_length);
    const SBRun *runs = SBLineGetRunsPtr(line);
    SBUInteger run_count = SBLineGetRunCount(line);
    size_t segment_count;
    BidiLayoutSegment *segments = bidi_layout_segments(
        &sequence, utf8_text_layout, &segment_count
    );
    TextLayout *result = create_layout();
    RC_NativeFont **fonts_rc = (RC_NativeFont**) fonts->elements;
    hb_language_t hb_language = language == NULL
        ? hb_language_get_default()
        : hb_language_from_string(language->data, language->size);

    /* SheenBidi runs are visual; visit their logical segments backwards for RTL. */
    for (SBUInteger i = 0; i < run_count; i++) {
        int32_t start = (int32_t)runs[i].offset;
        int32_t end = (int32_t)(runs[i].offset + runs[i].length);
        bool rtl = (runs[i].level & 1) != 0;
        int32_t cursor = rtl ? end : start;

        while (rtl ? cursor > start : cursor < end) {
            size_t index = bidi_layout_segment_at(
                segments, segment_count, rtl ? cursor - 1 : cursor
            );
            const BidiLayoutSegment *segment = &segments[index];
            int32_t part_start = rtl ? (segment->offset > start ? segment->offset : start) : cursor;
            int32_t segment_end = segment->offset + segment->length;
            int32_t part_end = rtl ? cursor : (segment_end < end ? segment_end : end);
            int32_t first = segment->emoji ? default_fonts_count : 0;
            int32_t last = segment->emoji ? fonts->length : default_fonts_count;
            layout_part(
                fonts_rc, first, last, first,
                text, part_start, part_end - part_start,
                feature_keys, feature_values,
                rtl ? HB_DIRECTION_RTL : HB_DIRECTION_LTR, segment->script, hb_language, &sequence,
                result
            );
            cursor = rtl ? part_start : part_end;
        }
    }
    free(segments);
    SBLineRelease(line);
    SBParagraphRelease(paragraph);
    SBAlgorithmRelease(algorithm);
    return result;

invalid:
    ft_error = FT_Err_Invalid_Argument;
    return NULL;
}
