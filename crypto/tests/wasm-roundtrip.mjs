import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

assert.equal(process.env.NODE_TLS_REJECT_UNAUTHORIZED, '1');
const require = createRequire(import.meta.url);
const { Peer } = require('../target-wasm/channel.cjs');
const mac = new Peer('mac');
const phone = new Peer('phone');
const outsider = new Peer('outsider');
try {
  const stored = phone.export_identity();
  const restored = Peer.restore_identity('phone', stored, phone.identity_key());
  assert.deepEqual(restored.identity_key(), phone.identity_key());
  restored.free();
  mac.create('wasm-regression');
  outsider.create('wasm-regression');
  const welcome = mac.add_pinned(phone.key_package(), phone.identity_key());
  phone.join_pinned(welcome, mac.tree(), mac.identity_key());
  const message = new TextEncoder().encode('standard P256 WASM');
  const frame = mac.send(message);
  assert.throws(() => outsider.receive(frame));
  const tampered = frame.slice();
  tampered[tampered.length - 1] ^= 1;
  for (const bad of [new Uint8Array(), frame.slice(0, 5), tampered]) {
    assert.throws(() => phone.receive(bad));
  }
  const received = phone.receive(frame);
  try {
    assert.deepEqual(received.bytes(), message);
    assert.equal(new TextDecoder().decode(received.sender()), 'mac');
  } finally { received.free(); }
  assert.throws(() => phone.receive(frame));
  const reply = mac.receive(phone.send(message));
  try { assert.equal(new TextDecoder().decode(reply.sender()), 'phone'); }
  finally { reply.free(); }
  assert.equal(phone.sign_authorization(new Uint8Array([1])).length <= 72, true);
} finally {
  mac.free(); phone.free(); outsider.free();
}
console.log('Rebuilt WASM P256 roundtrip, pinning, identity restoration, tamper/replay/nonmember checks passed.');
