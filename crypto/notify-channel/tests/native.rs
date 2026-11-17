use notify_channel::Peer;

#[test]
fn software_identity_restores_without_restoring_a_group() {
    let original = Peer::new("phone").unwrap();
    let stored = original.export_identity().unwrap();
    let public = original.identity_key();
    let mut restored = Peer::restore_identity("phone", &stored, &public).unwrap();
    assert_eq!(restored.identity_key(), public);
    assert!(restored.send(b"no old group").is_err());
    let mut mac = Peer::hardware("mac").unwrap();
    mac.create("fresh").unwrap();
    let welcome = mac
        .add_pinned(&restored.key_package().unwrap(), &public)
        .unwrap();
    restored
        .join_pinned(&welcome, &mac.tree().unwrap(), &mac.identity_key())
        .unwrap();
    assert_eq!(
        restored
            .receive(&mac.send(b"fresh channel").unwrap())
            .unwrap()
            .bytes(),
        b"fresh channel"
    );
    let other = Peer::new("phone").unwrap().identity_key();
    assert!(Peer::restore_identity("phone", &stored, &other).is_err());
    for malformed in [
        vec![],
        vec![0; 128],
        stored[..10].to_vec(),
        [stored.as_slice(), &[0]].concat(),
    ] {
        assert!(Peer::restore_identity("phone", &malformed, &public).is_err());
    }
}

#[test]
fn peer_pinning_rejects_same_label_with_another_key() {
    let mut mac = Peer::new("mac").unwrap();
    let mut phone = Peer::new("phone").unwrap();
    let impostor = Peer::new("phone").unwrap();
    mac.create("pinning").unwrap();
    assert!(mac
        .add_pinned(&impostor.key_package().unwrap(), &phone.identity_key())
        .is_err());
    let welcome = mac
        .add_pinned(&phone.key_package().unwrap(), &phone.identity_key())
        .unwrap();
    let wrong_mac = Peer::new("mac").unwrap();
    assert!(phone
        .join_pinned(&welcome, &mac.tree().unwrap(), &wrong_mac.identity_key())
        .is_err());
    assert!(phone.send(b"must not join").is_err());
}

#[test]
fn signing_authorization_is_bounded_and_hardware_cannot_be_exported() {
    use openmls_rust_crypto::OpenMlsRustCrypto;
    use openmls_traits::{crypto::OpenMlsCrypto, types::SignatureScheme, OpenMlsProvider};
    let mac = Peer::hardware("mac").unwrap();
    assert!(mac.export_identity().is_err());
    let challenge = [1; 32];
    let signature = mac.sign_authorization(&challenge).unwrap();
    let signed = [b"Notify remote authorization v1\0".as_slice(), &challenge].concat();
    let provider = OpenMlsRustCrypto::default();
    assert!(provider
        .crypto()
        .verify_signature(
            SignatureScheme::ECDSA_SECP256R1_SHA256,
            &signed,
            &mac.identity_key(),
            &signature
        )
        .is_ok());
    assert!(provider
        .crypto()
        .verify_signature(
            SignatureScheme::ECDSA_SECP256R1_SHA256,
            &challenge,
            &mac.identity_key(),
            &signature
        )
        .is_err());
    assert!(mac.sign_authorization(&[]).is_err());
    assert!(mac.sign_authorization(&vec![0; 4097]).is_err());
}

#[test]
fn hardware_identity_can_sign_mls_for_a_software_peer() {
    let mut mac = Peer::hardware("hardware-mac").unwrap();
    let mut phone = Peer::new("software-phone").unwrap();
    mac.create("hardware-group").unwrap();
    let welcome = mac
        .add_pinned(&phone.key_package().unwrap(), &phone.identity_key())
        .unwrap();
    phone
        .join_pinned(&welcome, &mac.tree().unwrap(), &mac.identity_key())
        .unwrap();
    let frame = mac.send(b"hardware-authenticated MLS").unwrap();
    let received = phone.receive(&frame).unwrap();
    assert_eq!(received.bytes(), b"hardware-authenticated MLS");
    assert_eq!(received.sender(), b"hardware-mac");
    let response = phone.send(b"software receipt").unwrap();
    assert_eq!(mac.receive(&response).unwrap().sender(), b"software-phone");
}

#[test]
fn native_pair_rejects_tamper_replay_and_nonmember() {
    let mut mac = Peer::new("synthetic-mac").unwrap();
    let mut phone = Peer::new("synthetic-phone").unwrap();
    let mut outsider = Peer::new("synthetic-outsider").unwrap();
    mac.create("synthetic-group").unwrap();
    outsider.create("synthetic-group").unwrap();
    let welcome = mac
        .add_pinned(&phone.key_package().unwrap(), &phone.identity_key())
        .unwrap();
    phone
        .join_pinned(&welcome, &mac.tree().unwrap(), &mac.identity_key())
        .unwrap();
    let plaintext = b"synthetic request, no real session";
    let encrypted = mac.send(plaintext).unwrap();
    assert_ne!(encrypted, plaintext);
    assert!(outsider.receive(&encrypted).is_err());
    let mut altered = encrypted.clone();
    *altered.last_mut().unwrap() ^= 1;
    assert!(phone.receive(&altered).is_err());
    let delivered = phone.receive(&encrypted).unwrap();
    assert_eq!(delivered.bytes(), plaintext);
    assert_eq!(delivered.sender(), b"synthetic-mac");
    assert!(phone.receive(&encrypted).is_err());
    let reply = phone.send(b"synthetic receipt").unwrap();
    let receipt = mac.receive(&reply).unwrap();
    assert_eq!(receipt.bytes(), b"synthetic receipt");
    assert_eq!(receipt.sender(), b"synthetic-phone");
}

#[test]
fn malformed_frames_do_not_panic_or_consume_a_valid_message() {
    let mut mac = Peer::new("mac").unwrap();
    let mut phone = Peer::new("phone").unwrap();
    mac.create("parsing").unwrap();
    let welcome = mac
        .add_pinned(&phone.key_package().unwrap(), &phone.identity_key())
        .unwrap();
    phone
        .join_pinned(&welcome, &mac.tree().unwrap(), &mac.identity_key())
        .unwrap();
    let encrypted = mac.send(b"still valid").unwrap();
    let mut trailing = encrypted.clone();
    trailing.push(0);
    for data in [&[][..], &[0, 1, 2][..], &encrypted[..5], &trailing[..]] {
        assert!(phone.receive(data).is_err());
    }
    assert_eq!(phone.receive(&encrypted).unwrap().bytes(), b"still valid");
}
