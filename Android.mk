# Copyright (C) 2016-2017 Projekt Substratum
#
# Modified/reimplemented for use by SlimRoms :
#
# Copyright (C) 2017 SlimRoms Project
# Copyright (C) 2017 Victor Lapin
# Copyright (C) 2017 Griffin Millender
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

LOCAL_PATH := $(call my-dir)
LOCAL_ASSETS_TEMP_PATH := $(call intermediates-dir-for,APPS,OmsBackend,,COMMON)/assets

# DEVICE AAPT
ifneq ($(SDK_ONLY),true)
include $(CLEAR_VARS)
LOCAL_MODULE := aapt
LOCAL_CFLAGS := -DAAPT_VERSION=\"$(BULD_NUMBER_FROM_FILE)\"
LOCAL_CFLAGS += -Wall -Werror
AAPT_PATH := ../../../frameworks/base/tools/aapt
LOCAL_SRC_FILES := $(AAPT_PATH)/Main.cpp \
    $(AAPT_PATH)/AaptAssets.cpp \
    $(AAPT_PATH)/AaptConfig.cpp \
    $(AAPT_PATH)/AaptUtil.cpp \
    $(AAPT_PATH)/AaptXml.cpp \
    $(AAPT_PATH)/ApkBuilder.cpp \
    $(AAPT_PATH)/Command.cpp \
    $(AAPT_PATH)/CrunchCache.cpp \
    $(AAPT_PATH)/FileFinder.cpp \
    $(AAPT_PATH)/Images.cpp \
    $(AAPT_PATH)/Package.cpp \
    $(AAPT_PATH)/pseudolocalize.cpp \
    $(AAPT_PATH)/Resource.cpp \
    $(AAPT_PATH)/ResourceFilter.cpp \
    $(AAPT_PATH)/ResourceIdCache.cpp \
    $(AAPT_PATH)/ResourceTable.cpp \
    $(AAPT_PATH)/SourcePos.cpp \
    $(AAPT_PATH)/StringPool.cpp \
    $(AAPT_PATH)/WorkQueue.cpp \
    $(AAPT_PATH)/XMLNode.cpp \
    $(AAPT_PATH)/ZipEntry.cpp \
    $(AAPT_PATH)/ZipFile.cpp
LOCAL_C_INCLUDES += bionic
LOCAL_SHARED_LIBRARIES := \
    libandroidfw \
    libpng \
    libutils \
    liblog \
    libcutils \
    libexpat \
    libbase \
    libz
LOCAL_STATIC_LIBRARIES := libexpat_static
LOCAL_MODULE_PATH := $(LOCAL_ASSETS_TEMP_PATH)
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-v7-appcompat \
    android-support-v4 \
    theme-core \
    apache-commons-io \
    zipsigner \
    zipio \
    kellinwood-logging-android \
    kellinwood-logging-lib \
    kellinwood-logging-log4j \
    gson

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/res \
    frameworks/support/v7/appcompat/res \
    frameworks/theme-core/res

LOCAL_REQUIRED_MODULES := aapt

LOCAL_INIT_RC := slim.omsbackend.rc

LOCAL_ASSET_DIR := $(LOCAL_ASSETS_TEMP_PATH)
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_PACKAGE_NAME := OmsBackend
LOCAL_AAPT_FLAGS := --auto-add-overlay \
    --extra-packages android.support.v7.appcompat:com.slimroms.themecore

include $(BUILD_PACKAGE)
