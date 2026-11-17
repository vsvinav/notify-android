// Fresh two-member MLS groups; identity approval and transport authorization are
// enforced by callers. Group/ratchet state is never restored after disconnect.
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::signatures::{Signer, SignerError};
use openmls_traits::{crypto::OpenMlsCrypto, OpenMlsProvider};
use tls_codec::{Deserialize, Serialize};
use wasm_bindgen::prelude::*;

#[cfg(all(target_os = "macos", feature = "macos-hardware"))]
mod hardware;

const SUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMP256_AES128GCM_SHA256_P256;
const LIMIT: usize = 1_048_576 + 16_384;

/// Platform-owned P256 signing key. Return a DER SHA256withECDSA signature;
/// private key material must stay with the platform.
pub trait ExternalSigner: Send + Sync {
    fn sign(&self, payload: &[u8]) -> Result<Vec<u8>, String>;
}

enum PeerSigner {
    Software(SignatureKeyPair),
    External {
        public: Vec<u8>,
        signer: Box<dyn ExternalSigner>,
    },
    #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
    Hardware(hardware::HardwareSigner),
}

impl PeerSigner {
    fn public(&self) -> &[u8] {
        match self {
            Self::Software(signer) => signer.public(),
            Self::External { public, .. } => public,
            #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
            Self::Hardware(signer) => signer.public(),
        }
    }
}

impl Signer for PeerSigner {
    fn sign(&self, payload: &[u8]) -> Result<Vec<u8>, SignerError> {
        match self {
            Self::Software(signer) => signer.sign(payload),
            Self::External { public, signer } => {
                if payload.len() > LIMIT {
                    return Err(SignerError::SigningError);
                }
                let signature = signer.sign(payload).map_err(|_| SignerError::SigningError)?;
                if !(8..=72).contains(&signature.len()) {
                    return Err(SignerError::SigningError);
                }
                OpenMlsRustCrypto::default()
                    .crypto()
                    .verify_signature(self.signature_scheme(), payload, public, &signature)
                    .map_err(|_| SignerError::SigningError)?;
                Ok(signature)
            }
            #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
            Self::Hardware(signer) => signer.sign(payload),
        }
    }

    fn signature_scheme(&self) -> SignatureScheme {
        SignatureScheme::ECDSA_SECP256R1_SHA256
    }
}

fn decode<T: Deserialize>(bytes: &[u8]) -> Result<T, String> {
    if bytes.len() > LIMIT {
        return Err("MLS frame too large".into());
    }
    let mut input = bytes;
    let value = T::tls_deserialize(&mut input).map_err(|_| "Invalid MLS encoding")?;
    if !input.is_empty() {
        return Err("Trailing MLS bytes".into());
    }
    Ok(value)
}

#[wasm_bindgen]
pub struct Peer {
    provider: OpenMlsRustCrypto,
    signer: PeerSigner,
    credential: CredentialWithKey,
    group: Option<MlsGroup>,
}

#[wasm_bindgen]
pub struct Message {
    bytes: Vec<u8>,
    sender: Vec<u8>,
}

#[wasm_bindgen]
impl Message {
    pub fn bytes(&self) -> Vec<u8> {
        self.bytes.clone()
    }

    pub fn sender(&self) -> Vec<u8> {
        self.sender.clone()
    }
}

impl Peer {
    pub fn external(
        label: &str,
        public: &[u8],
        signer: Box<dyn ExternalSigner>,
    ) -> Result<Peer, String> {
        if public.len() != 65 || public[0] != 4 {
            return Err("Invalid external P256 public key".into());
        }
        let signer = PeerSigner::External { public: public.to_vec(), signer };
        signer
            .sign(b"Notify external identity check v1")
            .map_err(|_| "External identity unavailable or does not match; no replacement was created")?;
        Self::from_signer(label, signer)
    }

    #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
    pub fn create_identity(id: &str) -> Result<Vec<u8>, String> {
        hardware::HardwareSigner::create_persisted(id)
    }

    #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
    pub fn delete_identity(id: &str) -> Result<(), String> {
        hardware::HardwareSigner::delete_persisted(id)
    }

    #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
    pub fn persistent(label: &str, id: &str, public: &[u8]) -> Result<Peer, String> {
        Self::from_signer(
            label,
            PeerSigner::Hardware(hardware::HardwareSigner::load(id, public)?),
        )
    }

    fn from_signer(label: &str, signer: PeerSigner) -> Result<Peer, String> {
        if label.is_empty() || label.len() > 256 {
            return Err("Invalid fixture label".into());
        }
        let provider = OpenMlsRustCrypto::default();
        match &signer {
            PeerSigner::Software(key) => key
                .store(provider.storage())
                .map_err(|_| "Cannot store identity")?,
            PeerSigner::External { .. } => {}
            #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
            PeerSigner::Hardware(_) => {}
        }
        let credential = CredentialWithKey {
            credential: BasicCredential::new(label.as_bytes().to_vec()).into(),
            signature_key: signer.public().into(),
        };
        Ok(Peer {
            provider,
            signer,
            credential,
            group: None,
        })
    }

    #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
    pub fn hardware(label: &str) -> Result<Peer, String> {
        Self::from_signer(
            label,
            PeerSigner::Hardware(hardware::HardwareSigner::new()?),
        )
    }
}

#[wasm_bindgen]
impl Peer {
    pub fn identity_key(&self) -> Vec<u8> {
        self.signer.public().to_vec()
    }

    pub fn sign_authorization(&self, challenge: &[u8]) -> Result<Vec<u8>, String> {
        if challenge.is_empty() || challenge.len() > 4096 {
            return Err("Invalid authorization challenge".into());
        }
        let mut payload = b"Notify remote authorization v1\0".to_vec();
        payload.extend_from_slice(challenge);
        self.signer
            .sign(&payload)
            .map_err(|_| "Cannot sign authorization challenge".into())
    }

    pub fn export_identity(&self) -> Result<Vec<u8>, String> {
        match &self.signer {
            PeerSigner::Software(key) => key
                .tls_serialize_detached()
                .map_err(|_| "Cannot encode identity".into()),
            PeerSigner::External { .. } => Err("External identities cannot be exported".into()),
            #[cfg(all(target_os = "macos", feature = "macos-hardware"))]
            PeerSigner::Hardware(_) => Err("Hardware identities cannot be exported".into()),
        }
    }

    pub fn restore_identity(label: &str, bytes: &[u8], expected: &[u8]) -> Result<Peer, String> {
        if bytes.len() > 256 || expected.len() != 65 {
            return Err("Invalid stored identity".into());
        }
        // The pinned upstream TLS record starts with SecretVLBytes. Validate its
        // length before invoking the upstream P256 signer, which requires 32 bytes.
        let secret = tls_codec::SecretVLBytes::tls_deserialize(&mut &bytes[..])
            .map_err(|_| "Invalid stored identity")?;
        if secret.as_slice().len() != 32 {
            return Err("Invalid stored identity".into());
        }
        let key: SignatureKeyPair = decode(bytes)?;
        if key.signature_scheme() != SignatureScheme::ECDSA_SECP256R1_SHA256
            || key.public() != expected
        {
            return Err("Stored identity does not match".into());
        }
        let proof = b"Notify identity restoration check v1";
        let signature = key.sign(proof).map_err(|_| "Invalid stored identity")?;
        OpenMlsRustCrypto::default()
            .crypto()
            .verify_signature(key.signature_scheme(), proof, expected, &signature)
            .map_err(|_| "Stored identity does not match")?;
        Self::from_signer(label, PeerSigner::Software(key))
    }

    #[wasm_bindgen(constructor)]
    pub fn new(label: &str) -> Result<Peer, String> {
        let signer = SignatureKeyPair::new(SignatureScheme::ECDSA_SECP256R1_SHA256)
            .map_err(|_| "Cannot create identity")?;
        Self::from_signer(label, PeerSigner::Software(signer))
    }

    pub fn key_package(&self) -> Result<Vec<u8>, String> {
        KeyPackage::builder()
            .build(SUITE, &self.provider, &self.signer, self.credential.clone())
            .map_err(|_| "Cannot create key package")?
            .key_package()
            .tls_serialize_detached()
            .map_err(|_| "Cannot encode key package".into())
    }

    pub fn create(&mut self, id: &str) -> Result<(), String> {
        if self.group.is_some() || id.is_empty() || id.len() > 256 {
            return Err("Invalid fixture group".into());
        }
        let group = MlsGroup::builder()
            .ciphersuite(SUITE)
            .with_group_id(GroupId::from_slice(id.as_bytes()))
            .build(&self.provider, &self.signer, self.credential.clone())
            .map_err(|_| "Cannot create group")?;
        self.group = Some(group);
        Ok(())
    }

    fn add_member(&mut self, bytes: &[u8]) -> Result<Vec<u8>, String> {
        let key: KeyPackageIn = decode(bytes)?;
        let key = key
            .validate(self.provider.crypto(), ProtocolVersion::Mls10)
            .map_err(|_| "Invalid key package")?;
        let group = self.group.as_mut().ok_or("No group")?;
        if group.members().count() != 1 {
            return Err("Fixture supports initial two-member pairing only".into());
        }
        group
            .propose_add_member(&self.provider, &self.signer, &key)
            .map_err(|_| "Cannot propose member")?;
        let (_, welcome, _) = group
            .commit_to_pending_proposals(&self.provider, &self.signer)
            .map_err(|_| "Cannot commit member")?;
        let welcome = welcome
            .ok_or("No welcome")?
            .tls_serialize_detached()
            .map_err(|_| "Cannot encode welcome")?;
        group
            .merge_pending_commit(&self.provider)
            .map_err(|_| "Cannot merge commit")?;
        Ok(welcome)
    }

    pub fn add_pinned(&mut self, bytes: &[u8], expected: &[u8]) -> Result<Vec<u8>, String> {
        let key: KeyPackageIn = decode(bytes)?;
        let key = key
            .validate(self.provider.crypto(), ProtocolVersion::Mls10)
            .map_err(|_| "Invalid key package")?;
        if expected.len() != 65
            || expected == self.signer.public()
            || key.ciphersuite() != SUITE
            || key.leaf_node().signature_key().as_slice() != expected
        {
            return Err("Peer identity does not match".into());
        }
        self.add_member(bytes)
    }

    pub fn tree(&self) -> Result<Vec<u8>, String> {
        let tree: RatchetTreeIn = self
            .group
            .as_ref()
            .ok_or("No group")?
            .export_ratchet_tree()
            .into();
        tree.tls_serialize_detached()
            .map_err(|_| "Cannot encode tree".into())
    }

    pub fn join_pinned(
        &mut self,
        welcome: &[u8],
        tree: &[u8],
        expected: &[u8],
    ) -> Result<(), String> {
        if expected.len() != 65 || expected == self.signer.public() {
            return Err("Invalid expected peer identity".into());
        }
        self.join_checked(welcome, tree, expected)
    }

    pub fn send(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, String> {
        if plaintext.len() > 1_048_576 {
            return Err("Fixture plaintext too large".into());
        }
        self.group
            .as_mut()
            .ok_or("No group")?
            .create_message(&self.provider, &self.signer, plaintext)
            .map_err(|_| "Cannot encrypt message")?
            .tls_serialize_detached()
            .map_err(|_| "Cannot encode message".into())
    }

    pub fn receive(&mut self, bytes: &[u8]) -> Result<Message, String> {
        let message: MlsMessageIn = decode(bytes)?;
        let MlsMessageBodyIn::PrivateMessage(message) = message.extract() else {
            return Err("Expected encrypted MLS message".into());
        };
        let processed = self
            .group
            .as_mut()
            .ok_or("No group")?
            .process_message(&self.provider, message)
            .map_err(|_| "Message rejected")?;
        let sender = BasicCredential::try_from(processed.credential().clone())
            .map_err(|_| "Unexpected credential type")?
            .identity()
            .to_vec();
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(message) => Ok(Message {
                bytes: message.into_bytes(),
                sender,
            }),
            _ => Err("Not a fixture application message".into()),
        }
    }
}

impl Peer {
    fn join_checked(&mut self, welcome: &[u8], tree: &[u8], expected: &[u8]) -> Result<(), String> {
        if self.group.is_some() {
            return Err("Fixture already joined".into());
        }
        let message: MlsMessageIn = decode(welcome)?;
        let MlsMessageBodyIn::Welcome(welcome) = message.extract() else {
            return Err("Expected MLS welcome".into());
        };
        let tree: RatchetTreeIn = decode(tree)?;
        let staged = StagedWelcome::new_from_welcome(
            &self.provider,
            &MlsGroupJoinConfig::builder().build(),
            welcome,
            Some(tree),
        )
        .map_err(|_| "Welcome rejected")?;
        let members: Vec<_> = staged.members().collect();
        if members.len() != 2
            || members
                .iter()
                .filter(|member| member.signature_key == expected)
                .count()
                != 1
            || members
                .iter()
                .filter(|member| member.signature_key == self.signer.public())
                .count()
                != 1
        {
            return Err("Peer identity does not match".into());
        }
        let group = staged
            .into_group(&self.provider)
            .map_err(|_| "Cannot join group")?;
        self.group = Some(group);
        Ok(())
    }
}
