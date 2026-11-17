use std::{env, path::PathBuf};

fn main() {
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos")
        && env::var_os("CARGO_FEATURE_MACOS_HARDWARE").is_some()
    {
        println!("cargo:rerun-if-changed=hardware/libNotifyHardwareSigner.dylib");
        let directory = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("hardware");
        println!("cargo:rustc-link-search=native={}", directory.display());
        println!("cargo:rustc-link-lib=dylib=NotifyHardwareSigner");
        println!("cargo:rustc-link-arg=-Wl,-rpath,@executable_path");
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", directory.display());
    }
}
