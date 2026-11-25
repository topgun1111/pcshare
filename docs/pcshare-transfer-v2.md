# PCShare transfer protocol version 2

Both peers use HTTP at `/p/pcshare-v2`. There is no content encoding. A GET with
`op=caps` returns the numeric version (2), chunk size (8388608), and discovery
peer ID. A 404 or 405 before starting a file permits the original upload
route. Network failures and errors after ticket creation must not select v1.
Directory creation remains the existing idempotent directory operation.

Every other operation is POST with a 32-character lowercase hexadecimal random
`X-Pcshare-Ticket`. Keep this ticket private. Discovery IDs and tickets do not
authenticate computers on an untrusted LAN. All path query values are UTF-8
percent-encoded. Control requests have empty bodies.

| op | Request | Successful response |
| --- | --- | --- |
| init | path, size in bytes, sha (lowercase SHA-256 of the entire source) | done and have (array of committed zero-based chunk indices) |
| status | ticket | same as init |
| chunk | i, X-Pcshare-Sha256, exact Content-Length, raw slice | ok |
| finish | ticket | done after final verification and publication |
| cancel | ticket | ok after partial state is removed |

A receiver derives offsets from the index, fixing the size of the last chunk
from the manifest. Files up to 64 GiB are supported. Upload streams use
256 KiB blocks. Only flush-complete chunks enter the atomic journal. A repeat
init with different metadata fails. After recording an initialization acknowledgement,
senders add `resume=1` to every later init. A missing receipt then returns HTTP
410 and cannot create a second copy. Repeated completion reuses the receipt.
Idle journals can expire after seven days. Status is scoped to a file.
