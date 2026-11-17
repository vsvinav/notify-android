use notify_channel::{ExternalSigner, Peer};
use openmls_basic_credential::SignatureKeyPair;
use openmls_traits::{
    signatures::{Signer, SignerError},
    types::SignatureScheme,
};

struct Callback(SignatureKeyPair);

impl ExternalSigner for Callback {
    fn sign(&self, payload: &[u8]) -> Result<Vec<u8>, String> {
        self.0.sign(payload).map_err(|_| "callback failed".into())
    }
}

fn external(label: &str) -> Peer {
    let key = SignatureKeyPair::new(SignatureScheme::ECDSA_SECP256R1_SHA256).unwrap();
    Peer::external(label, &key.public().to_vec(), Box::new(Callback(key))).unwrap()
}

#[test]
fn external_standard_p256_roundtrip_rejects_tamper_replay_and_nonmember() {
    let mut phone = external("phone");
    let mut mac = Peer::new("mac").unwrap();
    let mut outsider = Peer::new("outsider").unwrap();
    assert!(phone.export_identity().is_err());
    mac.create("callback").unwrap();
    outsider.create("callback").unwrap();
    let welcome = mac
        .add_pinned(&phone.key_package().unwrap(), &phone.identity_key())
        .unwrap();
    phone
        .join_pinned(&welcome, &mac.tree().unwrap(), &mac.identity_key())
        .unwrap();
    let frame = phone.send(b"external signer").unwrap();
    assert!(outsider.receive(&frame).is_err());
    let mut tampered = frame.clone();
    *tampered.last_mut().unwrap() ^= 1;
    assert!(mac.receive(&tampered).is_err());
    let received = mac.receive(&frame).unwrap();
    assert_eq!(received.bytes(), b"external signer");
    assert_eq!(received.sender(), b"phone");
    assert!(mac.receive(&frame).is_err());
    for malformed in [vec![], vec![0; 5], vec![0; 1_064_961]] {
        assert!(phone.receive(&malformed).is_err());
    }
    assert_eq!(
        phone.receive(&mac.send(b"reply").unwrap()).unwrap().bytes(),
        b"reply"
    );
}

#[test]
fn wrong_or_failed_external_signature_is_rejected_at_load() {
    let key = SignatureKeyPair::new(SignatureScheme::ECDSA_SECP256R1_SHA256).unwrap();
    let other = SignatureKeyPair::new(SignatureScheme::ECDSA_SECP256R1_SHA256).unwrap();
    assert!(Peer::external("bad", key.public(), Box::new(Callback(other))).is_err());
    struct Failed;
    impl ExternalSigner for Failed {
        fn sign(&self, _: &[u8]) -> Result<Vec<u8>, String> {
            Err(format!("{:?}", SignerError::SigningError))
        }
    }
    assert!(Peer::external("bad", key.public(), Box::new(Failed)).is_err());
    assert!(Peer::external("bad", &[0; 65], Box::new(Callback(key))).is_err());
}
