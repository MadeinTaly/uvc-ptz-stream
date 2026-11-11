// Bridge JNI verso libuvc: apre la camera UVC dal fd di UsbManager (uvc_wrap),
// streamma YUYV, tiene l'ultimo frame, ed espone pan/tilt/zoom sullo stesso handle.
// Provato sul box: cattura + PTZ coesistono (vedi nativetest/uvc_probe.c).
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <errno.h>
#include "libusb.h"            // PRIMA di libuvc.h: definisce LIBUSB_API_VERSION (abilita uvc_wrap)
#include "libuvc/libuvc.h"
#include "libuvc/libuvc_internal.h"  // per uvc_context.usb_ctx (pump eventi libusb)

#define TAG "HwStreamJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static uvc_context_t      *g_ctx  = nullptr;
static uvc_device_handle_t *g_devh = nullptr;
static uvc_stream_ctrl_t   g_ctrl;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static uint8_t *g_buf = nullptr;     // ultimo frame YUYV
static size_t   g_buf_cap = 0;
static size_t   g_buf_len = 0;
static int      g_w = 0, g_h = 0;
static volatile long g_seq = 0;
static volatile long g_frames = 0;

// Soppressione frame durante un control transfer PTZ: il SET su iface0 contende col
// bulk video -> il frame in volo si strappa ("quarti"). Marchiamo una finestra dopo
// ogni SET (pan/tilt/zoom/focus/ctrl) in cui frame_cb scarta i frame: durante il
// movimento si vede l'ultimo frame pulito, niente garbage.
static volatile int64_t g_ptz_until_ms = 0;
#define PTZ_BLANK_MS 50
static int64_t now_ms() {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static inline void mark_ptz_busy() { g_ptz_until_ms = now_ms() + PTZ_BLANK_MS; }

// Serializza TUTTI i control transfer di movimento (zoom/pantilt) sullo stesso
// handle libusb: due in parallelo (thread diversi) si corrompono.
//
// FUOCO: nessun codice. La camera ha un AUTOFOCUS di fabbrica completo; qualunque
// SET UVC sul fuoco (FOCUS_REL/ABS/AUTO) lo butta in manuale e lo rompe. Il software
// non deve MAI toccarlo.
static pthread_mutex_t g_ctl_lock = PTHREAD_MUTEX_INITIALIZER;

// ---- Luma SOFTWARE (+ AE) ----
// Il firmware ignora ogni controllo esposizione via USB (verificato su 045e:8888):
// unico modo per scurire/schiarire e' il post-processing del canale Y in pipeline,
// PRIMA di JPEG/encoder (agisce su MJPEG, snapshot, WS video e REC). LUT 256 byte
// ricostruita a ogni set; a valori neutri g_luma_on=false e il frame passa intatto.
// AE software: quando g_ae_on, frame_cb misura periodicamente la luma media in uscita
// e pilota da solo il gain della LUT verso g_ae_target (loop chiuso, smorzato).
static volatile bool g_luma_on = false;
static uint8_t g_luma_lut[256];
static volatile bool g_ae_on = false;
static volatile int  g_ae_target = 115;      // luma media desiderata (40..220)
static double g_ae_gain100 = 100.0;          // gain corrente pilotato dall'AE (10..300)
static volatile int  g_luma_gamma100 = 100;  // gamma utente (vale anche in AE)

// Costruisce la LUT: out = 255 * (y/255)^gamma * gain. Chiamare sotto g_lock
// (frame_cb la legge mentre applica il frame).
static void luma_build_lut(int gain100, int gamma100) {
    double gain = gain100 / 100.0, gamma = gamma100 / 100.0;
    for (int i = 0; i < 256; i++) {
        double v = pow(i / 255.0, gamma) * gain * 255.0 + 0.5;
        g_luma_lut[i] = v < 0 ? 0 : (v > 255 ? 255 : (uint8_t) v);
    }
    g_luma_on = (gain100 != 100 || gamma100 != 100);
}

// Pump degli eventi libusb: senza, i transfer async completati non vengono raccolti
// e lo streaming si ferma dopo ~LIBUVC_NUM_TRANSFER_BUFS frame. Thread dedicato.
static pthread_t g_evthread;
static volatile bool g_pump = false;
static void *ev_loop(void *) {
    struct timeval tv = {0, 100000}; // 100ms
    while (g_pump && g_ctx) {
        libusb_handle_events_timeout_completed(g_ctx->usb_ctx, &tv, nullptr);
    }
    return nullptr;
}

static void frame_cb(uvc_frame_t *frame, void *ptr) {
    if (!frame || frame->data_bytes == 0) return;
    // Scarta i frame catturati nella finestra di un comando PTZ: sono quelli strappati
    // dalla contesa col control transfer. Tiene l'ultimo frame pulito durante il movimento.
    if (now_ms() < g_ptz_until_ms) return;
    // Scarta anche i frame YUYV troncati (parziali -> blocchi di garbage).
    size_t expected = (size_t) frame->width * frame->height * 2; // YUY2 = 2 byte/pixel
    if (expected && frame->data_bytes < expected) return;
    pthread_mutex_lock(&g_lock);
    if (frame->data_bytes > g_buf_cap) {
        free(g_buf);
        g_buf = (uint8_t *) malloc(frame->data_bytes);
        g_buf_cap = g_buf ? frame->data_bytes : 0;
    }
    if (g_buf) {
        memcpy(g_buf, frame->data, frame->data_bytes);
        // luma software: YUY2 = Y0 U Y1 V -> Y sui byte pari (croma intatta)
        if (g_luma_on)
            for (size_t i = 0; i < frame->data_bytes; i += 2) g_buf[i] = g_luma_lut[g_buf[i]];
        // AE software: ogni ~15 frame misura la luma media IN USCITA (campione 1/512,
        // solo byte Y: offset pari) e corregge il gain verso il target con passo
        // proporzionale smorzato (niente pompaggio). Deadband +-5 di luma.
        if (g_ae_on && g_frames % 15 == 0 && frame->data_bytes >= 4096) {
            long sum = 0; int n = 0;
            for (size_t i = 0; i < frame->data_bytes; i += 512) { sum += g_buf[i]; n++; }
            int err = g_ae_target - (int) (sum / n);
            if (err > 5 || err < -5) {
                g_ae_gain100 *= 1.0 + (err / 255.0) * 0.35;
                if (g_ae_gain100 < 10) g_ae_gain100 = 10;
                if (g_ae_gain100 > 300) g_ae_gain100 = 300;
                luma_build_lut((int) (g_ae_gain100 + 0.5), g_luma_gamma100);
            }
        }
        g_buf_len = frame->data_bytes;
        g_w = frame->width; g_h = frame->height;
        g_seq++; g_frames++;
    }
    pthread_mutex_unlock(&g_lock);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeOpen(JNIEnv *env, jobject, jint fd) {
    if (g_devh) return 0; // gia' aperto
    // Android senza root: niente scansione di /dev/bus/usb (negata), si usa solo il fd di UsbManager
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    uvc_error_t r = uvc_init(&g_ctx, nullptr);
    if (r < 0) { LOGE("uvc_init %s", uvc_strerror(r)); return (jint) r; }
    r = uvc_wrap((int) fd, g_ctx, &g_devh);
    if (r < 0) { LOGE("uvc_wrap %s", uvc_strerror(r)); uvc_exit(g_ctx); g_ctx = nullptr; return (jint) r; }
    // Claim esplicito dell'interfaccia 0 (VideoControl): senza, i control transfer (PROBE/PTZ)
    // su iface 0 fanno "usbfs: did not claim interface 0" -> il kernel resetta il device -> stream morto.
    libusb_device_handle *h = uvc_get_libusb_handle(g_devh);
    if (h) {
        libusb_set_auto_detach_kernel_driver(h, 1);
        int ci = libusb_claim_interface(h, 0);
        LOGI("claim VideoControl iface0 -> %d", ci);
    }
    LOGI("uvc_wrap OK fd=%d", fd);
    return 0;
}

static int g_lastW = 0, g_lastH = 0, g_lastFps = 0;

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeStart(JNIEnv *env, jobject, jint w, jint h, jint fps) {
    if (!g_devh) return -1;
    uvc_error_t r = uvc_get_stream_ctrl_format_size(g_devh, &g_ctrl, UVC_FRAME_FORMAT_YUYV, w, h, fps);
    if (r < 0) { LOGE("negotiate %dx%d %s", w, h, uvc_strerror(r)); return (jint) r; }
    g_frames = 0; g_seq = 0;
    r = uvc_start_streaming(g_devh, &g_ctrl, frame_cb, nullptr, 0);
    if (r < 0) { LOGE("start_streaming %s", uvc_strerror(r)); return (jint) r; }
    g_lastW = w; g_lastH = h; g_lastFps = fps;
    // avvia il pump degli eventi libusb (indispensabile per uno streaming continuo)
    g_pump = true;
    pthread_create(&g_evthread, nullptr, ev_loop, nullptr);
    LOGI("streaming START %dx%d@%d (event pump on)", w, h, fps);
    return 0;
}

// Reset USB della camera (replug logico): recovery del link USB quando lo stream
// si pianta. libusb_reset_device fa un bus reset -> il firmware riparte pulito
// (autofocus di fabbrica incluso). Poi riavvia lo stream.
// Ritorna 0 ok; <0 se l'handle e' invalidato (il chiamante deve fare un reopen completo).
JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeReset(JNIEnv *, jobject) {
    if (!g_devh) return -1;
    bool wasStreaming = g_pump;
    int w = g_lastW, h = g_lastH, fps = g_lastFps;
    if (g_pump) { g_pump = false; pthread_join(g_evthread, nullptr); }
    if (wasStreaming) uvc_stop_streaming(g_devh);
    libusb_device_handle *hd = uvc_get_libusb_handle(g_devh);
    int r = hd ? libusb_reset_device(hd) : LIBUSB_ERROR_NO_DEVICE;
    LOGI("libusb_reset_device -> %d", r);
    if (r == LIBUSB_ERROR_NOT_FOUND || r == LIBUSB_ERROR_NO_DEVICE) {
        // device ri-enumerato come nuovo: handle morto, serve reopen lato Kotlin
        return (jint) r;
    }
    if (hd) { libusb_set_auto_detach_kernel_driver(hd, 1); libusb_claim_interface(hd, 0); }
    if (wasStreaming && w > 0) {
        usleep(300000); // lascia ripartire il firmware
        uvc_error_t e = uvc_get_stream_ctrl_format_size(g_devh, &g_ctrl, UVC_FRAME_FORMAT_YUYV, w, h, fps);
        if (e >= 0) {
            g_frames = 0; g_seq = 0;
            e = uvc_start_streaming(g_devh, &g_ctrl, frame_cb, nullptr, 0);
            if (e >= 0) { g_pump = true; pthread_create(&g_evthread, nullptr, ev_loop, nullptr); }
            else { LOGE("restart after reset: %s", uvc_strerror(e)); return (jint) e; }
        }
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_h96_hwstream_UvcNative_nativeStop(JNIEnv *, jobject) {
    if (g_devh) uvc_stop_streaming(g_devh); // cancella i transfer (gli eventi vengono ancora pompati)
    if (g_pump) { g_pump = false; pthread_join(g_evthread, nullptr); }
}

JNIEXPORT void JNICALL
Java_com_h96_hwstream_UvcNative_nativeClose(JNIEnv *, jobject) {
    // FERMA il thread event-pump PRIMA di chiudere l'handle, altrimenti ev_loop chiama
    // libusb_handle_events su un ctx liberato -> SIGSEGV (crash su afReset/re-enum).
    if (g_pump) { g_pump = false; pthread_join(g_evthread, nullptr); }
    if (g_devh) { uvc_stop_streaming(g_devh); uvc_close(g_devh); g_devh = nullptr; }
    if (g_ctx)  { uvc_exit(g_ctx); g_ctx = nullptr; }
    pthread_mutex_lock(&g_lock);
    free(g_buf); g_buf = nullptr; g_buf_cap = g_buf_len = 0;
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeSetPanTilt(JNIEnv *, jobject, jint pan, jint tilt) {
    if (!g_devh) return -1;
    mark_ptz_busy();
    pthread_mutex_lock(&g_ctl_lock);
    uvc_error_t r = uvc_set_pantilt_abs(g_devh, (int32_t) pan, (int32_t) tilt);
    pthread_mutex_unlock(&g_ctl_lock);
    mark_ptz_busy();
    if (r < 0) LOGE("set_pantilt(%d,%d) -> %s (%d)", pan, tilt, uvc_strerror(r), r);
    return (jint) r;
}

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeSetZoom(JNIEnv *, jobject, jint zoom) {
    if (!g_devh) return -1;
    mark_ptz_busy();
    pthread_mutex_lock(&g_ctl_lock);
    jint r = (jint) uvc_set_zoom_abs(g_devh, (uint16_t) zoom);
    pthread_mutex_unlock(&g_ctl_lock);
    mark_ptz_busy();
    return r;
}

// Luminosita'/gamma SOFTWARE sul canale Y, in percento; 100/100 = neutro (pass-through).
// Con ae=true il gain diventa AUTOMATICO (loop AE in frame_cb) e lo slider luminosita'
// fa da TARGET di luma media: target = 115 * gain100/100, clampato 40..220.
JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeSetLuma(JNIEnv *, jobject, jint gain100, jint gamma100, jboolean ae) {
    pthread_mutex_lock(&g_lock);
    g_luma_gamma100 = gamma100;
    if (ae) {
        int t = (int) (115.0 * gain100 / 100.0 + 0.5);
        g_ae_target = t < 40 ? 40 : (t > 220 ? 220 : t);
        if (!g_ae_on) { g_ae_on = true; g_ae_gain100 = gain100; }  // seed alla prima attivazione
        luma_build_lut((int) (g_ae_gain100 + 0.5), gamma100);
    } else {
        g_ae_on = false;
        luma_build_lut(gain100, gamma100);
    }
    pthread_mutex_unlock(&g_lock);
    LOGI("luma sw: gain=%d%% gamma=%d%% ae=%d target=%d", gain100, gamma100, (int) ae, g_ae_target);
    return 0;
}

// ===== DIAGNOSTICA: probe raw dei selettori UVC su un'unita' =====
// Interroga via control transfer ogni selettore (1..0x16) di un'unita' (Camera Terminal=1,
// Processing Unit=2) con GET_INFO/LEN/CUR/MIN/MAX/DEF. Serve a scoprire quali focus/AF
// la cam espone davvero e con che capabilities (GET_INFO bit0=GET,1=SET,2=disabled,3=auto-update).
// UVC: GET bmRequestType=0xA1, wValue=selector<<8, wIndex=unit<<8|iface(0).
#define UVC_GET_INFO_REQ 0x86
#define UVC_GET_LEN_REQ  0x85
#define UVC_GET_CUR_REQ  0x81
#define UVC_GET_MIN_REQ  0x82
#define UVC_GET_MAX_REQ  0x83
#define UVC_GET_DEF_REQ  0x87
#define UVC_SET_CUR_REQ  0x01

static int raw_get(libusb_device_handle *h, uint8_t unit, uint8_t sel, uint8_t req,
                   uint8_t *buf, int len) {
    return libusb_control_transfer(h, 0xA1, req, (uint16_t)(sel << 8),
                                   (uint16_t)((unit << 8) | 0), buf, (uint16_t)len, 300);
}

JNIEXPORT jstring JNICALL
Java_com_h96_hwstream_UvcNative_nativeProbe(JNIEnv *env, jobject, jint unit) {
    if (!g_devh) return env->NewStringUTF("device not open");
    libusb_device_handle *h = uvc_get_libusb_handle(g_devh);
    if (!h) return env->NewStringUTF("no libusb handle");
    char out[4096]; int p = 0;
    p += snprintf(out + p, sizeof(out) - p, "probe unit=%d (iface0)\n", unit);
    // nomi selettori Camera Terminal (UVC)
    const char *ctName[0x17] = {0};
    ctName[0x01]="SCANNING_MODE"; ctName[0x02]="AE_MODE"; ctName[0x03]="AE_PRIORITY";
    ctName[0x04]="EXPOSURE_ABS"; ctName[0x05]="EXPOSURE_REL"; ctName[0x06]="FOCUS_ABS";
    ctName[0x07]="FOCUS_REL"; ctName[0x08]="FOCUS_AUTO"; ctName[0x09]="IRIS_ABS";
    ctName[0x0a]="IRIS_REL"; ctName[0x0b]="ZOOM_ABS"; ctName[0x0c]="ZOOM_REL";
    ctName[0x0d]="PANTILT_ABS"; ctName[0x0e]="PANTILT_REL"; ctName[0x0f]="ROLL_ABS";
    ctName[0x10]="ROLL_REL"; ctName[0x11]="?"; ctName[0x12]="PRIVACY";
    ctName[0x13]="FOCUS_SIMPLE"; ctName[0x14]="WINDOW"; ctName[0x15]="ROI"; ctName[0x16]="?";
    uint8_t b[64];
    for (uint8_t sel = 1; sel <= 0x16; sel++) {
        int info = raw_get(h, (uint8_t)unit, sel, UVC_GET_INFO_REQ, b, 1);
        if (info < 0) continue; // selettore non risponde -> non esposto
        uint8_t caps = (info >= 1) ? b[0] : 0;
        int len = raw_get(h, (uint8_t)unit, sel, UVC_GET_LEN_REQ, b, 2);
        int wlen = (len >= 1) ? b[0] : 0;
        long cur = 0, mn = 0, mx = 0, df = 0; bool hc=false,hm=false,hx=false,hd=false;
        if (wlen > 0 && wlen <= 4) {
            uint8_t t[4];
            if (raw_get(h,(uint8_t)unit,sel,UVC_GET_CUR_REQ,t,wlen)>=0){hc=true; for(int i=0;i<wlen;i++) cur|=((long)t[i])<<(8*i);}
            if (raw_get(h,(uint8_t)unit,sel,UVC_GET_MIN_REQ,t,wlen)>=0){hm=true; mn=0; for(int i=0;i<wlen;i++) mn|=((long)t[i])<<(8*i);}
            if (raw_get(h,(uint8_t)unit,sel,UVC_GET_MAX_REQ,t,wlen)>=0){hx=true; mx=0; for(int i=0;i<wlen;i++) mx|=((long)t[i])<<(8*i);}
            if (raw_get(h,(uint8_t)unit,sel,UVC_GET_DEF_REQ,t,wlen)>=0){hd=true; df=0; for(int i=0;i<wlen;i++) df|=((long)t[i])<<(8*i);}
        }
        const char *nm = (unit==1 && sel<0x17 && ctName[sel]) ? ctName[sel] : "PU/sel";
        p += snprintf(out+p, sizeof(out)-p,
            "sel 0x%02x %-13s info=0x%02x[%s%s%s] len=%d cur=%s%ld min=%ld max=%ld def=%s%ld\n",
            sel, nm, caps,
            (caps&1)?"GET":"", (caps&2)?",SET":"", (caps&4)?",DIS":"",
            wlen, hc?"":"(n/a)", cur, mn, mx, hd?"":"(n/a)", df);
        if (p > (int)sizeof(out)-200) break;
    }
    LOGI("%s", out);
    return env->NewStringUTF(out);
}

// SET_CUR raw arbitrario su (unit, selector) con payload dato. Per esperimenti
// OSD/VISCA-over-UVC sulla Extension Unit (unit 3). Ritorna rc libusb (>=0 = byte scritti).
JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeRawSet(JNIEnv *env, jobject, jint unit, jint sel, jbyteArray data) {
    if (!g_devh) return -1000;
    libusb_device_handle *h = uvc_get_libusb_handle(g_devh);
    if (!h) return -1001;
    jsize n = env->GetArrayLength(data);
    if (n < 0 || n > 64) return -1002;
    uint8_t buf[64];
    env->GetByteArrayRegion(data, 0, n, (jbyte *) buf);
    int r = libusb_control_transfer(h, 0x21, UVC_SET_CUR_REQ, (uint16_t)(sel << 8),
                                    (uint16_t)((unit << 8) | 0), buf, (uint16_t) n, 500);
    LOGI("RAW SET unit=%d sel=0x%02x len=%d -> %d", unit, sel, (int) n, r);
    return (jint) r;
}

// GET_CUR raw arbitrario: ritorna "rc:HEX" (rc libusb + payload esadecimale se rc>0).
JNIEXPORT jstring JNICALL
Java_com_h96_hwstream_UvcNative_nativeRawGet(JNIEnv *env, jobject, jint unit, jint sel, jint len) {
    char out[256];
    if (!g_devh) return env->NewStringUTF("-1000:");
    libusb_device_handle *h = uvc_get_libusb_handle(g_devh);
    if (!h) return env->NewStringUTF("-1001:");
    int n = len; if (n < 1) n = 1; if (n > 64) n = 64;
    uint8_t buf[64];
    int r = raw_get(h, (uint8_t) unit, (uint8_t) sel, UVC_GET_CUR_REQ, buf, n);
    int p = snprintf(out, sizeof(out), "%d:", r);
    for (int i = 0; i < r && i < n; i++) p += snprintf(out + p, sizeof(out) - p, "%02x", buf[i]);
    return env->NewStringUTF(out);
}

// ===== Controlli generici (PU image controls) via libuvc =====
// ID: 1 brightness,2 contrast,3 hue,4 saturation,5 sharpness,6 gamma,7 wb_temp,8 wb_auto,
//     11 gain,12 backlight,13 powerline,14 zoom
// (9 focus_abs e 10 focus_auto RIMOSSI: il fuoco e' autofocus di fabbrica, il software
//  non deve mai toccarlo — un SET lo manda in manuale e lo rompe.)
// req: 0 CUR,1 MIN,2 MAX,3 DEF.  Get ritorna INT_MIN (0x80000000) se non supportato.
#define CTRL_UNSUPPORTED ((jint)0x80000000)

static enum uvc_req_code req_of(int req) {
    switch (req) { case 1: return UVC_GET_MIN; case 2: return UVC_GET_MAX;
                   case 3: return UVC_GET_DEF; default: return UVC_GET_CUR; }
}

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeGetCtrl(JNIEnv *, jobject, jint id, jint req) {
    if (!g_devh) return CTRL_UNSUPPORTED;
    enum uvc_req_code rc = req_of(req);
    uvc_error_t e; int16_t s16; uint16_t u16; uint8_t u8; int32_t pan, tilt;
    switch (id) {
        case 1:  e = uvc_get_brightness(g_devh, &s16, rc); return e<0?CTRL_UNSUPPORTED:(jint)s16;
        case 2:  e = uvc_get_contrast(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        case 3:  e = uvc_get_hue(g_devh, &s16, rc); return e<0?CTRL_UNSUPPORTED:(jint)s16;
        case 4:  e = uvc_get_saturation(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        case 5:  e = uvc_get_sharpness(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        case 6:  e = uvc_get_gamma(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        case 7:  e = uvc_get_white_balance_temperature(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        case 8:  e = uvc_get_white_balance_temperature_auto(g_devh, &u8, rc); return e<0?CTRL_UNSUPPORTED:(jint)u8;
        case 11: e = uvc_get_gain(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        case 12: e = uvc_get_backlight_compensation(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        case 13: e = uvc_get_power_line_frequency(g_devh, &u8, rc); return e<0?CTRL_UNSUPPORTED:(jint)u8;
        case 14: e = uvc_get_zoom_abs(g_devh, &u16, rc); return e<0?CTRL_UNSUPPORTED:(jint)u16;
        default: return CTRL_UNSUPPORTED;
    }
}

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeSetCtrl(JNIEnv *, jobject, jint id, jint v) {
    if (!g_devh) return -1;
    mark_ptz_busy();
    uvc_error_t e;
    switch (id) {
        case 1:  e = uvc_set_brightness(g_devh, (int16_t)v); break;
        case 2:  e = uvc_set_contrast(g_devh, (uint16_t)v); break;
        case 3:  e = uvc_set_hue(g_devh, (int16_t)v); break;
        case 4:  e = uvc_set_saturation(g_devh, (uint16_t)v); break;
        case 5:  e = uvc_set_sharpness(g_devh, (uint16_t)v); break;
        case 6:  e = uvc_set_gamma(g_devh, (uint16_t)v); break;
        case 7:  e = uvc_set_white_balance_temperature(g_devh, (uint16_t)v); break;
        case 8:  e = uvc_set_white_balance_temperature_auto(g_devh, (uint8_t)v); break;
        case 11: e = uvc_set_gain(g_devh, (uint16_t)v); break;
        case 12: e = uvc_set_backlight_compensation(g_devh, (uint16_t)v); break;
        case 13: e = uvc_set_power_line_frequency(g_devh, (uint8_t)v); break;
        case 14: e = uvc_set_zoom_abs(g_devh, (uint16_t)v); break;
        default: return -1;
    }
    mark_ptz_busy();
    if (e < 0) LOGE("set_ctrl(%d,%d) -> %s", id, v, uvc_strerror(e));
    return (jint) e;
}

// Copia l'ultimo frame YUYV in 'out'. Ritorna i byte copiati, 0 se non c'e', -1 se 'out' piccolo.
JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeGetFrame(JNIEnv *env, jobject, jbyteArray out) {
    pthread_mutex_lock(&g_lock);
    jint res;
    if (g_buf_len == 0 || !g_buf) { res = 0; }
    else if ((size_t) env->GetArrayLength(out) < g_buf_len) { res = -1; }
    else {
        env->SetByteArrayRegion(out, 0, (jsize) g_buf_len, (const jbyte *) g_buf);
        res = (jint) g_buf_len;
    }
    pthread_mutex_unlock(&g_lock);
    return res;
}

// Converte l'ultimo frame YUYV (4:2:2) in NV12 (4:2:0, Y plane + UV interleaved) dentro 'out',
// formato richiesto dall'encoder HW (COLOR_FormatYUV420SemiPlanar). out >= w*h*3/2.
// Ritorna i byte scritti, 0 se nessun frame, -1 se 'out' piccolo. Chroma sub-campionata
// verticalmente prendendo le righe pari (4:2:2->4:2:0).
JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeGetFrameNV12(JNIEnv *env, jobject, jbyteArray out) {
    pthread_mutex_lock(&g_lock);
    if (g_buf_len == 0 || !g_buf || g_w <= 0 || g_h <= 0) { pthread_mutex_unlock(&g_lock); return 0; }
    int w = g_w, h = g_h;
    size_t ysz = (size_t) w * h, need = ysz * 3 / 2;
    if ((size_t) env->GetArrayLength(out) < need) { pthread_mutex_unlock(&g_lock); return -1; }
    jbyte *dst = env->GetByteArrayElements(out, nullptr);
    if (!dst) { pthread_mutex_unlock(&g_lock); return -1; }
    const uint8_t *src = g_buf;            // YUYV: Y0 U Y1 V per 2 pixel, stride = w*2
    uint8_t *dy = (uint8_t *) dst;
    for (int y = 0; y < h; y++) {
        const uint8_t *r = src + (size_t) y * w * 2;
        for (int x = 0; x < w; x++) dy[(size_t) y * w + x] = r[x * 2];
    }
    uint8_t *duv = (uint8_t *) dst + ysz;
    for (int y = 0; y < h; y += 2) {
        const uint8_t *r = src + (size_t) y * w * 2;
        for (int x = 0; x < w; x += 2) { int i = x * 2; *duv++ = r[i + 1]; *duv++ = r[i + 3]; }
    }
    env->ReleaseByteArrayElements(out, dst, 0);
    pthread_mutex_unlock(&g_lock);
    return (jint) need;
}

JNIEXPORT jlong JNICALL
Java_com_h96_hwstream_UvcNative_nativeSeq(JNIEnv *, jobject) { return (jlong) g_seq; }

JNIEXPORT jlong JNICALL
Java_com_h96_hwstream_UvcNative_nativeFrames(JNIEnv *, jobject) { return (jlong) g_frames; }

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeWidth(JNIEnv *, jobject) { return (jint) g_w; }

JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeHeight(JNIEnv *, jobject) { return (jint) g_h; }

// === VFD pannello frontale: ioctl raw su /dev/digital_dis ===
// Reverse-eng da libled-jni.so: ioctl = _IOW('T', nr, int) (la vendor usava 0x4004540{5,7,9}).
// led_dev_init faceva 3 ioctl (nr 7,5,9) con valori byte = config (brightness/mode/enable).
// Probe live per scoprire l'encoding del display. value passato come int (low byte = dato).
// Ritorna: 0 ok, -1=open fallito (errno in -errno*..), altrimenti -errno dell'ioctl.
JNIEXPORT jint JNICALL
Java_com_h96_hwstream_UvcNative_nativeVfdIoctl(JNIEnv *, jobject, jint nr, jint value) {
    int fd = open("/dev/digital_dis", O_RDWR);
    if (fd < 0) return -1000 - errno;
    unsigned int req = (1u << 30) | (4u << 16) | ((unsigned)'T' << 8) | ((unsigned)nr & 0xff);
    int v = value;
    int rc = ioctl(fd, req, &v);
    int e = errno;
    close(fd);
    return rc == 0 ? 0 : -e;
}

} // extern "C"
