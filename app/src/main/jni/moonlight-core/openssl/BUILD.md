# Rebuilding libcrypto.a

The TCL build ships a 32-bit (armeabi-v7a) static OpenSSL 1.1.1w libcrypto trimmed to what
moonlight-common-c calls in PlatformCrypto.c: AES-128 GCM and CBC through EVP, and RAND_bytes.
libssl is not linked at all (HTTPS lives in Java/OkHttp).

    export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/27.3.13750724
    export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH
    tar xzf openssl-1.1.1w.tar.gz && cd openssl-1.1.1w
    perl ./Configure android-arm -D__ANDROID_API__=34 no-shared no-tests \
      no-ssl no-tls no-dtls no-ssl3 no-comp no-hw no-engine no-dynamic-engine no-static-engine \
      no-async no-dso no-err no-filenames no-ui-console no-autoload-config \
      no-ec no-ec2m no-ecdh no-ecdsa no-dsa no-dh \
      no-aria no-bf no-blake2 no-camellia no-cast no-chacha no-poly1305 no-cms no-ct no-des \
      no-gost no-idea no-md2 no-md4 no-mdc2 no-ocb no-ocsp no-rc2 no-rc4 no-rc5 no-rmd160 \
      no-scrypt no-seed no-siphash no-sm2 no-sm3 no-sm4 no-srp no-srtp no-ts no-whirlpool \
      no-psk no-nextprotoneg no-rfc3779 no-cmac no-egd no-afalgeng no-capieng no-devcryptoeng \
      no-zlib no-sock no-dgram \
      -ffunction-sections -fdata-sections -fvisibility=hidden
    make -j8 build_libs
    cp libcrypto.a <repo>/app/src/main/jni/moonlight-core/openssl/armeabi-v7a/
    cp include/openssl/*.h <repo>/app/src/main/jni/moonlight-core/openssl/include/openssl/

The headers must come from the same configure run: opensslconf.h records which algorithms exist.
ARMv8 AES and PMULL (GHASH) assembly stays in and is picked at runtime, so AES-GCM on the C8K's
Cortex cores still uses the crypto extensions.
