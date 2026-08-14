#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <limits.h>

#include "mupdf/fitz.h"
#include "mupdf/pdf.h"

#define XUEXUE_JNI_VERSION JNI_VERSION_1_8
#define MAX_OUTLINE_DEPTH 64
#define MAX_OUTLINE_NODES 1000000
#define MAX_OUTLINE_STRING_BYTES (16U * 1024U * 1024U)
#define MAX_OUTLINE_PAYLOAD_BYTES (64U * 1024U * 1024U)
static fz_context *base_context;
static pthread_key_t context_key;
static pthread_mutex_t init_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t mupdf_mutexes[FZ_LOCK_MAX];
static int context_key_ready;
static int runtime_ready;

typedef struct
{
    pthread_mutex_t mutex;
    int64_t length;
    size_t block_size;
    size_t block_count;
    size_t blocks_loaded;
    unsigned char **blocks;
    size_t *block_lengths;
    unsigned char *read_buffer;
    int64_t requested_offset;
    size_t requested_length;
    uint64_t request_generation;
} progressive_stream_state;

static void lock_mutex(void *user, int lock)
{
    (void)user;
    (void)pthread_mutex_lock(&mupdf_mutexes[lock]);
}

static void unlock_mutex(void *user, int lock)
{
    (void)user;
    (void)pthread_mutex_unlock(&mupdf_mutexes[lock]);
}

static const fz_locks_context locks = {
    NULL,
    lock_mutex,
    unlock_mutex,
};

static void drop_thread_context(void *pointer)
{
    fz_drop_context((fz_context *)pointer);
}

static void throw_java(JNIEnv *env, const char *class_name, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, class_name);
    if (exception_class != NULL)
    {
        (void)(*env)->ThrowNew(env, exception_class, message);
        (*env)->DeleteLocalRef(env, exception_class);
    }
}

static void rethrow_mupdf(JNIEnv *env, fz_context *ctx)
{
    int code;
    const char *message = fz_convert_error(ctx, &code);
    if (code == FZ_ERROR_ABORT)
        throw_java(env, "java/util/concurrent/CancellationException", message);
    else if (code == FZ_ERROR_TRYLATER)
        throw_java(env, "dev/xuexue/platform/PdfTryLaterException", message);
    else
        throw_java(env, "java/lang/IllegalStateException", message);
}

static size_t progressive_block_length(const progressive_stream_state *state, size_t block_index)
{
    int64_t offset = (int64_t)block_index * (int64_t)state->block_size;
    int64_t remaining = state->length - offset;
    if (remaining <= 0)
        return 0;
    return remaining < (int64_t)state->block_size ? (size_t)remaining : state->block_size;
}

static int next_progressive(fz_context *ctx, fz_stream *stream, size_t max)
{
    progressive_stream_state *state = (progressive_stream_state *)stream->state;
    int64_t position = stream->pos;
    size_t block_index;
    size_t block_offset;
    size_t available;

    (void)max;
    if (position >= state->length)
        return EOF;
    if (position < 0)
        fz_throw(ctx, FZ_ERROR_ARGUMENT, "Progressive stream position is negative.");

    block_index = (size_t)(position / (int64_t)state->block_size);
    block_offset = (size_t)(position % (int64_t)state->block_size);
    (void)pthread_mutex_lock(&state->mutex);
    if (state->blocks[block_index] == NULL)
    {
        state->requested_offset = (int64_t)block_index * (int64_t)state->block_size;
        state->requested_length = progressive_block_length(state, block_index);
        state->request_generation++;
        (void)pthread_mutex_unlock(&state->mutex);
        fz_throw(ctx, FZ_ERROR_TRYLATER, "PDF byte range is not available yet.");
    }
    available = state->block_lengths[block_index] - block_offset;
    memcpy(state->read_buffer, state->blocks[block_index] + block_offset, available);
    (void)pthread_mutex_unlock(&state->mutex);

    stream->rp = state->read_buffer;
    stream->wp = state->read_buffer + available;
    stream->pos += (int64_t)available;
    return *stream->rp++;
}

static void seek_progressive(fz_context *ctx, fz_stream *stream, int64_t offset, int whence)
{
    progressive_stream_state *state = (progressive_stream_state *)stream->state;
    int64_t position = offset;

    if (whence == SEEK_CUR)
        position += fz_tell(ctx, stream);
    else if (whence == SEEK_END)
        position += state->length;
    else if (whence != SEEK_SET)
        fz_throw(ctx, FZ_ERROR_ARGUMENT, "Unsupported progressive stream seek mode.");
    if (position < 0 || position > state->length)
        fz_throw(ctx, FZ_ERROR_ARGUMENT, "Progressive stream seek is outside the document.");
    stream->pos = position;
    stream->rp = state->read_buffer;
    stream->wp = state->read_buffer;
}

static void drop_progressive(fz_context *ctx, void *opaque)
{
    progressive_stream_state *state = (progressive_stream_state *)opaque;
    size_t index;

    (void)ctx;
    if (state == NULL)
        return;
    for (index = 0; index < state->block_count; ++index)
        free(state->blocks[index]);
    free(state->read_buffer);
    free(state->block_lengths);
    free(state->blocks);
    (void)pthread_mutex_destroy(&state->mutex);
    free(state);
}

static fz_context *get_context(JNIEnv *env)
{
    fz_context *ctx;

    if (!runtime_ready || base_context == NULL)
    {
        throw_java(env, "java/lang/IllegalStateException", "MuPDF native runtime is not initialized.");
        return NULL;
    }

    ctx = (fz_context *)pthread_getspecific(context_key);
    if (ctx != NULL)
        return ctx;

    ctx = fz_clone_context(base_context);
    if (ctx == NULL)
    {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not clone a MuPDF context.");
        return NULL;
    }
    if (pthread_setspecific(context_key, ctx) != 0)
    {
        fz_drop_context(ctx);
        throw_java(env, "java/lang/IllegalStateException", "Could not bind a MuPDF context to the worker thread.");
        return NULL;
    }
    return ctx;
}

static char *copy_utf8(JNIEnv *env, jbyteArray source)
{
    jsize length;
    char *copy;

    if (source == NULL)
    {
        throw_java(env, "java/lang/NullPointerException", "UTF-8 input must not be null.");
        return NULL;
    }
    length = (*env)->GetArrayLength(env, source);
    copy = (char *)malloc((size_t)length + 1);
    if (copy == NULL)
    {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not copy UTF-8 input for MuPDF.");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, source, 0, length, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env))
    {
        free(copy);
        return NULL;
    }
    copy[length] = '\0';
    return copy;
}

static jbyteArray copy_c_string(JNIEnv *env, const char *source)
{
    size_t size = source == NULL ? 0 : strlen(source);
    jbyteArray result;

    if (size > (size_t)INT32_MAX)
    {
        throw_java(env, "java/lang/IllegalStateException", "MuPDF text is too large for a JVM byte array.");
        return NULL;
    }
    result = (*env)->NewByteArray(env, (jsize)size);
    if (result == NULL || (*env)->ExceptionCheck(env))
        return NULL;
    if (size > 0)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)source);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}

static int read_selection_points(JNIEnv *env, jfloatArray source, fz_point *start, fz_point *end)
{
    jfloat points[4];

    if (source == NULL || (*env)->GetArrayLength(env, source) != 4)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "PDF selection requires two points.");
        return 0;
    }
    (*env)->GetFloatArrayRegion(env, source, 0, 4, points);
    if ((*env)->ExceptionCheck(env))
        return 0;
    start->x = points[0];
    start->y = points[1];
    end->x = points[2];
    end->y = points[3];
    return 1;
}

static void append_float_le(fz_context *ctx, fz_buffer *buffer, float value)
{
    union
    {
        float floating;
        uint32_t bits;
    } encoded;

    encoded.floating = value;
    fz_append_int32_le(ctx, buffer, (int)encoded.bits);
}

static float normalized_coordinate(float value, float origin, float extent)
{
    float normalized = extent > 0 ? (value - origin) / extent : 0;
    if (normalized < 0)
        return 0;
    if (normalized > 1)
        return 1;
    return normalized;
}

static jbyteArray copy_buffer(JNIEnv *env, fz_context *ctx, fz_buffer *buffer)
{
    unsigned char *data = NULL;
    size_t size = fz_buffer_storage(ctx, buffer, &data);
    jbyteArray result;

    if (size > (size_t)INT32_MAX)
    {
        throw_java(env, "java/lang/IllegalStateException", "MuPDF output is too large for a JVM byte array.");
        return NULL;
    }
    result = (*env)->NewByteArray(env, (jsize)size);
    if (result == NULL || (*env)->ExceptionCheck(env))
        return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)data);
    if ((*env)->ExceptionCheck(env))
        return NULL;
    return result;
}

static jbyteArray copy_pixmap_rgba(JNIEnv *env, fz_context *ctx, fz_pixmap *pixmap)
{
    int width = fz_pixmap_width(ctx, pixmap);
    int height = fz_pixmap_height(ctx, pixmap);
    int components = fz_pixmap_components(ctx, pixmap);
    int stride = fz_pixmap_stride(ctx, pixmap);
    int row_bytes = width * 4;
    size_t size = (size_t)row_bytes * (size_t)height;
    unsigned char *samples = fz_pixmap_samples(ctx, pixmap);
    jbyteArray result;
    int row;

    if (components != 4 || width <= 0 || height <= 0 || stride < row_bytes || size > (size_t)INT32_MAX)
    {
        throw_java(env, "java/lang/IllegalStateException", "MuPDF returned an invalid RGBA pixmap.");
        return NULL;
    }
    result = (*env)->NewByteArray(env, (jsize)size);
    if (result == NULL || (*env)->ExceptionCheck(env))
        return NULL;
    if (stride == row_bytes)
    {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)samples);
    }
    else
    {
        for (row = 0; row < height; ++row)
        {
            (*env)->SetByteArrayRegion(
                env,
                result,
                row * row_bytes,
                row_bytes,
                (const jbyte *)(samples + row * stride));
            if ((*env)->ExceptionCheck(env))
                return NULL;
        }
    }
    return (*env)->ExceptionCheck(env) ? NULL : result;
}

static void append_outline_string(fz_context *ctx, fz_buffer *buffer, const char *value)
{
    size_t length = value == NULL ? 0 : strlen(value);
    if (length > MAX_OUTLINE_STRING_BYTES || length > (size_t)INT32_MAX)
        fz_throw(ctx, FZ_ERROR_LIMIT, "PDF outline string is too long.");
    fz_append_int32_le(ctx, buffer, value == NULL ? -1 : (int)length);
    if (value != NULL && length > 0)
        fz_append_data(ctx, buffer, value, length);
}

static int outline_count(fz_context *ctx, fz_outline *outline, int depth)
{
    int count = 0;
    int child_count;
    if (depth > MAX_OUTLINE_DEPTH)
        fz_throw(ctx, FZ_ERROR_LIMIT, "PDF outline nesting is too deep.");
    while (outline != NULL)
    {
        if (count >= MAX_OUTLINE_NODES)
            fz_throw(ctx, FZ_ERROR_LIMIT, "PDF outline contains too many nodes.");
        ++count;
        child_count = outline_count(ctx, outline->down, depth + 1);
        if (child_count > MAX_OUTLINE_NODES - count)
            fz_throw(ctx, FZ_ERROR_LIMIT, "PDF outline contains too many nodes.");
        count += child_count;
        outline = outline->next;
    }
    return count;
}

static void append_outline_nodes(
    fz_context *ctx,
    fz_buffer *buffer,
    fz_document *document,
    fz_outline *outline,
    int depth)
{
    if (depth > MAX_OUTLINE_DEPTH)
        fz_throw(ctx, FZ_ERROR_LIMIT, "PDF outline nesting is too deep.");
    while (outline != NULL)
    {
        fz_location location = outline->page;
        fz_page *page = NULL;
        fz_rect bounds = fz_empty_rect;
        int page_index = -1;
        float top = -1;
        if (outline->uri != NULL)
            location = fz_resolve_link(ctx, document, outline->uri, &outline->x, &outline->y);
        page_index = fz_page_number_from_location(ctx, document, location);
        if (page_index >= 0)
        {
            page = fz_load_page(ctx, document, page_index);
            bounds = fz_bound_page(ctx, page);
            top = normalized_coordinate(outline->y, bounds.y0, bounds.y1 - bounds.y0);
            fz_drop_page(ctx, page);
        }
        fz_append_int32_le(ctx, buffer, depth);
        fz_append_int32_le(ctx, buffer, page_index);
        append_float_le(ctx, buffer, top);
        fz_append_int32_le(ctx, buffer, (int)outline->flags);
        append_outline_string(ctx, buffer, outline->title);
        append_outline_string(ctx, buffer, outline->uri);
        if (outline->down != NULL)
            append_outline_nodes(ctx, buffer, document, outline->down, depth + 1);
        outline = outline->next;
    }
}

static void append_rect_le(fz_context *ctx, fz_buffer *buffer, fz_rect rect)
{
    append_float_le(ctx, buffer, rect.x0);
    append_float_le(ctx, buffer, rect.y0);
    append_float_le(ctx, buffer, rect.x1);
    append_float_le(ctx, buffer, rect.y1);
}

static fz_rect char_bounds(fz_stext_char *character)
{
    fz_rect bounds = fz_empty_rect;
    fz_point points[4];
    int index;

    points[0] = character->quad.ul;
    points[1] = character->quad.ur;
    points[2] = character->quad.lr;
    points[3] = character->quad.ll;
    bounds.x0 = bounds.x1 = points[0].x;
    bounds.y0 = bounds.y1 = points[0].y;
    for (index = 1; index < 4; ++index)
    {
        if (points[index].x < bounds.x0)
            bounds.x0 = points[index].x;
        if (points[index].x > bounds.x1)
            bounds.x1 = points[index].x;
        if (points[index].y < bounds.y0)
            bounds.y0 = points[index].y;
        if (points[index].y > bounds.y1)
            bounds.y1 = points[index].y;
    }
    return bounds;
}

static int line_count(fz_stext_line *line)
{
    int count = 0;
    while (line != NULL)
    {
        ++count;
        line = line->next;
    }
    return count;
}

static int char_count(fz_stext_char *character)
{
    int count = 0;
    while (character != NULL)
    {
        ++count;
        character = character->next;
    }
    return count;
}

JNIEXPORT jstring JNICALL
Java_dev_xuexue_platform_MuPdfNative_initialize(JNIEnv *env, jobject self, jlong store_size)
{
    int i;

    (void)self;
    if (store_size <= 0)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "MuPDF store size must be positive.");
        return NULL;
    }

    (void)pthread_mutex_lock(&init_mutex);
    if (!runtime_ready)
    {
        for (i = 0; i < FZ_LOCK_MAX; ++i)
            (void)pthread_mutex_init(&mupdf_mutexes[i], NULL);
        if (pthread_key_create(&context_key, drop_thread_context) != 0)
        {
            (void)pthread_mutex_unlock(&init_mutex);
            throw_java(env, "java/lang/IllegalStateException", "Could not create MuPDF thread-local storage.");
            return NULL;
        }
        context_key_ready = 1;
        base_context = fz_new_context(NULL, &locks, (size_t)store_size);
        if (base_context == NULL)
        {
            (void)pthread_key_delete(context_key);
            context_key_ready = 0;
            (void)pthread_mutex_unlock(&init_mutex);
            throw_java(env, "java/lang/OutOfMemoryError", "Could not create the MuPDF base context.");
            return NULL;
        }
        fz_try(base_context)
            fz_register_document_handlers(base_context);
        fz_catch(base_context)
        {
            rethrow_mupdf(env, base_context);
            fz_drop_context(base_context);
            base_context = NULL;
            (void)pthread_key_delete(context_key);
            context_key_ready = 0;
            (void)pthread_mutex_unlock(&init_mutex);
            return NULL;
        }
        runtime_ready = 1;
    }
    (void)pthread_mutex_unlock(&init_mutex);
    return (*env)->NewStringUTF(env, FZ_VERSION);
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_emptyStore(JNIEnv *env, jobject self)
{
    fz_context *ctx = get_context(env);
    (void)self;
    if (ctx != NULL)
        fz_empty_store(ctx);
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_openDocument(JNIEnv *env, jobject self, jbyteArray path_utf8)
{
    fz_context *ctx = get_context(env);
    fz_document *document = NULL;
    char *path;

    (void)self;
    if (ctx == NULL)
        return 0;
    path = copy_utf8(env, path_utf8);
    if (path == NULL)
        return 0;

    fz_try(ctx)
        document = fz_open_document(ctx, path);
    fz_always(ctx)
        free(path);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return 0;
    }
    return (jlong)(intptr_t)document;
}

JNIEXPORT jboolean JNICALL
Java_dev_xuexue_platform_MuPdfNative_needsPassword(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);
    int needs_password = 0;

    (void)self;
    if (ctx == NULL || handle == 0)
        return JNI_FALSE;
    fz_try(ctx)
        needs_password = fz_needs_password(ctx, (fz_document *)(intptr_t)handle);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return JNI_FALSE;
    }
    return needs_password ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_xuexue_platform_MuPdfNative_authenticatePassword(
    JNIEnv *env,
    jobject self,
    jlong handle,
    jbyteArray password_utf8)
{
    fz_context *ctx = get_context(env);
    char *password;
    int authenticated = 0;

    (void)self;
    if (ctx == NULL || handle == 0)
        return JNI_FALSE;
    password = copy_utf8(env, password_utf8);
    if (password == NULL)
        return JNI_FALSE;
    fz_try(ctx)
        authenticated = fz_authenticate_password(ctx, (fz_document *)(intptr_t)handle, password);
    fz_always(ctx)
        free(password);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return JNI_FALSE;
    }
    return authenticated ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_createProgressiveStream(
    JNIEnv *env,
    jobject self,
    jlong length,
    jint block_size)
{
    fz_context *ctx = get_context(env);
    progressive_stream_state *state = NULL;
    fz_stream *stream = NULL;
    size_t block_count;

    (void)self;
    if (ctx == NULL)
        return 0;
    if (length <= 0 || block_size <= 0)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "Progressive PDF length and block size must be positive.");
        return 0;
    }
    block_count = (size_t)((length + block_size - 1) / block_size);
    if (block_count == 0 || block_count > SIZE_MAX / sizeof(unsigned char *))
    {
        throw_java(env, "java/lang/IllegalArgumentException", "Progressive PDF block table is too large.");
        return 0;
    }

    state = (progressive_stream_state *)calloc(1, sizeof(progressive_stream_state));
    if (state == NULL)
    {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate progressive PDF state.");
        return 0;
    }
    state->length = length;
    state->block_size = (size_t)block_size;
    state->block_count = block_count;
    state->blocks = (unsigned char **)calloc(block_count, sizeof(unsigned char *));
    state->block_lengths = (size_t *)calloc(block_count, sizeof(size_t));
    state->read_buffer = (unsigned char *)malloc((size_t)block_size);
    if (state->blocks == NULL || state->block_lengths == NULL || state->read_buffer == NULL ||
        pthread_mutex_init(&state->mutex, NULL) != 0)
    {
        free(state->read_buffer);
        free(state->block_lengths);
        free(state->blocks);
        free(state);
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate progressive PDF block cache.");
        return 0;
    }

    fz_try(ctx)
    {
        stream = fz_new_stream(ctx, state, next_progressive, drop_progressive);
        stream->seek = seek_progressive;
        stream->progressive = 1;
    }
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return 0;
    }
    return (jlong)(intptr_t)stream;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_provideProgressiveRange(
    JNIEnv *env,
    jobject self,
    jlong stream_handle,
    jlong offset,
    jbyteArray bytes)
{
    fz_stream *stream = (fz_stream *)(intptr_t)stream_handle;
    progressive_stream_state *state;
    size_t block_index;
    size_t expected_length;
    jsize length;
    unsigned char *block;

    (void)self;
    if (stream == NULL || bytes == NULL)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "Progressive PDF stream and bytes are required.");
        return;
    }
    state = (progressive_stream_state *)stream->state;
    if (offset < 0 || offset % (jlong)state->block_size != 0)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "Progressive PDF range must start on a block boundary.");
        return;
    }
    block_index = (size_t)(offset / (jlong)state->block_size);
    if (block_index >= state->block_count)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "Progressive PDF range starts outside the document.");
        return;
    }
    expected_length = progressive_block_length(state, block_index);
    length = (*env)->GetArrayLength(env, bytes);
    if ((size_t)length != expected_length)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "Progressive PDF range has an unexpected length.");
        return;
    }
    block = (unsigned char *)malloc(expected_length);
    if (block == NULL)
    {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate a progressive PDF block.");
        return;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)block);
    if ((*env)->ExceptionCheck(env))
    {
        free(block);
        return;
    }

    (void)pthread_mutex_lock(&state->mutex);
    if (state->blocks[block_index] == NULL)
    {
        state->blocks[block_index] = block;
        state->block_lengths[block_index] = expected_length;
        state->blocks_loaded++;
        block = NULL;
    }
    (void)pthread_mutex_unlock(&state->mutex);
    free(block);
}

JNIEXPORT jlongArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_progressiveRequestedRange(JNIEnv *env, jobject self, jlong stream_handle)
{
    fz_stream *stream = (fz_stream *)(intptr_t)stream_handle;
    progressive_stream_state *state;
    jlong values[2];
    jlongArray result;

    (void)self;
    if (stream == NULL)
        return NULL;
    state = (progressive_stream_state *)stream->state;
    (void)pthread_mutex_lock(&state->mutex);
    values[0] = (jlong)state->requested_offset;
    values[1] = (jlong)state->requested_length;
    (void)pthread_mutex_unlock(&state->mutex);
    result = (*env)->NewLongArray(env, 2);
    if (result != NULL)
        (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_progressiveBytesLoaded(JNIEnv *env, jobject self, jlong stream_handle)
{
    fz_stream *stream = (fz_stream *)(intptr_t)stream_handle;
    progressive_stream_state *state;
    size_t index;
    int64_t total = 0;

    (void)env;
    (void)self;
    if (stream == NULL)
        return 0;
    state = (progressive_stream_state *)stream->state;
    (void)pthread_mutex_lock(&state->mutex);
    for (index = 0; index < state->block_count; ++index)
        total += (int64_t)state->block_lengths[index];
    (void)pthread_mutex_unlock(&state->mutex);
    return (jlong)total;
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_openDocumentWithProgressiveStream(
    JNIEnv *env,
    jobject self,
    jlong stream_handle)
{
    fz_context *ctx = get_context(env);
    fz_stream *stream = (fz_stream *)(intptr_t)stream_handle;
    fz_document *document = NULL;

    (void)self;
    if (ctx == NULL || stream == NULL)
        return 0;
    fz_var(document);
    fz_try(ctx)
    {
        fz_seek(ctx, stream, 0, SEEK_SET);
        document = fz_open_document_with_stream(ctx, "application/pdf", stream);
    }
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return 0;
    }
    return (jlong)(intptr_t)document;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_dropProgressiveStream(JNIEnv *env, jobject self, jlong stream_handle)
{
    fz_context *ctx = get_context(env);
    (void)self;
    if (ctx != NULL && stream_handle != 0)
        fz_drop_stream(ctx, (fz_stream *)(intptr_t)stream_handle);
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_closeDocument(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);
    (void)self;
    if (ctx != NULL && handle != 0)
        fz_drop_document(ctx, (fz_document *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_dev_xuexue_platform_MuPdfNative_countPages(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);
    int count = 0;

    (void)self;
    if (ctx == NULL || handle == 0)
        return 0;
    fz_try(ctx)
        count = fz_count_pages(ctx, (fz_document *)(intptr_t)handle);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return 0;
    }
    return count;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_pageBounds(JNIEnv *env, jobject self, jlong handle, jint page_index)
{
    fz_context *ctx = get_context(env);
    fz_document *document = (fz_document *)(intptr_t)handle;
    fz_page *page = NULL;
    fz_rect bounds = fz_empty_rect;
    jfloat values[4];
    jfloatArray result;

    (void)self;
    if (ctx == NULL || document == NULL)
        return NULL;
    fz_var(page);
    fz_var(bounds);
    fz_try(ctx)
    {
        page = fz_load_page(ctx, document, page_index);
        bounds = fz_bound_page(ctx, page);
    }
    fz_always(ctx)
        fz_drop_page(ctx, page);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    values[0] = bounds.x0;
    values[1] = bounds.y0;
    values[2] = bounds.x1;
    values[3] = bounds.y1;
    result = (*env)->NewFloatArray(env, 4);
    if (result != NULL)
        (*env)->SetFloatArrayRegion(env, result, 0, 4, values);
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_loadDisplayList(JNIEnv *env, jobject self, jlong handle, jint page_index)
{
    fz_context *ctx = get_context(env);
    fz_document *document = (fz_document *)(intptr_t)handle;
    fz_page *page = NULL;
    fz_display_list *display_list = NULL;

    (void)self;
    if (ctx == NULL || document == NULL)
        return 0;
    fz_var(page);
    fz_var(display_list);
    fz_try(ctx)
    {
        page = fz_load_page(ctx, document, page_index);
        display_list = fz_new_display_list_from_page_contents(ctx, page);
    }
    fz_always(ctx)
        fz_drop_page(ctx, page);
    fz_catch(ctx)
    {
        fz_drop_display_list(ctx, display_list);
        rethrow_mupdf(env, ctx);
        return 0;
    }
    return (jlong)(intptr_t)display_list;
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_loadOverlayDisplayList(JNIEnv *env, jobject self, jlong handle, jint page_index)
{
    fz_context *ctx = get_context(env);
    fz_document *document = (fz_document *)(intptr_t)handle;
    fz_page *page = NULL;
    fz_display_list *display_list = NULL;
    fz_device *device = NULL;
    fz_rect bounds;

    (void)self;
    if (ctx == NULL || document == NULL)
        return 0;
    fz_var(page);
    fz_var(display_list);
    fz_var(device);
    fz_try(ctx)
    {
        page = fz_load_page(ctx, document, page_index);
        bounds = fz_bound_page(ctx, page);
        display_list = fz_new_display_list(ctx, bounds);
        device = fz_new_list_device(ctx, display_list);
        fz_run_page_annots(ctx, page, device, fz_identity, NULL);
        fz_run_page_widgets(ctx, page, device, fz_identity, NULL);
        fz_close_device(ctx, device);
    }
    fz_always(ctx)
    {
        fz_drop_device(ctx, device);
        fz_drop_page(ctx, page);
    }
    fz_catch(ctx)
    {
        fz_drop_display_list(ctx, display_list);
        rethrow_mupdf(env, ctx);
        return 0;
    }
    if (fz_display_list_is_empty(ctx, display_list))
    {
        fz_drop_display_list(ctx, display_list);
        return 0;
    }
    return (jlong)(intptr_t)display_list;
}

static jlongArray load_progressive_display_list(
    JNIEnv *env,
    jlong document_handle,
    jlong stream_handle,
    jint page_index,
    int overlay)
{
    fz_context *ctx = get_context(env);
    fz_document *document = (fz_document *)(intptr_t)document_handle;
    fz_stream *stream = (fz_stream *)(intptr_t)stream_handle;
    progressive_stream_state *state;
    fz_page *page = NULL;
    fz_display_list *display_list = NULL;
    fz_device *device = NULL;
    fz_cookie cookie = {0};
    fz_rect bounds = fz_empty_rect;
    int incomplete = 0;
    uint64_t request_generation;
    jlong values[2];
    jlongArray result;

    if (ctx == NULL || document == NULL || stream == NULL)
        return NULL;
    state = (progressive_stream_state *)stream->state;
    (void)pthread_mutex_lock(&state->mutex);
    request_generation = state->request_generation;
    (void)pthread_mutex_unlock(&state->mutex);
    cookie.progress_max = (size_t)-1;
    fz_var(page);
    fz_var(display_list);
    fz_var(device);
    fz_var(bounds);
    fz_try(ctx)
    {
        page = fz_load_page(ctx, document, page_index);
        bounds = fz_bound_page(ctx, page);
        display_list = fz_new_display_list(ctx, bounds);
        device = fz_new_list_device(ctx, display_list);
        if (overlay)
        {
            fz_run_page_annots(ctx, page, device, fz_identity, &cookie);
            fz_run_page_widgets(ctx, page, device, fz_identity, &cookie);
        }
        else
        {
            fz_run_page_contents(ctx, page, device, fz_identity, &cookie);
        }
        fz_close_device(ctx, device);
    }
    fz_always(ctx)
    {
        fz_drop_device(ctx, device);
        fz_drop_page(ctx, page);
    }
    fz_catch(ctx)
    {
        if (fz_caught(ctx) == FZ_ERROR_TRYLATER && display_list != NULL)
        {
            fz_ignore_error(ctx);
            incomplete = 1;
        }
        else
        {
            fz_drop_display_list(ctx, display_list);
            rethrow_mupdf(env, ctx);
            return NULL;
        }
    }
    if (overlay && display_list != NULL && fz_display_list_is_empty(ctx, display_list))
    {
        fz_drop_display_list(ctx, display_list);
        display_list = NULL;
    }
    values[0] = (jlong)(intptr_t)display_list;
    (void)pthread_mutex_lock(&state->mutex);
    values[1] = incomplete || cookie.incomplete != 0 || request_generation != state->request_generation;
    (void)pthread_mutex_unlock(&state->mutex);
    result = (*env)->NewLongArray(env, 2);
    if (result != NULL)
        (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    if (result == NULL || (*env)->ExceptionCheck(env))
    {
        fz_drop_display_list(ctx, display_list);
        return NULL;
    }
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_loadProgressiveDisplayList(
    JNIEnv *env,
    jobject self,
    jlong document_handle,
    jlong stream_handle,
    jint page_index)
{
    (void)self;
    return load_progressive_display_list(env, document_handle, stream_handle, page_index, 0);
}

JNIEXPORT jlongArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_loadProgressiveOverlayDisplayList(
    JNIEnv *env,
    jobject self,
    jlong document_handle,
    jlong stream_handle,
    jint page_index)
{
    (void)self;
    return load_progressive_display_list(env, document_handle, stream_handle, page_index, 1);
}

JNIEXPORT jfloatArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_displayListBounds(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);
    fz_display_list *display_list = (fz_display_list *)(intptr_t)handle;
    fz_rect bounds = fz_empty_rect;
    jfloat values[4];
    jfloatArray result;

    (void)self;
    if (ctx == NULL || display_list == NULL)
        return NULL;
    fz_var(bounds);
    fz_try(ctx)
        bounds = fz_bound_display_list(ctx, display_list);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    values[0] = bounds.x0;
    values[1] = bounds.y0;
    values[2] = bounds.x1;
    values[3] = bounds.y1;
    result = (*env)->NewFloatArray(env, 4);
    if (result != NULL)
        (*env)->SetFloatArrayRegion(env, result, 0, 4, values);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_dropDisplayList(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);
    (void)self;
    if (ctx != NULL && handle != 0)
        fz_drop_display_list(ctx, (fz_display_list *)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_createCookie(JNIEnv *env, jobject self)
{
    fz_cookie *cookie;
    (void)self;
    cookie = (fz_cookie *)calloc(1, sizeof(fz_cookie));
    if (cookie == NULL)
    {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not create a MuPDF render cookie.");
        return 0;
    }
    cookie->progress_max = (size_t)-1;
    return (jlong)(intptr_t)cookie;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_abortCookie(JNIEnv *env, jobject self, jlong handle)
{
    fz_cookie *cookie = (fz_cookie *)(intptr_t)handle;
    (void)env;
    (void)self;
    if (cookie != NULL)
        cookie->abort = 1;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_dropCookie(JNIEnv *env, jobject self, jlong handle)
{
    (void)env;
    (void)self;
    free((fz_cookie *)(intptr_t)handle);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_renderRgba(
    JNIEnv *env,
    jobject self,
    jlong display_list_handle,
    jfloat scale,
    jint x,
    jint y,
    jint width,
    jint height,
    jlong cookie_handle,
    jboolean transparent)
{
    fz_context *ctx = get_context(env);
    fz_display_list *display_list = (fz_display_list *)(intptr_t)display_list_handle;
    fz_cookie *cookie = (fz_cookie *)(intptr_t)cookie_handle;
    fz_pixmap *pixmap = NULL;
    fz_device *device = NULL;
    fz_matrix transform = fz_scale(scale, scale);
    fz_irect pixel_bounds = {x, y, x + width, y + height};
    jbyteArray result;

    (void)self;
    if (ctx == NULL || display_list == NULL || cookie == NULL)
        return NULL;
    if (scale <= 0 || width <= 0 || height <= 0)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "MuPDF render dimensions must be positive.");
        return NULL;
    }
    fz_var(pixmap);
    fz_var(device);
    fz_var(pixel_bounds);
    fz_var(result);
    fz_try(ctx)
    {
        if (cookie->abort)
            fz_throw(ctx, FZ_ERROR_ABORT, "MuPDF render was cancelled.");
        pixmap = fz_new_pixmap_with_bbox(ctx, fz_device_rgb(ctx), pixel_bounds, NULL, 1);
        if (transparent)
            fz_clear_pixmap(ctx, pixmap);
        else
            fz_clear_pixmap_with_value(ctx, pixmap, 255);
        device = fz_new_draw_device(ctx, fz_identity, pixmap);
        fz_run_display_list(ctx, display_list, device, transform, fz_rect_from_irect(pixel_bounds), cookie);
        fz_close_device(ctx, device);
        if (cookie->abort)
            fz_throw(ctx, FZ_ERROR_ABORT, "MuPDF render was cancelled.");
        result = copy_pixmap_rgba(env, ctx, pixmap);
    }
    fz_always(ctx)
    {
        fz_drop_device(ctx, device);
        fz_drop_pixmap(ctx, pixmap);
    }
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_renderRgbaNative(
    JNIEnv *env,
    jobject self,
    jlong display_list_handle,
    jfloat scale,
    jint x,
    jint y,
    jint width,
    jint height,
    jint crop_x,
    jint crop_y,
    jint crop_width,
    jint crop_height,
    jlong cookie_handle,
    jboolean transparent)
{
    fz_context *ctx = get_context(env);
    fz_display_list *display_list = (fz_display_list *)(intptr_t)display_list_handle;
    fz_cookie *cookie = (fz_cookie *)(intptr_t)cookie_handle;
    fz_pixmap *pixmap = NULL;
    fz_device *device = NULL;
    fz_matrix transform = fz_scale(scale, scale);
    fz_irect pixel_bounds = {x, y, x + width, y + height};
    size_t byte_count;
    unsigned char *buffer = NULL;
    jlong result = 0;

    (void)self;
    if (ctx == NULL || display_list == NULL || cookie == NULL)
        return 0;
    if (scale <= 0 || width <= 0 || height <= 0 ||
        crop_x < x || crop_y < y || crop_width <= 0 || crop_height <= 0 ||
        crop_x > x + width - crop_width || crop_y > y + height - crop_height)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "MuPDF render/crop dimensions must be positive and nested.");
        return 0;
    }
    byte_count = (size_t)crop_width * (size_t)crop_height * 4u;
    fz_var(pixmap);
    fz_var(device);
    fz_var(pixel_bounds);
    fz_var(buffer);
    fz_try(ctx)
    {
        if (cookie->abort)
            fz_throw(ctx, FZ_ERROR_ABORT, "MuPDF render was cancelled.");
        pixmap = fz_new_pixmap_with_bbox(ctx, fz_device_rgb(ctx), pixel_bounds, NULL, 1);
        if (fz_pixmap_components(ctx, pixmap) != 4)
            fz_throw(ctx, FZ_ERROR_FORMAT, "MuPDF returned an unexpected zero-copy pixmap format.");
        if (transparent)
            fz_clear_pixmap(ctx, pixmap);
        else
            fz_clear_pixmap_with_value(ctx, pixmap, 255);
        device = fz_new_draw_device(ctx, fz_identity, pixmap);
        fz_run_display_list(ctx, display_list, device, transform, fz_rect_from_irect(pixel_bounds), cookie);
        fz_close_device(ctx, device);
        if (cookie->abort)
            fz_throw(ctx, FZ_ERROR_ABORT, "MuPDF render was cancelled.");
        buffer = (unsigned char *)malloc(byte_count);
        if (buffer == NULL)
            fz_throw(ctx, FZ_ERROR_SYSTEM, "Could not allocate the zero-copy render buffer.");
        {
            int stride = fz_pixmap_stride(ctx, pixmap);
            unsigned char *samples = fz_pixmap_samples(ctx, pixmap);
            size_t row_bytes = (size_t)crop_width * 4u;
            size_t source_offset =
                ((size_t)(crop_y - y) * (size_t)stride) + ((size_t)(crop_x - x) * 4u);
            size_t row;
            for (row = 0; row < (size_t)crop_height; ++row)
                memcpy(buffer + row * row_bytes, samples + source_offset + row * (size_t)stride, row_bytes);
        }
        result = (jlong)(intptr_t)buffer;
        buffer = NULL;
    }
    fz_always(ctx)
    {
        fz_drop_device(ctx, device);
        fz_drop_pixmap(ctx, pixmap);
    }
    fz_catch(ctx)
    {
        if (buffer != NULL)
            free(buffer);
        rethrow_mupdf(env, ctx);
        return 0;
    }
    return result;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_freeRasterBuffer(JNIEnv *env, jobject self, jlong address)
{
    (void)env;
    (void)self;
    if (address != 0)
        free((void *)(intptr_t)address);
}

JNIEXPORT jboolean JNICALL
Java_dev_xuexue_platform_MuPdfNative_nativeZeroCopyProbe(JNIEnv *env, jobject self)
{
    (void)env;
    (void)self;
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_extractText(JNIEnv *env, jobject self, jlong handle, jbyteArray options_utf8)
{
    fz_context *ctx = get_context(env);
    fz_display_list *display_list = (fz_display_list *)(intptr_t)handle;
    fz_stext_options options;
    fz_buffer *buffer = NULL;
    char *options_text;
    jbyteArray result;

    (void)self;
    if (ctx == NULL || display_list == NULL)
        return NULL;
    options_text = copy_utf8(env, options_utf8);
    if (options_text == NULL)
        return NULL;
    fz_var(buffer);
    fz_try(ctx)
    {
        fz_parse_stext_options(ctx, &options, options_text);
        buffer = fz_new_buffer_from_display_list(ctx, display_list, &options);
    }
    fz_always(ctx)
        free(options_text);
    fz_catch(ctx)
    {
        fz_drop_buffer(ctx, buffer);
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    result = copy_buffer(env, ctx, buffer);
    fz_drop_buffer(ctx, buffer);
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_xuexue_platform_MuPdfNative_createTextPage(JNIEnv *env, jobject self, jlong display_list_handle)
{
    fz_context *ctx = get_context(env);
    fz_display_list *display_list = (fz_display_list *)(intptr_t)display_list_handle;
    fz_stext_page *text_page = NULL;

    (void)self;
    if (ctx == NULL || display_list == NULL)
        return 0;
    fz_try(ctx)
        text_page = fz_new_stext_page_from_display_list(ctx, display_list, NULL);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return 0;
    }
    return (jlong)(intptr_t)text_page;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_dropTextPage(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);

    (void)self;
    if (ctx != NULL && handle != 0)
        fz_drop_stext_page(ctx, (fz_stext_page *)(intptr_t)handle);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_extractStructuredText(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);
    fz_stext_page *text_page = (fz_stext_page *)(intptr_t)handle;
    fz_stext_block *block;
    fz_buffer *buffer = NULL;
    jbyteArray result;
    int block_total = 0;

    (void)self;
    if (ctx == NULL || text_page == NULL)
        return NULL;
    for (block = text_page->first_block; block != NULL; block = block->next)
        ++block_total;
    fz_var(buffer);
    fz_try(ctx)
    {
        fz_append_int32_le(ctx, buffer = fz_new_buffer(ctx, 4096), 0x31545853);
        fz_append_int32_le(ctx, buffer, 2);
        append_float_le(ctx, buffer, text_page->mediabox.x0);
        append_float_le(ctx, buffer, text_page->mediabox.y0);
        append_float_le(ctx, buffer, text_page->mediabox.x1 - text_page->mediabox.x0);
        append_float_le(ctx, buffer, text_page->mediabox.y1 - text_page->mediabox.y0);
        fz_append_int32_le(ctx, buffer, block_total);
        for (block = text_page->first_block; block != NULL; block = block->next)
        {
            fz_stext_line *line = NULL;
            int lines = block->type == FZ_STEXT_BLOCK_TEXT ? line_count(block->u.t.first_line) : 0;
            fz_append_int32_le(ctx, buffer, block->type);
            append_rect_le(ctx, buffer, block->bbox);
            fz_append_int32_le(ctx, buffer, lines);
            if (block->type != FZ_STEXT_BLOCK_TEXT)
                continue;
            for (line = block->u.t.first_line; line != NULL; line = line->next)
            {
                fz_stext_char *character;
                append_rect_le(ctx, buffer, line->bbox);
                fz_append_byte(ctx, buffer, line->wmode != 0);
                fz_append_byte(ctx, buffer, 0);
                fz_append_byte(ctx, buffer, 0);
                fz_append_byte(ctx, buffer, 0);
                fz_append_int32_le(ctx, buffer, char_count(line->first_char));
                for (character = line->first_char; character != NULL; character = character->next)
                {
                    append_rect_le(ctx, buffer, char_bounds(character));
                    fz_append_int32_le(ctx, buffer, character->c);
                    append_float_le(ctx, buffer, character->size);
                    fz_append_int32_le(ctx, buffer, character->flags);
                    fz_append_int32_le(ctx, buffer, (int)character->argb);
                }
            }
        }
    }
    fz_catch(ctx)
    {
        fz_drop_buffer(ctx, buffer);
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    result = copy_buffer(env, ctx, buffer);
    fz_drop_buffer(ctx, buffer);
    return result;
}

static int read_payload_uint32(
    const unsigned char *data,
    size_t length,
    size_t *offset,
    uint32_t *value)
{
    if (*offset > length || length - *offset < 4)
        return 0;
    *value = (uint32_t)data[*offset] |
        ((uint32_t)data[*offset + 1] << 8) |
        ((uint32_t)data[*offset + 2] << 16) |
        ((uint32_t)data[*offset + 3] << 24);
    *offset += 4;
    return 1;
}

static int read_payload_int(
    const unsigned char *data,
    size_t length,
    size_t *offset,
    int *value)
{
    uint32_t raw;
    if (!read_payload_uint32(data, length, offset, &raw))
        return 0;
    *value = (int32_t)raw;
    return 1;
}

static int read_payload_float(
    const unsigned char *data,
    size_t length,
    size_t *offset,
    float *value)
{
    uint32_t bits;
    union
    {
        uint32_t bits;
        float value;
    } decoded;
    if (!read_payload_uint32(data, length, offset, &bits))
        return 0;
    decoded.bits = bits;
    *value = decoded.value;
    return 1;
}

static char *read_payload_string(fz_context *ctx, const unsigned char *data, size_t length, size_t *offset)
{
    int string_length;
    char *result;
    if (!read_payload_int(data, length, offset, &string_length) ||
        string_length < 0 ||
        (size_t)string_length > MAX_OUTLINE_STRING_BYTES ||
        *offset > length ||
        (size_t)string_length > length - *offset)
        fz_throw(ctx, FZ_ERROR_FORMAT, "Invalid PDF outline export string.");
    result = fz_malloc(ctx, (size_t)string_length + 1);
    memcpy(result, data + *offset, (size_t)string_length);
    result[string_length] = '\0';
    *offset += (size_t)string_length;
    return result;
}

static char *outline_target_uri(fz_context *ctx, pdf_document *document, int page, float top)
{
    pdf_page *page_object = NULL;
    char *uri = NULL;
    fz_rect bounds;
    float target_y;

    fz_var(page_object);
    fz_var(uri);
    fz_try(ctx)
    {
        if (isfinite(top) && top >= 0.0f && top <= 1.0f)
        {
            page_object = pdf_load_page(ctx, document, page);
            bounds = pdf_bound_page(ctx, page_object, FZ_CROP_BOX);
            target_y = bounds.y0 + top * (bounds.y1 - bounds.y0);
            uri = fz_asprintf(ctx, "#page=%d&zoom=nan,nan,%g", page + 1, target_y);
        }
        else
        {
            uri = fz_asprintf(ctx, "#page=%d", page + 1);
        }
    }
    fz_always(ctx)
    {
        pdf_drop_page(ctx, page_object);
    }
    fz_catch(ctx)
    {
        fz_free(ctx, uri);
        fz_rethrow(ctx);
    }
    return uri;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_loadOutline(JNIEnv *env, jobject self, jlong handle)
{
    fz_context *ctx = get_context(env);
    fz_document *document = (fz_document *)(intptr_t)handle;
    fz_outline *outline = NULL;
    fz_buffer *buffer = NULL;
    jbyteArray result;
    int count;

    (void)self;
    if (ctx == NULL || document == NULL)
        return NULL;
    fz_var(outline);
    fz_var(buffer);
    fz_try(ctx)
    {
        outline = fz_load_outline(ctx, document);
        count = outline_count(ctx, outline, 0);
        buffer = fz_new_buffer(ctx, 4096);
        fz_append_int32_le(ctx, buffer, 0x314C4F58);
        fz_append_int32_le(ctx, buffer, 1);
        fz_append_int32_le(ctx, buffer, count);
        append_outline_nodes(ctx, buffer, document, outline, 0);
    }
    fz_always(ctx)
    {
        fz_drop_outline(ctx, outline);
    }
    fz_catch(ctx)
    {
        fz_drop_buffer(ctx, buffer);
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    result = copy_buffer(env, ctx, buffer);
    fz_drop_buffer(ctx, buffer);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_xuexue_platform_MuPdfNative_exportOutline(
    JNIEnv *env,
    jobject self,
    jbyteArray source_path_utf8,
    jbyteArray output_path_utf8,
    jbyteArray payload,
    jint strategy)
{
    fz_context *ctx = get_context(env);
    pdf_document *document = NULL;
    fz_outline_iterator *iterator = NULL;
    char *source_path = NULL;
    char *output_path = NULL;
    unsigned char *data = NULL;
    jsize payload_length;
    size_t offset = 0;
    int count;
    int previous_depth = -1;
    int current_depth = -1;
    int index;
    pdf_write_options write_options = pdf_default_write_options;

    (void)self;
    if (ctx == NULL)
        return;
    if (strategy != 0 && strategy != 1)
    {
        throw_java(env, "java/lang/IllegalArgumentException", "Unknown PDF outline export strategy.");
        return;
    }
    source_path = copy_utf8(env, source_path_utf8);
    if (source_path == NULL)
        return;
    output_path = copy_utf8(env, output_path_utf8);
    if (output_path == NULL)
    {
        free(source_path);
        return;
    }
    if (strcmp(source_path, output_path) == 0)
    {
        free(source_path);
        free(output_path);
        throw_java(env, "java/lang/IllegalArgumentException", "PDF outline export must use a new output path.");
        return;
    }
    if (payload == NULL)
    {
        free(source_path);
        free(output_path);
        throw_java(env, "java/lang/NullPointerException", "PDF outline export payload must not be null.");
        return;
    }
    payload_length = (*env)->GetArrayLength(env, payload);
    if (payload_length < 12 || (size_t)payload_length > MAX_OUTLINE_PAYLOAD_BYTES)
    {
        free(source_path);
        free(output_path);
        throw_java(env, "java/lang/IllegalArgumentException", "PDF outline export payload is too short.");
        return;
    }
    data = (unsigned char *)malloc((size_t)payload_length);
    if (data == NULL)
    {
        free(source_path);
        free(output_path);
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate PDF outline export payload.");
        return;
    }
    (*env)->GetByteArrayRegion(env, payload, 0, payload_length, (jbyte *)data);
    if ((*env)->ExceptionCheck(env))
    {
        free(data);
        free(source_path);
        free(output_path);
        return;
    }

    fz_var(document);
    fz_var(iterator);
    fz_try(ctx)
    {
        {
            int magic;
            int version;
            if (!read_payload_int(data, (size_t)payload_length, &offset, &magic) ||
                !read_payload_int(data, (size_t)payload_length, &offset, &version) ||
                magic != 0x31454C58 ||
                version != 1)
                fz_throw(ctx, FZ_ERROR_FORMAT, "Invalid PDF outline export payload.");
        }
        if (!read_payload_int(data, (size_t)payload_length, &offset, &count))
            fz_throw(ctx, FZ_ERROR_FORMAT, "Invalid PDF outline export payload.");
        if (count < 0 || count > MAX_OUTLINE_NODES)
            fz_throw(ctx, FZ_ERROR_LIMIT, "PDF outline export contains too many nodes.");
        document = pdf_open_document(ctx, source_path);
        if (pdf_specifics(ctx, (fz_document *)document) == NULL)
            fz_throw(ctx, FZ_ERROR_ARGUMENT, "Source document is not an editable PDF.");
        if (pdf_needs_password(ctx, document))
            fz_throw(ctx, FZ_ERROR_ARGUMENT, "PDF password authentication is required before Outline export.");
        if (!pdf_has_permission(ctx, document, FZ_PERMISSION_EDIT) ||
            !pdf_has_permission(ctx, document, FZ_PERMISSION_ASSEMBLE))
            fz_throw(ctx, FZ_ERROR_ARGUMENT, "PDF permissions do not allow Outline editing or assembly.");
        if (pdf_was_repaired(ctx, document))
            fz_throw(ctx, FZ_ERROR_FORMAT, "PDF was repaired while opening; refusing Outline export.");
        if (pdf_count_signatures(ctx, document) > 0)
            fz_throw(ctx, FZ_ERROR_ARGUMENT, "PDF contains digital signatures that export would invalidate.");
        iterator = fz_new_outline_iterator(ctx, (fz_document *)document);
        if (strategy == 1)
        {
            while (fz_outline_iterator_item(ctx, iterator) != NULL)
                fz_outline_iterator_delete(ctx, iterator);
        }
        else if (fz_outline_iterator_item(ctx, iterator) != NULL)
        {
            while (fz_outline_iterator_next(ctx, iterator) == 0)
                ;
        }
        for (index = 0; index < count; ++index)
        {
            fz_outline_item item;
            char *title = NULL;
            char *uri = NULL;
            int depth;
            int page;
            float top;
            if (!read_payload_int(data, (size_t)payload_length, &offset, &depth) ||
                !read_payload_int(data, (size_t)payload_length, &offset, &page) ||
                !read_payload_float(data, (size_t)payload_length, &offset, &top))
                fz_throw(ctx, FZ_ERROR_FORMAT, "Invalid PDF outline export payload.");
            (void)top;
            if (depth < 0 || depth > MAX_OUTLINE_DEPTH || (index > 0 && depth > previous_depth + 1))
                fz_throw(ctx, FZ_ERROR_FORMAT, "PDF outline export contains an invalid hierarchy.");
            if (page < 0 || page >= fz_count_pages(ctx, (fz_document *)document))
                fz_throw(ctx, FZ_ERROR_ARGUMENT, "PDF outline export target page is outside the document.");
            title = read_payload_string(ctx, data, (size_t)payload_length, &offset);
            uri = outline_target_uri(ctx, document, page, top);
            item.title = title;
            item.uri = uri;
            item.is_open = 1;
            item.flags = 0;
            item.r = 0;
            item.g = 0;
            item.b = 0;
            if (index == 0)
            {
                current_depth = 0;
            }
            else if (depth == current_depth)
            {
                if (fz_outline_iterator_next(ctx, iterator) < 0)
                    fz_throw(ctx, FZ_ERROR_ARGUMENT, "Could not move to the next PDF outline item.");
            }
            else if (depth == current_depth + 1)
            {
                if (fz_outline_iterator_down(ctx, iterator) < 0)
                    fz_throw(ctx, FZ_ERROR_ARGUMENT, "Could not descend into the PDF outline tree.");
            }
            else
            {
                while (current_depth > depth)
                {
                    if (fz_outline_iterator_up(ctx, iterator) < 0)
                        fz_throw(ctx, FZ_ERROR_ARGUMENT, "Could not ascend the PDF outline tree.");
                    --current_depth;
                }
                if (fz_outline_iterator_next(ctx, iterator) < 0)
                    fz_throw(ctx, FZ_ERROR_ARGUMENT, "Could not move to a PDF outline sibling.");
            }
            fz_outline_iterator_insert(ctx, iterator, &item);
            fz_free(ctx, title);
            fz_free(ctx, uri);
            if (fz_outline_iterator_prev(ctx, iterator) < 0)
                fz_throw(ctx, FZ_ERROR_ARGUMENT, "Could not normalize the PDF outline iterator.");
            previous_depth = depth;
            current_depth = depth;
        }
        if (offset != (size_t)payload_length)
            fz_throw(ctx, FZ_ERROR_FORMAT, "PDF outline export payload contains trailing bytes.");
        write_options.do_incremental = pdf_can_be_saved_incrementally(ctx, document);
        pdf_save_document(ctx, document, output_path, &write_options);
    }
    fz_always(ctx)
    {
        fz_drop_outline_iterator(ctx, iterator);
        pdf_drop_document(ctx, document);
        free(data);
        free(source_path);
        free(output_path);
    }
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_copySelection(
    JNIEnv *env,
    jobject self,
    jlong text_page_handle,
    jfloatArray selection_points)
{
    fz_context *ctx = get_context(env);
    fz_stext_page *text_page = (fz_stext_page *)(intptr_t)text_page_handle;
    fz_point start;
    fz_point end;
    char *selection = NULL;
    jbyteArray result;

    (void)self;
    if (ctx == NULL || text_page == NULL || !read_selection_points(env, selection_points, &start, &end))
        return NULL;
    fz_var(selection);
    fz_try(ctx)
        selection = fz_copy_selection(ctx, text_page, start, end, 0);
    fz_catch(ctx)
    {
        fz_free(ctx, selection);
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    result = copy_c_string(env, selection);
    fz_free(ctx, selection);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_highlightSelection(
    JNIEnv *env,
    jobject self,
    jlong text_page_handle,
    jfloatArray selection_points)
{
    fz_context *ctx = get_context(env);
    fz_stext_page *text_page = (fz_stext_page *)(intptr_t)text_page_handle;
    fz_point start;
    fz_point end;
    fz_quad hits[2048];
    jfloat coordinates[2048 * 8];
    jfloatArray result;
    int count = 0;
    int index;

    (void)self;
    if (ctx == NULL || text_page == NULL || !read_selection_points(env, selection_points, &start, &end))
        return NULL;
    fz_try(ctx)
        count = fz_highlight_selection(ctx, text_page, start, end, hits, 2048);
    fz_catch(ctx)
    {
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    for (index = 0; index < count; ++index)
    {
        coordinates[index * 8] = hits[index].ul.x;
        coordinates[index * 8 + 1] = hits[index].ul.y;
        coordinates[index * 8 + 2] = hits[index].ur.x;
        coordinates[index * 8 + 3] = hits[index].ur.y;
        coordinates[index * 8 + 4] = hits[index].lr.x;
        coordinates[index * 8 + 5] = hits[index].lr.y;
        coordinates[index * 8 + 6] = hits[index].ll.x;
        coordinates[index * 8 + 7] = hits[index].ll.y;
    }
    result = (*env)->NewFloatArray(env, count * 8);
    if (result != NULL && count > 0)
        (*env)->SetFloatArrayRegion(env, result, 0, count * 8, coordinates);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_loadLinks(JNIEnv *env, jobject self, jlong handle, jint page_index)
{
    fz_context *ctx = get_context(env);
    fz_document *document = (fz_document *)(intptr_t)handle;
    fz_page *page = NULL;
    fz_link *links = NULL;
    fz_link *link;
    fz_buffer *buffer = NULL;
    fz_rect bounds;
    int count = 0;
    jbyteArray result;

    (void)self;
    if (ctx == NULL || document == NULL)
        return NULL;
    fz_var(page);
    fz_var(links);
    fz_var(buffer);
    fz_try(ctx)
    {
        page = fz_load_page(ctx, document, page_index);
        bounds = fz_bound_page(ctx, page);
        links = fz_load_links(ctx, page);
        for (link = links; link != NULL; link = link->next)
            ++count;
        buffer = fz_new_buffer(ctx, (size_t)count * 64 + 4);
        fz_append_int32_le(ctx, buffer, count);
        for (link = links; link != NULL; link = link->next)
        {
            const char *uri = link->uri == NULL ? "" : link->uri;
            int external = fz_is_external_link(ctx, uri);
            int target_page = -1;
            size_t uri_length = strlen(uri);
            if (!external && uri_length > 0)
            {
                float target_x;
                float target_y;
                fz_location location = fz_resolve_link(ctx, document, uri, &target_x, &target_y);
                target_page = fz_page_number_from_location(ctx, document, location);
            }
            if (uri_length > (size_t)INT32_MAX)
                fz_throw(ctx, FZ_ERROR_LIMIT, "PDF link URI is too long.");
            append_float_le(ctx, buffer, normalized_coordinate(link->rect.x0, bounds.x0, bounds.x1 - bounds.x0));
            append_float_le(ctx, buffer, normalized_coordinate(link->rect.y0, bounds.y0, bounds.y1 - bounds.y0));
            append_float_le(ctx, buffer, normalized_coordinate(link->rect.x1, bounds.x0, bounds.x1 - bounds.x0));
            append_float_le(ctx, buffer, normalized_coordinate(link->rect.y1, bounds.y0, bounds.y1 - bounds.y0));
            fz_append_int32_le(ctx, buffer, external);
            fz_append_int32_le(ctx, buffer, target_page);
            fz_append_int32_le(ctx, buffer, (int)uri_length);
            fz_append_data(ctx, buffer, uri, uri_length);
        }
    }
    fz_always(ctx)
    {
        fz_drop_link(ctx, links);
        fz_drop_page(ctx, page);
    }
    fz_catch(ctx)
    {
        fz_drop_buffer(ctx, buffer);
        rethrow_mupdf(env, ctx);
        return NULL;
    }
    result = copy_buffer(env, ctx, buffer);
    fz_drop_buffer(ctx, buffer);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_xuexue_platform_MuPdfNative_search(JNIEnv *env, jobject self, jlong handle, jbyteArray needle_utf8)
{
    fz_context *ctx = get_context(env);
    fz_display_list *display_list = (fz_display_list *)(intptr_t)handle;
    fz_stext_page *text = NULL;
    fz_search *search = NULL;
    char *needle;
    int count = 0;
    int capacity = 0;
    jfloatArray result = NULL;
    jfloat *rectangles = NULL;

    (void)self;
    if (ctx == NULL || display_list == NULL)
        return NULL;
    needle = copy_utf8(env, needle_utf8);
    if (needle == NULL)
        return NULL;
    fz_var(text);
    fz_var(search);
    fz_var(rectangles);
    fz_var(count);
    fz_var(capacity);
    fz_try(ctx)
    {
        fz_search_result search_result;
        text = fz_new_stext_page_from_display_list(ctx, display_list, NULL);
        search = fz_new_search(ctx);
        fz_search_set_options(ctx, search, FZ_SEARCH_IGNORE_CASE, needle);
        fz_feed_search(ctx, search, fz_keep_stext_page(ctx, text), 0);
        do
        {
            search_result = fz_search_forwards(ctx, search);
            if (search_result.reason == FZ_SEARCH_MORE_INPUT)
            {
                fz_feed_search(ctx, search, NULL, search_result.u.more_input.seq_needed);
            }
            else if (search_result.reason == FZ_SEARCH_MATCH)
            {
                fz_search_result_details *details = search_result.u.match.result;
                int index;
                int required = count + details->num_quads;
                if (required > capacity)
                {
                    capacity = required + required / 2 + 16;
                    rectangles = fz_realloc_array(ctx, rectangles, (size_t)capacity * 4, float);
                }
                for (index = 0; index < details->num_quads; ++index)
                {
                    fz_rect bounds = fz_rect_from_quad(details->quads[index].quad);
                    rectangles[count * 4] = bounds.x0;
                    rectangles[count * 4 + 1] = bounds.y0;
                    rectangles[count * 4 + 2] = bounds.x1;
                    rectangles[count * 4 + 3] = bounds.y1;
                    ++count;
                }
            }
        }
        while (search_result.reason != FZ_SEARCH_COMPLETE);
    }
    fz_always(ctx)
    {
        fz_drop_search(ctx, search);
        fz_drop_stext_page(ctx, text);
        free(needle);
    }
    fz_catch(ctx)
    {
        fz_free(ctx, rectangles);
        rethrow_mupdf(env, ctx);
        return NULL;
    }

    result = (*env)->NewFloatArray(env, count * 4);
    if (result != NULL && count > 0)
        (*env)->SetFloatArrayRegion(env, result, 0, count * 4, rectangles);
    fz_free(ctx, rectangles);
    return result;
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    return XUEXUE_JNI_VERSION;
}

JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM *vm, void *reserved)
{
    int i;

    (void)vm;
    (void)reserved;
    (void)pthread_mutex_lock(&init_mutex);
    if (base_context != NULL)
    {
        fz_drop_context(base_context);
        base_context = NULL;
    }
    if (context_key_ready)
    {
        (void)pthread_key_delete(context_key);
        context_key_ready = 0;
    }
    if (runtime_ready)
    {
        for (i = 0; i < FZ_LOCK_MAX; ++i)
            (void)pthread_mutex_destroy(&mupdf_mutexes[i]);
        runtime_ready = 0;
    }
    (void)pthread_mutex_unlock(&init_mutex);
}
