{
  description = "Appletizer: Apple's iOS Nuance voices as an Android text-to-speech engine";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # r27c: its libc++ uses the std::__ndk1 namespace macho2elf rewrites to.
      ndkVersion = "27.2.12479018";
      buildToolsVersion = "35.0.0";
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeNDK = true;
        ndkVersions = [ ndkVersion ];
        cmakeVersions = [ "3.22.1" ];
        includeEmulator = false;
        includeSystemImages = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";

      # A headless x86_64 Android for testing without a phone: Google APIs
      # images translate arm64 apps, which is all the engine is.
      emulatorPackages = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
      };
      emulatorSdk = "${emulatorPackages.androidsdk}/libexec/android-sdk";
      # Android 17 on a 16 KB-page kernel, as some phones now ship; its own
      # shell, since the image is a 2 GB download the 4 KB tests never need.
      emulator16kPackages = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.0" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis_ps16k" ];
        abiVersions = [ "x86_64" ];
      };
      emulator16kSdk = "${emulator16kPackages.androidsdk}/libexec/android-sdk";

      python = pkgs.python3.withPackages (ps: [ ps.lief ]);
      cross = pkgs.pkgsCross.aarch64-multiplatform;
      # The iOS 10 engine needs a C++ runtime on the Linux test bench; the
      # native aarch64 build comes from the binary cache, unlike a cross one.
      aarch64LibCxx = nixpkgs.legacyPackages.aarch64-linux.llvmPackages.libcxx;

      # dynarmic runs the 32-bit iOS 5/6 engine. Its cross builds want the
      # dependencies it bundles, which nixpkgs' copy of its source leaves out,
      # so they are put back from nixpkgs' own pinned sources.
      dynarmicSrc = pkgs.runCommand "dynarmic-src-${pkgs.dynarmic.version}" { } ''
        cp -r --no-preserve=mode ${pkgs.dynarmic.src} $out
        rm -rf $out/externals/fmt $out/externals/mcl $out/externals/oaknut $out/externals/robin-map
        cp -r --no-preserve=mode ${pkgs.fmt.src} $out/externals/fmt
        cp -r --no-preserve=mode ${pkgs.mcl-cpp-utility-lib.src} $out/externals/mcl
        cp -r --no-preserve=mode ${pkgs.oaknut.src} $out/externals/oaknut
        cp -r --no-preserve=mode ${pkgs.robin-map.src} $out/externals/robin-map
      '';
    in
    {
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = [
            python
            android.androidsdk
            pkgs.jdk17
            pkgs.gradle
            pkgs.android-tools
            cross.stdenv.cc
            pkgs.qemu-user
            pkgs.llvmPackages.llvm
            pkgs.whisper-cpp
            pkgs._7zz
            pkgs.xpwn
            pkgs.lzfse
          ];

          # android-tools brings its own Python; name the one with LIEF outright.
          M2E_PYTHON = "${python}/bin/python3";
          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          ANDROID_NDK_ROOT = "${sdk}/ndk/${ndkVersion}";
          JAVA_HOME = pkgs.jdk17.home;
          # AGP otherwise fetches an aapt2 from Maven that cannot run on NixOS.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
          # Cross glibc's loader and libraries, for qemu-aarch64 -L.
          AARCH64_SYSROOT = "${cross.stdenv.cc.libc}";
          AARCH64_LIBCXX = "${aarch64LibCxx}/lib";
          # APFS images from iOS 10.3 on keep many files LZFSE-compressed.
          LIBLZFSE = "${pkgs.lzfse}/lib/liblzfse.so";
          DYNARMIC_SRC = "${dynarmicSrc}";
          BOOST_INCLUDE = "${pkgs.boost.dev}/include";
          # The host build of dynarmic, for testing the iOS 5/6 bridge here.
          HOST_DYNARMIC = "${pkgs.dynarmic}";
        };

        emulator = pkgs.mkShell {
          packages = [ emulatorPackages.androidsdk pkgs.android-tools ];
          ANDROID_HOME = emulatorSdk;
          ANDROID_SDK_ROOT = emulatorSdk;
        };

        emulator16k = pkgs.mkShell {
          packages = [ emulator16kPackages.androidsdk pkgs.android-tools ];
          ANDROID_HOME = emulator16kSdk;
          ANDROID_SDK_ROOT = emulator16kSdk;
        };
      };
    };
}
