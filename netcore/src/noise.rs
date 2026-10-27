//! Noise NK (initiator) + `[u16 BE len][payload]` framing.
//!
//! Wire contract (must stay byte-compatible with the deployed server's
//! responder — see the server repo, src/noise_proxy.rs):
//!   1. `[u16][msg1]`  ->  e, es
//!   2. `[u16][msg2]`  <-  e, ee
//!   3. transport mode; every frame is `[u16 ct_len][ciphertext(+16B MAC)]`

use std::io;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const NOISE_PATTERN: &str = "Noise_NK_25519_ChaChaPoly_BLAKE2s";
const MAX_NOISE_MSG: usize = 65535;

/// Max plaintext per frame — the ciphertext (plaintext + 16-byte MAC) has to fit
/// in the u16 length prefix, so anything larger would silently truncate.
pub const MAX_PLAINTEXT: usize = MAX_NOISE_MSG - 16;

fn other(e: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::Other, e.to_string())
}

pub struct NoiseSession {
    transport: snow::TransportState,
}

impl NoiseSession {
    pub fn encrypt(&mut self, plaintext: &[u8]) -> io::Result<Vec<u8>> {
        if plaintext.len() > MAX_PLAINTEXT {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "plaintext exceeds one Noise frame",
            ));
        }
        let mut ct = vec![0u8; plaintext.len() + 16];
        let n = self
            .transport
            .write_message(plaintext, &mut ct)
            .map_err(|e| other(format!("noise encrypt: {}", e)))?;
        ct.truncate(n);
        Ok(ct)
    }

    pub fn decrypt(&mut self, ciphertext: &[u8]) -> io::Result<Vec<u8>> {
        let mut pt = vec![0u8; ciphertext.len()];
        let n = self
            .transport
            .read_message(ciphertext, &mut pt)
            .map_err(|e| other(format!("noise decrypt: {}", e)))?;
        pt.truncate(n);
        Ok(pt)
    }
}

pub async fn write_frame<S: AsyncWrite + Unpin>(s: &mut S, data: &[u8]) -> io::Result<()> {
    if data.len() > MAX_NOISE_MSG {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "frame too large"));
    }
    s.write_all(&(data.len() as u16).to_be_bytes()).await?;
    s.write_all(data).await?;
    s.flush().await
}

pub async fn read_frame<S: AsyncRead + Unpin>(s: &mut S) -> io::Result<Vec<u8>> {
    let mut len_buf = [0u8; 2];
    s.read_exact(&mut len_buf).await?;
    let len = u16::from_be_bytes(len_buf) as usize;
    let mut buf = vec![0u8; len];
    s.read_exact(&mut buf).await?;
    Ok(buf)
}

/// Run the Noise NK handshake as initiator. `msg1` carries an empty payload —
/// the cid travels in the first *transport* frame (the META record), never in
/// the handshake or the URL.
pub async fn handshake_initiator<R, W>(
    r: &mut R,
    w: &mut W,
    server_pubkey: &[u8; 32],
) -> io::Result<NoiseSession>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let params = NOISE_PATTERN
        .parse()
        .map_err(|e| other(format!("noise params: {}", e)))?;
    let mut hs = snow::Builder::new(params)
        .remote_public_key(server_pubkey)
        .build_initiator()
        .map_err(|e| other(format!("noise initiator: {}", e)))?;

    // -> e, es
    let mut msg1 = vec![0u8; MAX_NOISE_MSG];
    let n1 = hs
        .write_message(&[], &mut msg1)
        .map_err(|e| other(format!("noise msg1: {}", e)))?;
    write_frame(w, &msg1[..n1]).await?;

    // <- e, ee
    let msg2 = read_frame(r).await?;
    let mut payload = vec![0u8; MAX_NOISE_MSG];
    hs.read_message(&msg2, &mut payload)
        .map_err(|e| other(format!("noise msg2: {}", e)))?;

    let transport = hs
        .into_transport_mode()
        .map_err(|e| other(format!("noise transport: {}", e)))?;
    Ok(NoiseSession { transport })
}
