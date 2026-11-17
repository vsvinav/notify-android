use notify_channel::Peer;
use serde_json::{json, Value};
use std::io::{self, BufRead, Read, Write};

fn run(peer: &mut Peer, request: Value) -> Result<Value, String> {
    let bytes = || {
        serde_json::from_value::<Vec<u8>>(request["bytes"].clone())
            .map_err(|_| "Invalid fixture bytes".to_string())
    };
    match request["op"].as_str().ok_or("Missing fixture operation")? {
        "identity" => Ok(json!(peer.identity_key())),
        "sign" => Ok(json!(peer.sign_authorization(&bytes()?)?)),
        "create" => {
            peer.create(request["id"].as_str().ok_or("Missing group ID")?)?;
            Ok(Value::Null)
        }
        "add" => {
            let expected: Vec<u8> = serde_json::from_value(request["publicKey"].clone())
                .map_err(|_| "Missing expected phone public key")?;
            Ok(json!(peer.add_pinned(&bytes()?, &expected)?))
        }
        "tree" => Ok(json!(peer.tree()?)),
        "send" => Ok(json!(peer.send(&bytes()?)?)),
        "receive" => {
            let message = peer.receive(&bytes()?)?;
            Ok(json!({ "bytes": message.bytes(), "sender": message.sender() }))
        }
        _ => Err("Unknown fixture operation".into()),
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut peer = match args.as_slice() {
        [op] if op == "--synthetic" => Peer::hardware("native")?,
        [op, id] if op == "create-identity" => {
            println!("{}", json!(Peer::create_identity(id)?));
            return Ok(());
        }
        [op, id] if op == "delete-identity" => {
            Peer::delete_identity(id)?;
            return Ok(());
        }
        [op, id, key] if op == "load-identity" => {
            let public: Vec<u8> = serde_json::from_str(key)?;
            Peer::persistent("native", id, &public)?
        }
        _ => {
            return Err(
                "Expected create-identity, delete-identity or load-identity arguments".into(),
            )
        }
    };
    let mut output = io::stdout().lock();
    let mut input = io::stdin().lock();
    loop {
        let mut line = Vec::new();
        let count = (&mut input).take(5_242_881).read_until(b'\n', &mut line)?;
        if count == 0 {
            break;
        }
        if count > 5_242_880 || line.last() != Some(&b'\n') {
            return Err("Invalid or oversized channel command line".into());
        }
        let request = serde_json::from_slice(&line)?;
        let response = match run(&mut peer, request) {
            Ok(value) => json!({ "result": value }),
            Err(error) => json!({ "error": error }),
        };
        writeln!(output, "{response}")?;
        output.flush()?;
    }
    Ok(())
}
