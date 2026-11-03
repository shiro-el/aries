//! Read-only Host files. Paths stay data; directory commands never interpolate them.
use crate::{
    CoreError, HostConnection, error::protocol, layout_edit::machine_exec_env, on_runtime,
};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::json;

#[derive(Debug, Clone, uniffi::Record)]
pub struct RemoteFileEntry {
    pub name: String,
    pub directory: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RemoteDirectory {
    pub path: String,
    pub entries: Vec<RemoteFileEntry>,
}

/// A bounded page of whole lines; request `last + 1` only when `last < lines`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RemoteFilePage {
    pub text: String,
    pub first: u64,
    pub last: u64,
    pub lines: u64,
}

/// Raw bytes for downloads and image/PDF previews. `offset` is zero-based;
/// `total_size` is sampled on each read, so callers must reject size changes
/// between chunks. No UTF-8 conversion or base64 decoding is needed by the UI.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RemoteFileBytes {
    pub data: Vec<u8>,
    pub offset: u64,
    pub total_size: u64,
    pub eof: bool,
}

// 4 MiB raw + a small size header remains below machine.exec's 8 MiB
// stdout budget even after base64 encoding. POSIX tools work on Linux/macOS.
const MAX_BYTE_CHUNK: u32 = 4 * 1024 * 1024;
const READ_BYTES: &str = r#"[ -f "$1" ] || exit 1
size=$(wc -c < "$1") || exit 1
printf '%s\n' "$size"
block=65536
skip=$(($2 / block))
remainder=$(($2 % block))
count=$(((remainder + $3 + block - 1) / block))
dd if="$1" bs=$block skip=$skip count=$count 2>/dev/null | tail -c +$((remainder + 1)) | head -c "$3""#;

// POSIX find/sh work on Linux and macOS. NULs preserve every UTF-8 filename,
// including hidden names, newlines and shell metacharacters. pwd resolves `..`.
const LIST: &str = r#"pwd -P || exit
printf '\0'
find . ! -name . -prune -exec sh -c 'for p do
if [ -d "$p" ]; then kind=d; else kind=f; fi
printf "%s\0%s\0" "$kind" "${p#./}"
done' files {} +"#;

fn machine(address: &str) -> Option<&str> {
    address.rsplit_once('/').map(|(m, _)| m)
}

#[uniffi::export]
impl HostConnection {
    /// Reads at most 4 MiB from an absolute path returned by `list_files`.
    /// Uses the Terminal's machine (including nested machine addresses), just
    /// like `read_file_page`; paths are positional data, never shell source.
    /// Empty files and a read exactly at EOF return empty data with `eof=true`.
    /// Callers cancel by stopping between bounded reads; an in-flight Host
    /// command may finish in the background (15-second command timeout).
    /// This API does not accumulate a file: download/preview callers choose
    /// their own total-size cap and check `total_size` before keeping bytes.
    pub async fn read_file_bytes(
        &self,
        address: String,
        path: String,
        offset: u64,
        length: u32,
    ) -> Result<RemoteFileBytes, CoreError> {
        if !path.starts_with('/')
            || path.contains('\0')
            || length == 0
            || length > MAX_BYTE_CHUNK
            || offset > i64::MAX as u64 - u64::from(MAX_BYTE_CHUNK)
        {
            return Err(protocol(
                "absolute file path and byte length 1..=4 MiB required",
            ));
        }
        let client = self.client.clone();
        on_runtime(async move {
            let out = client.call("machine.exec", json!({
                "argv": ["sh", "-c", READ_BYTES, "files", path, offset.to_string(), length.to_string()],
                "on": machine(&address), "timeout": 15,
                "env": machine_exec_env(),
            })).await?;
            if out["code"].as_i64() != Some(0)
                || out["truncated"].as_bool() == Some(true)
                || out["timed_out"].as_bool() == Some(true)
            {
                return Err(CoreError::Rpc {
                    kind: "file_read_failed".into(),
                    detail: "File is unavailable, timed out or exceeded the response budget".into(),
                });
            }
            let bytes = STANDARD.decode(out["stdout"].as_str().ok_or_else(|| protocol("missing file bytes"))?)
                .map_err(|_| protocol("invalid file bytes"))?;
            let header = bytes.iter().position(|b| *b == b'\n').ok_or_else(|| protocol("missing file size"))?;
            let total_size: u64 = std::str::from_utf8(&bytes[..header]).ok().and_then(|s| s.trim().parse().ok())
                .ok_or_else(|| protocol("invalid file size"))?;
            let data = bytes[header + 1..].to_vec();
            if offset > total_size || data.len() as u64 != (total_size - offset).min(u64::from(length)) {
                return Err(protocol("file changed or byte range could not be read"));
            }
            Ok(RemoteFileBytes { eof: offset + data.len() as u64 == total_size, data, offset, total_size })
        }).await
    }

    /// Starts at the Terminal's actual foreground folder, then its launch folder.
    /// `path` continues browsing on that same Terminal's machine.
    pub async fn list_files(
        &self,
        address: String,
        path: Option<String>,
    ) -> Result<RemoteDirectory, CoreError> {
        let client = self.client.clone();
        on_runtime(async move {
            let cwd = match path {
                Some(p) => p,
                None => {
                    let terminal = client
                        .call("terminal.get", json!({"terminal": address}))
                        .await?;
                    terminal["foreground"]["cwd"]
                        .as_str()
                        .filter(|s| !s.is_empty())
                        .or_else(|| terminal["cwd"].as_str().filter(|s| !s.is_empty()))
                        .ok_or_else(|| protocol("Terminal has no current folder"))?
                        .to_owned()
                }
            };
            let out = client.call("machine.exec", json!({
                "argv": ["sh", "-c", LIST], "cwd": cwd, "on": machine(&address), "timeout": 10,
                "env": machine_exec_env(),
            })).await?;
            if out["code"].as_i64() != Some(0)
                || out["truncated"].as_bool() == Some(true)
                || out["timed_out"].as_bool() == Some(true)
            {
                return Err(CoreError::Rpc {
                    kind: "file_listing_failed".into(),
                    detail: "Folder listing failed, timed out or exceeded the response budget"
                        .into(),
                });
            }
            let bytes = STANDARD
                .decode(
                    out["stdout"]
                        .as_str()
                        .ok_or_else(|| protocol("missing directory output"))?,
                )
                .map_err(|_| protocol("invalid directory output"))?;
            parse_listing(&bytes)
        })
        .await
    }

    /// `offset` is one-based. herdr-core caps each response at 8 MiB of escaped
    /// text, so a page may end before `limit`. `not_text` remains a typed RPC error.
    pub async fn read_file_page(
        &self,
        address: String,
        path: String,
        offset: u64,
        limit: u32,
    ) -> Result<RemoteFilePage, CoreError> {
        if offset == 0 || limit == 0 {
            return Err(protocol("file offset and limit must be positive"));
        }
        let client = self.client.clone();
        on_runtime(async move {
            let out = client.call("file.read", json!({"path": path, "on": machine(&address), "offset": offset, "limit": limit})).await?;
            let number = |key: &str| out[key].as_u64().ok_or_else(|| protocol("invalid file page"));
            Ok(RemoteFilePage {
                text: out["text"].as_str().ok_or_else(|| protocol("missing file text"))?.to_owned(),
                first: number("first")?, last: number("last")?, lines: number("lines")?,
            })
        }).await
    }
}

fn parse_listing(bytes: &[u8]) -> Result<RemoteDirectory, CoreError> {
    let text =
        std::str::from_utf8(bytes).map_err(|_| protocol("Folder contains non-UTF-8 filenames"))?;
    let mut fields = text.split_terminator('\0');
    let path = fields
        .next()
        .and_then(|s| s.strip_suffix('\n'))
        .filter(|s| s.starts_with('/'))
        .ok_or_else(|| protocol("invalid folder path"))?
        .to_owned();
    if !text.ends_with('\0') {
        return Err(protocol("incomplete directory output"));
    }
    let mut entries = Vec::new();
    while let Some(kind) = fields.next() {
        let name = fields
            .next()
            .filter(|s| !s.is_empty() && *s != "." && *s != ".." && !s.contains('/'))
            .ok_or_else(|| protocol("invalid directory entry"))?;
        if kind != "d" && kind != "f" {
            return Err(protocol("invalid directory entry kind"));
        }
        entries.push(RemoteFileEntry {
            name: name.into(),
            directory: kind == "d",
        });
    }
    entries.sort_by(|a, b| {
        b.directory
            .cmp(&a.directory)
            .then_with(|| a.name.cmp(&b.name))
    });
    Ok(RemoteDirectory { path, entries })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_nul_delimited_names_and_sorts_directories_first() {
        let listing =
            parse_listing(b"/repo\n\0f\0tab\tand\nline\0d\0.hidden\0f\0a ' quote\0").unwrap();
        assert_eq!(listing.path, "/repo");
        assert_eq!(
            listing
                .entries
                .iter()
                .map(|e| (e.name.as_str(), e.directory))
                .collect::<Vec<_>>(),
            [
                (".hidden", true),
                ("a ' quote", false),
                ("tab\tand\nline", false)
            ]
        );
        assert!(parse_listing(b"/empty\n\0").unwrap().entries.is_empty());
        assert_eq!(parse_listing(b"/folder\n\n\0").unwrap().path, "/folder\n");
    }

    #[test]
    fn rejects_truncated_ambiguous_or_non_utf8_listings() {
        for bytes in [
            b"/repo\n\0f\0name".as_slice(),
            b"/repo\n\0d\0",
            b"/repo\n\0f\0../escape\0",
            b"/repo\n\0f\0\xff\0",
            b"/repo\n\0x\0name\0",
        ] {
            assert!(parse_listing(bytes).is_err(), "{bytes:?}");
        }
    }
}
