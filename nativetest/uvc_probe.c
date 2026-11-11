// Proof-of-concept: cattura video UVC + PTZ simultanei via libuvc, su Android (root, /dev/bus/usb).
// Verifica la fattibilita' della soluzione PRIMA di portarla nell'APK.
// Apre la prima camera UVC (testato su 045e:8888), streamma YUYV, conta i frame, e A META' STREAM invia pan/tilt/zoom:
// se i frame continuano ad arrivare DOPO i comandi PTZ -> cattura + controllo coesistono.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "libuvc/libuvc.h"

static volatile int frame_count = 0;
static volatile int first_w = 0, first_h = 0;
static int saved = 0;

static void cb(uvc_frame_t *frame, void *ptr) {
    frame_count++;
    first_w = frame->width; first_h = frame->height;
    if (!saved && frame->data_bytes > 0) {
        FILE *f = fopen("/data/local/tmp/uvc_frame.yuyv", "wb");
        if (f) { fwrite(frame->data, 1, frame->data_bytes, f); fclose(f); saved = 1; }
    }
}

int main(int argc, char **argv) {
    int W = argc > 1 ? atoi(argv[1]) : 640;
    int H = argc > 2 ? atoi(argv[2]) : 480;
    int FPS = argc > 3 ? atoi(argv[3]) : 30;

    uvc_context_t *ctx; uvc_device_t *dev; uvc_device_handle_t *devh;
    uvc_stream_ctrl_t ctrl;
    uvc_error_t r;

    r = uvc_init(&ctx, NULL);
    if (r < 0) { printf("RESULT init FAIL %s\n", uvc_strerror(r)); return 1; }
    printf("init OK\n");

    r = uvc_find_device(ctx, &dev, 0, 0, NULL);
    if (r < 0) { printf("RESULT find FAIL %s\n", uvc_strerror(r)); uvc_exit(ctx); return 1; }
    printf("find OK\n");

    r = uvc_open(dev, &devh);
    if (r < 0) { printf("RESULT open FAIL %s\n", uvc_strerror(r)); uvc_unref_device(dev); uvc_exit(ctx); return 1; }
    printf("open OK (kernel driver detach gestito da libusb)\n");

    r = uvc_get_stream_ctrl_format_size(devh, &ctrl, UVC_FRAME_FORMAT_YUYV, W, H, FPS);
    if (r < 0) { printf("RESULT negotiate FAIL %dx%d %s\n", W, H, uvc_strerror(r)); goto done; }
    printf("negotiate OK %dx%d@%d\n", W, H, FPS);

    r = uvc_start_streaming(devh, &ctrl, cb, NULL, 0);
    if (r < 0) { printf("RESULT stream-start FAIL %s\n", uvc_strerror(r)); goto done; }
    printf("stream START OK\n");

    // test di tenuta: conta i frame ogni secondo per 8s (root-only, nessun'altra interferenza)
    int prev = 0;
    for (int s = 1; s <= 8; s++) {
        sleep(1);
        int c = frame_count;
        printf("t%d: %d frame (+%d) %dx%d\n", s, c, c - prev, first_w, first_h);
        fflush(stdout);
        prev = c;
    }
    int before = frame_count;

    // --- PTZ A META' STREAM ---
    uvc_error_t rp, rt, rz;
    rz = uvc_set_zoom_abs(devh, 200);
    rp = uvc_set_pantilt_abs(devh, 18000, 0);   // pan +5 gradi (arcsec)
    printf("PTZ during stream: zoom=%s pan/tilt=%s\n",
           uvc_strerror(rz), uvc_strerror(rp));
    rt = uvc_set_pantilt_abs(devh, 0, 18000);   // tilt +5 gradi
    printf("PTZ tilt=%s\n", uvc_strerror(rt));
    rp = uvc_set_pantilt_abs(devh, 0, 0);       // home
    printf("PTZ home=%s\n", uvc_strerror(rp));

    sleep(2);
    int after = frame_count;
    printf("frames dopo PTZ (+2s) = %d\n", after);

    uvc_stop_streaming(devh);
    printf("RESULT %s capture=%d->%d frames ptz_ok=%d save=%d\n",
           (after > before && rp == UVC_SUCCESS) ? "PASS" : "PARTIAL",
           before, after, (rp==0&&rt==0&&rz==0), saved);

done:
    uvc_close(devh);
    uvc_unref_device(dev);
    uvc_exit(ctx);
    return 0;
}
