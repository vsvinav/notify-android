use openmls_traits::{
    signatures::{Signer, SignerError},
    types::SignatureScheme,
};

unsafe extern "C" {
    fn notify_hw_create(public_key: *mut u8, capacity: i32) -> u64;
    fn notify_hw_sign(
        handle: u64,
        input: *const u8,
        size: i32,
        output: *mut u8,
        capacity: i32,
    ) -> i32;
    fn notify_hw_destroy(handle: u64);
    fn notify_hw_persist(id: *const u8, count: i32, public: *mut u8, capacity: i32) -> i32;
    fn notify_hw_load(id: *const u8, count: i32, public: *const u8, length: i32) -> u64;
    fn notify_hw_delete(id: *const u8, count: i32) -> i32;
}

pub(crate) struct HardwareSigner {
    handle: u64,
    public: [u8; 65],
}

impl HardwareSigner {
    pub(crate) fn create_persisted(id: &str) -> Result<Vec<u8>, String> {
        if id.len() != 36 {
            return Err("Invalid identity ID".into());
        }
        let mut public = [0; 65];
        // Fixed buffers live through the synchronous call; no private key crosses the ABI.
        if unsafe { notify_hw_persist(id.as_ptr(), 36, public.as_mut_ptr(), 65) } != 0 {
            return Err("Cannot create persisted identity".into());
        }
        Ok(public.to_vec())
    }

    pub(crate) fn load(id: &str, public: &[u8]) -> Result<Self, String> {
        let public: [u8; 65] = public.try_into().map_err(|_| "Invalid pinned public key")?;
        if id.len() != 36 {
            return Err("Invalid identity ID".into());
        }
        let handle = unsafe { notify_hw_load(id.as_ptr(), 36, public.as_ptr(), 65) };
        if handle == 0 {
            return Err("Cannot load pinned hardware identity; no replacement was created".into());
        }
        Ok(Self { handle, public })
    }

    pub(crate) fn delete_persisted(id: &str) -> Result<(), String> {
        if id.len() != 36 {
            return Err("Invalid identity ID".into());
        }
        if unsafe { notify_hw_delete(id.as_ptr(), 36) } != 0 {
            return Err("Cannot delete persisted identity".into());
        }
        Ok(())
    }

    pub(crate) fn new() -> Result<Self, String> {
        let mut public = [0; 65];
        // The bridge writes at most the supplied fixed buffer size.
        let handle = unsafe { notify_hw_create(public.as_mut_ptr(), 65) };
        if handle == 0 {
            return Err("Hardware identity unavailable; software fallback is prohibited".into());
        }
        Ok(Self { handle, public })
    }

    pub(crate) fn public(&self) -> &[u8] {
        &self.public
    }
}

impl Signer for HardwareSigner {
    fn signature_scheme(&self) -> SignatureScheme {
        SignatureScheme::ECDSA_SECP256R1_SHA256
    }

    fn sign(&self, payload: &[u8]) -> Result<Vec<u8>, SignerError> {
        if payload.len() > crate::LIMIT {
            return Err(SignerError::SigningError);
        }
        let size = i32::try_from(payload.len()).map_err(|_| SignerError::SigningError)?;
        let mut signature = [0; 72];
        // Both buffers remain live for the synchronous call; Swift retains neither.
        let length = unsafe {
            notify_hw_sign(
                self.handle,
                payload.as_ptr(),
                size,
                signature.as_mut_ptr(),
                72,
            )
        };
        let length = usize::try_from(length).map_err(|_| SignerError::SigningError)?;
        if !(1..=signature.len()).contains(&length) {
            return Err(SignerError::SigningError);
        }
        Ok(signature[..length].to_vec())
    }
}

impl Drop for HardwareSigner {
    fn drop(&mut self) {
        // This Rust value owns the non-cloneable handle; destruction is idempotent.
        unsafe { notify_hw_destroy(self.handle) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Peer, PeerSigner};

    #[test]
    fn missing_hardware_identity_cannot_fall_back_to_software() {
        let signer = HardwareSigner::new().unwrap();
        unsafe { notify_hw_destroy(signer.handle) };
        assert_eq!(signer.sign(b"synthetic"), Err(SignerError::SigningError));
        let mut peer = Peer::from_signer("failed-hardware", PeerSigner::Hardware(signer)).unwrap();
        assert!(peer.create("must-not-create").is_err());
    }

    #[test]
    fn released_handles_do_not_exhaust_the_hardware_registry() {
        for _ in 0..40 {
            let signer = HardwareSigner::new().unwrap();
            assert!(signer.sign(b"synthetic lifetime check").is_ok());
        }
    }

    #[test]
    fn oversized_signing_requests_are_rejected() {
        let signer = HardwareSigner::new().unwrap();
        assert_eq!(
            signer.sign(&vec![0; crate::LIMIT + 1]),
            Err(SignerError::SigningError)
        );
    }
}
