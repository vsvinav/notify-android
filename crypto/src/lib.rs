use jni::{
    objects::{GlobalRef, JByteArray, JObject, JValue},
    sys::{jbyteArray, jint, jlong},
    JNIEnv, JavaVM,
};
use notify_channel::{ExternalSigner, Peer};
use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Mutex, OnceLock},
};

const MAX_PLAINTEXT: usize = 1_048_576;
const MAX_FRAME: usize = MAX_PLAINTEXT + 16_384;
const MAX_CHANNELS: usize = 32;
const KEY_PACKAGE: jint = 1;
const CREATE: jint = 2;
const ADD: jint = 3;
const TREE: jint = 4;
const JOIN: jint = 5;
const AUTHORIZE: jint = 6;
const PROTECT: jint = 7;
const UNPROTECT: jint = 8;

#[derive(Default)]
struct Registry {
    next: jlong,
    peers: HashMap<jlong, Entry>,
}

struct Entry {
    peer: Peer,
    key_package_created: bool,
}

impl Registry {
    fn insert(&mut self, peer: Peer) -> Result<jlong, String> {
        if self.peers.len() >= MAX_CHANNELS {
            return Err("Too many open channels".into());
        }
        self.next = self
            .next
            .checked_add(1)
            .ok_or("Channel handle space exhausted")?;
        self.peers.insert(
            self.next,
            Entry {
                peer,
                key_package_created: false,
            },
        );
        Ok(self.next)
    }

    fn get(&mut self, handle: jlong) -> Result<&mut Entry, String> {
        self.peers
            .get_mut(&handle)
            .ok_or("Channel is closed".into())
    }

    fn remove(&mut self, handle: jlong) {
        self.peers.remove(&handle);
    }
}

fn registry() -> &'static Mutex<Registry> {
    static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

fn label(bytes: &[u8]) -> Result<&str, String> {
    if bytes.is_empty() || bytes.len() > 256 {
        return Err("Invalid label".into());
    }
    std::str::from_utf8(bytes).map_err(|_| "Invalid UTF-8 label".into())
}

fn call(peer: &mut Peer, operation: jint, a: &[u8], b: &[u8], c: &[u8]) -> Result<Vec<u8>, String> {
    if [a, b, c].iter().any(|bytes| bytes.len() > MAX_FRAME) {
        return Err("Input too large".into());
    }
    let result = match operation {
        KEY_PACKAGE => peer.key_package(),
        CREATE => peer.create(label(a)?).map(|_| vec![]),
        ADD => peer.add_pinned(a, b),
        TREE => peer.tree(),
        JOIN => peer.join_pinned(a, b, c).map(|_| vec![]),
        AUTHORIZE => peer.sign_authorization(a),
        PROTECT => peer.send(a),
        UNPROTECT => {
            if b.is_empty() || b.len() > 256 {
                return Err("Invalid expected sender".into());
            }
            let message = peer.receive(a)?;
            if message.sender() != b {
                return Err("Authenticated sender does not match".into());
            }
            Ok(message.bytes())
        }
        _ => Err("Unknown channel operation".into()),
    }?;
    if result.len() > MAX_FRAME {
        return Err("Output too large".into());
    }
    Ok(result)
}

struct JavaSigner {
    vm: JavaVM,
    object: GlobalRef,
}

impl ExternalSigner for JavaSigner {
    fn sign(&self, payload: &[u8]) -> Result<Vec<u8>, String> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| "Cannot attach signer thread")?;
        env.with_local_frame(8, |env| -> jni::errors::Result<Vec<u8>> {
            let input = env.byte_array_from_slice(payload)?;
            let output = env
                .call_method(
                    self.object.as_obj(),
                    "sign",
                    "([B)[B",
                    &[JValue::Object(input.as_ref())],
                )?
                .l()?;
            let output = JByteArray::from(output);
            if output.is_null() || env.get_array_length(&output)? > 72 {
                return Err(jni::errors::Error::NullPtr("Invalid signing result"));
            }
            env.convert_byte_array(output)
        })
        .map_err(|_| "Android signing failed".into())
    }
}

fn read_bytes(env: &mut JNIEnv, array: &JByteArray, maximum: usize) -> Result<Vec<u8>, String> {
    if array.is_null() {
        return Err("Null byte array".into());
    }
    let length = env
        .get_array_length(array)
        .map_err(|_| "Cannot read array length")?;
    if length < 0 || length as usize > maximum {
        return Err("Input too large".into());
    }
    env.convert_byte_array(array)
        .map_err(|_| "Cannot read byte array".into())
}

// Every export contains panics and leaves pending Java exceptions intact.
fn boundary<T: Default>(
    env: &mut JNIEnv,
    operation: impl FnOnce(&mut JNIEnv) -> Result<T, String>,
) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| operation(env)));
    match result {
        Ok(Ok(value)) => value,
        error => {
            let message = match error {
                Ok(Err(message)) => message,
                _ => "Native channel failed".into(),
            };
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", message);
            }
            T::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_notify_android_crypto_NativeBridge_open(
    mut env: JNIEnv,
    _this: JObject,
    signer: JObject,
    name: JByteArray,
    public: JByteArray,
) -> jlong {
    boundary(&mut env, |env| {
        if signer.is_null() {
            return Err("Missing Android signer".into());
        }
        let name = read_bytes(env, &name, 256)?;
        let public = read_bytes(env, &public, 65)?;
        let signer = JavaSigner {
            vm: env.get_java_vm().map_err(|_| "Cannot retain Java VM")?,
            object: env
                .new_global_ref(signer)
                .map_err(|_| "Cannot retain Android identity")?,
        };
        let peer = Peer::external(label(&name)?, &public, Box::new(signer))?;
        registry()
            .lock()
            .map_err(|_| "Channel registry unavailable")?
            .insert(peer)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_notify_android_crypto_NativeBridge_call(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    operation: jint,
    a: JByteArray,
    b: JByteArray,
    c: JByteArray,
) -> jbyteArray {
    boundary(&mut env, |env| {
        let a = read_bytes(env, &a, MAX_FRAME)?;
        let b = read_bytes(env, &b, MAX_FRAME)?;
        let c = read_bytes(env, &c, MAX_FRAME)?;
        let mut registry = registry()
            .lock()
            .map_err(|_| "Channel registry unavailable")?;
        let entry = registry.get(handle)?;
        if operation == KEY_PACKAGE {
            if entry.key_package_created {
                return Err("Only one key package per channel".into());
            }
            entry.key_package_created = true;
        }
        let output = call(&mut entry.peer, operation, &a, &b, &c)?;
        env.byte_array_from_slice(&output)
            .map(|array| array.into_raw())
            .map_err(|_| "Cannot allocate channel output".into())
    })
}

#[no_mangle]
pub extern "system" fn Java_app_notify_android_crypto_NativeBridge_close(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    boundary(&mut env, |_| {
        // Removing the owned Peer drops its JNI global reference on this attached thread.
        registry()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(handle);
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use notify_channel::ExternalSigner;
    use openmls_basic_credential::SignatureKeyPair;
    use openmls_traits::{signatures::Signer, types::SignatureScheme};

    struct SigningCallback(SignatureKeyPair);
    impl ExternalSigner for SigningCallback {
        fn sign(&self, payload: &[u8]) -> Result<Vec<u8>, String> {
            self.0.sign(payload).map_err(|_| "sign failed".into())
        }
    }

    fn peer(label: &str) -> Peer {
        let key = SignatureKeyPair::new(SignatureScheme::ECDSA_SECP256R1_SHA256).unwrap();
        Peer::external(
            label,
            &key.public().to_vec(),
            Box::new(SigningCallback(key)),
        )
        .unwrap()
    }

    #[test]
    fn handles_close_idempotently_and_never_alias_replacements() {
        let mut registry = Registry::default();
        let first = registry.insert(peer("one")).unwrap();
        registry.remove(first);
        registry.remove(first);
        let second = registry.insert(peer("two")).unwrap();
        assert_ne!(first, second);
        assert!(registry.get(first).is_err());
        assert!(registry.get(0).is_err());
        assert!(registry.get(-1).is_err());
        assert!(registry.get(second).is_ok());
    }

    #[test]
    fn adapter_roundtrip_enforces_exact_authenticated_sender() {
        let mut alice = peer("mac");
        let mut bob = peer("android");
        call(&mut alice, CREATE, b"group", &[], &[]).unwrap();
        let welcome = call(
            &mut alice,
            ADD,
            &call(&mut bob, KEY_PACKAGE, &[], &[], &[]).unwrap(),
            &bob.identity_key(),
            &[],
        )
        .unwrap();
        call(
            &mut bob,
            JOIN,
            &welcome,
            &alice.tree().unwrap(),
            &alice.identity_key(),
        )
        .unwrap();
        let encrypted = call(&mut alice, PROTECT, b"private", &[], &[]).unwrap();
        assert!(call(&mut bob, UNPROTECT, &encrypted, b"Mac", &[]).is_err());
        let encrypted = call(&mut alice, PROTECT, b"next", &[], &[]).unwrap();
        assert_eq!(
            call(&mut bob, UNPROTECT, &encrypted, b"mac", &[]).unwrap(),
            b"next"
        );
        assert!(call(&mut bob, UNPROTECT, &encrypted, b"mac", &[]).is_err());
        assert!(call(&mut bob, 999, &[], &[], &[]).is_err());
        assert!(call(&mut bob, JOIN, b"bad", b"bad", &[4; 65]).is_err());
        assert!(call(&mut bob, PROTECT, &vec![0; MAX_PLAINTEXT + 1], &[], &[]).is_err());
    }

    #[test]
    fn channel_registry_is_bounded() {
        let mut registry = Registry::default();
        for _ in 0..MAX_CHANNELS {
            registry.insert(peer("phone")).unwrap();
        }
        assert!(registry.insert(peer("phone")).is_err());
    }
}
