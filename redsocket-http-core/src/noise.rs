// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Noise NK (initiator) + `[u16 BE len][payload]` framing.

use std::io;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const NOISE_PATTERN: &str = "Noise_NK_25519_ChaChaPoly_BLAKE2s";
const MAX_NOISE_MSG: usize = 65535;

/// Max plaintext per frame — the ciphertext (plaintext + 16-byte MAC) has to
/// fit in the u16 length prefix, so anything larger would silently truncate.
pub const MAX_PLAINTEXT: usize = MAX_NOISE_MSG - 16;

fn other(e: impl std::fmt::Display) -> io::Error {
    io::Error::other(e.to_string())
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

/// One write per frame, so the length prefix never leaves as a segment of its own.
pub async fn write_frame<S: AsyncWrite + Unpin>(s: &mut S, data: &[u8]) -> io::Result<()> {
    if data.len() > MAX_NOISE_MSG {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "frame too large"));
    }
    let mut frame = Vec::with_capacity(2 + data.len());
    frame.extend_from_slice(&(data.len() as u16).to_be_bytes());
    frame.extend_from_slice(data);
    s.write_all(&frame).await?;
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

    let mut msg1 = vec![0u8; MAX_NOISE_MSG];
    let n1 = hs
        .write_message(&[], &mut msg1)
        .map_err(|e| other(format!("noise msg1: {}", e)))?;
    write_frame(w, &msg1[..n1]).await?;

    let msg2 = read_frame(r).await?;
    let mut payload = vec![0u8; MAX_NOISE_MSG];
    hs.read_message(&msg2, &mut payload)
        .map_err(|e| other(format!("noise msg2: {}", e)))?;

    let transport = hs
        .into_transport_mode()
        .map_err(|e| other(format!("noise transport: {}", e)))?;
    Ok(NoiseSession { transport })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::pin::Pin;
    use std::task::{Context, Poll};

    /// Records the buffer of every write call.
    #[derive(Default)]
    struct Writes(Vec<Vec<u8>>);

    impl AsyncWrite for Writes {
        fn poll_write(mut self: Pin<&mut Self>, _: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
            self.0.push(buf.to_vec());
            Poll::Ready(Ok(buf.len()))
        }

        fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }

        fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    /// A 2-byte segment ahead of every Noise message is a one-rule DPI signature,
    /// so the prefix and the payload go out in one write; the bytes are unchanged.
    #[tokio::test]
    async fn a_frame_is_one_write_of_the_same_bytes() {
        let mut w = Writes::default();
        write_frame(&mut w, &[7u8; 48]).await.unwrap();
        assert_eq!(w.0.len(), 1);
        assert_eq!(w.0[0][..2], [0x00, 0x30]);
        assert_eq!(read_frame(&mut &w.0[0][..]).await.unwrap(), vec![7u8; 48]);
    }
}
