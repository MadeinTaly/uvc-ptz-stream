cmake_minimum_required(VERSION 3.18)
project(uvccam)

# Sorgenti libusb + libuvc clonati nella root del repo (D:\Dev\tvbox\{libusb,libuvc}).
# Ricreabili con git clone se mancano.
set(REPO ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
set(LIBUSB ${REPO}/libusb)
set(LIBUVC ${REPO}/libuvc)

# ---- libusb (backend linux usbfs, no udev) ----
add_library(usb STATIC
    ${LIBUSB}/libusb/core.c
    ${LIBUSB}/libusb/descriptor.c
    ${LIBUSB}/libusb/hotplug.c
    ${LIBUSB}/libusb/io.c
    ${LIBUSB}/libusb/strerror.c
    ${LIBUSB}/libusb/sync.c
    ${LIBUSB}/libusb/os/linux_usbfs.c
    ${LIBUSB}/libusb/os/linux_netlink.c
    ${LIBUSB}/libusb/os/events_posix.c
    ${LIBUSB}/libusb/os/threads_posix.c
)
target_compile_definitions(usb PRIVATE HAVE_CONFIG_H)
target_include_directories(usb PUBLIC ${LIBUSB}/android ${LIBUSB}/libusb)

# ---- libuvc (senza JPEG: la cam fa solo YUYV) ----
add_library(uvc STATIC
    ${LIBUVC}/src/ctrl.c
    ${LIBUVC}/src/ctrl-gen.c
    ${LIBUVC}/src/device.c
    ${LIBUVC}/src/diag.c
    ${LIBUVC}/src/frame.c
    ${LIBUVC}/src/init.c
    ${LIBUVC}/src/misc.c
    ${LIBUVC}/src/stream.c
)
target_include_directories(uvc PUBLIC ${LIBUVC}/include ${LIBUSB}/libusb)
target_link_libraries(uvc usb)

# ---- bridge JNI ----
add_library(uvccam SHARED uvccam.cpp)
target_include_directories(uvccam PRIVATE ${LIBUVC}/include ${LIBUSB}/libusb)
find_library(log-lib log)
target_link_libraries(uvccam uvc usb ${log-lib} m)
