LOCAL_PATH:= $(call my-dir)

# OpenSSL 1.1.1w libcrypto built for android-arm with everything moonlight-common-c does not use
# disabled (no EC/DSA/DH, no TLS, no legacy ciphers or digests, no engines, no error strings): the
# stream only needs AES-128 GCM/CBC through EVP and RAND_bytes. Configure line and rebuild notes
# are in openssl/BUILD.md.
include $(CLEAR_VARS)
LOCAL_MODULE:= libcrypto
LOCAL_SRC_FILES:= $(TARGET_ARCH_ABI)/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_STATIC_LIBRARY)
