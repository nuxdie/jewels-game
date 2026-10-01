TOP_PATH := $(call my-dir)

# libopenmpt is a git submodule (pinned to a release tag). Its NDK makefile lives in
# build/android_ndk/ but expects to sit at the source root, so point my-dir there while it loads.
# The release tarballs carry the upstream revision; a git checkout doesn't, so give it here (0.8.9 = r25652).
MPT_SVNVERSION := 25652
MPT_SVNURL := https://source.openmpt.org/svn/openmpt/tags/libopenmpt-0.8.9
my-dir = $(TOP_PATH)/libopenmpt
include $(TOP_PATH)/libopenmpt/build/android_ndk/Android.mk
my-dir = $(call parent-dir,$(lastword $(MAKEFILE_LIST)))

LOCAL_PATH := $(TOP_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := jewelsaudio
LOCAL_SRC_FILES := jewelsaudio.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libopenmpt
LOCAL_SHARED_LIBRARIES := openmpt
include $(BUILD_SHARED_LIBRARY)
